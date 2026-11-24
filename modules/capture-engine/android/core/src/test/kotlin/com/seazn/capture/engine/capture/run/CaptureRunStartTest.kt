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
 * [CaptureRun]: a start's failures - a camera busy at the start, a drop while it is in flight, and the start-failed line.
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle and runCurrent
class CaptureRunStartTest : CaptureRunTestKit() {
  // --- C7 carry: StreamPack opens the camera lazily, so another app's hold can surface first at the start ---

  @Test
  fun `C7 a camera found busy at the start is taken and the attempt UNHELD, and a transport's failure classifies as before`() =
    runTest {
      val s = opened()
      val busy = RuntimeException("Camera already in use")
      s.parts.failing[1] = busy
      s.parts.busyFailures[busy] = "0"
      does(s, connect(1))
      assertEquals(listOf(request), s.told.busy, "the camera is taken: the pairing waits for it")
      assertEquals(listOf<Input>(Input.ConnectFailed(1, ConnectFailure.UNHELD, "camera busy")), s.told.reports, "one report, never counted")
      assertTrue(s.cameras.contended("0"), "its release is waited for")
      assertEquals(emptyList(), s.told.pairFailed, "never an end")
      // The pair: the next attempt's transport failure is the signals' word, and no camera.
      s.parts.failing[2] = ConnectException("Connection setup failure: connection timed out")
      does(s, connect(2))
      val failed = s.told.reports.last()
      assertIs<Input.ConnectFailed>(failed)
      assertEquals(2 to ConnectFailure.TIMEOUT, failed.attemptId to failed.failure)
      assertEquals(listOf(request), s.told.busy, "a transport's failure is no busy camera")
    }

  @Test
  fun `C7 after a busy start the release reopens the camera, and the next attempt closes the first's endpoint and connects`() =
    runTest {
      val s = opened()
      val busy = RuntimeException("Max cameras in use")
      s.parts.failing[1] = busy
      s.parts.busyFailures[busy] = "0"
      does(s, connect(1))
      s.parts.log.clear()
      assertEquals(Input.CameraReleased, s.cameras.available("0", 5_000), "the device's release is told")
      does(s, Command.ReopenCamera, connect(2))
      assertEquals(listOf("reopen", "stop -", "stop done", "before 2", "start 2", "start done"), s.parts.log)
      assertEquals(listOf<Input>(Input.CameraReopened(true), Input.Connected(2)), s.told.reports.drop(1))
      assertEquals(2, s.parts.publishing)
    }

  /** R3: the sink's completion can end the attempt first; the busy start is then no second report, and still the camera's. */
  @Test
  fun `C7 M-3 a busy start whose attempt dropped while it started is UNHELD, never a drop, and takes the camera`() =
    runTest {
      val signals = AttemptSignals()
      val s = scene(signals = signals)
      s.run.open(request)
      advanceUntilIdle()
      val busy = RuntimeException("Camera already in use")
      s.parts.failing[1] = busy
      s.parts.busyFailures[busy] = "0"
      s.parts.starting = { id -> signals.dropped(id, IOException("endpoint closed"))?.let { s.told.reports += it } }
      does(s, connect(1))
      assertEquals(listOf<Input>(Input.ConnectFailed(1, ConnectFailure.UNHELD, "camera busy")), s.told.reports, "the drop was the busy start's own")
      assertEquals(listOf(request), s.told.busy)
      assertTrue(s.cameras.contended("0"))
    }

