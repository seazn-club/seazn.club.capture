package com.seazn.capture.engine.capture.run

import com.seazn.capture.engine.StreamPack
import com.seazn.capture.engine.adapter.AttemptSignals
import com.seazn.capture.engine.adapter.CameraAvailability
import com.seazn.capture.engine.adapter.EncoderKeys
import com.seazn.capture.engine.adapter.Failures
import com.seazn.capture.engine.adapter.PermanentPlatformFailure
import com.seazn.capture.engine.core.Command
import com.seazn.capture.engine.core.Configs
import com.seazn.capture.engine.core.ConnectFailure
import com.seazn.capture.engine.core.DropReason
import com.seazn.capture.engine.core.EndReason
import com.seazn.capture.engine.core.Input
import com.seazn.capture.engine.core.RecordEntry
import com.seazn.capture.engine.core.SessionMachine
import com.seazn.capture.engine.core.SessionRecord
import com.seazn.capture.engine.core.Shared
import com.seazn.capture.engine.core.Side
import com.seazn.capture.engine.thrownAt
import java.io.IOException
import java.net.ConnectException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [CaptureRun]: a camera StreamPack lost, or one that failed the start (F-S4-1).
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle and runCurrent
class CaptureRunCameraLossTest : CaptureRunTestKit() {
  // --- F-S4-1: a camera StreamPack lost, or one that failed the start, is the camera's: not ready, and built afresh ---

  /**
   * F-S4-1: a start the camera failed says nothing of the path. It is CAMERA, never counted (FallbackPolicy), the
   * camera reads not ready while it is lost, and the capture builds it afresh, a real open: StreamPack 3.2.1's
   * `VideoInput.setSource` skips a camera source of the same id (its sources), so only a new build opens it again.
   * The cleanup's close while the start was in flight is the camera's failure's own, never a drop.
   */
  @Test
  fun `F-S4-1 a start the camera failed is CAMERA, marks it lost until it is built afresh, and the next attempt connects`() =
    runTest {
      // Each with the drop its start made meanwhile: the cleanup's close (no cause), or the far end's (a cause).
      val rows =
        listOf(
          RuntimeException("Camera has been disconnected") to null,
          thrownAt(IllegalArgumentException("Output STREAM not found"), "${StreamPack.CAMERA}.controllers.CameraController", "addTarget") to
            IOException("endpoint closed"),
        )
      var checked = 0
      for ((failure, cause) in rows) {
        val signals = AttemptSignals()
        val s = scene(signals = signals)
        s.run.open(request)
        advanceUntilIdle()
        s.parts.opened("0")
        advanceUntilIdle()
        assertTrue(s.cameras.ready(camera = true), "open, before")
        s.parts.log.clear()
        s.parts.failing[1] = failure
        s.parts.starting = { id -> signals.dropped(id, cause)?.let { s.told.reports += it } }
        s.run.execute(connect(1))
        runCurrent() // to the rebuild's release, which waits STEP_MS
        val name = failure.javaClass.simpleName
        assertEquals(listOf<Input>(Input.ConnectFailed(1, ConnectFailure.CAMERA, "camera: $name")), s.told.reports, name)
        assertFalse(s.cameras.ready(camera = true), "$name: lost, not ready")
        advanceUntilIdle()
        assertEquals(listOf("before 1", "start 1", "release", "release done", "build $left", "build done"), s.parts.log, name)
        assertTrue(s.cameras.ready(camera = true), "$name: a new open asked")
        assertEquals(1, s.parts.watchingLosses, "the new build's losses are watched, once")
        val lines = s.told.lines.filter { it.kind in setOf("camera-lost", "capture-rebuilt") }
        assertEquals(
          listOf(
            RecordEntry("camera-lost", listOf("from" to "start", "attempt" to 1)),
            RecordEntry("capture-rebuilt", listOf("action" to "camera")),
          ),
          lines,
          name,
        )
        does(s, connect(2))
        assertEquals(Input.Connected(2), s.told.reports.last(), name)
        assertEquals(emptyList(), s.told.busy + s.told.pairFailed.map { it.first }, "$name: no take, no end")
        checked += 1
      }
      assertEquals(rows.size, checked, "the sweep's own count")
    }

