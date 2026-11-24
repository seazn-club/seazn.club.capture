package com.seazn.capture.engine.adapter

import com.seazn.capture.engine.core.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * [BridgeCore]: each readiness check reaching the machine, and the readiness lines' rate.
 */
class BridgeCoreReadinessTest : BridgeCoreTestKit() {
  /**
   * Review C4a I-1: the reviewer's flap, 0.049 and 0.051 a reading apart (either side of the level floor this task
   * deleted), every 500 ms for ten minutes. Spec §4
   * (owner, 2026-10-06): sound is the mic's availability, so it turns at the first reading, whatever its level,
   * and no level turns it back: one line.
   */
  @Test
  fun `I-1 a peak flapping between two quiet levels for ten minutes writes one readiness line, and the tail keeps the line before it`() {
    core.start()
    core.report(Input.Network(true))
    core.readiness(cameraFrames = true, pose = Pose.Held(Side.LEFT))
    pair()
    core.log("info", "probe.before", emptyMap())
    scheduler.advanceBy(0)
    val before = lines().count { it.getString("kind") == "readiness" }
    var readings = 0
    repeat(1_200) { n ->
      core.readiness(audioPeak = if (n % 2 == 0) 0.049 else 0.051)
      scheduler.advanceBy(Engine.TICK_MS)
      readings += 1
    }
    assertEquals(1_200, readings, "the sweep's own count")
    assertEquals(1, lines().count { it.getString("kind") == "readiness" } - before, "on at the first reading, and never off")
    assertTrue(tailLines().any { "probe.before" in it["line"] as String }, "the line before the flap is still in the tail: ${core.tail()}")
    assertEquals(true, readyOn(core)["sound"])
  }

  /**
   * Review C4a I-1, the second guard alone: the camera, which has no hold, turns 300 ms into each second,
   * 600 times in ten minutes, each turn published by the tick after it. (Sound turned here before the
   * owner's 15 s silence window, 2026-10-05, which no longer lets it turn once a second.) At most six readiness lines a minute, and the first of each later
   * minute carries the count held back since the last one written; every turn still reaches the
   * machine. By hand: minute 1 opens at the pair's line (0 ms) and writes it and the turns at 300 to
   * 4 300 ms, then holds back the 55 turns from 5 300 to 59 300 ms. Minute m + 1 opens at the tick at
   * 60 000m ms with the current state and those 55 (N-1's flush), writes the five turns after it and
   * holds back the next 55; the tick at 600 000 ms flushes the last 55.
   */
  @Test
  fun `I-1 a check that keeps turning writes at most six readiness lines a minute, and every turn reaches the machine`() {
    core.start()
    val before = platform.lines.size
    pair()
    var heard = 0
    for (k in 0 until 600) {
      val frames = k % 2 == 0
      scheduler.advanceBy(TURN_AT_MS)
      core.readiness(cameraFrames = frames)
      scheduler.advanceBy(1_000 - TURN_AT_MS)
      if (readyOn(core)["camera"] == frames) heard += 1
    }
    assertEquals(600, heard, "every turn reached the machine, written or not")
    val written = lines().drop(before).filter { it.getString("kind") == "readiness" }
    val heldBack = List(6) { 0 } + (1..9).flatMap { listOf(55) + List(5) { 0 } } + listOf(55)
    assertEquals(heldBack, written.map { it.getInt("suppressed") }, "61 lines: six a minute for ten minutes, and the last minute's flush")
  }

