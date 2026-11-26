package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N49 (stg, 2026-11-24, session 5e5403ce): the operator moved the phone from 4G to Wi-Fi on air. The default network
 * stayed validated, so nothing was told, and the SRT socket stayed on 4G until the delivery watch's stall verdict 22 s
 * later; the reconnect itself took 0.76 s. Each attempt now carries the network it was asked on, and a validated default
 * other than that one replaces it: at once on air, and once it connects when it is still connecting.
 */
class NetworkSwitchTest {
  private val cellular = 100L
  private val wifi = 101L

  /** Live on 4G: the network's handle told before the pair, as the watch tells it at the engine's start. */
  private fun liveOnCellular(descriptor: BroadcastDescriptor = Configs.descriptor()): MachineRig {
    val rig = MachineRig(descriptor = descriptor)
    rig.send(Input.Network(true, cellular))
    return rig.live()
  }

  private fun MachineRig.on(handle: Long): List<Command> = send(Input.Network(true, handle))

  private fun MachineRig.lost(): List<Command> = send(Input.Network(false))

  private fun MachineRig.switchLines(): List<String> = records("network-switched").map { (it.field("result") as Wire).wire }

  private fun List<Command>.replaces(): List<Command.StartNewSession> = filterIsInstance<Command.StartNewSession>()

  private fun List<Command>.opens(): Boolean = any { it is Command.StartNewSession || it is Command.Connect || it is Command.Rebuild }

  /** Connected again on [attemptId] and a second of frames: the outage is over. */
  private fun MachineRig.resumedOn(attemptId: Int): MachineRig {
    send(Input.Connected(attemptId))
    advance(1_000)
    check(phase is Phase.OnAir && (phase as Phase.OnAir).outage == null) { "not resumed: ${phase.name}" }
    return this
  }

  @Test
  fun `N49 a switch on air replaces the attempt at once, on the transport in hand, and the record says why`() {
    val rig = liveOnCellular()
    assertEquals(cellular, rig.session.attemptNetwork, "the first attempt was asked on 4G")
    val since = (rig.state as SnapshotState.Publishing).sinceEpochMs
    val commands = rig.on(wifi)
    val renewed = commands.replaces().single()
    assertEquals(1, renewed.previousAttemptId)
    assertEquals(2, renewed.next.attemptId)
    assertIs<SrtTarget>(renewed.next.target)
    assertEquals(wifi, rig.session.attemptNetwork, "the new attempt is asked on Wi-Fi")
    assertEquals(listOf("network-switched", "connecting"), commands.filterIsInstance<Command.Record>().map { it.entry.kind })
    val line = rig.records("network-switched").single()
    assertEquals(listOf("result" to NetworkSwitch.REPLACED, "attempt" to 1, "phase" to "on-air"), line.fields)
    // The snapshot's word for a replaced uplink: N49 adds none JS would have to read.
    val reconnecting = assertIs<SnapshotState.Reconnecting>(rig.state)
    assertEquals(ReconnectCause.UPLINK_LOST, reconnecting.cause)
    assertEquals(since, reconnecting.sinceEpochMs)
    assertFalse(rig.session.notDelivered, "viewers were never said not to receive")
    rig.resumedOn(2)
    assertEquals(SnapshotState.Publishing(Transport.SRT, since), rig.state)
    assertEquals(1, rig.records("resumed").size)
  }

  @Test
  fun `a switch is no failure - it never counts toward the fallback`() {
    val rig = liveOnCellular()
    val before = rig.session.fallback
    rig.on(wifi)
    assertEquals(before, rig.session.fallback)
  }

  @Test
  fun `on RTMPS a switch replaces on RTMPS`() {
    val rig = liveOnCellular(Configs.descriptor(primary = Configs.rtmps, fallback = Configs.srt))
    val renewed = rig.on(wifi).replaces().single()
    assertIs<RtmpsTarget>(renewed.next.target)
    assertEquals(Transport.RTMPS, rig.records("connecting").last().field("transport"))
  }

