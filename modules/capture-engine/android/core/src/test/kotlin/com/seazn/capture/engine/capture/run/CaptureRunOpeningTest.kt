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
 * [CaptureRun]: the opening's camera watch, N2, I4, P11, P12, the ledger advisory, CD42, N8 and the release's reopen.
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle and runCurrent
class CaptureRunOpeningTest : CaptureRunTestKit() {
  // --- Review C7 I-2: StreamPack opens the camera lazily, so the build holds nothing until the open is seen ---

  @Test
  fun `I-2 a camera taken before the build is told at it, and stays taken until StreamPack reports our open`() =
    runTest {
      val cameras = CameraAvailability().apply { unavailable("0", 1_000) } // CameraWatch heard it before the build
      val s = scene(cameras)
      s.run.open(request)
      advanceUntilIdle()
      assertEquals(listOf<Input>(Input.CameraContended), s.told.reports, "the build holds nothing yet: the take stands")
      assertTrue(cameras.contended("0"))
      s.parts.opened("1")
      advanceUntilIdle()
      assertEquals(listOf<Input>(Input.CameraContended), s.told.reports, "another camera's open is not ours")
      s.parts.opened("0")
      advanceUntilIdle()
      assertEquals(listOf(Input.CameraContended, Input.CameraReleased), s.told.reports, "ours once open, and told over")
      assertFalse(cameras.contended("0"))
    }

  @Test
  fun `I-2 a build with nothing taken tells nothing, and its open tells nothing either`() =
    runTest {
      val s = opened()
      s.parts.opened("0")
      advanceUntilIdle()
      assertEquals(emptyList(), s.told.reports)
      assertTrue(s.cameras.ready(camera = true))
    }

  @Test
  fun `I-2 each opening watches its own camera's opens, the release ends the watch, and a rebuild keeps one`() =
    runTest {
      val s = scene()
      assertEquals(0, s.parts.watching, "nothing built, nothing watched")
      s.run.open(request)
      advanceUntilIdle()
      assertEquals(1, s.parts.watching)
      s.parts.log.clear()
      does(s, Command.Orient(Side.RIGHT)) // CD42: another side rebuilds
      assertEquals(listOf("release", "release done", "build ${EncoderKeys.surfaceRotation(Side.RIGHT)}", "build done"), s.parts.log.filter { it.startsWith("build") || it.startsWith("release") })
      assertEquals(1, s.parts.watching, "the old opening's watch ended with its release")
      s.run.close()
      advanceUntilIdle()
      assertEquals(0, s.parts.watching, "the release ends the watch")
      s.run.open(request)
      advanceUntilIdle()
      assertEquals(1, s.parts.watching, "the next opening watches again")
    }

  @Test
  fun `I-2 a busy build's release then the reopen's build is told nothing taken, and its open nothing more`() =
    runTest {
      val cameras = CameraAvailability()
      val s = scene(cameras)
      s.parts.builds += Built.Failed("0", cameraInUse, encoder = false, error = RuntimeException("Camera already in use"))
      s.run.open(request)
      advanceUntilIdle()
      assertEquals(listOf(request), s.told.busy)
      assertEquals(Input.CameraReleased, cameras.available("0", 3_000), "CameraWatch: the holder let go")
      does(s, Command.ReopenCamera)
      assertEquals(listOf<Input>(Input.CameraReopened(true)), s.told.reports, "nothing taken at the reopen's build")
      s.parts.opened("0")
      advanceUntilIdle()
      assertEquals(listOf<Input>(Input.CameraReopened(true)), s.told.reports)
    }

  // --- N2, I4, P11, P12 and the ledger advisory ---

  @Test
  fun `N2 a second open while one is held builds nothing, and serves the new pairing`() =
    runTest {
      val s = opened()
      val again = Configs.pairRequest(code = "fx42hmnp7k3r")
      s.run.open(again)
      advanceUntilIdle()
      assertEquals(emptyList(), s.parts.log)
      assertSame(again, s.run.request)
    }

  @Test
  fun `I4 a busy camera waits - Camera2's reasons, or the device's own word - and never fails the pairing`() =
    runTest {
      for ((label, reason, takenFirst) in listOf(Triple("in use", cameraInUse, false), Triple("max in use", maxCamerasInUse, false), Triple("onError, no code", null, true))) {
        val cameras = CameraAvailability()
        if (takenFirst) cameras.unavailable("0", 1_000) // another app opened camera 0 first
        val s = scene(cameras)
        s.parts.builds += Built.Failed("0", reason, encoder = false, error = RuntimeException("Camera already in use"))
        s.run.open(request)
        advanceUntilIdle()
        assertEquals(listOf(request), s.told.busy, label)
        assertEquals(emptyList(), s.told.pairFailed, label)
        assertTrue(cameras.contended("0"), "$label: the release is waited for")
        // The release asks for the reopen, with nothing open: the pairing's request is built. Review C7 I-2:
        // the release is heard first, as CameraWatch hears it; the reopen's build erases no take of its own.
        assertEquals(Input.CameraReleased, cameras.available("0", 3_000), "$label: the holder let go")
        s.parts.log.clear()
        does(s, Command.ReopenCamera)
        assertEquals(listOf("build $left", "build done"), s.parts.log, label)
        assertEquals(listOf<Input>(Input.CameraReopened(true)), s.told.reports, label)
      }
    }

