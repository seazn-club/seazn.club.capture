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
 * [CaptureRun]: the take's probe while the slate is up (N45).
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle and runCurrent
class CaptureRunProbeTest : CaptureRunTestKit() {
  /**
   * Session 7, F-S7-1: every SRT start failed with `site: Preconditions.outOfBounds`, the JDK's bounds check, which
   * names no call of ours or StreamPack's. The stack below is the one a list's `get(0)` makes in StreamPack 3.2.1's
   * `TsMuxer.addStreams` (reproduced on the JVM against its bytecode): the site is the first frame outside the Java
   * and Kotlin runtimes. A stack made of runtime frames alone keeps its first, rather than naming nothing.
   */
  @Test
  fun `F-S7-1 the site skips the Java and Kotlin runtimes' frames, to the call that reached them`() =
    runTest {
      val runtime = listOf("jdk.internal.util.Preconditions" to "outOfBounds", "java.util.Objects" to "checkIndex", "java.util.ArrayList" to "get", "kotlin.collections.CollectionsKt" to "first")
      val reached = runtime.foldRight(thrownAt(IndexOutOfBoundsException("Index 0 out of bounds for length 0"), StreamPack.TS_MUXER, "addStreams")) { (type, method), thrown -> thrownAt(thrown, type, method) }
      val bare = IndexOutOfBoundsException("Index 0 out of bounds for length 0").apply { stackTrace = runtime.map { (type, method) -> StackTraceElement(type, method, null, 1) }.toTypedArray() }
      val rows =
        listOf(
          reached to "${StreamPack.TS_MUXER.substringAfterLast('.')}.addStreams",
          bare to runtime.first().let { (type, method) -> "${type.substringAfterLast('.')}.$method" },
        )
      var checked = 0
      for ((thrown, site) in rows) {
        assertEquals(runtime.first().first, thrown.stackTrace.first().className, "the stack starts in the runtime")
        val s = scene(signals = AttemptSignals())
        s.run.open(request)
        advanceUntilIdle()
        s.parts.failing[1] = thrown
        does(s, connect(1))
        val line = s.told.lines.single { it.kind == "capture-start-failed" }
        assertEquals(listOf<Pair<String, Any?>>("attempt" to 1, "error" to "IndexOutOfBoundsException", "site" to site), line.fields)
        checked += 1
      }
      assertEquals(2, checked, "the sweep's own count")
    }

  /** On air, our camera taken and the slate up: the take's first probe, at 5 s, waits on [gate] for Camera2's answer. */
  private fun TestScope.probeInFlight(gate: CompletableDeferred<Probe>): Scene =
    opened().also { s ->
      s.parts.probeGate = gate
      does(s, connect(1))
      s.parts.lose("0")
      runCurrent()
      s.run.execute(Command.Slate(true))
      advanceTimeBy(5_500)
      runCurrent()
      assertEquals(1, s.parts.probeCount, "a probe in flight")
    }

  /**
   * N45 R2-M2 ruling (re-review 2): Stop and unpair never wait behind a probe. A probe holds the capture's queue until
   * Camera2 answers its open, up to 3 s (`BOUND_MS`, then `SETTLE_MS`), and the endpoint's close and the camera's
   * release waited behind it. They abandon it: each is done within its own step, and the probe's answer, when it
   * comes, tells nothing.
   */
  @Test
  fun `N45 R2-M2 Stop, End and unpair abandon a probe in flight - each is done within its own step`() =
    runTest {
      val ends =
        listOf<Pair<String, (Scene) -> Unit>>(
          "stop" to { it.run.execute(Command.StopBroadcast) },
          "end" to { it.run.execute(Command.End(EndReason.OPERATOR_STOPPED)) },
          "unpair" to { it.run.close() },
        )
      for ((label, end) in ends) {
        val gate = CompletableDeferred<Probe>()
        val s = probeInFlight(gate)
        end(s)
        advanceTimeBy(FakeParts.STEP_MS + 1)
        runCurrent()
        assertEquals(listOf(1), s.parts.closed, "$label: the endpoint closed within its step: ${s.parts.log}")
        gate.complete(Probe.Opened("0")) // Camera2 answers the open abandoned
        advanceUntilIdle()
        assertFalse(Input.CameraReleased in s.told.reports, "$label: an abandoned probe tells nothing: ${s.told.reports}")
        assertEquals(1, s.parts.probeCount, "$label: and the probing ends with it")
      }
    }

