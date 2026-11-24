package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R-RTRF (stg, 2026-11-24, session 5e5403ce; N49): cuts at the 500k floor recorded 500k as the rate that failed, so the
 * raise cap was 80% of it, 400k, under the floor, and nothing raised for 5 min after the last one. None of them wrote a
 * line. A cut at the floor now keeps the rate that failed, and says itself at most once a minute (`bitrate-floor`).
 * Every number is worked by hand.
 */
class BitrateRegulatorFloorTest {
  private val latency = 2_000

  private fun reading(egress: Long? = 4_000_000, buffer: Int? = 50, dropped: Long = 0) = LinkSample(dropped, 0, buffer, null, egress, bytes = 0)

  /** The record's 09:55:12 reading: 240 drops on a 1.9 s send buffer. */
  private val draining = reading(buffer = 1_900, dropped = 240)

  /** The record's backlog with no drops: 1.9 s of send buffer, over half SRT's 2 s latency. */
  private val backlog = reading(buffer = 1_900)

  /** Every reading from [fromMs] to [toMs], one a second, with the floor lines said and when. */
  private fun run(start: Regulation, fromMs: Long, toMs: Long, sample: (Long) -> LinkSample): Pair<Regulation, List<Pair<Long, FloorCut>>> {
    val lines = mutableListOf<Pair<Long, FloorCut>>()
    var state = start
    for (t in fromMs..toMs step 1_000) {
      val regulated = BitrateRegulator.regulate(state, sample(t), t, Transport.SRT, latency)
      regulated.floor?.let { lines += t to it }
      state = regulated.state
    }
    return state to lines
  }

  /**
   * The record's last move, 677_232 → 500_000 on drops at 1 s (half is 338_616, held to the floor), then drops on every
   * reading to 21 s. Each cut waits out one latency, 2 s, so the floor is cut at 3 s, 5 s, … 21 s.
   */
  private fun atFloorUntil21s(start: Regulation = Regulation(677_232)): Regulation = run(start, 1_000, 21_000) { draining }.first

  @Test
  fun `R-RTRF a run of cuts at the floor keeps the rate that failed, so a clean link raises 40 s after the last`() {
    val floored = atFloorUntil21s()
    assertEquals(500_000, floored.targetBps)
    assertEquals(FailedRate(677_232, 1_000), floored.failed, "the cut that moved the target named the rate; the floor's did not")
    // Clean from 22 s: the interval is due at 32 s, the cut's wait at 21 s + 30 s = 51 s. The cap is 80% of 677_232,
    // 6_772 × 80 = 541_760, under the 600_000 a 100k step would reach. 51 s is inside the last cut + 30 s + 10 s.
    val clean = { _: Long -> reading() }
    assertEquals(500_000, run(floored, 22_000, 50_000, clean).first.targetBps)
    val raised = run(floored, 22_000, 51_000, clean).first
    assertEquals(541_760, raised.targetBps)
    assertTrue(51_000 <= 21_000 + BitrateRegulator.RAISE_AFTER_CUT_MS + BitrateRegulator.RAISE_INTERVAL_MS)
  }

  @Test
  fun `N49 fix 5 the floor's rule is the last cut's, said or not`() {
    // Drops at the floor to 21 s, their line said; then a backlog at the floor at 24 s, inside the minute: not said.
    val floored = atFloorUntil21s()
    assertEquals(BitrateMove.DROPS, floored.floorReason)
    val held = BitrateRegulator.regulate(floored, backlog, 24_000, Transport.SRT, latency)
    assertNull(held.floor, "inside the minute")
    assertEquals(BitrateMove.BACKLOG, held.state.floorReason)
  }

  @Test
  fun `R-RTRF a cut at the floor is still a cut - Weak signal stays, and the raise waits 30 s from it`() {
    val floored = atFloorUntil21s()
    assertEquals(21_000L, floored.lastCutAtMs)
    // Clean from 22 s to 50 s: the cut at 21 s holds the raise; one counted from the move at 1 s would raise at 32 s.
    assertEquals(500_000, run(floored, 22_000, 50_000) { reading() }.first.targetBps)
  }

