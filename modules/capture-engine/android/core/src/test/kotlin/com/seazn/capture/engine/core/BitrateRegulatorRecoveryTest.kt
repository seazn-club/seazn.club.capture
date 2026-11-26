package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N53 (stg, 2026-11-26, session 66753ccd, 40 min on air, CBR): the target sat at 0.7M or under for 10.8 min. Each time a
 * cascade of cuts ended on a rate just over the floor, and F-P5-7's failed-rate cap, 80% of it, held raises for 5 min:
 * 563_040 from 20:03:44 to 20:08:15, and 486_400 (under the floor) from 20:17:07 to 20:22:07, on a link that read 5-17
 * Mbps. A cap under two floors now holds 60 s, doubling each time the link fails again soon after one ran out. Times are
 * the record's, in ms from the first reading replayed; every expected number is worked by hand.
 */
class BitrateRegulatorRecoveryTest {
  private val latency = 2_000

  private fun reading(dropped: Long = 0, lost: Long = 0, buffer: Int? = 30, estimate: Long? = null, egress: Long? = 4_000_000) =
    LinkSample(dropped, lost, buffer, estimate, egress, bytes = 0)

  /** Readings every [stepMs] from [fromMs] to [toMs] inclusive, with every move and when. */
  private fun run(
    start: Regulation,
    fromMs: Long,
    toMs: Long,
    stepMs: Long = 1_000,
    sample: (Long, Regulation) -> LinkSample = { _, _ -> reading() },
  ): Pair<Regulation, List<Pair<Long, Regulated>>> {
    var state = start
    val moves = mutableListOf<Pair<Long, Regulated>>()
    var t = fromMs
    while (t <= toMs) {
      val regulated = BitrateRegulator.regulate(state, sample(t, state), t, Transport.SRT, latency)
      if (regulated.move != null) moves += t to regulated
      state = regulated.state
      t += stepMs
    }
    return state to moves
  }

  /**
   * 20:03:12.801 is 0. The backlog cut at 20:03:10.800 left 1_407_643 and failed 1_920_000. Drops (146, lost 128, 1.9 s
   * of buffer, estimate 2_676_000) halve it to 703_821 (the estimate allows 2_676_000 × 80% − 128_000 = 2_012_800).
   * At 2_001 (20:03:14.802), outside the 2 s drain, drops (181, lost 122, estimate 3_828_000) halve it to 351_910, held
   * to the floor, and 703_821 is the rate that failed: a cap of 7_038 × 80 = 563_040, under 1_000_000.
   */
  private fun episode1(): Regulation {
    val before = Regulation(1_407_643, lastCutAtMs = -2_001, failed = FailedRate(1_920_000, -2_001))
    val first = BitrateRegulator.regulate(before, reading(146, 128, 1_899, 2_676_000, 2_799_280), 0, Transport.SRT, latency)
    assertEquals(703_821, first.state.targetBps)
    val second = BitrateRegulator.regulate(first.state, reading(181, 122, 1_316, 3_828_000, 1_543_576), 2_001, Transport.SRT, latency)
    assertEquals(500_000, second.state.targetBps)
    assertEquals(FailedRate(703_821, 2_001), second.state.failed)
    return second.state
  }

  @Test
  fun `N53 episode 1 - the first raise is 30 s after the cut, and the cap holds 60 s, not 5 min`() {
    // Clean every 2 s from 4_001 (the record's cadence). The cut's wait ends at 32_001 (20:03:44.8, as recorded): 500_000
    // + 100_000, capped at 563_040. The cap held to 302_001 (20:08:15.1, as recorded); it now lapses at 62_001 (60 s after
    // the cut, 20:04:14.8), the next reading, which raises 20%: 563_040 + 112_608 = 675_648.
    val (_, moves) = run(episode1(), 4_001, 120_001, stepMs = 2_000)
    val raises = moves.filter { it.second.move == BitrateMove.RAISE }.map { it.first to it.second.state.targetBps }
    assertEquals(32_001L to 563_040, raises[0])
    assertEquals(62_001L to 675_648, raises[1])
    // From there it climbs every 10 s, uncapped: 810_777 at 72_001, 972_932 at 82_001.
    assertEquals(listOf(72_001L to 810_777, 82_001L to 972_932), raises.subList(2, 4))
  }