  /**
   * N45 R2-M2: anything else waits for the probe's answer, which is heard: the probe never runs beside a reopen or a
   * build of ours, a reconnect's Connect may wait (the ruling), and a bitrate step (ABR's, every few seconds on a poor
   * uplink) never costs a take its probe.
   */
  @Test
  fun `N45 R2-M2 any other command waits for the probe's answer, which is heard`() =
    runTest {
      val gate = CompletableDeferred<Probe>()
      val s = probeInFlight(gate)
      s.run.execute(Command.SetBitrate(1, 2_000_000))
      advanceTimeBy(2_000)
      runCurrent()
      assertFalse("bitrate 2000000" in s.parts.log, "behind the probe: ${s.parts.log}")
      gate.complete(Probe.Opened("0"))
      runCurrent()
      assertTrue("bitrate 2000000" in s.parts.log, "after its answer: ${s.parts.log}")
      assertEquals(Input.CameraReleased, s.told.reports.last(), "the answer heard: ${s.told.reports}")
    }

  /**
   * N45 R2-M2: a probe queued behind a busy queue (a reconnect's start) when Stop is asked is never made, so Stop
   * waits for the start alone, never for a probe asked after the broadcast was told to end.
   */
  @Test
  fun `N45 R2-M2 a probe queued before Stop is never made`() =
    runTest {
      val s = opened()
      s.parts.probeGate = CompletableDeferred() // a probe made would never answer
      does(s, connect(1))
      s.parts.lose("0")
      runCurrent()
      s.run.execute(Command.Slate(true))
      runCurrent()
      s.parts.gates[2] = CompletableDeferred()
      s.run.execute(Command.Rebuild(1, connect(2))) // the reconnect's start holds the queue
      advanceTimeBy(5_500) // the probe's turn: queued behind the start
      runCurrent()
      assertEquals(0, s.parts.probeCount, "queued, not made")
      s.run.execute(Command.StopBroadcast)
      s.parts.gates.getValue(2).complete(true)
      advanceTimeBy(FakeParts.STEP_MS * 2 + 1)
      runCurrent()
      assertEquals(0, s.parts.probeCount, "never made: ${s.parts.log}")
      assertEquals(listOf("stop 2", "stop done", "after"), s.parts.log.takeLast(3), "Stop waited for the start alone: ${s.parts.log}")
    }

  /**
   * N45 I2, on air: the busy word is marked as it is heard, not when the queue gets to it. StreamPack sends the word and
   * its "streaming" report of the same failed open together, from inside the open. The preview's busy failure tells
   * the take from the glue, outside the queue (C7 I-2), so a report read before a queued mark was our open, and told
   * the take over while the other app still held the camera: the machine reopened into its hold.
   */
  @Test
  fun `N45 I2 on air a busy word and its streaming report heard together are no open - the preview's take stands`() =
    runTest {
      val s = opened()
      does(s, connect(1))
      assertEquals(Input.CameraContended, s.cameras.openFailed("0", busy = true), "the preview's busy failure: a take")
      s.parts.busyOpen("0")
      s.parts.opened("0")
      advanceUntilIdle()
      assertEquals(listOf<Input>(Input.Connected(1)), s.told.reports, "the report of a busy open is no open: no release")
      assertTrue(s.cameras.contended("0"), "the take stands")
    }