  @Test
  fun `N49 fix 1 a loss and then a new network under SRT's peer-idle timeout is a switch - the socket outlived its loss`() {
    // onLost(4G), then Wi-Fi validated: SRT keeps the 4G socket for about 5 s, and it lives on through the new route.
    val rig = liveOnCellular()
    rig.lost()
    assertIs<Phase.OnAir>(rig.phase, "no drop heard yet")
    assertEquals(cellular, rig.session.attemptNetwork, "a loss never forgets the attempt's network")
    rig.on(wifi).replaces().single()
    assertEquals(listOf("replaced"), rig.switchLines())
  }

  @Test
  fun `the same network validated again after a loss is no switch`() {
    val rig = liveOnCellular()
    rig.lost()
    assertFalse(rig.on(cellular).opens())
    assertEquals(emptyList(), rig.switchLines())
    assertIs<SnapshotState.Publishing>(rig.state)
  }

  @Test
  fun `the same network reported again tells nothing`() {
    val rig = liveOnCellular()
    assertFalse(rig.on(cellular).opens())
    assertEquals(emptyList(), rig.switchLines())
  }

  @Test
  fun `N49 fix 2 a switch back while the replace is connecting holds a replace for its connect`() {
    // 4G to Wi-Fi to 4G inside a ~1 s connect: attempt 2 opens on Wi-Fi, which is no longer the default.
    val rig = liveOnCellular()
    rig.on(wifi)
    assertEquals(2, rig.attempt)
    assertFalse(rig.on(cellular).opens(), "a connect in flight is never torn down")
    assertEquals(listOf("replaced", "pending"), rig.switchLines())
    assertEquals("connecting", rig.records("network-switched").last().field("phase"))
    val outage = (rig.phase as Phase.Connecting).outage!!
    rig.at(rig.mono + 700)
    val connected = rig.send(Input.Connected(2))
    val renewed = connected.replaces().single()
    assertEquals(2, renewed.previousAttemptId)
    assertEquals(3, renewed.next.attemptId)
    assertEquals(cellular, rig.session.attemptNetwork)
    assertEquals(listOf("replaced", "pending", "replaced"), rig.switchLines())
    assertEquals(outage, (rig.phase as Phase.Connecting).outage, "the first replace's outage and hold stand")
    rig.resumedOn(3)
    assertEquals(1, rig.records("resumed").size, "one outage for both replaces")
  }

  @Test
  fun `N49 fix 2 a switch away and back before the connect answers replaces nothing more`() {
    // Asked on Wi-Fi, 4G for a moment, then Wi-Fi again: the attempt is on the default it was asked on.
    val rig = liveOnCellular()
    rig.on(wifi)
    rig.on(cellular)
    assertFalse(rig.on(wifi).opens())
    assertFalse(rig.send(Input.Connected(2)).opens())
    assertEquals(listOf("replaced", "pending", "same"), rig.switchLines())
    rig.resumedOn(2)
    assertEquals(listOf(1, 2), rig.connects().map { it.attemptId })
  }

  @Test
  fun `N49 fix 2 connected and before its first frames, a switch replaces at once and keeps the outage`() {
    val rig = liveOnCellular()
    rig.on(wifi)
    rig.send(Input.Connected(2))
    val outage = (rig.phase as Phase.OnAir).outage!!
    rig.at(rig.mono + 700)
    val renewed = rig.on(cellular).replaces().single()
    assertEquals(2, renewed.previousAttemptId)
    assertEquals(outage, (rig.phase as Phase.Connecting).outage, "the same outage and hold")
    assertEquals(listOf("replaced", "replaced"), rig.switchLines())
  }

  @Test
  fun `N49 fix 2 the first connect, before live, is replaced once it connects when the default moved`() {
    val rig = MachineRig()
    rig.send(Input.Network(true, cellular))
    rig.armed()
    rig.ready()
    assertEquals(cellular, rig.session.attemptNetwork)
    assertFalse(rig.on(wifi).opens())
    assertEquals(listOf("pending"), rig.switchLines())
    val renewed = rig.send(Input.Connected(1)).replaces().single()
    assertEquals(2, renewed.next.attemptId)
    assertNull((rig.phase as Phase.Connecting).outage, "never live: nothing to hold")
    rig.send(Input.Connected(2))
    rig.advance(1_000)
    assertIs<SnapshotState.Publishing>(rig.state)
  }

