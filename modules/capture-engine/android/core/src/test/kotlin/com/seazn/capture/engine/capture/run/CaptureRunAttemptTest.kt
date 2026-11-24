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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
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
 * [CaptureRun]: the endpoint's attempts - Disconnect(n), the signals, the start's bound, the broadcast's end and the commands passed through.
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle and runCurrent
class CaptureRunAttemptTest : CaptureRunTestKit() {
  // --- C6 CRITICAL: Disconnect(n) closes attempt n's endpoint only ---

  @Test
  fun `C6 CRITICAL Disconnect of an attempt the endpoint no longer holds leaves the newer attempt publishing`() =
    runTest {
      val s = opened()
      does(s, connect(1), connect(2))
      assertEquals(listOf(1), s.parts.closed, "attempt 2's connect closed attempt 1's endpoint")
      assertEquals(2, s.parts.publishing)
      s.parts.log.clear()
      does(s, Command.Disconnect(1))
      assertEquals(2, s.parts.publishing, "attempt 2 keeps publishing")
      assertEquals(emptyList(), s.parts.log, "nothing was stopped")
      // The positive pair: the attempt held is the one a Disconnect closes.
      does(s, Command.Disconnect(2))
      assertNull(s.parts.publishing)
      assertEquals(listOf(1, 2), s.parts.closed)
      assertEquals(emptyList(), s.told.reports.filterIsInstance<Input.Dropped>(), "both closes were asked for: no drop")
    }

  @Test
  fun `C6 a Disconnect with nothing held, or after the broadcast stopped, stops nothing`() =
    runTest {
      val s = opened()
      does(s, Command.Disconnect(1))
      assertEquals(emptyList(), s.parts.log)
      does(s, connect(1), Command.StopBroadcast)
      s.parts.log.clear()
      does(s, Command.Disconnect(1))
      assertEquals(emptyList(), s.parts.log, "the stop already closed it")
    }

  @Test
  fun `Rebuild and StartNewSession close the attempt held, then connect the next`() =
    runTest {
      val s = opened()
      does(s, connect(1))
      s.parts.log.clear()
      does(s, Command.Rebuild(1, connect(2)))
      assertEquals(listOf("stop 1", "stop done", "before 2", "start 2", "start done"), s.parts.log)
      s.parts.log.clear()
      does(s, Command.StartNewSession(2, connect(3)))
      assertEquals(listOf("stop 2", "stop done", "before 3", "start 3", "start done"), s.parts.log)
      assertEquals(listOf(1, 2), s.parts.closed)
      assertEquals(listOf(1, 2, 3), s.told.reports.filterIsInstance<Input.Connected>().map { it.attemptId })
      assertEquals(emptyList(), s.told.reports.filterIsInstance<Input.Dropped>(), "each close was asked for: no drop")
    }

  // --- AttemptSignals: every open reaches the machine; one drop per attempt ---

  @Test
  fun `every open reaches the machine, once, and an endpoint closing unasked is that attempt's one drop`() =
    runTest {
      val s = opened()
      does(s, connect(1))
      assertEquals(listOf<Input>(Input.Connected(1)), s.told.reports)
      s.parts.farEndCloses()
      advanceUntilIdle()
      val dropped = s.told.reports.filterIsInstance<Input.Dropped>()
      assertEquals(listOf(1), dropped.map { it.attemptId })
      assertEquals(DropReason.ENDPOINT_CLOSED, dropped.single().reason)
      // The machine's retry after the drop: the dead endpoint is closed first, as a stop asked for.
      does(s, connect(2))
      assertEquals(listOf("stop -", "stop done", "before 2", "start 2", "start done"), s.parts.log.takeLast(5))
      assertEquals(1, s.told.reports.count { it is Input.Dropped }, "still one drop")
    }

  @Test
  fun `a failed connect is reported through the signals, classified, and a target that does not plan is OTHER`() =
    runTest {
      val s = opened()
      s.parts.failing[1] = ConnectException("Connection setup failure: connection timed out")
      does(s, connect(1))
      val failed = s.told.reports.single()
      assertIs<Input.ConnectFailed>(failed)
      assertEquals(1, failed.attemptId)
      assertEquals(ConnectFailure.TIMEOUT, failed.failure)
      s.parts.gates[2] = CompletableDeferred(false) // already answered: the target does not plan
      does(s, connect(2))
      assertEquals(Input.ConnectFailed(2, ConnectFailure.OTHER, "srt url does not parse"), s.told.reports.last())
      assertEquals(emptyList(), s.told.reports.filterIsInstance<Input.Connected>())
    }

