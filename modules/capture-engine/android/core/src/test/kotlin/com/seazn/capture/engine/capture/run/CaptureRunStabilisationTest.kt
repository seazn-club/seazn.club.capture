package com.seazn.capture.engine.capture.run

import com.seazn.capture.engine.core.Command
import com.seazn.capture.engine.core.Configs
import com.seazn.capture.engine.core.RecordEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [CaptureRun]: optical image stabilisation (spec 2026-11-24-camera-stabilisation; owner, 2026-11-24: "OIS only, drop
 * the EIS setting entirely"). Camera settings belong to one camera source (StreamPack 3.2.1, `ICameraSource.getSettings`,
 * javap), and every path that gives the streamer a new source (a build, a rebuild, a switch, the slate's fall, a reopen)
 * ends in StreamPack's report of that source open, so OIS is turned on at each accepted report. Whether StreamPack
 * reports each of those opens on a phone is the adapter's `cameraOpens` (device-only); here each report is given.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureRunStabilisationTest : CaptureRunTestKit() {
  private val on = RecordEntry("stabilisation", listOf("source" to "optical", "state" to "on"))
  private val none = RecordEntry("stabilisation", listOf("source" to "optical", "state" to "unavailable"))

  private fun lines(s: Scene) = s.told.lines.filter { it.kind == "stabilisation" }

  private fun TestScope.opened(): Scene =
    scene().also {
      it.run.open(Configs.pairRequest())
      advanceUntilIdle()
    }

  private fun TestScope.reported(s: Scene, camera: String) {
    s.parts.opened(camera)
    advanceUntilIdle()
  }

  @Test
  fun `the camera's open turns OIS on and says so once - and nothing before the open`() =
    runTest {
      val s = opened()
      assertEquals(emptyList(), s.parts.stabilised, "a build asks for a camera; nothing is open to set yet")
      reported(s, "0")
      assertEquals(listOf("0"), s.parts.stabilised)
      assertEquals(listOf(on), lines(s))
      assertEquals(emptyList(), s.told.failures)
    }

  @Test
  fun `a second report of the same open turns it on again and writes no second line`() =
    runTest {
      val s = opened()
      reported(s, "0")
      reported(s, "0") // the preview's restart: a new device session, whose request builder is new (javap, 3.2.1)
      assertEquals(listOf("0", "0"), s.parts.stabilised)
      assertEquals(listOf(on), lines(s))
    }

  @Test
  fun `a rebuild after a loss turns OIS on for the new camera source, with its line again`() =
    runTest {
      val s = opened()
      reported(s, "0")
      s.parts.lose("0")
      advanceUntilIdle()
      assertEquals(listOf("release", "release done", "build $left", "build done"), s.parts.log.takeLast(4), "rebuilt")
      reported(s, "0")
      assertEquals(listOf("0", "0"), s.parts.stabilised)
      assertEquals(listOf(on, on), lines(s), "a new opening writes its own line")
    }

  @Test
  fun `a switch to a camera with no OIS sets nothing on it and says it has none, and the switch back turns it on`() =
    runTest {
      val s = opened()
      reported(s, "0")
      does(s, Command.SwitchCamera)
      reported(s, "1") // FakeParts: camera 1 has no OIS
      assertEquals(listOf("0"), s.parts.stabilised, "an unavailable OIS is never set")
      does(s, Command.SwitchCamera)
      reported(s, "0")
      assertEquals(listOf("0", "0"), s.parts.stabilised)
      assertEquals(listOf(on, none, on), lines(s))
    }

  @Test
  fun `on air the slate's fall and a reopen turn OIS on for the camera source they bring back`() =
    runTest {
      val s = opened()
      reported(s, "0")
      does(s, connect(1), Command.Slate(true))
      s.parts.optical.remove("0") // while the slate is the source, no camera source of ours is held
      reported(s, "0") // a stale report: nothing to set on a slate
      assertEquals(listOf("0"), s.parts.stabilised)
      s.parts.optical["0"] = true
      does(s, Command.ReopenCamera)
      reported(s, "0")
      assertEquals(listOf("0", "0"), s.parts.stabilised)
    }

  /** Review finding 2: a busy open's false report (N45) is no open: a setting against it would open the device. */
  @Test
  fun `a busy open's report on air is never stabilised, and the next accepted open is`() =
    runTest {
      val s = opened()
      reported(s, "0")
      does(s, connect(1))
      s.parts.busyOpen("0")
      advanceUntilIdle()
      reported(s, "0")
      assertEquals(listOf("0"), s.parts.stabilised, "the false open set nothing")
      does(s, Command.StopBroadcast) // the busy mark ends with the broadcast (review R1-I2)
      reported(s, "0")
      assertEquals(listOf("0", "0"), s.parts.stabilised)
    }

  @Test
  fun `a setter that throws writes one capture-call-failed line, never fails the session, and the next open says again`() =
    runTest {
      val s = opened()
      s.parts.stabiliseFailure = IllegalStateException("session closed")
      reported(s, "0")
      assertEquals(emptyList(), s.told.failures)
      assertEquals(
        listOf(RecordEntry("capture-call-failed", listOf("action" to "stabilisation", "error" to "IllegalStateException"))),
        s.told.lines.filter { it.kind == "capture-call-failed" },
      )
      assertEquals(emptyList(), lines(s), "not on: never said on")
      s.parts.stabiliseFailure = null
      reported(s, "0")
      assertEquals(listOf("0"), s.parts.stabilised, "the queue goes on")
      assertEquals(listOf(on), lines(s))
    }

  @Test
  fun `a throw after a line said on lets the next open say on again`() =
    runTest {
      val s = opened()
      reported(s, "0")
      s.parts.stabiliseFailure = IllegalStateException("session closed")
      reported(s, "0")
      s.parts.stabiliseFailure = null
      reported(s, "0")
      assertEquals(listOf(on, on), lines(s), "the failure left the state unknown, so the next open says it")
    }

  @Test
  fun `after a release the next pairing's open says its line again`() =
    runTest {
      val s = opened()
      reported(s, "0")
      s.run.close()
      advanceUntilIdle()
      s.run.open(Configs.pairRequest())
      advanceUntilIdle()
      reported(s, "0")
      assertEquals(listOf("0", "0"), s.parts.stabilised)
      assertEquals(listOf(on, on), lines(s))
    }
}