  /**
   * Review C4a N-1 (ruling C4a-N1), the reviewer's first probe: a minute's six lines spent, the sixth
   * change is held back, and nothing changes for five minutes. The camera turns, as sound no longer can
   * once a second (owner, 2026-10-05). The tick after the minute
   * runs out writes the state the machine holds, with the one held back; a state that stays, with
   * nothing held back, writes nothing more. The readings after the turns carry no audio buffer: this
   * capture is never opened, and under spec §4 any buffer, a silent one too, would turn sound on.
   */
  @Test
  fun `N-1 a turn held back is written once its minute runs out, and a state that stays writes nothing more`() {
    core.start()
    val before = platform.lines.size
    pair()
    for (k in 0 until 6) turnCamera(k % 2 == 0)
    val lastChangeMs = clock.monotonicMs() - 1_000 + TURN_AT_MS
    val readiness = { lines().drop(before).filter { it.getString("kind") == "readiness" } }
    assertEquals(6, readiness().size, "the pair's line and five turns; the sixth is held back")
    assertEquals(mapOf("camera" to false, "sound" to false, "held" to false), machineChecks(), "the machine heard the sixth")
    assertEquals(true, readiness().last().getBoolean("camera"), "the record has not, yet")
    var quiet = 0
    while (clock.monotonicMs() < lastChangeMs + 60_000) {
      core.readiness(cameraFrames = false)
      scheduler.advanceBy(Engine.TICK_MS)
      quiet += 1
    }
    val flushed = readiness()
    assertEquals(7, flushed.size, "one line for the minute that ran out")
    assertEquals(machineChecks(), checksOf(flushed.last()), "within 60 s, the record's last line is the machine's state")
    assertEquals(1, flushed.last().getInt("suppressed"))
    while (clock.monotonicMs() < lastChangeMs + 300_000) {
      core.readiness(cameraFrames = false)
      scheduler.advanceBy(Engine.TICK_MS)
      quiet += 1
    }
    assertTrue(quiet >= 590, "five minutes of quiet readings: $quiet")
    assertEquals(7, readiness().size, "a stable state with nothing held back writes no more lines")
  }

  /**
   * Review C4a N-1, the reviewer's second probe: the pair's line and five changes (camera, held, sound, and
   * held off and on again) spend the minute, then the camera dies and stays dead. The death is held back,
   * and written by the tick after the minute runs out; a camera that stays dead writes nothing more.
   */
  @Test
  fun `N-1 a camera that dies after the minute's lines are spent reaches the record within 60 s`() {
    core.start()
    val before = platform.lines.size
    pair()
    core.readiness(cameraFrames = true)
    scheduler.advanceBy(1_000)
    core.readiness(pose = Pose.Held(Side.LEFT))
    scheduler.advanceBy(1_000)
    core.readiness(audioPeak = 0.4)
    scheduler.advanceBy(1_000)
    // F4: the glue's meter reading before each later report, as it comes every 500 ms while the capture is open;
    // without one for NO_AUDIO_MS the mic is not available (spec §4). A reading that turns nothing writes nothing.
    core.readiness(audioPeak = 0.4)
    core.readiness(pose = Pose.NotHeld)
    scheduler.advanceBy(1_000)
    core.readiness(audioPeak = 0.4)
    core.readiness(pose = Pose.Held(Side.LEFT))
    scheduler.advanceBy(1_000)
    core.readiness(audioPeak = 0.4)
    core.readiness(cameraFrames = false)
    val diedMs = clock.monotonicMs()
    scheduler.advanceBy(Engine.TICK_MS)
    val readiness = { lines().drop(before).filter { it.getString("kind") == "readiness" } }
    assertEquals(6, readiness().size, "the pair's line and five changes; the death is held back")
    assertEquals(mapOf("camera" to true, "sound" to true, "held" to true), checksOf(readiness().last()), "the record's last line has the camera, yet")
    assertEquals(mapOf("camera" to false, "sound" to true, "held" to true), machineChecks(), "the machine knows it died")
    while (clock.monotonicMs() < diedMs + 60_000) {
      core.readiness(cameraFrames = false, audioPeak = 0.4)
      scheduler.advanceBy(Engine.TICK_MS)
    }
    assertEquals(7, readiness().size, "one line for the minute that ran out")
    assertEquals(machineChecks(), checksOf(readiness().last()), "within 60 s, the record's last line is the machine's state")
    assertEquals(1, readiness().last().getInt("suppressed"))
    repeat(480) {
      core.readiness(cameraFrames = false, audioPeak = 0.4)
      scheduler.advanceBy(Engine.TICK_MS)
    }
    assertEquals(7, readiness().size, "a camera that stays dead, with nothing held back, writes no more lines")
  }
}