  /**
   * N45 R1-I2 (re-review, probe G): the busy mark is set only on air. Off air the preview opens the camera, and a mark
   * would make its opens no open until one of ours is asked (re-review 2, R2-M3: not because a failed preview open
   * reports nothing; javap has the preview and the stream report alike). A holder on another id (error 2) never makes
   * ours available; the preview's own next start (the window shown again, with no open of ours asked) opens it once the
   * holder lets go, and that open tells the take over: the camera reads ready, and Go live may go.
   */
  @Test
  fun `N45 R1-I2 off air an error-2 take of the preview heals at the preview's next open, and the camera reads ready`() =
    runTest {
      val s = opened()
      s.parts.opened("0")
      advanceUntilIdle()
      s.parts.busyOpen("0") // the preview's restart: "Camera 0 is in error 2"
      advanceUntilIdle()
      assertEquals(Input.CameraContended, s.cameras.openFailed("0", busy = true), "the preview's failure: a take")
      assertFalse(s.cameras.ready(camera = true), "taken: not ready")
      s.parts.opened("0") // the holder let go, and the preview's next start opened it
      advanceUntilIdle()
      assertEquals(listOf<Input>(Input.CameraReleased), s.told.reports, "its open tells the take over")
      assertTrue(s.cameras.ready(camera = true), "and the camera reads ready")
    }

  /**
   * N45 R1-I2: a mark set on air ends with the broadcast. A take whose camera source stayed in (no slate to swap back
   * from) asks no new open at the stop, so without this the preview's open after it stayed no open, and Go live with it.
   */
  @Test
  fun `N45 R1-I2 a busy word's mark ends with the broadcast - the preview's open after the stop tells the take over`() =
    runTest {
      val s = opened()
      s.parts.opened("0")
      advanceUntilIdle()
      does(s, connect(1))
      s.parts.busyOpen("0")
      advanceUntilIdle()
      assertEquals(listOf(Input.Connected(1), Input.CameraContended), s.told.reports, "on air: a take")
      does(s, Command.StopBroadcast)
      s.parts.opened("0") // off air, the preview's open
      advanceUntilIdle()
      assertEquals(Input.CameraReleased, s.told.reports.last(), "the mark went with the broadcast: ${s.told.reports}")
    }

  /**
   * N45 M1 (re-review's surviving mutant): a retake is a take within 10 s of a probe telling the LAST take over, so a
   * take ends that release's count: a take after one told over by another path is no retake, whatever its timing,
   * and three such never back the probe off.
   */
  @Test
  fun `N45 M1 only a take straight after a probe's release is a retake`() =
    runTest {
      val s = opened()
      s.parts.probeAnswer = Probe.Opened("0")
      try {
        does(s, connect(1))
        s.parts.lose("0")
        runCurrent()
        s.run.execute(Command.Slate(true))
        advanceTimeBy(5_500)
        runCurrent()
        assertEquals(1, s.parts.probeCount, "the probe told the take over")
        s.run.execute(Command.Slate(false))
        runCurrent()
        repeat(3) {
          s.parts.lose("0") // within 10 s of that release: the first a retake, the others after another path's end
          runCurrent()
          assertNotNull(s.cameras.probed("0"), "told over by another path")
          advanceTimeBy(1_000)
          runCurrent()
        }
        s.parts.lose("0")
        runCurrent()
        s.run.execute(Command.Slate(true))
        advanceTimeBy(5_500)
        runCurrent()
        assertEquals(2, s.parts.probeCount, "never backed off: probed within 5 s")
      } finally {
        s.parts.probeAnswer = null
      }
    }