  @Test
  fun `M-3 a drop while a start is in flight passes at its end when the camera was not busy, as before`() =
    runTest {
      var checked = 0
      for ((label, fails) in listOf("a transport failure" to true, "an open" to false)) {
        val signals = AttemptSignals()
        val s = scene(signals = signals)
        s.run.open(request)
        advanceUntilIdle()
        if (fails) s.parts.failing[1] = ConnectException("refused")
        s.parts.starting = { id -> signals.dropped(id, IOException("endpoint closed"))?.let { s.told.reports += it } }
        does(s, connect(1))
        val expected = listOf<Input>(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, "IOException: endpoint closed")) + if (fails) emptyList() else listOf(Input.Connected(1))
        assertEquals(expected, s.told.reports, label)
        checked += 1
      }
      assertEquals(2, checked, "the sweep's own count")
    }

  @Test
  fun `M-3 a drop held while a start is in flight passes when the start times out or is cancelled`() =
    runTest {
      var checked = 0
      for (cancelled in listOf(false, true)) {
        val signals = AttemptSignals()
        val parts = FakeParts()
        val told = Told()
        val scope = queueScope()
        val run = CaptureRun(parts, signals, CameraAvailability(), told.calls(), { Side.LEFT }, scope)
        run.open(request)
        advanceUntilIdle()
        parts.gates[1] = CompletableDeferred() // never answered: the start ends by its bound, or by the cancel
        parts.starting = { id -> signals.dropped(id, IOException("endpoint closed"))?.let { told.reports += it } }
        run.execute(connect(1))
        runCurrent()
        assertEquals(emptyList(), told.reports, "held while the start is in flight")
        if (cancelled) scope.cancel()
        advanceUntilIdle()
        val label = if (cancelled) "cancelled" else "timed out"
        assertEquals(listOf<Input>(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, "IOException: endpoint closed")), told.reports, label)
        checked += 1
      }
      assertEquals(2, checked, "the sweep's own count")
    }

  /**
   * Device session 4, F-S4-1, through the real run and the real signals. The SRT socket connected, then StreamPack
   * 3.2.1's start failed later on, and its own catch closed the endpoint it had opened (`EncodingPipelineOutput
   * .startStream`, the 3.2.1 sources): our sink's close is a drop with no cause, held while the start was in flight.
   * The start's failure is what the machine hears, never that close; the record names the throw by class only.
   */
  @Test
  fun `F-S4-1 a start that fails after its endpoint opened reports its own failure, never the close its cleanup made`() =
    runTest {
      val quoted = "srt://live.cloudflare.com:778?streamid=${Configs.STREAM_ID}"
      val pipeline = thrownAt(IllegalArgumentException("Endpoint must be opened before starting stream: $quoted"), StreamPack.PIPELINE_OUTPUT, "startStreamUnsafe")
      val refused = thrownAt(ConnectException("Connection setup failure: connection rejected"), "com.seazn.capture.engine.srt.SeaznSrtSink", "startStream")
      val wrapped = RuntimeException("start failed", thrownAt(IllegalStateException("Codec is released"), "${StreamPack.ENCODER}\$startStream\$1", "invokeSuspend"))
      val rows =
        listOf(
          Triple(pipeline, ConnectFailure.OTHER, listOf("error" to "IllegalArgumentException", "site" to "EncodingPipelineOutput.startStreamUnsafe")),
          Triple(refused, ConnectFailure.REFUSED, listOf("error" to "ConnectException", "site" to "SeaznSrtSink.startStream")),
          Triple(wrapped, ConnectFailure.OTHER, listOf("error" to "RuntimeException", "site" to "MediaCodecEncoder.invokeSuspend", "rootError" to "IllegalStateException")),
        )
      var checked = 0
      for ((thrown, expected, named) in rows) {
        val signals = AttemptSignals()
        val s = scene(signals = signals)
        s.run.open(request)
        advanceUntilIdle()
        s.parts.failing[1] = thrown
        s.parts.starting = { id -> signals.dropped(id, null)?.let { s.told.reports += it } } // the cleanup's close, while the start is in flight
        does(s, connect(1))
        assertEquals(listOf<Input>(Input.ConnectFailed(1, expected, Failures.describe(thrown))), s.told.reports, "${thrown.javaClass.simpleName}: the start's own failure")
        val line = s.told.lines.single { it.kind == "capture-start-failed" }
        assertEquals(listOf<Pair<String, Any?>>("attempt" to 1) + named, line.fields, "class names only")
        assertFalse(line.fields.any { (_, value) -> value.toString().contains("srt://") || value.toString().contains(" ") }, "never a message: ${line.fields}")
        checked += 1
      }
      assertEquals(3, checked, "the sweep's own count")
    }

  /** F-S4-1: the line is the allow-list's (kind and keys), and the record keeps its class names whole, never masked. */
  @Test
  fun `F-S4-1 the start-failed line is the allow-list's, and the record keeps its names`() =
    runTest {
      val signals = AttemptSignals()
      val s = scene(signals = signals)
      s.run.open(request)
      advanceUntilIdle()
      s.parts.failing[1] = RuntimeException("start failed", thrownAt(IllegalStateException("Codec is released"), StreamPack.ENCODER, "startStream"))
      does(s, connect(1))
      val line = s.told.lines.single { it.kind == "capture-start-failed" }
      val list = Shared.read("scrub/allow-list.json")
      val plain = list.getJSONArray("plain").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
      assertTrue(line.kind in list.getJSONObject("kinds").keySet(), "the kind is listed")
      assertEquals(emptyList(), line.fields.map { it.first }.filterNot { it in plain || it == "attempt" }, "each key plain")
      val written = mutableListOf<String>()
      SessionRecord { written += it }.append(0L, line)
      val record = written.single()
      for (name in listOf("RuntimeException", "MediaCodecEncoder.startStream", "IllegalStateException")) assertTrue("\"$name\"" in record, "$name kept: $record")
      assertFalse(SessionRecord.MASK in record, record)
    }

  /**
   * N45 I1: the slate with no take told probes nothing, and its probing ends by itself (the next `Slate(true)`, the
   * machine's answer to a take, starts it again). A probe that throws is a
   * refusal named by its class, never the session's failure, and its line is the allow-list's: a listed kind, each key
   * plain or a scalar. The slate's fall ends the probing.
   */
  @Test
  fun `N45 I1 a probe's throw is a refusal, never a failure, its line is the allow-list's, and a slate with no take probes nothing`() =
    runTest {
      val s = opened()
      try {
        s.parts.probeAnswer = Probe.Opened("0")
        does(s, connect(1), Command.Slate(true))
        assertEquals(0, s.parts.probeCount, "no take told: nothing probed")
        s.parts.probeFailure = IllegalStateException("made-up")
        s.parts.lose("0")
        runCurrent()
        s.run.execute(Command.Slate(true)) // the machine's answer to the take; never drained: a held take probes on
        advanceTimeBy(5_500)
        runCurrent()
        assertEquals(1, s.parts.probeCount)
        assertEquals(emptyList(), s.told.failures, "never the session's failure")
        val line = s.told.lines.single { it.kind == "camera-probe" }
        assertEquals(listOf<Pair<String, Any?>>("ok" to false, "error" to "IllegalStateException", "failures" to 1), line.fields)
        val list = Shared.read("scrub/allow-list.json")
        val plain = list.getJSONArray("plain").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        assertTrue(line.kind in list.getJSONObject("kinds").keySet(), "the kind is listed")
        assertEquals(emptyList(), line.fields.map { it.first }.filterNot { it in plain || it in list.getJSONObject("scalars").keySet() }, "each key plain or a scalar")
        does(s, Command.Slate(false))
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(1, s.parts.probeCount, "the slate down: no probe")
      } finally {
        s.parts.probeAnswer = null // the probing ends whatever the assertions do, so runTest's drain does
        s.parts.probeFailure = null
      }
    }

  /** F-S4-1: a start that runs out its bound is TIMEOUT, though its cancelled cleanup closed the endpoint it opened. */
  @Test
  fun `F-S4-1 a start that times out after its endpoint opened is TIMEOUT, never the close its cleanup made`() =
    runTest {
      val signals = AttemptSignals()
      val s = scene(signals = signals)
      s.run.open(request)
      advanceUntilIdle()
      s.parts.gates[1] = CompletableDeferred() // never answered: the start ends by its bound
      s.parts.starting = { id -> signals.dropped(id, null)?.let { s.told.reports += it } }
      does(s, connect(1))
      assertEquals(ConnectFailure.TIMEOUT, (s.told.reports.single() as? Input.ConnectFailed)?.failure, "${s.told.reports}")
    }
}
