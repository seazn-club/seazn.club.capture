package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cause split (owner, 2026-11-24): the phone's own link stuck reconnects at once, before the delivery watch's 20 s
 * verdict. The rule reads the link samples the regulator reads, at Cloudflare's SRT latency of 2000 ms. Every number is
 * the record's or worked by hand.
 */
class LinkStuckTest {
  private val latency = 2_000
  private val floor = Encode.FLOOR_BPS

  private fun sample(buffer: Int?, dropped: Long = 0, egress: Long? = 700_000) = LinkSample(dropped, 0, buffer, null, egress, 0)

  /** [readings] of (target after the reading, send buffer), one a second from 1 s; the time of the first verdict. */
  private fun firstVerdict(readings: List<Pair<Int, Int?>>, transport: Transport = Transport.SRT): Pair<Long, Stuck>? {
    var watch = StuckWatch()
    readings.forEachIndexed { i, (target, buffer) ->
      val at = 1_000L * (i + 1)
      val (next, stuck) = watch.read(sample(buffer), target, transport, latency, at)
      watch = next
      if (stuck != null) return at to stuck
    }
    return null
  }

  @Test
  fun `the rule's numbers - 75 percent of the latency, five readings over 5 s`() {
    assertEquals(75, StuckWatch.STUCK_BUFFER_PERCENT)
    assertEquals(5, StuckWatch.STUCK_READINGS)
    assertEquals(5_000L, StuckWatch.STUCK_MS)
  }

  /**
   * R-RTRF at the 4G death (N49): 1131 ms at 1354k, 2049 at 677k, 1902 at the floor at 09:55:12; the verdict came at
   * 09:55:30. The record has no reading after 09:55:12 (no floor cut wrote one), so the buffer is held at 1902 from there,
   * DEVICE-UNVERIFIED. The floor's first full reading is the run's start: 5 s later is 09:55:17, 13 s before the verdict.
   */
  @Test
  fun `R-RTRF's 4G death is stuck 5 s after the first full reading at the floor`() {
    val death = listOf(1_354_464 to 1_131, 677_232 to 2_049) + List(10) { floor to 1_902 }
    val (at, stuck) = firstVerdict(death) ?: error("never stuck")
    assertEquals(8_000L, at, "the floor from the reading at 3 s; 5 s on is 8 s")
    assertEquals(6, stuck.readings)
    assertEquals(5_000L, stuck.stuckMs)
  }

  /** Today's 2.4 GHz Wi-Fi run: 1020 ms with 28 lost, then 627 with 174 dropped, then stable at the floor. Never stuck. */
  @Test
  fun `the 2_4 GHz Wi-Fi transient never reads stuck`() {
    val transient = listOf(1_000_000 to 1_020, floor to 627) + List(120) { floor to 40 }
    assertNull(firstVerdict(transient))
  }

  @Test
  fun `a buffer just under 75 percent of the latency at the floor is never stuck, and at it is`() {
    assertNull(firstVerdict(List(60) { floor to 1_499 }))
    assertEquals(6_000L, firstVerdict(List(60) { floor to 1_500 })?.first)
  }

  @Test
  fun `above the floor nothing is stuck - the regulator cuts first`() {
    assertNull(firstVerdict(List(60) { (floor + 1) to 1_999 }))
  }

  @Test
  fun `one reading that drains, or that has no buffer, starts the run again`() {
    for (gap in listOf<Int?>(1_000, null, -336)) {
      val broken = List(4) { floor to 1_900 } + listOf(floor to gap) + List(10) { floor to 1_900 }
      val (at, stuck) = firstVerdict(broken) ?: error("$gap: never stuck")
      assertEquals(11_000L, at, "$gap: the run restarts at 6 s, and 5 s on is 11 s")
      assertEquals(6, stuck.readings, "$gap")
    }
  }

  @Test
  fun `five readings in under 5 s are not enough, nor 5 s with fewer readings`() {
    var watch = StuckWatch()
    for (at in listOf(1_000L, 1_500, 2_000, 2_500, 3_000, 3_500, 4_000, 4_500, 5_000, 5_500)) {
      val (next, stuck) = watch.read(sample(1_900), floor, Transport.SRT, latency, at)
      watch = next
      assertNull(stuck, "$at ms: ${(at - 1_000) / 500 + 1} readings over ${at - 1_000} ms")
    }
    assertIs<Stuck>(watch.read(sample(1_900), floor, Transport.SRT, latency, 6_000).second, "11 readings over 5 s")
    var sparse = StuckWatch()
    for (at in listOf(1_000L, 3_000, 5_000, 7_000)) sparse = sparse.read(sample(1_900), floor, Transport.SRT, latency, at).first
    assertIs<Stuck>(sparse.read(sample(1_900), floor, Transport.SRT, latency, 9_000).second, "5 readings over 8 s")
    var short = StuckWatch()
    for (at in listOf(1_000L, 3_000, 5_000)) short = short.read(sample(1_900), floor, Transport.SRT, latency, at).first
    assertNull(short.read(sample(1_900), floor, Transport.SRT, latency, 7_000).second, "4 readings over 6 s")
  }

