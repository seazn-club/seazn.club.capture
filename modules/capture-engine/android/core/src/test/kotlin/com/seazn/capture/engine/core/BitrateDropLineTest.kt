package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S3: a drop moves the target ([BitrateRegulator.afterDrop]): it halves after a failing link, or restores the last
 * healthy target after a clean one. No `bitrate` line said so, so the record's next line read from a target it never
 * named. Now a drop that moves the target writes `bitrate {from, to, reason}`, with `drop-halved` or `drop-restored`.
 */
class BitrateDropLineTest {
  private fun liveWith(regulation: Regulation): MachineRig {
    val rig = MachineRig().live()
    rig.phase = (rig.phase as Phase.OnAir).let { it.copy(session = it.session.copy(regulation = regulation)) }
    return rig
  }

  private fun MachineRig.drop() = send(Input.Dropped((phase as Phase.OnAir).attemptId, DropReason.ENDPOINT_CLOSED, null))

  @Test
  fun `the drop's reason words`() {
    assertEquals(listOf("drops", "backlog", "raise", "clamp", "drop-halved", "drop-restored", "new-network"), BitrateMove.entries.map { it.wire })
  }

  @Test
  fun `a drop after a failing link halves the target and says so`() {
    val rig = liveWith(Regulation(1_500_000, failingAtMs = 0))
    rig.phase = (rig.phase as Phase.OnAir).let { it.copy(session = it.session.copy(regulation = it.session.regulation.copy(failingAtMs = rig.mono - 1_000))) }
    rig.drop()
    val line = rig.records("bitrate").single()
    assertEquals(listOf("from" to 1_500_000, "to" to 750_000, "reason" to BitrateMove.DROP_HALVED), line.fields)
  }

  @Test
  fun `a drop after a clean link restores the healthy target and says so`() {
    val rig = liveWith(Regulation(750_000, healthyTargetBps = 1_500_000))
    rig.drop()
    val line = rig.records("bitrate").single()
    assertEquals(listOf("from" to 750_000, "to" to 1_500_000, "reason" to BitrateMove.DROP_RESTORED), line.fields)
  }

  @Test
  fun `a drop that leaves the target where it was says nothing`() {
    val rig = liveWith(Regulation(1_500_000))
    rig.drop()
    assertTrue(rig.records("bitrate").isEmpty())
    val floor = liveWith(Regulation(Encode.FLOOR_BPS))
    floor.phase = (floor.phase as Phase.OnAir).let { it.copy(session = it.session.copy(regulation = it.session.regulation.copy(failingAtMs = floor.mono))) }
    floor.drop()
    assertTrue(floor.records("bitrate").isEmpty(), "halving at the floor stays at the floor")
  }
}