  /**
   * F-S4-1: StreamPack's word that our camera was lost, with nothing on air, reads not ready and builds it afresh;
   * another camera's loss is nothing. N45 (device check 2026-11-17): with an attempt held the loss is another app's
   * take (an app that opens our camera evicts us, and Camera2 says so only as a disconnect), told once so the engine
   * puts the slate up and waits for the release; never a rebuild, so a broadcast is never cut by the capture's guess.
   * An open of ours seen after it tells it over.
   */
  @Test
  fun `F-S4-1 a camera lost with nothing on air is built afresh, and N45 with an attempt held it is a take, never a rebuild`() =
    runTest {
      val s = opened()
      s.parts.opened("0")
      advanceUntilIdle()
      s.parts.lose("1")
      advanceUntilIdle()
      assertEquals(emptyList(), s.parts.log, "another camera's loss is nothing")
      assertTrue(s.cameras.ready(camera = true))

      s.parts.lose("0")
      runCurrent()
      assertFalse(s.cameras.ready(camera = true), "lost: not ready")
      advanceUntilIdle()
      assertEquals(listOf("release", "release done", "build $left", "build done"), s.parts.log)
      assertTrue(s.cameras.ready(camera = true), "a new open asked")
      s.parts.opened("0")
      advanceUntilIdle()
      assertTrue(s.cameras.ready(camera = true), "and seen")
      assertEquals(
        listOf(RecordEntry("camera-lost", listOf("from" to "device", "attempt" to null)), RecordEntry("capture-rebuilt", listOf("action" to "camera"))),
        s.told.lines.filter { it.kind in setOf("camera-lost", "capture-rebuilt") },
      )

      does(s, connect(1))
      assertEquals(listOf<Input>(Input.Connected(1)), s.told.reports)
      s.parts.log.clear()
      s.parts.lose("0")
      advanceUntilIdle()
      assertFalse(s.cameras.ready(camera = true), "lost on air: not ready")
      assertEquals(emptyList(), s.parts.log, "never a rebuild under an attempt held")
      assertEquals(1, s.parts.publishing, "still on air")
      assertEquals(RecordEntry("camera-lost", listOf("from" to "device", "attempt" to 1)), s.told.lines.last { it.kind == "camera-lost" })
      assertEquals(listOf(Input.Connected(1), Input.CameraContended), s.told.reports, "N45: on air, a loss is a take")
      assertTrue(s.cameras.contended("0"), "its release is waited for")
      s.parts.lose("0") // StreamPack's own second word of it
      advanceUntilIdle()
      assertEquals(listOf(Input.Connected(1), Input.CameraContended), s.told.reports, "told once")
      assertEquals(emptyList(), s.parts.log, "still no rebuild")
      s.parts.opened("0") // StreamPack opened it again after all
      advanceUntilIdle()
      assertTrue(s.cameras.ready(camera = true), "an open seen clears the loss")
      assertEquals(Input.CameraReleased, s.told.reports.last(), "and tells the take over")
    }

  /**
   * N45 (device check 2026-11-17, 15:19:40-46Z): a start's or a reopen's open of our camera that another app holds
   * fails busy ("Camera 0 is in error 2"), and StreamPack 3.2.1 logs it and carries on, so the start connected with no
   * picture and the stall watchdog reconnected, again and again. With an attempt held that word is a take, told
   * once, never a rebuild. With nothing on air it is the open's own failure, read where the open fails (I4, I-2):
   * nothing here. Another camera's is nothing.
   */
  /**
   * N45 I1: each slate ends the last one's probing, so the next take is probed afresh, by one probing only: its first
   * refusal is written with its own count, never folded into the last take's 30 s. A take told over by another path
   * (an available, an open seen) leaves the probing waiting for its next step, and the slate comes down before it.
   */
  @Test
  fun `N45 I1 a take after the slate came down is probed afresh - its first refusal is written`() =
    runTest {
      val s = opened()
      s.parts.probeAnswer = Probe.Refused("max-in-use")
      try {
        does(s, connect(1))
        s.parts.lose("0")
        runCurrent()
        s.run.execute(Command.Slate(true))
        advanceTimeBy(5_500)
        runCurrent()
        assertEquals(listOf<Any?>(1), probeCounts(s), "the first refusal")
        assertNotNull(s.cameras.probed("0"), "told over by another path, the probing waiting")
        s.run.execute(Command.Slate(false))
        runCurrent()
        s.parts.lose("0")
        runCurrent()
        s.run.execute(Command.Slate(true))
        advanceTimeBy(5_500)
        runCurrent()
        assertEquals(listOf<Any?>(1, 1), probeCounts(s), "the next take's first refusal, written")
        assertEquals(2, s.parts.probeCount, "one probing at a time: the last one's step never comes")
      } finally {
        s.parts.probeAnswer = null // nothing to probe: the probing ends, so runTest's drain does, pass or fail
      }
    }
}
