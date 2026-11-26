package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N52 (R-SAV6, stg 2026-11-26): airplane mode on and off for about 5 s. SRT reconnected at once over a default network
 * Android had not validated, broke again 5.7 s later, and waited today's 2 s before its next try; validation came 3 s
 * after that. Owner ruling (2026-11-26, option B): reconnect at once, as today, but a connection made while the default
 * network is not validated is provisional. Its break retries at once, within the flap bound ([ProvisionalRetries]), and
 * counts toward nothing; validation where it was opened confirms it, and validation of another network replaces it.
 */
class ProvisionalReconnectTest {
  private val cellular = 100L
  private val wifi = 101L

  /**
   * Live on validated 4G; then 4G loses validation, attempt 1 drops, today's 2 s retry asks attempt 2, and it connects
   * while the network is still not validated: provisional, and resumed.
   */
  private fun provisional(descriptor: BroadcastDescriptor = Configs.descriptor(), network: Long? = cellular): MachineRig {
    val rig = MachineRig(descriptor = descriptor)
    rig.send(Input.Network(true, cellular))
    rig.live()
    rig.send(if (network == null) Input.Network(false) else Input.Network(false, network))
    rig.send(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, null))
    rig.advance(2_000)
    rig.send(Input.Connected(2))
    rig.advance(1_000)
    val onAir = assertIs<Phase.OnAir>(rig.phase)
    check(onAir.outage == null) { "not resumed" }
    return rig
  }

  private val MachineRig.onAir: Phase.OnAir
    get() = phase as Phase.OnAir

  private fun List<Command>.connects(): List<Command.Connect> = filterIsInstance<Command.Connect>()

  private fun List<Command>.opens(): Boolean = any { it is Command.StartNewSession || it is Command.Connect || it is Command.Rebuild }

  private fun MachineRig.switchLines(): List<RecordEntry> = records("network-switched")

  @Test
  fun `a connection made on a validated network is not provisional, and the record says validated`() {
    val rig = MachineRig()
    rig.send(Input.Network(true, cellular))
    rig.live()
    assertFalse(rig.onAir.provisional)
    assertEquals(true, rig.records("connected").single().field("validated"))
  }

  @Test
  fun `a connection made while the network is not validated is provisional, and the record says so`() {
    val rig = provisional()
    assertTrue(rig.onAir.provisional)
    assertEquals(listOf(true, false), rig.records("connected").map { it.field("validated") })
  }

  @Test
  fun `validated before the drop - today's behaviour, a 2 s wait`() {
    val rig = MachineRig()
    rig.send(Input.Network(true, cellular))
    rig.live()
    val commands = rig.send(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, null))
    assertTrue(commands.connects().isEmpty(), "no connect in the drop's own step")
    val waiting = assertIs<ConnectStep.Waiting>((rig.phase as Phase.Connecting).step)
    assertEquals(rig.mono + SessionMachine.RETRY_MS, waiting.connectAtMs)
    assertNull(rig.records("dropped").single().fields.firstOrNull { it.first == "retryMs" }, "no provisional wait said")
  }

  @Test
  fun `a validated connection whose network loses validation is not provisional - its drop waits as today`() {
    val rig = MachineRig()
    rig.send(Input.Network(true, cellular))
    rig.live()
    rig.send(Input.Network(false, cellular))
    assertFalse(rig.onAir.provisional)
    rig.send(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, null))
    assertEquals(rig.mono + SessionMachine.RETRY_MS, ((rig.phase as Phase.Connecting).step as ConnectStep.Waiting).connectAtMs)
  }

  @Test
  fun `a provisional break retries at once, in the same step, with no backoff and no budget spent`() {
    val rig = provisional()
    val fallback = rig.session.fallback
    val retries = rig.session.retries
    val commands = rig.send(Input.Dropped(2, DropReason.ENDPOINT_CLOSED, null))
    assertEquals(listOf(3), commands.connects().map { it.attemptId }, "attempt 3 asked in the drop's own step")
    assertEquals(ConnectStep.Requested(3, rig.mono), (rig.phase as Phase.Connecting).step)
    assertEquals(fallback, rig.session.fallback, "the fallback count is untouched")
    assertEquals(retries, rig.session.retries, "the retry ladder is untouched")
    val line = rig.records("dropped").last()
    assertEquals(false, line.field("validated"))
    assertEquals(false, line.field("counted"))
    assertEquals(0L, line.field("retryMs"))
  }

  @Test
  fun `a provisional break keeps the outage and its hold - the platform's clock is not the phone's budget`() {
    val rig = provisional()
    rig.send(Input.Dropped(2, DropReason.ENDPOINT_CLOSED, null))
    val outage = (rig.phase as Phase.Connecting).outage!!
    assertEquals(rig.mono, outage.hold.startedAtMs, "the hold starts at the drop, as every outage's does")
  }

  @Test
  fun `three provisional breaks far apart never reach the fallback, which three counted drops would`() {
    val rig = provisional()
    for (attempt in 2..4) {
      rig.at(rig.mono + 2_000)
      rig.send(Input.Dropped(attempt, DropReason.ENDPOINT_CLOSED, null))
      rig.send(Input.Connected(attempt + 1))
      assertTrue(rig.onAir.provisional)
    }
    assertEquals(Transport.SRT, rig.session.fallback.current)
    assertEquals(0, rig.session.fallback.count)
    assertTrue(rig.records("fell-back").isEmpty())
  }

  @Test
  fun `before live a provisional break retries at once and the never-live ladder does not move`() {
    val rig = MachineRig()
    rig.send(Input.Network(true, cellular))
    rig.armed()
    rig.ready()
    rig.send(Input.Network(false, cellular))
    rig.send(Input.Connected(1))
    assertTrue(rig.onAir.provisional)
    val commands = rig.send(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, null))
    assertEquals(listOf(2), commands.connects().map { it.attemptId })
    assertEquals(NeverLiveRetries(), rig.session.retries)
  }

  @Test
  fun `a break under a second after the last immediate retry waits out the spacing`() {
    val rig = provisional()
    rig.send(Input.Dropped(2, DropReason.ENDPOINT_CLOSED, null))
    rig.at(rig.mono + 300)
    rig.send(Input.Connected(3))
    val commands = rig.send(Input.Dropped(3, DropReason.ENDPOINT_CLOSED, null))
    assertTrue(commands.connects().isEmpty())
    assertEquals(rig.mono + 700, ((rig.phase as Phase.Connecting).step as ConnectStep.Waiting).connectAtMs)
    assertEquals(700L, rig.records("dropped").last().field("retryMs"))
  }

  @Test
  fun `validation flapping on the same network never reconnects, and a confirmed connection's drop waits as today`() {
    val rig = provisional()
    val waits = mutableListOf<Long>()
    for (attempt in 2..6) {
      rig.at(rig.mono + 1_500)
      if (rig.phase is Phase.Connecting) rig.advance(SessionMachine.RETRY_MS)
      val asked = rig.attempt!!
      rig.send(Input.Connected(asked))
      // Validation flaps while the provisional connection publishes: the same network never replaces it.
      assertFalse(rig.send(Input.Network(true, cellular)).opens())
      assertFalse(rig.send(Input.Network(false, cellular)).opens())
      rig.send(Input.Connected(asked))
      val dropAt = rig.mono
      val commands = rig.send(Input.Dropped(asked, DropReason.ENDPOINT_CLOSED, null))
      val step = (rig.phase as Phase.Connecting).step
      waits += if (commands.connects().isNotEmpty()) 0L else (step as ConnectStep.Waiting).connectAtMs - dropAt
    }
    // Each validated report on the same network confirmed the connection, so none of these drops was provisional.
    assertEquals(List(5) { SessionMachine.RETRY_MS }, waits)
  }

  @Test
  fun `a storm of provisional breaks - three immediate retries a window, then today's 2 s`() {
    val rig = provisional()
    val waits = mutableListOf<Long>()
    var attempt = 2
    repeat(5) {
      rig.at(rig.mono + 1_500)
      val commands = rig.send(Input.Dropped(attempt, DropReason.ENDPOINT_CLOSED, null))
      val step = (rig.phase as Phase.Connecting).step
      waits += if (commands.connects().isNotEmpty()) 0L else (step as ConnectStep.Waiting).connectAtMs - rig.mono
      if (step is ConnectStep.Waiting) rig.advance(SessionMachine.RETRY_MS)
      attempt = rig.attempt!!
      rig.send(Input.Connected(attempt))
      assertTrue(rig.onAir.provisional)
    }
    assertEquals(listOf(0L, 0L, 0L, SessionMachine.RETRY_MS, SessionMachine.RETRY_MS), waits)
    assertNull(rig.records("dropped")[4].fields.firstOrNull { it.first == "retryMs" }, "past the cap a drop is today's, with no provisional wait")
  }

  @Test
  fun `validated where the provisional connection was opened confirms it - no reconnect`() {
    val rig = provisional()
    assertFalse(rig.send(Input.Network(true, cellular)).opens())
    assertFalse(rig.onAir.provisional)
    assertTrue(rig.switchLines().isEmpty())
    // Confirmed, its break is today's: a 2 s wait.
    rig.send(Input.Dropped(2, DropReason.ENDPOINT_CLOSED, null))
    assertEquals(rig.mono + SessionMachine.RETRY_MS, ((rig.phase as Phase.Connecting).step as ConnectStep.Waiting).connectAtMs)
  }

  @Test
  fun `review of #79 - a validated fact naming no network still confirms the provisional connection, with no reconnect`() {
    // Asked while no network was known, so the validated report names none: there is no handle to replace it on.
    val rig = provisional(network = null)
    assertTrue(rig.onAir.provisional)
    assertFalse(rig.send(Input.Network(true, null)).opens())
    assertFalse(rig.onAir.provisional, "provisional always means not validated since it connected")
    rig.send(Input.Dropped(2, DropReason.ENDPOINT_CLOSED, null))
    assertEquals(rig.mono + SessionMachine.RETRY_MS, ((rig.phase as Phase.Connecting).step as ConnectStep.Waiting).connectAtMs)
  }

  @Test
  fun `review of #79 Q1 - provisional connections that never publish a frame do not starve the hold - it ends on time`() {
    val rig = provisional(Configs.descriptor(holdWindowSeconds = mapOf(Transport.SRT to 20, Transport.RTMPS to 20)))
    rig.send(Input.Dropped(2, DropReason.ENDPOINT_CLOSED, null))
    val hold = (rig.phase as Phase.Connecting).outage!!.hold
    var drops = 1
    while (rig.phase.session != null) {
      check(rig.mono < hold.startedAtMs + 30_000) { "the hold never ended: ${rig.phase.name}" }
      val phase = rig.phase
      when {
        phase is Phase.OnAir -> {
          assertEquals(hold, phase.outage?.hold, "the hold is the first drop's, whatever the connects")
          rig.send(Input.Dropped(phase.attemptId, DropReason.ENDPOINT_CLOSED, null))
          drops++
        }
        phase is Phase.Connecting && phase.step is ConnectStep.Requested -> {
          assertEquals(hold, phase.outage?.hold)
          rig.send(Input.Connected((phase.step as ConnectStep.Requested).attemptId))
        }
      }
      rig.advance(500, feeding = false)
    }
    assertIs<Phase.Paired>(rig.phase)
    assertEquals(LastEnd.HOLD_WINDOW_EXPIRED, rig.phase.pairing?.lastEnd)
    assertTrue(rig.mono - hold.startedAtMs in 20_000L..20_500L, "ended ${rig.mono - hold.startedAtMs} ms after the first drop")
    assertTrue(drops > ProvisionalRetries.MAX_IMMEDIATE, "past the bound too: $drops drops")
  }

  @Test
  fun `a second validated report is idempotent`() {
    val rig = provisional()
    rig.send(Input.Network(true, cellular))
    assertFalse(rig.send(Input.Network(true, cellular)).opens())
    assertFalse(rig.onAir.provisional)
    assertEquals(2, rig.records("connected").size)
  }

  @Test
  fun `validated on another network replaces the provisional connection, and the record says why`() {
    val rig = provisional()
    val renewed = rig.send(Input.Network(true, wifi)).filterIsInstance<Command.StartNewSession>().single()
    assertEquals(2, renewed.previousAttemptId)
    assertEquals(wifi, rig.session.attemptNetwork)
    val line = rig.switchLines().single()
    assertEquals(NetworkSwitch.REPLACED, line.field("result"))
    assertEquals("provisional", line.field("reason"))
    rig.send(Input.Connected(3))
    assertFalse(rig.onAir.provisional, "connected on a validated network")
  }

  @Test
  fun `a provisional connection asked on no known network is replaced when a network validates - its network is unknown`() {
    val rig = provisional(network = null)
    assertNull(rig.session.attemptNetwork)
    val renewed = rig.send(Input.Network(true, cellular)).filterIsInstance<Command.StartNewSession>().single()
    assertEquals(2, renewed.previousAttemptId)
    assertEquals(cellular, rig.session.attemptNetwork)
    assertEquals("provisional", rig.switchLines().single().field("reason"))
  }

  @Test
  fun `no network at all - a provisional break still retries at once, asked on no network`() {
    val rig = provisional()
    rig.send(Input.Network(false))
    val connect = rig.send(Input.Dropped(2, DropReason.ENDPOINT_CLOSED, null)).connects().single()
    assertNull(connect.network)
  }

  @Test
  fun `a process restart mid-provisional - nothing is carried over, and the new session's bound starts empty`() {
    // The old process spent its bound; a process death ends that session, and the bound was never stored.
    val old = provisional()
    repeat(3) {
      old.at(old.mono + 2_000)
      old.send(Input.Dropped(old.attempt!!, DropReason.ENDPOINT_CLOSED, null))
      old.send(Input.Connected(old.attempt!!))
    }
    assertEquals(3, old.session.provisional.recentAtMs.size)
    // The new process: the engine starts from Idle, and its network watch tells the default as it finds it.
    val rig = provisional()
    assertEquals(ProvisionalRetries(), rig.session.provisional)
    assertEquals(listOf(3), rig.send(Input.Dropped(2, DropReason.ENDPOINT_CLOSED, null)).connects().map { it.attemptId })
  }

  @Test
  fun `on RTMPS a provisional break retries at once on RTMPS`() {
    val rig = provisional(Configs.descriptor(primary = Configs.rtmps, fallback = Configs.srt))
    val connect = rig.send(Input.Dropped(2, DropReason.ENDPOINT_CLOSED, null)).connects().single()
    assertIs<RtmpsTarget>(connect.target)
    assertEquals(0, rig.session.fallback.count)
  }

  @Test
  fun `after the fallback, RTMPS gets the same provisional treatment and the fallback stays final`() {
    val rig = MachineRig()
    rig.send(Input.Network(true, cellular))
    rig.live()
    rig.session.fallback.let { check(it.current == Transport.SRT) }
    // Three short counted drops on a validated network: C1's fallback to RTMPS.
    for (attempt in 1..3) {
      rig.send(Input.Dropped(attempt, DropReason.ENDPOINT_CLOSED, null))
      rig.advance(SessionMachine.RETRY_MS)
      rig.send(Input.Connected(attempt + 1))
    }
    assertEquals(Transport.RTMPS, rig.session.fallback.current)
    rig.send(Input.Network(false, cellular))
    rig.send(Input.Dropped(4, DropReason.ENDPOINT_CLOSED, null))
    rig.advance(SessionMachine.RETRY_MS)
    rig.send(Input.Connected(5))
    assertTrue(rig.onAir.provisional)
    val connect = rig.send(Input.Dropped(5, DropReason.ENDPOINT_CLOSED, null)).connects().single()
    assertIs<RtmpsTarget>(connect.target)
    assertEquals(Transport.RTMPS, rig.session.fallback.current)
  }
}