  @Test
  fun `a switch between attempts replaces nothing - the next attempt is asked on the new default`() {
    val rig = liveOnCellular()
    rig.send(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, null))
    assertFalse(rig.on(wifi).opens())
    assertEquals(listOf("between-attempts"), rig.switchLines())
    rig.advance(2_000)
    assertEquals(wifi, rig.session.attemptNetwork)
    assertFalse(rig.send(Input.Connected(2)).opens())
    assertEquals(listOf(1, 2), rig.connects().map { it.attemptId }, "the first connect and the drop's retry")
  }

  @Test
  fun `an attempt asked with no validated network adopts the next one, and is not replaced`() {
    val rig = liveOnCellular()
    rig.lost()
    rig.send(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, null))
    rig.advance(2_000)
    assertNull(rig.session.attemptNetwork, "asked while no network was validated")
    assertFalse(rig.on(wifi).opens())
    assertEquals(wifi, rig.session.attemptNetwork)
    assertEquals(emptyList(), rig.switchLines())
    assertFalse(rig.send(Input.Connected(2)).opens())
  }

  @Test
  fun `once resumed, the next switch replaces again`() {
    val rig = liveOnCellular()
    rig.on(wifi)
    rig.resumedOn(2)
    rig.on(cellular)
    assertEquals(listOf(1, 2, 3), rig.connects().map { it.attemptId })
    assertEquals(listOf("replaced", "replaced"), rig.switchLines())
  }

  @Test
  fun `off air a switch is only recorded - idle, paired, armed - and the first network is no switch`() {
    val idle = MachineRig()
    idle.on(cellular)
    assertEquals(emptyList(), idle.switchLines(), "the first network")
    idle.on(wifi)
    val paired = MachineRig().apply { on(cellular) }.paired()
    paired.on(wifi)
    val armed = MachineRig().apply { on(cellular) }.armed()
    armed.on(wifi)
    for ((name, rig) in listOf("idle" to idle, "paired" to paired, "armed" to armed)) {
      assertEquals(listOf("not-on-air"), rig.switchLines(), name)
      assertTrue(rig.sent<Command.StartNewSession>().isEmpty(), name)
    }
    assertEquals("paired", paired.records("network-switched").single().field("phase"))
    armed.ready()
    assertEquals(wifi, armed.session.attemptNetwork, "the first attempt is asked on the default it arms on")
  }

  @Test
  fun `the default network is kept from phase to phase, as the validated fact is`() {
    val rig = MachineRig()
    rig.on(cellular)
    val seen = mutableListOf(rig.phase.name to rig.phase.defaultNetwork)
    rig.paired()
    seen += rig.phase.name to rig.phase.defaultNetwork
    rig.hear("go-live", sid = "sess_42", startedBy = "organiser")
    seen += rig.phase.name to rig.phase.defaultNetwork
    rig.ready()
    seen += rig.phase.name to rig.phase.defaultNetwork
    rig.send(Input.Connected(1))
    rig.advance(1_000)
    seen += rig.phase.name to rig.phase.defaultNetwork
    rig.send(Input.Stop)
    seen += rig.phase.name to rig.phase.defaultNetwork
    rig.send(Input.Reset)
    seen += rig.phase.name to rig.phase.defaultNetwork
    val expected: List<Pair<String, Long?>> = listOf("idle", "paired", "armed", "connecting", "on-air", "ended", "idle").map { it to cellular }
    assertEquals<List<Pair<String, Long?>>>(expected, seen)
  }

  @Test
  fun `the default network is kept through the pairing's own transitions - unpair, a never-live stop, a re-pair`() {
    val rig = MachineRig()
    rig.on(cellular)
    rig.paired()
    rig.send(Input.Unpair)
    assertEquals("idle" to cellular, rig.phase.name to rig.phase.defaultNetwork)
    rig.paired()
    rig.hear("go-live", sid = "sess_42", startedBy = "organiser")
    rig.send(Input.Stop)
    assertEquals("paired" to cellular, rig.phase.name to rig.phase.defaultNetwork)
    rig.send(Input.Pair(rig.request, Configs.PHONE, stopped = null))
    assertEquals(cellular, rig.phase.defaultNetwork, rig.phase.name)
  }

  @Test
  fun `the default network is kept through every other way a pairing or a broadcast ends`() {
    val seen = mutableListOf<Pair<String, Long?>>()
    // Permissions refused: back to Idle.
    val refused = MachineRig().apply { on(cellular) }
    refused.permissions = false
    refused.send(Input.Pair(refused.request, Configs.PHONE, stopped = null))
    seen += "refused ${refused.phase.name}" to refused.phase.defaultNetwork
    // Unpaired while permissions are asked.
    val asking = MachineRig().apply { on(cellular) }
    asking.permissions = null
    asking.send(Input.Pair(asking.request, Configs.PHONE, stopped = null))
    seen += "asking ${asking.phase.name}" to asking.phase.defaultNetwork
    asking.send(Input.Unpair)
    seen += "unpaired ${asking.phase.name}" to asking.phase.defaultNetwork
    // The notification's Forget.
    val forgotten = MachineRig().apply { on(cellular) }.paired()
    forgotten.send(Input.Forget)
    seen += "forgotten ${forgotten.phase.name}" to forgotten.phase.defaultNetwork
    // The code ended by the server: a 401 on a beat.
    val ended = MachineRig().apply { on(cellular) }.paired()
    ended.hear(null, status = 401)
    seen += "code ended ${ended.phase.name}" to ended.phase.defaultNetwork
    // The organiser stopped it: a reconnect's code answer says so, and the pairing waits again.
    val over = liveOnCellular()
    over.send(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, null))
    val ask = over.sent<Command.FetchCode>().last()
    over.send(Input.CodeAnswered(ask.requestId, CodeAnswer.Over("sess_42", "stopped")))
    seen += "over ${over.phase.name}" to over.phase.defaultNetwork
    // A start that fails at Arming: the code unreadable, and the pairing waits again.
    val unreadable = MachineRig().apply { on(cellular) }
    unreadable.codes = { if (unreadable.phase is Phase.Arming) CodeAnswer.Unreadable("made-up") else null }
    unreadable.armed()
    seen += "start failed ${unreadable.phase.name}" to unreadable.phase.defaultNetwork
    val expected: List<Pair<String, Long?>> =
      listOf("refused idle", "asking pairing", "unpaired idle", "forgotten idle", "code ended idle", "over paired", "start failed paired").map { it to cellular }
    assertEquals<List<Pair<String, Long?>>>(expected, seen)
  }

  @Test
  fun `a loss while the replace connects replaces nothing at its connect - no default to judge it by`() {
    val rig = liveOnCellular()
    rig.on(wifi)
    rig.lost()
    assertFalse(rig.send(Input.Connected(2)).opens())
    assertEquals(listOf("replaced"), rig.switchLines())
  }

  @Test
  fun `not validated forgets the default network, and validated again names it`() {
    val rig = MachineRig()
    rig.on(cellular)
    rig.lost()
    assertNull(rig.phase.defaultNetwork)
    rig.on(wifi)
    assertEquals(wifi, rig.phase.defaultNetwork)
    assertEquals(emptyList(), rig.switchLines(), "after a loss there is no default to have switched from")
  }

  @Test
  fun `a switch tells nothing of validation - no second network line, and readiness as it was`() {
    val rig = MachineRig().apply { on(cellular) }.paired()
    val before = rig.snapshot
    rig.on(wifi)
    assertEquals(1, rig.records("network").size, "the first validated line only: ${rig.records("network")}")
    assertTrue(rig.phase.networkValidated)
    assertEquals(before.pairing, rig.snapshot.pairing)
  }

  @Test
  fun `the switch line's words`() {
    assertEquals(listOf("replaced", "pending", "same", "between-attempts", "not-on-air"), NetworkSwitch.entries.map { it.wire })
  }

  @Test
  fun `the network-switched line is the allow-list's, each key plain or a scalar`() {
    val list = Shared.read("scrub/allow-list.json")
    val rig = liveOnCellular()
    rig.on(wifi)
    val line = rig.records("network-switched").single()
    assertTrue(line.kind in list.getJSONObject("kinds").keySet())
    val plain = list.getJSONArray("plain").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    val scalars = list.getJSONObject("scalars").keySet()
    assertEquals(emptyList(), line.fields.map { it.first }.filterNot { it in plain || it in scalars })
  }

  // N49 fix 3: a replaced switch is a new link. N53: the regulator restarts for it, the target and the cut included.

  private fun MachineRig.failedAt(bps: Int, cutAtMs: Long, target: Int) {
    phase = phase.withSession(session.copy(regulation = session.regulation.copy(targetBps = target, lastCutAtMs = cutAtMs, failed = FailedRate(bps, cutAtMs))))
  }

  @Test
  fun `N53 a replaced switch restarts the regulator - the start rate, no cut to wait out, and Weak signal clears`() {
    val rig = liveOnCellular()
    rig.failedAt(1_000_000, rig.mono, target = Encode.FLOOR_BPS)
    assertTrue(DegradeReason.POOR_UPLINK in Projection.reasons(rig.session, rig.now), "the rig's cut shows")
    val commands = rig.on(wifi)
    val regulation = rig.session.regulation
    assertNull(regulation.failed)
    assertEquals(Encode.START_BPS, regulation.targetBps, "the new link starts where a session does")
    assertNull(regulation.lastCutAtMs, "nothing the old link cut is waited out")
    assertFalse(DegradeReason.POOR_UPLINK in Projection.reasons(rig.session, rig.now))
    // The new attempt is asked at it, and the record says the move before the connect.
    assertEquals(Encode.START_BPS, commands.replaces().single().next.startBitrateBps)
    val kinds = commands.filterIsInstance<Command.Record>().map { it.entry.kind }
    assertEquals(listOf("network-switched", "bitrate", "connecting"), kinds)
    val line = rig.records("bitrate").single()
    assertEquals(listOf("from" to Encode.FLOOR_BPS, "to" to Encode.START_BPS, "reason" to BitrateMove.NEW_NETWORK), line.fields)
  }

  @Test
  fun `N49 fix 3 a replace held for its connect restarts the regulator too`() {
    val rig = liveOnCellular()
    rig.on(wifi)
    rig.on(cellular)
    rig.failedAt(1_000_000, rig.mono, target = Encode.FLOOR_BPS)
    rig.send(Input.Connected(2))
    assertNull(rig.session.regulation.failed)
    assertNull(rig.session.regulation.lastCutAtMs)
    assertEquals(Encode.START_BPS, rig.session.regulation.targetBps)
  }

  @Test
  fun `N53 a switch between attempts restarts the regulator, since the next attempt is on the new network`() {
    val rig = liveOnCellular()
    rig.send(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, null))
    rig.failedAt(1_000_000, rig.mono, target = Encode.FLOOR_BPS)
    val commands = rig.on(wifi)
    assertNull(rig.session.regulation.failed)
    assertEquals(Encode.START_BPS, rig.session.regulation.targetBps)
    assertEquals(listOf("network-switched", "bitrate"), commands.filterIsInstance<Command.Record>().map { it.entry.kind })
    assertTrue(commands.none { it is Command.SetBitrate }, "nothing is on air to hear it: the next connect carries it")
    rig.advance(SessionMachine.RETRY_MS)
    assertEquals(Encode.START_BPS, rig.connects().last().startBitrateBps)
  }

  @Test
  fun `N53 a switch the attempt is already on restarts nothing`() {
    val rig = liveOnCellular()
    rig.failedAt(1_000_000, rig.mono, target = Encode.FLOOR_BPS)
    rig.on(cellular)
    assertEquals(FailedRate(1_000_000, rig.mono), rig.session.regulation.failed)
    assertEquals(Encode.FLOOR_BPS, rig.session.regulation.targetBps)
  }

  // N49 fix 4: the delivery watch after a replaced switch.

  private val masterUrl = Configs.PLAYBACK_URL + "?clientBandwidthHint=0.1"
  private val masterText = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3128000\nstream_720/video.m3u8\n"

  private fun media(head: Long) =
    "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:${head - 2}\n" + (head - 2..head).joinToString("") { "#EXTINF:2.000,\ns$it.ts\n" }

  /** Live on 4G with a playlist that moves until [frozen] says it stops: Cloudflare holding the old publisher. */
  private fun deliveredThenHeld(frozen: () -> Boolean): MachineRig {
    var head = 100L
    val rig = MachineRig()
    rig.playlists = { url -> FetchResult.Body(if (url == masterUrl) masterText else media(if (frozen()) head else ++head)) }
    rig.send(Input.Network(true, cellular))
    rig.live()
    rig.advance(10_000)
    check(rig.snapshot.delivery == Delivery.OK) { "never delivered" }
    return rig
  }

  @Test
  fun `N49 fix 4 the grace is 45 s - Cloudflare's read timeout, as N49 reads it`() {
    assertEquals(45_000L, DeliveryWatch.SWITCH_GRACE_MS)
  }

  @Test
  fun `N49 fix 4 a replaced switch restarts the delivery watch's session, as a forced new session does`() {
    var frozen = false
    val rig = deliveredThenHeld { frozen }
    frozen = true
    rig.on(wifi)
    val watch = rig.session.delivery
    assertNull(watch.head)
    assertFalse(watch.advancedInSession)
    assertEquals(0, watch.sinceAdvanceMs)
    assertEquals(Delivery.UNKNOWN, watch.delivery)
  }

  @Test
  fun `N49 fix 4 no not-delivered verdict until SWITCH_GRACE_MS after a replaced switch, and one after it`() {
    var frozen = false
    val rig = deliveredThenHeld { frozen }
    frozen = true
    val switchedAt = rig.mono
    rig.on(wifi)
    rig.resumedOn(2)
    var verdictAt: Long? = null
    rig.advance(DeliveryWatch.SWITCH_GRACE_MS + 4_000) { if (verdictAt == null && rig.records("not-delivered").isNotEmpty()) verdictAt = rig.mono }
    val at = verdictAt ?: error("no verdict after the grace")
    assertTrue(at - switchedAt >= DeliveryWatch.SWITCH_GRACE_MS, "verdict ${at - switchedAt} ms after the switch")
    assertTrue(at - switchedAt <= DeliveryWatch.SWITCH_GRACE_MS + DeliveryWatch.POLL_MS, "and at the first poll past it: ${at - switchedAt}")
  }

  @Test
  fun `N49 fix 4 a playlist that moves after the switch clears nothing it should not, and gives no verdict`() {
    var frozen = false
    val rig = deliveredThenHeld { frozen }
    frozen = true
    rig.on(wifi)
    rig.resumedOn(2)
    rig.advance(30_000)
    frozen = false
    rig.advance(30_000)
    assertEquals(emptyList(), rig.records("not-delivered"))
    assertEquals(Delivery.OK, rig.snapshot.delivery)
  }

  @Test
  fun `without a switch the not-delivered verdict still comes at NOT_DELIVERED_MS`() {
    var frozen = false
    val rig = deliveredThenHeld { frozen }
    frozen = true
    val frozeAt = rig.mono
    var verdictAt: Long? = null
    rig.advance(DeliveryWatch.NOT_DELIVERED_MS + 4_000) { if (verdictAt == null && rig.records("not-delivered").isNotEmpty()) verdictAt = rig.mono }
    val at = verdictAt ?: error("no verdict")
    assertTrue(at - frozeAt < DeliveryWatch.SWITCH_GRACE_MS)
  }

  /**
   * Review of 98c12ffa (c): a not-validated report carries its network too, so an attempt asked while the default is
   * not validated yet keeps the network it opened on, never adopting the next one validated. Here the default moved
   * to Wi-Fi, not validated yet, and the drop's retry opened there; then 4G validates as the default again.
   */
  @Test
  fun `an attempt asked on an unvalidated default keeps its true network, and another validated replaces it`() {
    val rig = liveOnCellular()
    rig.send(Input.Network(false, wifi))
    assertEquals(wifi, rig.phase.defaultNetwork, "held, though not validated")
    rig.send(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, null))
    rig.advance(2_000)
    assertEquals(wifi, rig.session.attemptNetwork, "the retry opened on Wi-Fi")
    rig.resumedOn(2)
    val renewed = rig.on(cellular).replaces().single()
    assertEquals(2, renewed.previousAttemptId)
    assertEquals(listOf("replaced"), rig.switchLines())
  }

  @Test
  fun `an attempt asked on an unvalidated default is not replaced when that network validates`() {
    val rig = liveOnCellular()
    rig.send(Input.Network(false, wifi))
    rig.send(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, null))
    rig.advance(2_000)
    rig.resumedOn(2)
    assertFalse(rig.on(wifi).opens())
    assertEquals(wifi, rig.session.attemptNetwork)
    assertEquals(emptyList(), rig.switchLines(), "validated where it was opened: no switch, and no network before it to say same against")
  }

  /**
   * Review of 98c12ffa (b), N49: Cloudflare is read to hold the old publisher as the input's source for about 45 s. A
   * reconnect after a drop that opens on another network than the last attempt that published gets the switch's grace,
   * as a replaced switch does: no not-delivered verdict for [DeliveryWatch.SWITCH_GRACE_MS] from its ask.
   */
  @Test
  fun `a reconnect after a drop on another network than the publisher's gets the switch grace`() {
    var frozen = false
    val rig = deliveredThenHeld { frozen }
    frozen = true
    rig.lost()
    rig.send(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, null))
    rig.on(wifi)
    rig.advance(2_000)
    val askedAt = rig.mono
    assertEquals(wifi, rig.session.attemptNetwork)
    assertEquals(askedAt + DeliveryWatch.SWITCH_GRACE_MS, rig.session.delivery.verdictNotBeforeMs)
    rig.resumedOn(2)
    var verdictAt: Long? = null
    rig.advance(DeliveryWatch.SWITCH_GRACE_MS + 4_000) { if (verdictAt == null && rig.records("not-delivered").isNotEmpty()) verdictAt = rig.mono }
    val at = verdictAt ?: error("no verdict after the grace")
    assertTrue(at - askedAt >= DeliveryWatch.SWITCH_GRACE_MS, "verdict ${at - askedAt} ms after the reconnect was asked")
  }

  @Test
  fun `a reconnect after a drop on the publisher's own network gets no grace`() {
    var frozen = false
    val rig = deliveredThenHeld { frozen }
    frozen = true
    rig.send(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, null))
    rig.advance(2_000)
    assertEquals(cellular, rig.session.attemptNetwork)
    assertNull(rig.session.delivery.verdictNotBeforeMs)
  }

  /**
   * N49, binding: the glue binds each SRT socket to the network its attempt was asked on, so every connect carries
   * that network's handle ([Command.Connect.network]): the first, a switch's replacement, and a drop's retry alike. An
   * attempt asked while no network is known carries none, and the glue connects it unbound.
   */
  @Test
  fun `binding - each connect carries the network it was asked on, and none when none is known`() {
    val rig = liveOnCellular()
    rig.on(wifi)
    rig.resumedOn(2)
    rig.lost()
    rig.send(Input.Dropped(2, DropReason.ENDPOINT_CLOSED, null))
    rig.advance(2_000)
    assertEquals(listOf(cellular, wifi, null), rig.connects().map { it.network }, "4G, then the switch's Wi-Fi, then a retry with no network")
    assertEquals(listOf(1, 2, 3), rig.connects().map { it.attemptId })
  }
}