  @Test
  fun `R-RTRF a cut onto the floor by its clamp still names the rate it failed, and caps raises for 5 min`() {
    // 900_000 halves to 450_000, held to the floor: 900_000 failed at 1 s, a cap of 720_000 until 301 s. Floor cuts to
    // 21 s keep it. Clean from 22 s: 600_000 at 51 s, 720_000 (600_000 + 120_000, at the cap) at 61 s, then capped. At
    // 301 s the memory has lapsed: 720_000 + 144_000 = 864_000.
    val first = BitrateRegulator.regulate(Regulation(900_000), draining, 1_000, Transport.SRT, latency)
    assertEquals(BitrateMove.DROPS, first.move)
    assertNull(first.floor, "a cut that moved the target is a bitrate line, not a floor line")
    assertEquals(FailedRate(900_000, 1_000), first.state.failed)
    val floored = atFloorUntil21s(Regulation(900_000))
    assertEquals(FailedRate(900_000, 1_000), floored.failed)
    val clean = { _: Long -> reading() }
    assertEquals(600_000, run(floored, 22_000, 51_000, clean).first.targetBps)
    assertEquals(720_000, run(floored, 22_000, 61_000, clean).first.targetBps)
    assertEquals(720_000, run(floored, 22_000, 300_000, clean).first.targetBps)
    assertEquals(864_000, run(floored, 22_000, 301_000, clean).first.targetBps)
  }

  @Test
  fun `R-RTRF a cut at the floor with no rate failed before it names none`() {
    // A clean far-end restart forgot its episode's rate (afterDrop): a cut at the floor does not invent one.
    val state = BitrateRegulator.next(Regulation(Encode.FLOOR_BPS), backlog, 1_000, Transport.SRT, latency)
    assertNull(state.failed)
    assertEquals(1_000L, state.lastCutAtMs)
  }

  @Test
  fun `R-RTRF a failing drop at the floor keeps the rate that failed`() {
    // The regulator restart's halving is a cut too: at the floor it lands where it was, and names nothing new.
    val floored = Regulation(Encode.FLOOR_BPS, failed = FailedRate(677_232, 1_000), failingAtMs = 20_000)
    val dropped = BitrateRegulator.afterDrop(floored, nowMs = 21_000)
    assertEquals(500_000, dropped.targetBps)
    assertEquals(FailedRate(677_232, 1_000), dropped.failed)
    assertEquals(21_000L, dropped.lastCutAtMs)
    // Above the floor it still names the rate: 1_000_000 halves to 500_000.
    val above = BitrateRegulator.afterDrop(Regulation(1_000_000, failingAtMs = 20_000), nowMs = 21_000)
    assertEquals(FailedRate(1_000_000, 21_000), above.failed)
  }

  @Test
  fun `R-RTRF cuts at the floor say themselves once a minute, with how many there were`() {
    // 677_232 → 500_000 at 1 s is a move (no floor line). A backlog on every reading cuts the floor at 3 s, 5 s, …,
    // each 2 s after the last. The first line goes at once; the next at 63 s, counting the cuts at 5 s to 63 s,
    // (63 − 5) / 2 + 1 = 30; then 123 s, 65 s to 123 s, 30.
    val (_, lines) = run(Regulation(677_232), 1_000, 130_000) { t -> if (t == 1_000L) draining else backlog }
    val said = { count: Int -> FloorCut(BitrateMove.BACKLOG, count) }
    assertEquals(listOf(3_000L to said(1), 63_000L to said(30), 123_000L to said(30)), lines)
  }

  @Test
  fun `R-RTRF a floor line names the rule of the cut it is said on`() {
    val (_, lines) = run(Regulation(677_232), 1_000, 3_000) { draining }
    assertEquals(listOf(3_000L to FloorCut(BitrateMove.DROPS, 1)), lines)
  }

  @Test
  fun `R-RTRF a drop and a new attempt keep the floor line's minute and count`() {
    // Said at 3 s; cuts at 5 s and 7 s counted. A failing drop at 8 s halves onto the floor (no floor line), and with
    // a new attempt keeps both: the floor cuts at 10 s, 12 s, … (one latency after it) count on, so the next line is at
    // the first cut a minute after 3 s, 64 s, counting 2 + (64 − 10) / 2 + 1 = 30; never at 10 s, and never 28.
    val (before, said) = run(Regulation(677_232), 1_000, 7_000) { t -> if (t == 1_000L) draining else backlog }
    assertEquals(listOf(3_000L), said.map { it.first })
    val failing = before.copy(failingAtMs = 7_500)
    val reconnected = BitrateRegulator.attemptStarted(BitrateRegulator.afterDrop(failing, nowMs = 8_000))
    val (_, after) = run(reconnected, 9_000, 64_000) { backlog }
    assertEquals(listOf(64_000L to FloorCut(BitrateMove.BACKLOG, 30)), after)
  }
}
