package com.seazn.capture.engine.capture.run

import com.seazn.capture.engine.core.Command
import com.seazn.capture.engine.core.Configs
import com.seazn.capture.engine.core.RecordEntry
import com.seazn.capture.engine.core.Tuning
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CaptureRun]: the operator's capture choices and the camera's antibanding (spec 2026-11-24-capture-tuning). The
 * trap the camera-stabilisation spec names (§6): a choice held in JS that never reached the capture because the capture
 * opened from the pair's original request. Here the run keeps the latest [Command.Tune], held or not, and every build
 * reads it; match sound also reaches a held microphone at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureRunTuningTest : CaptureRunTestKit() {
  private val quiet = Tuning(matchSound = false)
  private val constant = Tuning(constantBitrate = true)

  private fun speechLines(s: Scene) = s.told.lines.filter { it.kind == "speech-processing" }

  private fun modeLines(s: Scene) = s.told.lines.filter { it.kind == "bitrate-mode" }

  private fun bandLines(s: Scene) = s.told.lines.filter { it.kind == "antibanding" }

  private fun speech(state: String) = RecordEntry("speech-processing", listOf("state" to state))

  private fun mode(state: String) = RecordEntry("bitrate-mode", listOf("state" to state))

  private fun band(state: String) = RecordEntry("antibanding", listOf("state" to state))

  private fun failed(action: String) = RecordEntry("capture-call-failed", listOf("action" to action, "error" to "IllegalStateException"))

  private fun TestScope.reopened(s: Scene) {
    s.run.close()
    advanceUntilIdle()
    s.run.open(Configs.pairRequest())
    advanceUntilIdle()
  }

  // ---- constant bitrate: read at every build ----

  @Test
  fun `a build reads the latest choice - the pair's Tune, heard before the capture opens`() =
    runTest {
      val s = scene()
      does(s, Command.Tune(constant))
      s.run.open(Configs.pairRequest())
      advanceUntilIdle()
      assertEquals(listOf(constant), s.parts.builtWith)
      assertEquals(listOf(mode("cbr")), modeLines(s))
    }

  @Test
  fun `with no choice heard a build is VBR, and writes no line`() =
    runTest {
      val s = scene()
      s.run.open(Configs.pairRequest())
      advanceUntilIdle()
      assertEquals(listOf(Tuning.DEFAULT), s.parts.builtWith)
      assertEquals(emptyList(), modeLines(s))
    }

  @Test
  fun `constant bitrate on an encoder without CBR says unsupported`() =
    runTest {
      val s = scene()
      s.parts.cbrSupported = false
      does(s, Command.Tune(constant))
      s.run.open(Configs.pairRequest())
      advanceUntilIdle()
      assertEquals(listOf(mode("unsupported")), modeLines(s))
    }

  /** Review minor 1: an encoder that lists CBR and refuses to configure with it must not fail every pair. */
  @Test
  fun `an encoder that refuses CBR is built once more with VBR, says unsupported, and the pair holds`() =
    runTest {
      val s = scene()
      s.parts.builds += Built.Failed("0", null, encoder = true, error = IllegalArgumentException("codec refused"))
      does(s, Command.Tune(constant))
      s.run.open(Configs.pairRequest())
      advanceUntilIdle()
      assertEquals(listOf(constant, Tuning.DEFAULT), s.parts.builtWith)
      assertEquals(listOf(mode("unsupported")), modeLines(s))
      assertEquals(emptyList(), s.told.pairFailed)
      assertTrue(s.parts.held)
    }

  @Test
  fun `a VBR build's encoder failure is never retried - it fails the pair as before`() =
    runTest {
      val s = scene()
      s.parts.builds += Built.Failed("0", null, encoder = true, error = IllegalArgumentException("codec refused"))
      s.parts.builds += Built.Failed("0", null, encoder = true, error = IllegalArgumentException("codec refused"))
      does(s, Command.Tune(constant))
      s.run.open(Configs.pairRequest())
      advanceUntilIdle()
      assertEquals(listOf(constant, Tuning.DEFAULT), s.parts.builtWith, "one retry, with VBR, and no more")
      assertEquals(1, s.told.pairFailed.size)
      assertEquals(emptyList(), modeLines(s))
    }

  /** The stabilisation spec's trap: Settings covers the viewfinder, the capture closes, the choice changes, it reopens. */
  @Test
  fun `a choice made while the capture is closed reaches the encoder at the reopen`() =
    runTest {
      val s = opened()
      s.run.close()
      advanceUntilIdle()
      does(s, Command.Tune(constant))
      s.run.open(Configs.pairRequest())
      advanceUntilIdle()
      assertEquals(listOf(Tuning.DEFAULT, constant), s.parts.builtWith)
      assertEquals(listOf(mode("cbr")), modeLines(s))
    }

  @Test
  fun `a change of constant bitrate over a held capture with nothing on air rebuilds it, once`() =
    runTest {
      val s = opened()
      does(s, Command.Tune(constant))
      assertEquals(listOf(Tuning.DEFAULT, constant), s.parts.builtWith)
      assertEquals(listOf("release", "release done", "build $left", "build done"), s.parts.log.takeLast(4))
      assertEquals(listOf("tuning"), s.told.lines.filter { it.kind == "capture-rebuilt" }.map { it.fields.single().second })
      does(s, Command.Tune(constant)) // a second call: the same choice
      assertEquals(2, s.parts.builtWith.size, "the same choice rebuilds nothing")
    }

  @Test
  fun `constant bitrate never rebuilds under an attempt - it waits for the next build`() =
    runTest {
      val s = opened()
      does(s, connect(1), Command.Tune(constant))
      assertEquals(listOf(Tuning.DEFAULT), s.parts.builtWith, "never a rebuild on air")
      does(s, Command.StopBroadcast)
      s.parts.lose("0") // off air, a loss rebuilds
      advanceUntilIdle()
      assertEquals(listOf(Tuning.DEFAULT, constant), s.parts.builtWith)
    }

  @Test
  fun `a rebuild after a loss reads the latest choice too`() =
    runTest {
      val s = scene()
      does(s, Command.Tune(constant))
      s.run.open(Configs.pairRequest())
      advanceUntilIdle()
      s.parts.lose("0")
      advanceUntilIdle()
      assertEquals(listOf(constant, constant), s.parts.builtWith)
    }

  // ---- match sound: the build, and a held microphone at once ----

  @Test
  fun `each opening sets speech processing as the choice says - off for match sound on, the default, with no line`() =
    runTest {
      val s = scene()
      s.run.open(Configs.pairRequest())
      advanceUntilIdle()
      assertEquals(listOf(false), s.parts.speeches)
      assertEquals(emptyList(), speechLines(s))
    }

  @Test
  fun `match sound off while held turns speech processing on at once, and back - with no stop or start`() =
    runTest {
      val s = opened()
      does(s, connect(1))
      s.parts.log.clear()
      does(s, Command.Tune(quiet))
      does(s, Command.Tune(Tuning.DEFAULT))
      assertEquals(listOf(false, true, false), s.parts.speeches)
      assertEquals(listOf(speech("on"), speech("off")), speechLines(s))
      assertEquals(emptyList(), s.parts.log, "the broadcast is never stopped for it")
    }

  @Test
  fun `a second Tune with the same match sound changes nothing`() =
    runTest {
      val s = opened()
      does(s, Command.Tune(quiet), Command.Tune(quiet))
      assertEquals(listOf(false, true), s.parts.speeches)
    }

  @Test
  fun `a choice made while closed reaches the microphone at the next opening`() =
    runTest {
      val s = opened()
      s.run.close()
      advanceUntilIdle()
      does(s, Command.Tune(quiet))
      assertEquals(listOf(false), s.parts.speeches, "nothing held: nothing set")
      s.run.open(Configs.pairRequest())
      advanceUntilIdle()
      assertEquals(listOf(false, true), s.parts.speeches)
      assertEquals(listOf(speech("on")), speechLines(s), "a new opening says its state")
    }

  @Test
  fun `a new opening with speech processing on says so again, and a recording unread at its build is set at its connect`() =
    runTest {
      val s = opened()
      does(s, Command.Tune(quiet))
      s.run.close()
      advanceUntilIdle()
      s.parts.speechKnown = false
      s.run.open(Configs.pairRequest())
      advanceUntilIdle()
      s.parts.speechKnown = true
      does(s, connect(1))
      assertEquals(listOf(false, true, true), s.parts.speeches, "the new recording is set: the old one's effects went with it")
      assertEquals(listOf(speech("on"), speech("on")), speechLines(s), "each opening says its own state")
    }

  @Test
  fun `the microphone's session not known at the build is set at the connect`() =
    runTest {
      val s = scene()
      s.parts.speechKnown = false
      does(s, Command.Tune(quiet))
      s.run.open(Configs.pairRequest())
      advanceUntilIdle()
      assertEquals(emptyList(), speechLines(s))
      s.parts.speechKnown = true
      does(s, connect(1))
      assertEquals(listOf(speech("on")), speechLines(s))
    }

  @Test
  fun `a phone with no speech effects says unavailable`() =
    runTest {
      val s = scene()
      s.parts.speechAvailable = false
      does(s, Command.Tune(quiet))
      s.run.open(Configs.pairRequest())
      advanceUntilIdle()
      assertEquals(listOf(speech("unavailable")), speechLines(s))
    }

  @Test
  fun `a speech change that throws is one line, never the session's failure, and the next change says again`() =
    runTest {
      val s = opened()
      s.parts.speechFailure = IllegalStateException("released")
      does(s, Command.Tune(quiet))
      assertEquals(emptyList(), s.told.failures)
      assertEquals(listOf(failed("speech-processing")), s.told.lines.filter { it.kind == "capture-call-failed" })
      s.parts.speechFailure = null
      does(s, Command.Tune(Tuning.DEFAULT))
      assertEquals(listOf(speech("off")), speechLines(s), "the failure left it unknown, so off is said")
    }

  // ---- antibanding: every accepted open ----

  @Test
  fun `the camera's open sets antibanding AUTO and says what the result read, once an opening`() =
    runTest {
      val s = opened()
      assertEquals(emptyList(), s.parts.antibanded, "nothing is open at the build")
      s.parts.opened("0")
      advanceUntilIdle()
      s.parts.opened("0")
      advanceUntilIdle()
      assertEquals(listOf("0", "0"), s.parts.antibanded, "set at each open: a new device session starts from defaults")
      assertEquals(listOf(band("auto")), bandLines(s))
    }

  @Test
  fun `a camera that does not list AUTO says unavailable, and a switch back says auto`() =
    runTest {
      val s = opened()
      s.parts.opened("0")
      advanceUntilIdle()
      does(s, Command.SwitchCamera)
      s.parts.opened("1") // FakeParts: camera 1 does not list AUTO
      advanceUntilIdle()
      does(s, Command.SwitchCamera)
      s.parts.opened("0")
      advanceUntilIdle()
      assertEquals(listOf(band("auto"), band("unavailable"), band("auto")), bandLines(s))
    }

  @Test
  fun `a busy open's false report is never given antibanding`() =
    runTest {
      val s = opened()
      s.parts.busyOpen("0")
      advanceUntilIdle()
      s.parts.opened("0")
      advanceUntilIdle()
      assertEquals(emptyList(), s.parts.antibanded)
    }

  @Test
  fun `an antibanding setter that throws is one line, never the session's failure`() =
    runTest {
      val s = opened()
      s.parts.antibandFailure = IllegalStateException("session closed")
      s.parts.opened("0")
      advanceUntilIdle()
      assertEquals(emptyList(), s.told.failures)
      assertEquals(listOf(failed("antibanding")), s.told.lines.filter { it.kind == "capture-call-failed" })
      s.parts.antibandFailure = null
      s.parts.opened("0")
      advanceUntilIdle()
      assertEquals(listOf(band("auto")), bandLines(s))
      assertEquals(listOf("0"), s.parts.stabilised.distinct(), "OIS still went on: one setting's throw is not the other's")
    }

  @Test
  fun `a new pairing's opening says its antibanding line again`() =
    runTest {
      val s = opened()
      s.parts.opened("0")
      advanceUntilIdle()
      reopened(s)
      s.parts.opened("0")
      advanceUntilIdle()
      assertEquals(listOf(band("auto"), band("auto")), bandLines(s))
    }

}