  @Test
  fun `P12 the camera's own failure passes as it is, and a disconnect is not busy`() =
    runTest {
      val s = scene()
      val error = RuntimeException("Camera has been disabled")
      s.parts.builds += Built.Failed("0", cameraDisconnected, encoder = false, error = error)
      s.run.open(request)
      advanceUntilIdle()
      assertEquals(emptyList(), s.told.busy)
      assertSame(request, s.told.pairFailed.single().first)
      assertSame(error, s.told.pairFailed.single().second, "never wrapped: Failures.permanent alone decides")
      assertEquals(emptyList(), s.told.failures, "reported, never thrown (P11)")
    }

  @Test
  fun `CD11 the encoder's configuration failure is permanent, unless another app holds the camera`() =
    runTest {
      val s = scene()
      val error = IllegalArgumentException("codec refused")
      s.parts.builds += Built.Failed("0", null, encoder = true, error = error)
      s.run.open(request)
      advanceUntilIdle()
      val failed = s.told.pairFailed.single().second
      assertIs<PermanentPlatformFailure>(failed)
      assertSame(error, failed.cause)
      // StreamPack opens the camera lazily: a configuration that failed while another app holds it is busy.
      val cameras = CameraAvailability().apply { unavailable("0", 1_000) }
      val busy = scene(cameras)
      busy.parts.builds += Built.Failed("0", null, encoder = true, error = error)
      busy.run.open(request)
      advanceUntilIdle()
      assertEquals(listOf(request), busy.told.busy)
      assertEquals(emptyList(), busy.told.pairFailed)
    }

  @Test
  fun `ledger advisory - closing forgets the pairing, so a later reopen builds nothing`() =
    runTest {
      val s = scene()
      s.parts.builds += Built.Failed("0", cameraInUse, encoder = false, error = RuntimeException("busy"))
      s.run.open(request)
      s.run.close()
      advanceUntilIdle()
      assertNull(s.run.request)
      s.parts.log.clear()
      does(s, Command.ReopenCamera)
      assertEquals(emptyList(), s.parts.log, "no build for a pairing the capture no longer serves")
      assertEquals(listOf<Input>(Input.CameraReopened(false)), s.told.reports)
      // A second close is harmless, and opens nothing.
      s.run.close()
      advanceUntilIdle()
      assertEquals(listOf("release", "release done"), s.parts.log)
    }

  // --- CD42: the rotation is the build's ---

  @Test
  fun `CD42 Orient for the side the build encodes changes nothing, and with nothing open there is nothing to turn`() =
    runTest {
      val s = opened()
      does(s, Command.Orient(Side.LEFT))
      assertEquals(emptyList(), s.parts.log)
      assertEquals(emptyList(), s.told.lines)
      s.run.close()
      advanceUntilIdle()
      s.parts.log.clear()
      does(s, Command.Orient(Side.RIGHT))
      assertEquals(emptyList(), s.parts.log, "closed: no request, no rebuild")
    }

  @Test
  fun `CD42 the opening's side is read at the build, never later`() =
    runTest {
      var side = Side.RIGHT
      val s = scene(side = { side })
      s.run.open(request)
      advanceUntilIdle()
      side = Side.LEFT
      does(s, connect(1))
      assertEquals(listOf("build ${EncoderKeys.surfaceRotation(Side.RIGHT)}", "build done", "before 1", "start 1", "start done"), s.parts.log)
    }

  // --- N8 ---

  @Test
  fun `N8 an unreadable mic session is recorded once per opening, at its first connect`() =
    runTest {
      val s = opened()
      s.parts.micKnown = false
      does(s, connect(1), Command.Disconnect(1), connect(2))
      val said = { s.told.lines.filter { it.kind != "zoom" } } // spec 2026-11-25: air's zoom line is CaptureRunFramingTest's
      assertEquals(listOf("watch-failed"), said().map { it.kind })
      assertEquals(listOf("action" to "mic-session"), said().single().fields)
      does(s, Command.Orient(Side.RIGHT), connect(3)) // a new opening
      assertEquals(listOf("watch-failed", "capture-rebuilt", "watch-failed"), said().map { it.kind })
    }

  /** The sequence: a reopen whose build finds the camera taken again is the busy build's (I4), and the next release opens it. */
  @Test
  fun `(c) a reopen whose build finds the camera taken again claims no reopen, and the next release builds it`() =
    runTest {
      val s = opened()
      s.parts.builds += Built.Failed("0", cameraInUse, encoder = false, error = RuntimeException("Camera already in use"))
      does(s, Command.ReopenCamera)
      assertEquals(listOf(request), s.told.busy, "taken again: the pairing waits for the release")
      assertEquals(emptyList(), s.told.reports, "no reopen claimed")
      assertFalse(s.parts.held)
      s.parts.log.clear()
      assertEquals(Input.CameraReleased, s.cameras.available("0", 3_000), "CameraWatch: the holder let go again")
      does(s, Command.ReopenCamera)
      assertEquals(listOf("build $left", "build done"), s.parts.log, "nothing held: a build, no release")
      assertEquals(listOf<Input>(Input.CameraReopened(true)), s.told.reports)
      assertEquals(1, s.told.lines.count { it.kind == "capture-rebuilt" }, "the release once, the first reopen's")
    }
}
