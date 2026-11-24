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
 * [CaptureRun]: the loss rebuilds' spacing and their cap (re-reviews 3, 4 and 5).
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle and runCurrent
class CaptureRunRebuildTest : CaptureRunTestKit() {
  /** Re-review 4 m2: a loss rebuilt at once, and a second loss inside the spacing, owed. */
  private fun TestScope.owedLoss(): Scene {
    val s = opened()
    s.parts.opened("0")
    advanceTimeBy(100)
    s.parts.lose("0")
    advanceTimeBy(100)
    runCurrent()
    s.parts.lose("0")
    runCurrent()
    assertEquals(1, s.told.lines.count { it.kind == "capture-rebuilt" }, "the first loss, at once")
    assertFalse(s.cameras.ready(camera = true), "owed: lost")
    return s
  }

  /** Re-review 4 m2, guard (a): an attempt started inside the spacing owns the camera; the owed rebuild would cut it. */
  @Test
  fun `re-review 4 m2 an owed rebuild does not run once a connect started inside the spacing, and the attempt is not closed`() =
    runTest {
      val s = owedLoss()
      s.run.execute(connect(1))
      advanceTimeBy(100)
      runCurrent()
      assertEquals(Input.Connected(1), s.told.reports.last())
      s.parts.log.clear()
      advanceTimeBy(SessionMachine.RETRY_MS + 200)
      runCurrent()
      assertEquals(emptyList(), s.parts.log, "no release, no build")
      assertEquals(1, s.parts.publishing, "still on air")
      assertEquals(1, s.told.lines.count { it.kind == "capture-rebuilt" })
    }

  /** Re-review 4 m2, guard (b): an open seen inside the spacing answers the loss; nothing is rebuilt. */
  @Test
  fun `re-review 4 m2 an owed rebuild does not run once an open was seen inside the spacing`() =
    runTest {
      val s = owedLoss()
      s.parts.opened("0")
      runCurrent()
      assertTrue(s.cameras.ready(camera = true), "the open seen")
      s.parts.log.clear()
      advanceTimeBy(SessionMachine.RETRY_MS + 200)
      runCurrent()
      assertEquals(emptyList(), s.parts.log, "no release, no build")
      assertEquals(1, s.told.lines.count { it.kind == "capture-rebuilt" })
    }