  @Test
  fun `N45 a busy open of our camera on air is a take, told once - with nothing on air it is the open's own`() =
    runTest {
      val s = opened()
      s.parts.opened("0")
      advanceUntilIdle()
      assertEquals(1, s.parts.watchingBusy, "the opening watches its busy opens")
      s.parts.busyOpen("0")
      advanceUntilIdle()
      assertEquals(emptyList(), s.told.reports + s.told.lines.map { it.kind } + s.parts.log, "nothing on air: the open's own")
      assertTrue(s.cameras.ready(camera = true), "and nothing marked here")

      does(s, connect(1))
      s.parts.log.clear()
      s.parts.busyOpen("1")
      advanceUntilIdle()
      assertEquals(listOf<Input>(Input.Connected(1)), s.told.reports, "another camera's is nothing")
      s.parts.busyOpen("0")
      advanceUntilIdle()
      assertEquals(listOf(Input.Connected(1), Input.CameraContended), s.told.reports, "on air: a take")
      assertEquals(listOf(RecordEntry("camera-lost", listOf("from" to "busy", "attempt" to 1))), s.told.lines.filter { it.kind == "camera-lost" })
      val written = mutableListOf<String>()
      SessionRecord { written += it }.append(0L, s.told.lines.single { it.kind == "camera-lost" })
      assertTrue(""""from":"busy"""" in written.single(), "the record keeps the word: ${written.single()}")
      assertFalse(s.cameras.ready(camera = true), "taken: not ready")
      assertTrue(s.cameras.contended("0"), "its release is waited for")
      s.parts.busyOpen("0")
      advanceUntilIdle()
      assertEquals(listOf(Input.Connected(1), Input.CameraContended), s.told.reports, "told once")
      assertEquals(emptyList(), s.parts.log, "never a rebuild")
      assertEquals(1, s.parts.publishing, "still on air")
      s.run.close()
      advanceUntilIdle()
      assertEquals(0, s.parts.watchingBusy, "the release ends the watch")
    }

  /**
   * Re-review 3 m2: a camera that fails as it opens is lost again as soon as its rebuild opens it, so a loss rebuild
   * at once would loop at open speed. Loss rebuilds are spaced by the machine's own retry rhythm (RETRY_MS): a second
   * loss straight after one is marked (not ready) and owed, and however many come inside the spacing, one rebuild
   * follows when it ends. A loss after it is built at once again.
   */
  @Test
  fun `re-review 3 m2 a second loss straight after a loss rebuild waits RETRY_MS, then builds once`() =
    runTest {
      val s = opened()
      s.parts.opened("0")
      advanceUntilIdle()
      s.parts.lose("0")
      advanceTimeBy(100)
      runCurrent()
      assertEquals(listOf("release", "release done", "build $left", "build done"), s.parts.log, "the first loss, at once")
      s.parts.log.clear()
      s.parts.lose("0") // the rebuild's own open failed, 100 ms after the first loss: inside the spacing
      s.parts.lose("0")
      advanceTimeBy(SessionMachine.RETRY_MS - 200)
      runCurrent()
      assertEquals(emptyList(), s.parts.log, "no rebuild inside the spacing")
      assertFalse(s.cameras.ready(camera = true), "lost meanwhile: not ready")
      advanceTimeBy(400)
      runCurrent()
      assertEquals(listOf("release", "release done", "build $left", "build done"), s.parts.log, "one rebuild, owed, when it ends")
      assertTrue(s.cameras.ready(camera = true), "a new open asked")
      assertEquals(
        listOf("camera-lost", "capture-rebuilt", "camera-lost", "camera-lost", "capture-rebuilt"),
        s.told.lines.map { it.kind }.filter { it in setOf("camera-lost", "capture-rebuilt") },
      )
      s.parts.log.clear()
      s.parts.opened("0")
      advanceTimeBy(SessionMachine.RETRY_MS + 100)
      runCurrent()
      s.parts.lose("0")
      advanceTimeBy(100)
      runCurrent()
      assertEquals(listOf("release", "release done", "build $left", "build done"), s.parts.log, "past the spacing, at once again")
      advanceUntilIdle()
      assertEquals(4, s.parts.log.size, "and nothing owed after it")
    }
}