  @Test
  fun `N53 episode 2 on the same network - the floor's first raise is 60 s after the cut, not 5 min`() {
    // 20:17:07.006 is 0: drops (7, 1.9 s of buffer, the new network's estimate 11_400_000) cut the restored 608_000 to the
    // floor; it failed, a cap of 6_080 × 80 = 486_400, under the floor. Backlogs at the floor at 2 s and 4 s (the record's
    // two unsaid floor cuts) move the cut's wait to 34 s. Clean from 5 s: the raise falls due at 34 s, but the cap holds
    // it to 60 s; the record's held to 300 s (20:22:07). Then 500_000 + 100_000.
    val restored = Regulation(608_000, healthyTargetBps = 608_000)
    val cut = BitrateRegulator.regulate(restored, reading(7, 0, 1_933, 11_400_000, 798_211), 0, Transport.SRT, latency)
    assertEquals(500_000, cut.state.targetBps)
    assertEquals(486_400, BitrateRegulator.raiseCap(cut.state, 1_000))
    val (_, moves) = run(cut.state, 2_000, 120_000) { t, _ -> if (t <= 4_000) reading(buffer = 1_900) else reading() }
    val raises = moves.filter { it.second.move == BitrateMove.RAISE }
    assertEquals(60_000L, raises.first().first)
    assertEquals(600_000, raises.first().second.state.targetBps)
  }

  @Test
  fun `N53 a cap of two floors or more still holds 5 min, as F-P5-7 set`() {
    // 1_250_000 failed: a cap of 12_500 × 80 = 1_000_000, not under LOW_CAP_BPS. 999_999 failed: 799_920, under it.
    assertEquals(1_000_000, BitrateRegulator.LOW_CAP_BPS)
    assertEquals(BitrateRegulator.FAILED_RATE_MEMORY_MS, BitrateRegulator.holdMs(FailedRate(1_250_000, 0)))
    assertEquals(60_000L, BitrateRegulator.holdMs(FailedRate(999_999, 0)))
    val high = Regulation(1_000_000, failed = FailedRate(1_250_000, 0))
    assertEquals(1_000_000, BitrateRegulator.raiseCap(high, 299_999))
    assertEquals(Encode.CEILING_BPS, BitrateRegulator.raiseCap(high, 300_000))
    val low = Regulation(500_000, failed = FailedRate(999_999, 0))
    assertEquals(799_920, BitrateRegulator.raiseCap(low, 59_999))
    assertEquals(Encode.CEILING_BPS, BitrateRegulator.raiseCap(low, 60_000))
  }

  @Test
  fun `N53 the hold doubles with each repeat, and never passes 5 min`() {
    assertEquals(listOf(60_000L, 120_000L, 240_000L, 300_000L, 300_000L), (0..4).map(BitrateRegulator::lowCapHoldMs))
    assertEquals(120_000L, BitrateRegulator.holdMs(FailedRate(700_000, 0, repeats = 1)))
  }

  /** A link that carries 800k: a target over it drops packets on every reading, under it nothing. */
  private val carries800k = { _: Long, state: Regulation ->
    if (state.targetBps > 800_000) reading(dropped = 50, buffer = 1_500) else reading()
  }

  @Test
  fun `N53 oscillation guard - a link that keeps failing near the same rate is probed less and less often`() {
    // Each failure is 1 s after the probe over 800k. Holds of 60, 120, 240 and then 300 s, each from its failure, put the
    // failures at least 60, 120, 240 and 300 s apart: in 20 min, failures at 0 and then no sooner than 60, 180, 420, 720,
    // 1_020 s, so six at most. A fixed 60 s hold would allow one every 70 s or so, 17.
    val (end, moves) = run(Regulation(1_000_000), 0, 1_200_000, sample = carries800k)
    val cuts = moves.filter { it.second.move == BitrateMove.DROPS }.map { it.first }
    assertTrue(cuts.size in 2..6, "cuts at $cuts")
    val gaps = cuts.zipWithNext { a, b -> b - a }
    val floors = listOf(60_000L, 120_000L, 240_000L, 300_000L, 300_000L)
    gaps.forEachIndexed { i, gap -> assertTrue(gap >= floors[i], "gap $i of $gaps") }
    assertEquals(BitrateRegulator.LOW_CAP_MAX_REPEATS, end.failed?.repeats, "the hold reached 5 min and stayed")
  }

  @Test
  fun `N53 a cascade inside a hold is one failure, not a repeat`() {
    // 900_000 fails at 0 (cap 720_000, 60 s); 700_000 fails 2 s later, inside that hold: a first failure again.
    val first = Regulation(500_000, failed = FailedRate(900_000, 0))
    val cascade = BitrateRegulator.regulate(first.copy(targetBps = 700_000), reading(dropped = 9), 2_000, Transport.SRT, latency)
    assertEquals(FailedRate(700_000, 2_000, repeats = 0), cascade.state.failed)
  }