  // --- C6 I-1: a start is bounded, under the machine's own connect timeout ---

  @Test
  fun `C6 I-1 a start that never returns fails TIMEOUT, and a close queued behind it releases the camera within the machine's connect timeout`() =
    runTest {
      val s = opened()
      s.parts.gates[1] = CompletableDeferred() // never answered: a connect hung in the vendor's code
      var releasedAt: Long? = null
      s.parts.stopped = { if (s.parts.log.lastOrNull() == "release done") releasedAt = testScheduler.currentTime }
      s.run.execute(connect(1))
      s.run.close()
      advanceTimeBy(SessionMachine.CONNECT_TIMEOUT_MS / 2)
      runCurrent()
      assertFalse("release" in s.parts.log, "the hung start holds the one queue, and the close waits behind it")
      advanceUntilIdle()
      assertTrue("release done" in s.parts.log, "the close queued behind the hung start ran: ${s.parts.log}")
      assertFalse(s.parts.held, "the camera is released")
      val at = assertNotNull(releasedAt)
      assertTrue(at <= SessionMachine.CONNECT_TIMEOUT_MS, "released at $at ms, past the machine's ${SessionMachine.CONNECT_TIMEOUT_MS} ms")
      val failed = s.told.reports.single()
      assertIs<Input.ConnectFailed>(failed)
      assertEquals(1, failed.attemptId)
      assertEquals(ConnectFailure.TIMEOUT, failed.failure)
    }

  /** The sequence after it: the half-open endpoint is closed, the machine's own late Disconnect stops nothing, and the next attempt connects. */
  @Test
  fun `C6 I-1 after a start timed out its endpoint is closed, and the next attempt connects`() =
    runTest {
      val s = opened()
      s.parts.gates[1] = CompletableDeferred()
      does(s, connect(1))
      assertEquals(listOf("before 1", "start 1", "stop -", "stop done"), s.parts.log, "the cancelled start's endpoint is closed as a stop asked for")
      s.parts.log.clear()
      does(s, Command.Disconnect(1), connect(2))
      assertEquals(listOf("before 2", "start 2", "start done"), s.parts.log)
      assertEquals(2, s.parts.publishing)
      assertEquals(listOf(ConnectFailure.TIMEOUT), s.told.reports.filterIsInstance<Input.ConnectFailed>().map { it.failure })
      assertEquals(listOf<Input>(Input.Connected(2)), s.told.reports.filter { it !is Input.ConnectFailed })
    }

  // --- S9 (N50): a start that holds its thread, which no cancel ends, is abandoned at the bound ---

  /**
   * komuxer's connect suspends and a cancel ends it, but Ktor resolves the ingest host inside it with a blocking call, on
   * StreamPack's IO thread, which no cancel ends (N50). A start of an attempt in [blocked] holds a real thread of [pool]
   * until [freed], for at most 30 s; the queue runs on the test's clock. Opened, its log cleared.
   */
  private fun TestScope.blockedStarts(pool: ExecutorService, freed: CountDownLatch, blocked: Set<Int> = setOf(1)): Scene {
    val parts = FakeParts()
    val told = Told()
    val cameras = CameraAvailability()
    val run = CaptureRun(parts, AttemptSignals(), cameras, told.calls(), { Side.LEFT }, queueScope(), startContext = pool.asCoroutineDispatcher())
    parts.starting = { id -> if (id in blocked) freed.await(30, TimeUnit.SECONDS) }
    run.open(request)
    advanceUntilIdle()
    parts.log.clear()
    return Scene(parts, told, cameras, run)
  }

  /**
   * Runs what the queue has due now, never moving the test's clock (a start on [blockedStarts]' pool runs in real time),
   * until [done] or [forMs] of real time.
   */
  private fun TestScope.pumpReal(forMs: Long = 5_000, done: () -> Boolean = { false }) {
    val until = System.nanoTime() + forMs * 1_000_000
    while (!done() && System.nanoTime() < until) {
      Thread.sleep(10)
      runCurrent()
    }
  }

