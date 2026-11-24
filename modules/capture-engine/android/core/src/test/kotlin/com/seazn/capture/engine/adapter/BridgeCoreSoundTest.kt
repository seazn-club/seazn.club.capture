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
 * [BridgeCore]: sound's readiness (spec §4): silence, calls, no audio, and the capture's close.
 */
class BridgeCoreSoundTest : BridgeCoreTestKit() {
  /** Spec §4 (owner, 2026-10-06): "even though there is no sound … it is ok … should allow to go live". */
  @Test
  fun `S4 a room silent from the start is sound at its first reading`() {
    core.start()
    pair()
    core.viewfinder(true)
    scheduler.advanceBy(0)
    assertEquals(false, readyOn(core)["sound"], "no reading yet: the mic is not known to be available")
    core.readiness(audioPeak = 0.0)
    scheduler.advanceBy(0)
    assertEquals(true, readyOn(core)["sound"], "a silent reading is a working mic")
    assertTrue(lines().last { it.getString("kind") == "readiness" }.getBoolean("sound"), "and on the record")
  }

  /** Spec §4: 60 s of silence is still ready. 60 s is the spec's figure; the cadence is the engine's tick. */
  @Test
  fun `S4 sixty seconds of silence stays sound`() {
    core.start()
    pair()
    core.viewfinder(true)
    core.readiness(audioPeak = 0.0)
    scheduler.advanceBy(0)
    val from = clock.monotonicMs()
    var readings = 0
    while (clock.monotonicMs() < from + 60_000) {
      core.readiness(audioPeak = 0.0)
      scheduler.advanceBy(0)
      assertEquals(true, readyOn(core)["sound"], "${clock.monotonicMs() - from} ms of silence")
      scheduler.advanceBy(Engine.TICK_MS)
      readings += 1
    }
    assertEquals(60_000 / Engine.TICK_MS, readings.toLong(), "the sweep's own count")
  }

  /** Spec §4: a call takes the mic (F-P5-8) and gives it back; the sequence twice, so the second call is not the first's leftovers. */
  @Test
  fun `S4 a call makes sound not ready at once, and ready again the moment it ends, twice`() {
    core.start()
    pair()
    core.viewfinder(true)
    core.readiness(audioPeak = 0.0)
    scheduler.advanceBy(0)
    repeat(2) { call ->
      core.report(Input.MicSilenced(true))
      scheduler.advanceBy(0)
      assertEquals(false, readyOn(core)["sound"], "call ${call + 1}: at once, with no reading after it")
      core.readiness(audioPeak = 0.4)
      scheduler.advanceBy(Engine.TICK_MS)
      assertEquals(false, readyOn(core)["sound"], "call ${call + 1}: a loud reading while silenced is not sound")
      core.report(Input.MicSilenced(false))
      scheduler.advanceBy(0)
      assertEquals(true, readyOn(core)["sound"], "call ${call + 1}: ready when the call ends, with no reading after it")
    }
    assertEquals(2, kinds().count { it == "mic-silenced" }, "the machine heard both calls, as ever")
  }

  /** Spec §4: on air, a silent room never degrades, and the broadcast never leaves publishing. */
  @Test
  fun `S4 on air a silent room never degrades`() {
    live()
    platform.answer = PairWire::answerLive // beats say live, so the broadcast runs on past the first beat
    assertEquals("publishing", state())
    // live() never opens the viewfinder: the broadcast holds the capture. Opened here, as the phone shows it on air.
    core.viewfinder(true)
    val from = clock.monotonicMs()
    var readings = 0
    while (clock.monotonicMs() < from + 60_000) {
      core.readiness(audioPeak = 0.0)
      scheduler.advanceBy(Engine.TICK_MS)
      assertEquals("publishing", state(), "${clock.monotonicMs() - from} ms of silence on air")
      assertEquals(true, readyOn(core)["sound"], "${clock.monotonicMs() - from} ms of silence on air: the mic is available")
      readings += 1
    }
    assertEquals(60_000 / Engine.TICK_MS, readings.toLong(), "the sweep's own count")
    // F6: no record assertion, as no record kind "degraded" exists and one could never fail. The per-step state() and
    // sound are what this test reads; the line the screen shows is statusKey.test.ts's.
  }

  /**
   * C4a carry, in the glue's real call order (pre-flight F5). The close is `noticeCapture` → `silenced()`
   * (BridgeCore's `noticeCapture`), then the glue's `release()` reports `readiness(false, null)` (StreamerAdapter's
   * `release()`, `0.0` before this task). Spec §4: a closed capture is "audio capture unavailable", so sound is not
   * ready after it.
   */
  @Test
  fun `C4a carry the capture's close is not sound after the glue's close report, and the next opening's first buffer is`() {
    core.start()
    pair()
    core.viewfinder(true)
    core.readiness(audioPeak = 0.0)
    scheduler.advanceBy(0)
    assertEquals(true, readyOn(core)["sound"])
    core.viewfinder(false) // posted first: noticeCapture closes the capture and calls silenced()
    core.readiness(cameraFrames = false, audioPeak = null) // posted after: the glue's release(), as it reports
    scheduler.advanceBy(0)
    assertEquals(false, readyOn(core)["sound"], "closed: the capture delivers nothing")
    core.viewfinder(true)
    scheduler.advanceBy(0)
    assertEquals(false, readyOn(core)["sound"], "open, no buffer yet")
    core.readiness(audioPeak = 0.0)
    scheduler.advanceBy(0)
    assertEquals(true, readyOn(core)["sound"], "its first buffer, silent or not")
  }