  @Test
  fun `RTMPS has no send buffer, so it is never stuck by this rule`() {
    assertNull(firstVerdict(List(60) { floor to null }, Transport.RTMPS))
    assertNull(firstVerdict(List(60) { floor to 1_900 }, Transport.RTMPS))
  }

  @Test
  fun `a verdict starts the watch again`() {
    var watch = StuckWatch()
    var verdicts = 0
    for (i in 1..20) {
      val (next, stuck) = watch.read(sample(1_900), floor, Transport.SRT, latency, 1_000L * i)
      watch = next
      if (stuck != null) verdicts += 1
    }
    assertEquals(3, verdicts, "at 6 s, 12 s and 18 s")
  }

  // Through the machine.

  /** Counters for one reading a second: [bufferAt] at each, 90_000 bytes a second (720 kb/s), no drops. */
  private fun link(bufferAt: (Long) -> Int): (Long) -> LinkCounters {
    var bytes = 0L
    return { t ->
      bytes += 90_000
      LinkCounters(bytes, t, 0, 0, 0, 20, bufferAt(t), null)
    }
  }

  @Test
  fun `on air, a stuck link reconnects at once as a new ingest session, with a link-stuck line, before any playlist verdict`() {
    val rig = MachineRig()
    var stuckFrom = Long.MAX_VALUE
    rig.link = link { t -> if (t >= stuckFrom) 1_900 else 40 }
    rig.live()
    rig.phase = (rig.phase as Phase.OnAir).let { it.copy(session = it.session.copy(regulation = Regulation(floor))) }
    val since = (rig.state as SnapshotState.Publishing).sinceEpochMs
    stuckFrom = (rig.mono / 1_000 + 1) * 1_000
    var at: Long? = null
    rig.advance(10_000) { if (at == null && rig.sent<Command.StartNewSession>().isNotEmpty()) at = rig.mono }
    assertEquals(stuckFrom + StuckWatch.STUCK_MS, at, "5 s after the first stuck reading")
    val renewed = rig.sent<Command.StartNewSession>().single()
    assertEquals(1, renewed.previousAttemptId)
    val line = rig.records("link-stuck").single()
    assertEquals(
      listOf("attempt" to 1, "count" to 6, "target" to floor, "sendBufferMs" to 1_900, "egressBps" to 720_000L, "dropped" to 0L, "lost" to 0L),
      line.fields,
    )
    val reconnecting = assertIs<SnapshotState.Reconnecting>(rig.state)
    assertEquals(ReconnectCause.UPLINK_LOST, reconnecting.cause)
    assertEquals(since, reconnecting.sinceEpochMs, "the hold keeps the broadcast's start")
    assertTrue(rig.records("not-delivered").isEmpty())
    assertEquals(Transport.SRT, rig.session.fallback.current)
    assertEquals(0, rig.session.fallback.count, "no failure counted")
  }

  /**
   * Frames end an outage at their first advance, so a stuck verdict inside one needs the reading to land before them:
   * set here by hand, one reading short of the verdict, with an outage standing.
   */
  @Test
  fun `a stuck link in an outage keeps the outage's hold`() {
    val rig = MachineRig().live()
    val onAir = rig.phase as Phase.OnAir
    val held = Outage(Hold(Transport.SRT, 60_000, rig.mono - 2_000), ReconnectCause.VIDEO_STALLED)
    val primed = StuckWatch(sinceMs = rig.mono - 5_000, readings = 5)
    rig.phase = onAir.copy(session = onAir.session.copy(regulation = Regulation(floor)), outage = held, stuck = primed)
    rig.send(Input.Link(onAir.attemptId, LinkCounters(90_000, 1, 0, 0, 0, 20, 1_900, null)))
    assertEquals(1, rig.records("link-stuck").size)
    assertEquals(held, assertIs<Phase.Connecting>(rig.phase).outage)
  }

  @Test
  fun `the line is the allow-list's, each key plain or a scalar`() {
    val list = Shared.read("scrub/allow-list.json")
    assertTrue(list.getJSONObject("kinds").has("link-stuck"))
    val plain = list.getJSONArray("plain").let { a -> (0 until a.length()).map { a.getString(it) } }
    val scalars = list.getJSONObject("scalars").keySet()
    for (key in listOf("attempt", "count", "target", "sendBufferMs", "egressBps", "dropped", "lost")) {
      assertTrue(key in plain || key in scalars, key)
    }
  }

  @Test
  fun `a healthy link at the floor never reconnects`() {
    val rig = MachineRig()
    rig.link = link { 40 }
    rig.live()
    rig.phase = (rig.phase as Phase.OnAir).let { it.copy(session = it.session.copy(regulation = Regulation(floor))) }
    rig.advance(60_000)
    assertTrue(rig.records("link-stuck").isEmpty())
    assertFalse(rig.sent<Command.StartNewSession>().isNotEmpty())
  }
}