  @Test
  fun `N53 a failure soon after a low hold ran out is a repeat, and one 5 min after that is a first again`() {
    // 900_000 failed at 0; its 60 s hold ran out at 60 s. 800_000 fails at 61 s: a repeat. At 60 s + 300 s it is a first.
    val lapsed = Regulation(800_000, failed = FailedRate(900_000, 0))
    val again = BitrateRegulator.regulate(lapsed, reading(dropped = 9), 61_000, Transport.SRT, latency)
    assertEquals(1, again.state.failed?.repeats)
    val late = BitrateRegulator.regulate(lapsed, reading(dropped = 9), 360_000, Transport.SRT, latency)
    assertEquals(0, late.state.failed?.repeats)
    val edge = BitrateRegulator.regulate(lapsed, reading(dropped = 9), 359_999, Transport.SRT, latency)
    assertEquals(1, edge.state.failed?.repeats)
  }

  @Test
  fun `N53 a repeat after a 5 min cap is a first, and repeats stop at the cap`() {
    // 1_500_000 failed (cap 1_200_000, 5 min): a later failure is never a repeat of it.
    val high = Regulation(800_000, failed = FailedRate(1_500_000, 0))
    assertEquals(0, BitrateRegulator.regulate(high, reading(dropped = 9), 301_000, Transport.SRT, latency).state.failed?.repeats)
    val capped = Regulation(800_000, failed = FailedRate(900_000, 0, repeats = 3))
    assertEquals(3, BitrateRegulator.regulate(capped, reading(dropped = 9), 301_000, Transport.SRT, latency).state.failed?.repeats)
  }

  @Test
  fun `N53 a failing drop's halving escalates as a cut does`() {
    val lapsed = Regulation(800_000, failed = FailedRate(900_000, 0), failingAtMs = 60_500)
    assertEquals(FailedRate(800_000, 61_000, repeats = 1), BitrateRegulator.afterDrop(lapsed, 61_000).failed)
  }

  // N53 rule 1: a new network.

  @Test
  fun `N53 a new network restarts the floor at the start rate, with nothing to wait out`() {
    val floored = Regulation(500_000, lastCutAtMs = 9_000, failed = FailedRate(608_000, 9_000), failingAtMs = 8_000, cleanEgressBps = listOf(800_000), intervalEgressTicks = 1, cleanSinceMs = 5_000)
    val reset = BitrateRegulator.newNetwork(floored)
    assertEquals(1_500_000, reset.targetBps)
    assertNull(reset.lastCutAtMs)
    assertNull(reset.failed)
    assertNull(reset.failingAtMs)
    assertEquals(emptyList(), reset.cleanEgressBps)
    assertNull(reset.cleanSinceMs)
    assertEquals(1_500_000, reset.healthyTargetBps, "a clean far-end drop restarts here, not at the old link's 608_000")
    // Clean from 10 s on the new network's 11.4 Mbps: the first raise is 10 s of clean interval later, 20 s.
    val (_, moves) = run(reset, 10_000, 20_000) { _, _ -> reading(estimate = 11_400_000) }
    assertEquals(listOf(20_000L to 1_800_000), moves.map { it.first to it.second.state.targetBps })
  }

  @Test
  fun `N53 a new network never cuts a target over the start rate, and keeps the lines' counts`() {
    val high = Regulation(2_400_000, lastCutAtMs = 1_000, failed = FailedRate(3_000_000, 1_000), floorCuts = 3, floorSaidAtMs = 500, untestedRaises = 2, heldSaidAtMs = 700)
    val reset = BitrateRegulator.newNetwork(high)
    assertEquals(2_400_000, reset.targetBps)
    assertNull(reset.failed)
    assertEquals(3, reset.floorCuts)
    assertEquals(500L, reset.floorSaidAtMs)
    assertEquals(2, reset.untestedRaises)
    assertEquals(700L, reset.heldSaidAtMs)
  }

  @Test
  fun `N53 a second new network is the same as one, and a session's first is a no-op on its target`() {
    val once = BitrateRegulator.newNetwork(Regulation(500_000, lastCutAtMs = 1, failed = FailedRate(600_000, 1)))
    assertEquals(once, BitrateRegulator.newNetwork(once))
    assertEquals(Encode.START_BPS, BitrateRegulator.newNetwork(BitrateRegulator.sessionStarted()).targetBps)
  }
}