  /**
   * Spec §4 "audio capture unavailable", pre-flight F4. Driven through the real producer: the glue's tick hands the
   * core PeakMeter's take every READINESS_MS (StreamerAdapter's `reportReadiness`), and the take is null when no
   * buffer came.
   */
  @Test
  fun `S4 no audio buffer for NO_AUDIO_MS is not sound, and the next buffer is`() {
    val glueTickMs = 500L // StreamerAdapter.READINESS_MS, the glue's cadence
    val meter = PeakMeter()
    core.start()
    pair()
    core.viewfinder(true)
    meter.offer(0.0) // a silent buffer: a working mic
    core.readiness(audioPeak = meter.take())
    scheduler.advanceBy(0)
    assertEquals(true, readyOn(core)["sound"], "a silent buffer is a working mic")
    var ticks = 0
    for (elapsed in glueTickMs..(BridgeCore.NO_AUDIO_MS + glueTickMs) step glueTickMs) {
      scheduler.advanceBy(glueTickMs)
      core.readiness(audioPeak = meter.take()) // nothing was offered: null
      scheduler.advanceBy(0)
      assertEquals(elapsed < BridgeCore.NO_AUDIO_MS, readyOn(core)["sound"], "$elapsed ms with no audio buffer")
      ticks += 1
    }
    assertEquals((BridgeCore.NO_AUDIO_MS / glueTickMs + 1).toInt(), ticks, "the sweep's own count")
    meter.offer(0.0)
    core.readiness(audioPeak = meter.take())
    scheduler.advanceBy(0)
    assertEquals(true, readyOn(core)["sound"], "the next buffer, silent, is a working mic again")
  }

  /** Pre-flight F4: "no audio buffer" is 2 s (D1), a literal here, never read back from the code under test. */
  @Test
  fun `F4 the no-audio window is two seconds`() {
    assertEquals(2_000L, BridgeCore.NO_AUDIO_MS)
  }

  /**
   * Spec §4 "audio capture unavailable": a level that is not a number is no buffer. PeakMeter drops one already, so
   * this is the core's own guard for a producer that does not (review m-4): an open capture, no buffer before it.
   */
  @Test
  fun `S4 a non-finite peak is no buffer, so it never makes the mic available`() {
    core.start()
    pair()
    core.viewfinder(true)
    var checked = 0
    for (peak in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
      core.readiness(audioPeak = peak)
      scheduler.advanceBy(0)
      assertEquals(false, readyOn(core)["sound"], "$peak")
      checked += 1
    }
    assertEquals(3, checked, "the sweep's own count")
    core.readiness(audioPeak = 0.0)
    scheduler.advanceBy(0)
    assertEquals(true, readyOn(core)["sound"], "a finite buffer still is: the readings above were judged, not ignored")
  }

  /**
   * Review G-2: a call is the system's word, not a reading, and MicWatch tells each change once (MicSilenceWatch's
   * baseline lives for the process). A call still going when the capture closes and reopens is told no second
   * time, so the core keeps it across the close; its end is heard on the new meter's recording (MicSilenceWatchTest's
   * handover). Clearing it at the close would make a call in progress read as an available mic.
   */
  @Test
  fun `G-2 a call that spans the capture's close is still not sound at the next opening, until it ends`() {
    core.start()
    pair()
    core.viewfinder(true)
    core.readiness(audioPeak = 0.0)
    scheduler.advanceBy(0)
    core.report(Input.MicSilenced(true))
    scheduler.advanceBy(0)
    assertEquals(false, readyOn(core)["sound"], "the call")
    core.viewfinder(false)
    core.readiness(cameraFrames = false, audioPeak = null) // the glue's release(), as it reports
    scheduler.advanceBy(0)
    core.viewfinder(true)
    core.readiness(audioPeak = 0.0) // the new meter's first buffer: zeros, as a silenced client reads
    scheduler.advanceBy(0)
    assertEquals(false, readyOn(core)["sound"], "reopened during the call: the mic is still taken")
    core.report(Input.MicSilenced(false))
    scheduler.advanceBy(0)
    assertEquals(true, readyOn(core)["sound"], "the call's end, heard on the new recording")
  }

  /**
   * Spec §4, the sequence: a pair, an unpair and a re-pair carry no mic reading over. Pairing 1's capture closes at the
   * unpair, which is "audio capture unavailable" at once (the C4a carry), and its pose is forgotten at Release
   * (final I-1). Pairing 2 is not sound until its own first reading, a silent one, and not held until its first held
   * report. (The glue's pose watch starts a new tracker at each Hold, so held's own window starts afresh there.) A
   * call's silencing is not a reading and does carry over (G-2's test above).
   */
  @Test
  fun `S4 a pair, an unpair and a re-pair carry no mic reading over`() {
    core.start()
    core.report(Input.Network(true))
    core.viewfinder(true)
    ready()
    pair()
    scheduler.advanceBy(Engine.TICK_MS)
    assertEquals(mapOf("camera" to true, "sound" to true, "held" to true), machineChecks(), "pairing 1 ready")
    core.send(mapOf("kind" to "unpair"))
    scheduler.advanceBy(0)
    pair()
    scheduler.advanceBy(Engine.TICK_MS)
    assertEquals("paired", state())
    assertEquals(mapOf("camera" to true, "sound" to false, "held" to false), machineChecks(), "pairing 2 starts with no mic and not held")
    core.readiness(audioPeak = 0.0, pose = Pose.Held(Side.RIGHT))
    scheduler.advanceBy(0)
    assertEquals(mapOf("camera" to true, "sound" to true, "held" to true), machineChecks(), "each at its first reading, the mic's a silent one")
  }
}
