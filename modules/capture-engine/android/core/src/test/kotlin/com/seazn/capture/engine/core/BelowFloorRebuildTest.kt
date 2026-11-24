package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * S1: frames under the rate floor rebuilt the pipeline every few seconds for as long as they lasted (session 8: 54
 * `video-stalled {below-floor}` in 19 min before the shed waiver), each a new hold, so the 60 s hold never bounded it. The
 * first three in a row rebuild at once, as before; each one after waits from the last, 30, 60, 120, then 240 s, and the
 * slow picture publishes meanwhile. One window at the floor or over it starts the count again.
 */
class BelowFloorRebuildTest {
  /** Live, then [videoPerStep] frames each 500 ms (4 is 8 fps, under the 10 fps floor); every new attempt connects at once. */
  private fun MachineRig.slow(ms: Long, videoPerStep: Long = 4, onStep: () -> Unit = {}) =
    advance(ms, videoPerStep = videoPerStep) {
      val connecting = phase as? Phase.Connecting
      if (connecting?.step is ConnectStep.Requested) send(Input.Connected(attempt!!))
      onStep()
    }

  private fun MachineRig.belowFloor(): List<RecordEntry> = records("video-stalled").filter { (it.field("cause") as Wire).wire == "below-floor" }

  @Test
  fun `the bound's numbers - three free, then 30, 60, 120 and 240 s, staying at 240`() {
    assertEquals(listOf(0L, 0, 0, 30_000, 60_000, 120_000, 240_000, 240_000), (1..8).map { BelowFloorRebuilds.waitMs(it) })
  }

  @Test
  fun `under the floor for 10 minutes rebuilds three times at once, then backs off, and publishes between`() {
    val rig = MachineRig().live()
    val at = mutableListOf<Long>()
    rig.slow(600_000) { if (rig.belowFloor().size > at.size) at += rig.mono }
    val gaps = at.zipWithNext { a, b -> b - a }
    assertTrue(gaps.take(2).all { it < 10_000 }, "the first three at once: $gaps")
    val waits = listOf(30_000L, 60_000, 120_000, 240_000)
    assertEquals(waits.size, gaps.drop(2).size, "$gaps")
    for ((gap, wait) in gaps.drop(2).zip(waits)) assertTrue(gap - wait in 0..1_000, "a gap of $gap for a wait of $wait: $gaps")
    assertEquals(7, at.size, "rebuilds in 10 min, not one every few seconds: $gaps")
    assertIs<SnapshotState.Publishing>(rig.state, "a slow picture is still a picture")
  }

  @Test
  fun `each line says how many in a row and how long the next waits`() {
    val rig = MachineRig().live()
    rig.slow(200_000)
    val lines = rig.belowFloor()
    assertEquals(listOf(1, 2, 3, 4, 5), lines.take(5).map { it.field("count") })
    assertEquals(listOf(0L, 0L, 30_000L, 60_000L, 120_000L), lines.take(5).map { it.field("retryMs") })
  }

  @Test
  fun `one window at the floor starts the count again - the next rebuild goes at once`() {
    val rig = MachineRig().live()
    val at = mutableListOf<Long>()
    while (rig.belowFloor().size < 3) rig.slow(500)
    rig.slow(5_000, videoPerStep = 15)
    assertEquals(0, rig.session.belowFloor.count, "30 fps over a window")
    rig.slow(15_000) { if (rig.belowFloor().size > 3 + at.size) at += rig.mono }
    assertTrue(at.isNotEmpty(), "the fourth went inside 15 s, with no 30 s wait")
    assertEquals(1, rig.belowFloor()[3].field("count"))
  }

  @Test
  fun `the waivers stand - a shed or a silenced mic rebuilds nothing`() {
    val shed = MachineRig().live()
    shed.send(Input.Device(DeviceSample(2, null, 50, false, null, ShedStep.VIEWER_FEED)))
    shed.slow(60_000)
    assertEquals(emptyList(), shed.belowFloor())
    val mic = MachineRig().live()
    mic.send(Input.MicSilenced(true))
    mic.slow(60_000)
    assertEquals(emptyList(), mic.belowFloor())
  }

  @Test
  fun `the other causes are not held - no video still rebuilds at once after three below the floor`() {
    val rig = MachineRig().live()
    while (rig.belowFloor().size < 3) rig.slow(500)
    rig.slow(2_000)
    val before = rig.records("video-stalled").size
    rig.slow(4_000, videoPerStep = 0)
    val noVideo = rig.records("video-stalled").drop(before).map { (it.field("cause") as Wire).wire }
    assertEquals("no-video", noVideo.firstOrNull(), "$noVideo")
  }
}
