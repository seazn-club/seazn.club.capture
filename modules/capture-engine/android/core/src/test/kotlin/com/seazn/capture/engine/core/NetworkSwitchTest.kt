package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * N49 (stg, 2026-11-24, session 5e5403ce): the operator moved the phone from 4G to Wi-Fi on air. The default network
 * stayed validated, so nothing was told, and the SRT socket stayed on 4G until the delivery watch's stall verdict 22 s
 * later; the reconnect itself took 0.76 s. A switch of the default network now replaces the attempt at once, on air
 * only, through the same path as a new ingest session.
 */
class NetworkSwitchTest {
  private fun MachineRig.switched(attemptId: Int = attempt!!): List<Command> = send(Input.NetworkSwitched(attemptId))

  private fun MachineRig.switchLines(): List<String> = records("network-switched").map { (it.field("result") as Wire).wire }

  /** Connected again on [attemptId] and a second of frames: the outage is over. */
  private fun MachineRig.resumedOn(attemptId: Int): MachineRig {
    send(Input.Connected(attemptId))
    advance(1_000)
    check(phase is Phase.OnAir && (phase as Phase.OnAir).outage == null) { "not resumed: ${phase.name}" }
    return this
  }

  @Test
  fun `N49 a switch on air replaces the attempt at once, on the transport in hand, and the record says why`() {
    val rig = MachineRig().live()
    val since = (rig.state as SnapshotState.Publishing).sinceEpochMs
    val commands = rig.switched(1)
    val renewed = commands.filterIsInstance<Command.StartNewSession>().single()
    assertEquals(1, renewed.previousAttemptId)
    assertEquals(2, renewed.next.attemptId)
    assertIs<SrtTarget>(renewed.next.target)
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
    val rig = MachineRig().live()
    val before = rig.session.fallback
    rig.switched(1)
    assertEquals(before, rig.session.fallback)
  }

  @Test
  fun `on RTMPS a switch replaces on RTMPS`() {
    val rig = MachineRig(descriptor = Configs.descriptor(primary = Configs.rtmps, fallback = Configs.srt)).live()
    val renewed = rig.switched(1).filterIsInstance<Command.StartNewSession>().single()
    assertIs<RtmpsTarget>(renewed.next.target)
    assertEquals(Transport.RTMPS, rig.records("connecting").last().field("transport"))
  }

  @Test
  fun `a second switch while the first replace is in flight starts no second one`() {
    val rig = MachineRig().live()
    rig.switched(1)
    // Attempt 2 is asked for and has not answered: the attempt in hand is 2, and the outage stands.
    assertEquals(2, rig.attempt)
    assertTrue(rig.switched(2).none { it is Command.StartNewSession || it is Command.Connect })
    // Connected, before its first frame: still the outage.
    rig.send(Input.Connected(2))
    assertTrue(rig.switched(2).none { it is Command.StartNewSession || it is Command.Connect })
    assertEquals(listOf(1, 2), rig.connects().map { it.attemptId }, "the first connect and the replace")
    assertEquals(listOf("replaced", "in-outage", "in-outage"), rig.switchLines())
  }

  @Test
  fun `once resumed, the next switch replaces again`() {
    val rig = MachineRig().live()
    rig.switched(1)
    rig.resumedOn(2)
    rig.switched(2)
    assertEquals(listOf(1, 2, 3), rig.connects().map { it.attemptId })
    assertEquals(listOf("replaced", "replaced"), rig.switchLines())
  }

  @Test
  fun `a switch during an uplink outage starts no second replace`() {
    val rig = MachineRig().live()
    rig.send(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, null))
    rig.switched(1)
    rig.advance(2_000)
    rig.switched(2)
    assertEquals(listOf(1, 2), rig.connects().map { it.attemptId }, "the first connect and the drop's retry")
    assertEquals(listOf("in-outage", "in-outage"), rig.switchLines())
  }

  @Test
  fun `a switch seen on an attempt already replaced is stale`() {
    // Dropped and back on attempt 2: a switch stamped with attempt 1 was seen before that reconnect, which opened its
    // socket on the new network.
    val rig = MachineRig().live()
    rig.send(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, null))
    rig.advance(2_000)
    rig.resumedOn(2)
    assertTrue(rig.switched(1).none { it is Command.StartNewSession || it is Command.Connect })
    assertEquals(listOf("stale"), rig.switchLines())
    assertEquals(1, rig.records("network-switched").single().field("attempt"))
    assertIs<SnapshotState.Publishing>(rig.state)
  }

  @Test
  fun `off air a switch is only recorded - idle, paired, armed, connecting the first time`() {
    val idle = MachineRig()
    assertEquals(listOf("network-switched"), idle.switched(1).filterIsInstance<Command.Record>().map { it.entry.kind })
    val paired = MachineRig().paired()
    paired.switched(1)
    val armed = MachineRig().armed()
    armed.switched(1)
    val connecting = MachineRig().armed()
    connecting.ready()
    assertIs<Phase.Connecting>(connecting.phase)
    connecting.switched(1)
    for ((name, rig) in listOf("idle" to idle, "paired" to paired, "armed" to armed, "connecting" to connecting)) {
      assertEquals(listOf("not-on-air"), rig.switchLines(), name)
      assertTrue(rig.sent<Command.StartNewSession>().isEmpty(), name)
      assertTrue(rig.sent<Command.Rebuild>().isEmpty(), name)
    }
    assertEquals(listOf(1), connecting.connects().map { it.attemptId }, "the first connect only")
    assertEquals("paired", paired.records("network-switched").single().field("phase"))
  }

  @Test
  fun `a switch tells nothing of validation - no network line, and readiness as it was`() {
    val rig = MachineRig().paired()
    val before = rig.snapshot
    rig.switched(1)
    assertTrue(rig.records("network").size == 1, "the pairing's own network line only: ${rig.records("network")}")
    assertTrue(rig.phase.networkValidated)
    assertEquals(before.pairing, rig.snapshot.pairing)
  }

  @Test
  fun `the switch line's words`() {
    assertEquals(listOf("replaced", "in-outage", "stale", "not-on-air"), NetworkSwitch.entries.map { it.wire })
  }

  @Test
  fun `the network-switched line is the allow-list's, each key plain or a scalar`() {
    val list = Shared.read("scrub/allow-list.json")
    val rig = MachineRig().live()
    rig.switched(1)
    val line = rig.records("network-switched").single()
    assertTrue(line.kind in list.getJSONObject("kinds").keySet())
    val plain = list.getJSONArray("plain").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    val scalars = list.getJSONObject("scalars").keySet()
    assertEquals(emptyList(), line.fields.map { it.first }.filterNot { it in plain || it in scalars })
  }
}