  @Test
  fun `S9 a start that holds its thread past the bound fails TIMEOUT at the bound, and a Stop queued behind it is done then`() =
    runTest {
      val pool = Executors.newFixedThreadPool(2)
      val freed = CountDownLatch(1)
      try {
        val s = blockedStarts(pool, freed)
        s.run.execute(connect(1))
        s.run.execute(Command.StopBroadcast)
        pumpReal(forMs = 200) // the start reaches its thread, and holds it
        advanceTimeBy(CaptureRun.START_BOUND_MS - 1)
        runCurrent()
        assertEquals(emptyList(), s.told.reports, "inside the bound the start is waited for")
        advanceTimeBy(1)
        runCurrent()
        val failed = s.told.reports.single()
        assertIs<Input.ConnectFailed>(failed, "at the bound, with the start's thread still held")
        assertEquals(1, failed.attemptId)
        assertEquals(ConnectFailure.TIMEOUT, failed.failure)
        advanceTimeBy(SessionMachine.CONNECT_TIMEOUT_MS - CaptureRun.START_BOUND_MS - 1)
        runCurrent()
        assertTrue("after" in s.parts.log, "the Stop queued behind the start is done before the machine's connect timeout: ${s.parts.log}")
        assertFalse(s.parts.log.any { it.startsWith("stop") }, "the endpoint waits for the abandoned start, which holds it")
        freed.countDown()
        pumpReal { "stop -" in s.parts.log }
        advanceUntilIdle() // no start is in flight now
        assertEquals(listOf("stop -", "stop done"), s.parts.log.takeLast(2), "the abandoned start ended: its endpoint is closed, once")
        assertEquals(listOf<Input>(failed), s.told.reports, "the late end reports nothing")
      } finally {
        freed.countDown()
        pool.shutdownNow()
      }
    }

  @Test
  fun `S9 an abandoned start that ends after the next attempt connected closes nothing - the newer attempt keeps publishing`() =
    runTest {
      val pool = Executors.newFixedThreadPool(2)
      val freed = CountDownLatch(1)
      try {
        val s = blockedStarts(pool, freed)
        s.run.execute(connect(1))
        pumpReal(forMs = 200)
        advanceTimeBy(SessionMachine.CONNECT_TIMEOUT_MS)
        runCurrent()
        s.run.execute(connect(2))
        pumpReal { s.parts.publishing == 2 && Input.Connected(2) in s.told.reports }
        assertEquals(2, s.parts.publishing, "attempt 2 connected beside the start still held: ${s.parts.log}")
        freed.countDown()
        pumpReal(forMs = 300)
        assertEquals(2, s.parts.publishing, "attempt 2 keeps publishing")
        assertFalse(s.parts.log.any { it.startsWith("stop") }, "attempt 1's late end closes nothing: ${s.parts.log}")
      } finally {
        freed.countDown()
        pool.shutdownNow()
      }
    }

  /**
   * An unpair inside the abandon window releases the capture; a re-pair builds a new one. The abandoned start's late end
   * then closes nothing: the release closed its endpoint, and the streamer held now is another opening's.
   */
  @Test
  fun `S9 an abandoned start's late end after an unpair, or after a re-pair, closes nothing`() =
    runTest {
      var checked = 0
      for (repair in listOf(false, true)) {
        val pool = Executors.newFixedThreadPool(2)
        val freed = CountDownLatch(1)
        try {
          val s = blockedStarts(pool, freed)
          s.run.execute(connect(1))
          pumpReal(forMs = 200)
          advanceTimeBy(SessionMachine.CONNECT_TIMEOUT_MS)
          runCurrent()
          s.run.close()
          advanceUntilIdle() // the abandoned start's thread is held; nothing else is in flight
          assertTrue("release done" in s.parts.log, "repair=$repair: the fake's release takes no lock (N50: on a device the unpair waits): ${s.parts.log}")
          if (repair) {
            s.run.open(request)
            advanceUntilIdle()
            assertTrue(s.parts.held, "repair=$repair: the re-pair built a capture")
          }
          s.parts.log.clear()
          freed.countDown()
          pumpReal(forMs = 300)
          advanceUntilIdle()
          assertEquals(emptyList(), s.parts.log, "repair=$repair: the late end touches nothing")
          assertEquals(repair, s.parts.held, "repair=$repair: the capture held is as the operator left it")
          checked += 1
        } finally {
          freed.countDown()
          pool.shutdownNow()
        }
      }
      assertEquals(2, checked, "both cases ran")
    }