  /**
   * Re-review 4 m2: on a dispatcher that resumes at once (as Dispatchers.Default can), the spacing's queued block
   * runs while the spacing's own job is still active. Read as "inside the spacing", the owed rebuild owed itself
   * and never ran: the camera stayed lost. The block clears the spacing first.
   */
  @Test
  fun `re-review 4 m2 the owed rebuild runs when the spacing's block runs inside its own job`() =
    runTest {
      val s = opened(CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob()))
      s.parts.opened("0")
      advanceTimeBy(100)
      s.parts.lose("0")
      advanceTimeBy(100)
      runCurrent()
      s.parts.lose("0") // owed
      assertFalse(s.cameras.ready(camera = true))
      advanceTimeBy(SessionMachine.RETRY_MS + 200)
      runCurrent()
      val rebuilds = s.told.lines.count { it.kind == "capture-rebuilt" }
      assertEquals(2, rebuilds, "the loss at once, then the owed one: ${s.parts.log}")
      assertTrue(s.cameras.ready(camera = true), "a new open asked")
    }

  // --- Re-review 4 m2 cap (controller ruling 2026-10-05): consecutive loss rebuilds with no open seen stop ---

  /** The camera fails the open each rebuild asks for: [times] losses, each followed by its spacing. */
  private fun TestScope.failOpens(s: Scene, times: Int) =
    repeat(times) {
      s.parts.lose("0")
      advanceTimeBy(SessionMachine.RETRY_MS + 100)
      runCurrent()
    }

  private fun rebuilds(s: Scene) = s.told.lines.count { it.kind == "capture-rebuilt" }

  private fun gaveUp(s: Scene) = s.told.lines.filter { it.kind == "camera-gave-up" }

  private fun TestScope.seenOpen(): Scene =
    opened().also {
      it.parts.opened("0")
      advanceTimeBy(100)
      runCurrent()
    }

  @Test
  fun `re-review 4 cap a camera that fails every open is rebuilt MAX_LOSS_REBUILDS times, then one camera-gave-up line, then nothing`() =
    runTest {
      val max = CaptureRun.MAX_LOSS_REBUILDS
      val s = seenOpen()
      failOpens(s, max)
      assertEquals(max, rebuilds(s), "each loss rebuilt, up to the cap")
      assertEquals(emptyList(), gaveUp(s), "not yet")
      failOpens(s, 1)
      assertEquals(max, rebuilds(s), "no rebuild past the cap")
      assertEquals(listOf(RecordEntry("camera-gave-up", listOf("rebuilds" to max))), gaveUp(s))
      assertFalse(s.cameras.ready(camera = true), "it stays not ready")
      val lines = s.told.lines.size
      val log = s.parts.log.size
      advanceTimeBy(10 * SessionMachine.RETRY_MS)
      runCurrent()
      assertEquals(lines to log, s.told.lines.size to s.parts.log.size, "silence")
      failOpens(s, 2)
      assertEquals(max, rebuilds(s), "a later loss rebuilds nothing")
      assertEquals(1, gaveUp(s).size, "and is not said twice")
    }

  @Test
  fun `re-review 4 cap the next foreground re-arms - one rebuild at once, and the count starts again`() =
    runTest {
      val max = CaptureRun.MAX_LOSS_REBUILDS
      val s = seenOpen()
      failOpens(s, max + 1)
      assertEquals(1, gaveUp(s).size)
      s.run.foreground()
      advanceTimeBy(100)
      runCurrent()
      assertEquals(max + 1, rebuilds(s), "re-armed: built at once")
      assertTrue(s.cameras.ready(camera = true), "a new open asked")
      failOpens(s, max)
      assertEquals(2 * max, rebuilds(s), "the count started again: ${max - 1} more")
      assertEquals(2, gaveUp(s).size, "a new give-up, said once")
      s.run.foreground() // given up again: re-armed again
      advanceTimeBy(100)
      runCurrent()
      assertEquals(2 * max + 1, rebuilds(s))
      s.parts.opened("0")
      runCurrent()
      s.parts.log.clear()
      s.run.foreground()
      advanceTimeBy(SessionMachine.RETRY_MS + 100)
      runCurrent()
      assertEquals(emptyList(), s.parts.log, "a foreground with the camera ready rebuilds nothing")
    }

  /**
   * Re-review 5 C2: the re-arm builds at once only a camera GIVEN UP on. A camera lost on air is marked only (the
   * attempt's own paths decide), so once that attempt ends it reads lost, given up on by no one, with nothing on
   * air: a foreground then re-arms the count and builds nothing. The sequence goes on to the positive pair: the
   * same camera, opened, then given up on, is built at the next foreground.
   */
  @Test
  fun `re-review 5 C2 a foreground builds only a camera given up on, never one lost on air and left for its attempt`() =
    runTest {
      val max = CaptureRun.MAX_LOSS_REBUILDS
      val s = seenOpen()
      does(s, connect(1))
      s.parts.lose("0")
      advanceUntilIdle()
      does(s, Command.Disconnect(1))
      assertFalse(s.cameras.ready(camera = true), "lost on air, and still lost after its attempt")
      assertEquals(emptyList(), gaveUp(s), "given up on by no one")
      s.run.foreground()
      advanceTimeBy(SessionMachine.RETRY_MS + 100)
      runCurrent()
      assertEquals(0, rebuilds(s), "not given up on: the foreground builds nothing")
      s.parts.opened("0")
      advanceTimeBy(100)
      runCurrent()
      failOpens(s, max + 1)
      assertEquals(max to 1, rebuilds(s) to gaveUp(s).size, "then given up on")
      s.run.foreground()
      advanceTimeBy(100)
      runCurrent()
      assertEquals(max + 1, rebuilds(s), "given up on: built at once")
    }

  @Test
  fun `re-review 4 cap a re-pair re-arms a camera given up on`() =
    runTest {
      val max = CaptureRun.MAX_LOSS_REBUILDS
      val s = seenOpen()
      failOpens(s, max + 1)
      assertEquals(1, gaveUp(s).size)
      s.run.open(request)
      advanceTimeBy(100)
      runCurrent()
      assertEquals(max + 1, rebuilds(s), "re-armed: built at once")
      failOpens(s, max)
      assertEquals(2, gaveUp(s).size, "the count started again")
    }

  @Test
  fun `re-review 4 cap an open seen between losses resets the count`() =
    runTest {
      val max = CaptureRun.MAX_LOSS_REBUILDS
      val s = seenOpen()
      val before = max - 1
      failOpens(s, before)
      s.parts.opened("0")
      advanceTimeBy(100)
      runCurrent()
      failOpens(s, max)
      assertEquals(before + max, rebuilds(s), "the open reset the count")
      assertEquals(emptyList(), gaveUp(s))
      failOpens(s, 1)
      assertEquals(listOf(RecordEntry("camera-gave-up", listOf("rebuilds" to max))), gaveUp(s))
    }
}
