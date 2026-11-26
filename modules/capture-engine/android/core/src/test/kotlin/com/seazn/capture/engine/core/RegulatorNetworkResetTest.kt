package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N53 rule 1 (stg, 2026-11-26, session 66753ccd): at 20:16:55 the network lost validation, SRT dropped at 20:16:59.9 and
 * connected again at 20:17:04 over a network not yet validated (N52's provisional connection). Drops at 20:17:07 cut the
 * restored 608_000 to the floor, with 11.4 Mbps of estimate, and validation came at 20:17:09.97; the target held the
 * floor to 20:22:07. Validation that ends a stretch in which the link was made again now restarts the regulator at the
 * start rate, as a session starts, and the encoder hears it at once.
 */
class RegulatorNetworkResetTest {
  private val wifi = 101L

  /** [bps] of egress on a clean link with SRT's estimate at 11.4 Mbps, read once a second. */
  private fun steady(bps: Long = 4_000_000): (Long) -> LinkCounters {
    var bytes = 0L
    return { t ->
      bytes += bps / 8
      LinkCounters(bytes, t, 0, 0, 0, 20, 30, 11_400_000)
    }
  }

  private fun MachineRig.atFloor(failedBps: Int) {
    val regulation = session.regulation.copy(targetBps = Encode.FLOOR_BPS, lastCutAtMs = mono, failed = FailedRate(failedBps, mono))
    phase = phase.withSession(session.copy(regulation = regulation))
  }

  /**
   * The episode to 20:17:07: live on validated Wi-Fi, validation lost, the drop, today's 2 s retry, connected while not
   * validated (provisional), and the drops cut to the floor 3 s on.
   */
  private fun episode2(descriptor: BroadcastDescriptor = Configs.descriptor()): MachineRig {
    val rig = MachineRig(descriptor = descriptor)
    rig.send(Input.Network(true, wifi))
    rig.live()
    rig.send(Input.Network(false, wifi))
    rig.send(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, null))
    rig.advance(SessionMachine.RETRY_MS)
    rig.send(Input.Connected(2))
    rig.advance(3_000)
    check((rig.phase as Phase.OnAir).provisional) { "not provisional" }
    rig.atFloor(608_000)
    return rig
  }

  @Test
  fun `N53 validation after a provisional connection restarts the floor at the start rate, and the encoder hears it`() {
    val rig = episode2()
    rig.advance(3_000)
    val commands = rig.send(Input.Network(true, wifi))
    val regulation = rig.session.regulation
    assertEquals(Encode.START_BPS, regulation.targetBps, "the start rate at validation itself, 0 s after it")
    assertNull(regulation.failed)
    assertNull(regulation.lastCutAtMs)
    assertTrue(Command.SetBitrate(2, Encode.START_BPS) in commands)
    assertTrue(Command.SetMaxBw(2, SrtBandwidth.maxBwBytesPerSecond(Encode.START_BPS)) in commands)
    val line = rig.records("bitrate").single()
    assertEquals(listOf("from" to Encode.FLOOR_BPS, "to" to Encode.START_BPS, "reason" to BitrateMove.NEW_NETWORK), line.fields)
    assertEquals(listOf("network", "bitrate"), commands.filterIsInstance<Command.Record>().map { it.entry.kind })
    // A busy picture on the 11.4 Mbps network: clean from the first reading, so the first raise is 10 s on, 1_800_000.
    rig.link = steady()
    rig.advance(11_000)
    assertEquals(1_800_000, rig.session.regulation.targetBps)
  }

  @Test
  fun `N53 validation while the next attempt waits restarts the regulator, and the connect carries the start rate`() {
    val rig = MachineRig()
    rig.send(Input.Network(true, wifi))
    rig.live()
    rig.send(Input.Network(false, wifi))
    rig.send(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, null))
    rig.atFloor(608_000)
    assertIs<Phase.Connecting>(rig.phase)
    rig.send(Input.Network(true, wifi))
    assertEquals(Encode.START_BPS, rig.session.regulation.targetBps)
    rig.advance(SessionMachine.RETRY_MS)
    assertEquals(Encode.START_BPS, rig.connects().last().startBitrateBps)
  }

  @Test
  fun `N53 a validation flap the connection outlived restarts nothing - it is the link the regulator was judging`() {
    val rig = MachineRig()
    rig.send(Input.Network(true, wifi))
    rig.live()
    rig.atFloor(608_000)
    val before = rig.session.regulation
    rig.send(Input.Network(false, wifi))
    val commands = rig.send(Input.Network(true, wifi))
    assertEquals(before, rig.session.regulation)
    assertTrue(rig.records("bitrate").isEmpty())
    assertTrue(commands.none { it is Command.SetBitrate })
  }

  @Test
  fun `N53 a second validated report restarts nothing more`() {
    val rig = episode2()
    rig.send(Input.Network(true, wifi))
    rig.atFloor(700_000)
    rig.send(Input.Network(true, wifi))
    assertEquals(Encode.FLOOR_BPS, rig.session.regulation.targetBps, "validated already: the rig's later cut stands")
    assertEquals(1, rig.records("bitrate").size)
  }

  @Test
  fun `N53 a validated report between attempts on a network that stayed validated restarts nothing`() {
    // The drop came on a validated network, and the report names the same one: nothing was made again unvalidated.
    val rig = MachineRig()
    rig.send(Input.Network(true, wifi))
    rig.live()
    rig.send(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, null))
    rig.atFloor(608_000)
    rig.send(Input.Network(true, wifi))
    assertEquals(Encode.FLOOR_BPS, rig.session.regulation.targetBps)
    assertEquals(FailedRate(608_000, rig.mono), rig.session.regulation.failed)
  }

  @Test
  fun `N53 validation with no session held regulates nothing`() {
    val rig = MachineRig().paired(validated = false)
    val commands = rig.send(Input.Network(true, wifi))
    assertIs<Phase.Paired>(rig.phase)
    assertTrue(commands.none { it is Command.Record && it.entry.kind == "bitrate" })
  }

  @Test
  fun `N53 on RTMPS the encoder hears the restart, with no SRT bandwidth cap`() {
    val rig = episode2(Configs.descriptor(primary = Configs.rtmps, fallback = null))
    val commands = rig.send(Input.Network(true, wifi))
    assertTrue(Command.SetBitrate(2, Encode.START_BPS) in commands)
    assertTrue(commands.none { it is Command.SetMaxBw })
  }
}