  // --- The broadcast's end, and the commands passed through ---

  @Test
  fun `StopBroadcast and End stop the attempt held and keep the camera`() =
    runTest {
      for (end in listOf(Command.StopBroadcast, Command.End(EndReason.OPERATOR_STOPPED), Command.End(EndReason.FATAL_ERROR))) {
        val s = opened()
        does(s, connect(1), end)
        assertEquals(listOf("before 1", "start 1", "start done", "stop 1", "stop done", "after"), s.parts.log, "$end")
        assertTrue(s.parts.held, "$end: the camera stays until close")
        assertEquals(emptyList(), s.told.reports.filterIsInstance<Input.Dropped>(), "$end")
      }
    }

  @Test
  fun `nothing held - no command reaches StreamPack, a connect is answered at once, and a reopen answers false`() =
    runTest {
      val s = scene()
      does(s, connect(1), Command.Disconnect(1), Command.SetBitrate(1, 2_000_000), Command.Slate(true), Command.SwitchCamera, Command.ReopenCamera)
      assertEquals(emptyList(), s.parts.log)
      assertEquals(listOf<Input>(Input.ConnectFailed(1, ConnectFailure.UNHELD, "no capture held"), Input.CameraReopened(false)), s.told.reports)
    }

  /**
   * C6 m-3: a connect with no capture held, a Rebuild's or a new session's next one too, is answered at
   * once with a line, never left for the machine's connect timeout. After a close as before an open, and
   * again for the next attempt.
   */
  @Test
  fun `C6 m-3 and I-3 a connect with nothing held writes a line and fails UNHELD at once, each time`() =
    runTest {
      val s = opened()
      s.run.close()
      advanceUntilIdle()
      s.parts.log.clear()
      does(s, connect(1), Command.Rebuild(1, connect(2)), Command.StartNewSession(2, connect(3)))
      assertEquals(emptyList(), s.parts.log, "nothing reached StreamPack")
      assertEquals((1..3).map<Int, Input> { Input.ConnectFailed(it, ConnectFailure.UNHELD, "no capture held") }, s.told.reports)
      assertEquals(List(3) { "capture-unheld" }, s.told.lines.map { it.kind })
      assertEquals((1..3).map { listOf<Pair<String, Any?>>("action" to "connect", "attempt" to it) }, s.told.lines.map { it.fields })
    }

  /** With an attempt held the reopen is the source's swap back from the slate, its answer passed as it is. */
  @Test
  fun `held - the encoder, the link, the camera and the slate commands reach StreamPack`() =
    runTest {
      val s = opened()
      does(s, connect(1))
      s.parts.log.clear()
      s.parts.reopens = false
      does(s, Command.SetBitrate(1, 2_000_000), Command.SetMaxBw(1, 250_000), Command.SwitchCamera, Command.ReopenCamera, Command.Slate(true))
      assertEquals(listOf("bitrate 2000000", "maxbw 1 250000", "switch", "reopen", "slate true"), s.parts.log)
      assertEquals(listOf<Input>(Input.Connected(1), Input.CameraReopened(false)), s.told.reports)
    }

  /**
   * Device fix round, item (c): with nothing on air no slate is up (I2), so the source held is our camera, and
   * StreamPack 3.2.1's `VideoInput.setSource` skips a camera source of the same id (`CameraSourceFactory.isSourceEquals`,
   * javap): the swap opened nothing and answered true. The reopen is a build afresh, a real open, at the rotation
   * the broadcast holds (CD42), never the side the phone turned to since; the new opening's open is watched.
   */
  @Test
  fun `(c) with nothing on air the release's reopen builds the camera afresh, at the rotation held, and watches its open`() =
    runTest {
      var side = Side.LEFT
      val s = scene(side = { side })
      s.run.open(request)
      advanceUntilIdle()
      s.parts.log.clear()
      side = Side.RIGHT // turned, with no Orient yet: the build keeps the rotation held
      does(s, Command.ReopenCamera)
      assertEquals(listOf("release", "release done", "build $left", "build done"), s.parts.log)
      assertEquals(listOf<Input>(Input.CameraReopened(true)), s.told.reports)
      assertEquals(listOf(RecordEntry("capture-rebuilt", listOf("action" to "reopen"))), s.told.lines)
      assertEquals(1, s.parts.watching, "the new opening watches its camera")
    }
}
