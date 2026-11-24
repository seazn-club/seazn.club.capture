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
 * The capture's rules (Task 36) against a fake of StreamPack's parts. Single-mode: the capture has no
 * sport; the side held is its one mode, and the Orient cases take both.
 *
 * The fixtures the `CaptureRun*Test` files of the one class split by concern share (S11: each file under
 * the 1500-line rule). Each test class extends it.
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle and runCurrent
abstract class CaptureRunTestKit {
  protected val request = Configs.pairRequest()
  protected val left = EncoderKeys.surfaceRotation(Side.LEFT)

  // Camera2's reasons, read with javap from android-36's android.jar (CameraAccessException).
  protected val cameraInUse = 4
  protected val maxCamerasInUse = 5
  protected val cameraDisconnected = 2

  protected fun connect(id: Int) = Command.Connect(id, Configs.srt, startBitrateBps = 3_000_000, maxBwBytesPerSecond = 1_000_000, network = null)

  protected class Scene(val parts: FakeParts, val told: Told, val cameras: CameraAvailability, val run: CaptureRun)

  protected fun TestScope.scene(
    cameras: CameraAvailability = CameraAvailability(),
    side: () -> Side = { Side.LEFT },
    signals: AttemptSignals = AttemptSignals(),
    scope: CoroutineScope = queueScope(),
  ): Scene {
    val parts = FakeParts()
    val told = Told()
    return Scene(parts, told, cameras, CaptureRun(parts, signals, cameras, told.calls(), side, scope))
  }

  protected fun TestScope.opened(scope: CoroutineScope = queueScope()): Scene =
    scene(scope = scope).also {
      it.run.open(request)
      advanceUntilIdle()
      it.parts.log.clear()
    }

  protected fun TestScope.does(scene: Scene, vararg commands: Command) {
    commands.forEach(scene.run::execute)
    advanceUntilIdle()
  }

  protected fun probeCounts(s: Scene): List<Any?> =
    s.told.lines.filter { it.kind == "camera-probe" }.map { line -> line.fields.single { it.first == "failures" }.second }
}
