package com.seazn.capture.engine.capture.run

import com.seazn.capture.engine.core.CameraTouch
import com.seazn.capture.engine.core.Command
import com.seazn.capture.engine.core.Configs
import com.seazn.capture.engine.core.MeterPoint
import com.seazn.capture.engine.core.RecordEntry
import com.seazn.capture.engine.core.PanCentre
import com.seazn.capture.engine.core.Side
import com.seazn.capture.engine.core.Zoom
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [CaptureRun]: the preview's pinch and tap (spec 2026-11-25-zoom-and-metering; owner, 2026-11-24: pinch zoom before
 * air only, a tap sets focus and exposure there). Both reach the camera only after an accepted open, as OIS does: a
 * setter against a device not open opens it (StreamPack 3.2.1, javap). Whether StreamPack reports each open, and what
 * the camera does with a ratio or a region, is the adapter's and the phone's; here each report is given.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureRunFramingTest : CaptureRunTestKit() {
  private val spot = MeterPoint(0.25f, 0.5f)

  private fun TestScope.ready(side: () -> Side = { Side.LEFT }): Scene =
    scene(side = side).also {
      it.run.open(request)
      advanceUntilIdle()
      it.parts.opened("0")
      advanceUntilIdle()
    }

  private fun TestScope.touched(s: Scene, vararg touches: CameraTouch) {
    touches.forEach(s.run::touch)
    advanceUntilIdle()
  }

  private fun TestScope.reported(s: Scene, camera: String) {
    s.parts.opened(camera)
    advanceUntilIdle()
  }

  private fun ignored(s: Scene) = s.told.lines.filter { it.kind == "camera-touch" }

  private fun ignoredLine(action: String, reason: String) = RecordEntry("camera-touch", listOf("action" to action, "reason" to reason))

  private fun metered(camera: String, at: MeterPoint?, degrees: Int) = Triple(camera, at, degrees)

  private fun zoomLines(s: Scene) = s.told.lines.filter { it.kind == "zoom" }

  @Test
  fun `a pinch on the open camera zooms it, and the next pinch goes on from there`() =
    runTest {
      val s = ready()
      touched(s, CameraTouch.Pinch(2f))
      touched(s, CameraTouch.Pinch(1.5f))
      assertEquals(listOf("0" to 2f, "0" to 3f), s.parts.zooms)
      assertEquals(emptyList(), ignored(s))
      assertEquals(emptyList(), s.told.failures)
    }

  @Test
  fun `a pinch that asks for the ratio already held sets nothing`() =
    runTest {
      val s = ready()
      touched(s, CameraTouch.Pinch(0.5f)) // under 1x: held at 1x
      touched(s, CameraTouch.Pinch(8f), CameraTouch.Pinch(1f))
      touched(s, CameraTouch.Pinch(2f)) // camera 0 tops out at 8x
      assertEquals(listOf("0" to 8f), s.parts.zooms)
    }

  @Test
  fun `pinches that come faster than the queue are one zoom, by their product`() =
    runTest {
      val s = ready()
      s.run.touch(CameraTouch.Pinch(2f))
      s.run.touch(CameraTouch.Pinch(1.5f))
      s.run.touch(CameraTouch.Pinch(1.25f))
      advanceUntilIdle()
      assertEquals(listOf("0" to 3.75f), s.parts.zooms)
      assertEquals(1, s.parts.rangeReads, "one turn of the queue for the three")
      touched(s, CameraTouch.Pinch(0.5f))
      assertEquals(listOf("0" to 3.75f, "0" to 1.875f), s.parts.zooms, "the product starts again after it was taken")
    }

  @Test
  fun `before any open a pinch and a tap set nothing, and each says so once`() =
    runTest {
      val s = scene()
      s.run.open(request)
      advanceUntilIdle()
      touched(s, CameraTouch.Pinch(2f), CameraTouch.Pinch(2f), CameraTouch.Tap(spot), CameraTouch.Reset)
      assertEquals(emptyList(), s.parts.zooms)
      assertEquals(emptyList(), s.parts.meters)
      assertEquals(listOf(ignoredLine("zoom", "closed"), ignoredLine("meter", "closed")), ignored(s))
    }

  @Test
  fun `from Arming on a pinch zooms as before it, with no easing or cap, and a tap still meters`() =
    runTest {
      val s = ready()
      touched(s, CameraTouch.Pinch(2f))
      s.told.held = true // the machine holds a broadcast from Arming on (BridgeCore.broadcastHeld)
      touched(s, CameraTouch.Pinch(2f), CameraTouch.Pinch(2f), CameraTouch.Tap(spot))
      assertEquals(listOf("0" to 2f, "0" to 8f), s.parts.zooms, "owner 2026-11-26: the same range on air; the two pinches are one turn")
      assertEquals(emptyList(), ignored(s),"no 'broadcast' refusal is said any more")
      assertEquals(listOf(metered("0", spot, 90)), s.parts.meters, "a tap moves no frame, so it goes on air too")
    }

  @Test
  fun `on air a gesture's end writes one zoom line with the ratio, and none when it moved nothing`() =
    runTest {
      val s = ready()
      touched(s, CameraTouch.Pinch(2f), CameraTouch.PinchEnd)
      assertEquals(emptyList(), zoomLines(s), "before air the line at air start says the zoom, not each gesture")
      s.told.held = true
      touched(s, CameraTouch.Pinch(1.5f))
      touched(s, CameraTouch.Pinch(1.2f))
      assertEquals(emptyList(), zoomLines(s), "no line per move")
      touched(s, CameraTouch.PinchEnd)
      assertEquals(listOf(Zoom.entry(3.6f)), zoomLines(s), "one line, the ratio to a tenth")
      touched(s, CameraTouch.PinchEnd)
      assertEquals(1, zoomLines(s).size, "a second end with no move since writes nothing")
      touched(s, CameraTouch.Pinch(1.1f), CameraTouch.PinchEnd)
      assertEquals(listOf(Zoom.entry(3.6f), Zoom.entry(4f)), zoomLines(s), "the next gesture writes its own")
    }

  @Test
  fun `a pinch at the top of the range moves nothing and so writes no line on air`() =
    runTest {
      val s = ready()
      s.told.held = true
      touched(s, CameraTouch.Pinch(100f), CameraTouch.PinchEnd)
      val top = zoomLines(s).size
      touched(s, CameraTouch.Pinch(2f), CameraTouch.PinchEnd)
      assertEquals(top, zoomLines(s).size, "already at the camera's top: no change, no line")
    }

  @Test
  fun `a camera switch puts the zoom back at 1x even after an on-air pinch`() =
    runTest {
      val s = ready()
      s.told.held = true
      touched(s, CameraTouch.Pinch(2f), CameraTouch.PinchEnd)
      reported(s, "1")
      touched(s, CameraTouch.Pinch(2f))
      assertEquals(listOf("0" to 2f, "1" to 2f), s.parts.zooms, "the other camera starts at 1x, so one pinch of 2 is 2x")
    }

  private fun panned(ratio: Float, x: Float, y: Float, degrees: Int = 90) = FakeParts.Panned("0", ratio, PanCentre(x, y), degrees)

  @Test
  fun `a drag at 1x does nothing, before air and on it`() =
    runTest {
      val s = ready()
      touched(s, CameraTouch.Drag(0.3f, 0.3f))
      s.told.held = true
      touched(s, CameraTouch.Drag(0.3f, 0.3f))
      assertEquals(emptyList(), s.parts.pans)
      assertEquals(emptyList(), s.parts.zooms)
      assertEquals(emptyList(), ignored(s))
    }

  @Test
  fun `a drag above 1x moves the crop 1 to 1, and the drags the queue had not taken are one move`() =
    runTest {
      val s = ready()
      touched(s, CameraTouch.Pinch(2f))
      touched(s, CameraTouch.Drag(0.1f, 0f), CameraTouch.Drag(0.1f, 0f))
      assertEquals(listOf(panned(2f, 0.4f, 0.5f)), s.parts.pans)
      touched(s, CameraTouch.Drag(-0.2f, 0.2f))
      assertEquals(panned(2f, 0.5f, 0.4f), s.parts.pans.last(), "from where it was, not from the middle")
      touched(s, CameraTouch.Drag(5f, 5f))
      assertEquals(panned(2f, 0.25f, 0.25f), s.parts.pans.last(), "clamped to the field")
    }

  @Test
  fun `a drag on air pans the same, and writes no line`() =
    runTest {
      val s = ready()
      touched(s, CameraTouch.Pinch(2f))
      s.told.held = true
      touched(s, CameraTouch.Drag(0.2f, 0f), CameraTouch.PinchEnd)
      assertEquals(listOf(panned(2f, 0.4f, 0.5f)), s.parts.pans)
      assertEquals(emptyList(), zoomLines(s).filter { it != Zoom.entry(2f) }, "no contract key carries a pan, so none is said")
    }

  @Test
  fun `a pinch while panned keeps the crop inside the field, and back to 1x puts the whole array back`() =
    runTest {
      val s = ready()
      touched(s, CameraTouch.Pinch(4f))
      touched(s, CameraTouch.Drag(-5f, 0f)) // 4x: the centre goes to its edge, 0.875
      assertEquals(panned(4f, 0.875f, 0.5f), s.parts.pans.last())
      touched(s, CameraTouch.Pinch(0.5f))
      assertEquals(panned(2f, 0.75f, 0.5f), s.parts.pans.last(), "the centre is brought in: 2x keeps 0.25 from the edge")
      touched(s, CameraTouch.Pinch(0.25f))
      assertEquals(panned(1f, 0.5f, 0.5f), s.parts.pans.last(), "1x is centred, and the crop region is set back to the whole array")
      touched(s, CameraTouch.Pinch(2f))
      assertEquals("0" to 2f, s.parts.zooms.last(), "centred and whole again: the plain zoom")
    }

  @Test
  fun `a drag moves the place tapped to the camera's own metering, as a pinch does`() =
    runTest {
      val s = ready()
      touched(s, CameraTouch.Pinch(2f), CameraTouch.Tap(spot), CameraTouch.Drag(0.2f, 0f))
      assertEquals(listOf(metered("0", spot, 90), metered("0", null, 90)), s.parts.meters)
    }

  @Test
  fun `a tap still meters while zoomed, and a drag that moves nothing sets nothing`() =
    runTest {
      val s = ready()
      touched(s, CameraTouch.Pinch(2f), CameraTouch.Tap(spot))
      assertEquals(listOf(metered("0", spot, 90)), s.parts.meters)
      touched(s, CameraTouch.Drag(0f, 0f))
      assertEquals(emptyList(), s.parts.pans)
    }

  @Test
  fun `a camera switch and a new pairing re-centre the crop, and the same camera's reopen puts it back`() =
    runTest {
      val s = ready()
      touched(s, CameraTouch.Pinch(2f), CameraTouch.Drag(0.2f, 0f))
      reported(s, "0") // Settings closed and reopened the capture: the same camera, a new source at the defaults
      assertEquals(panned(2f, 0.4f, 0.5f), s.parts.pans.last())
      assertEquals(2, s.parts.pans.size, "put back with the crop, once")
      s.run.open(Configs.pairRequest()) // a new pairing over the held capture
      advanceUntilIdle()
      assertEquals(panned(1f, 0.5f, 0.5f), s.parts.pans.last(), "centred at 1x, whole array")
      touched(s, CameraTouch.Drag(0.3f, 0.3f))
      assertEquals(3, s.parts.pans.size, "and a drag at 1x does nothing")
      touched(s, CameraTouch.Pinch(2f), CameraTouch.Drag(0.2f, 0f))
      assertEquals(4, s.parts.pans.size)
      does(s, Command.SwitchCamera)
      reported(s, "1")
      touched(s, CameraTouch.Drag(0.2f, 0f))
      assertEquals(4, s.parts.pans.size, "the other camera is at 1x: no pan")
      does(s, Command.SwitchCamera)
      reported(s, "0")
      touched(s, CameraTouch.Pinch(2f))
      assertEquals("0" to 2f, s.parts.zooms.last(), "back on the first camera it starts again at 1x, centred")
    }

  @Test
  fun `a tap meters at the place tapped for the side held, and a double tap gives the camera its own metering back`() =
    runTest {
      val s = ready(side = { Side.RIGHT })
      touched(s, CameraTouch.Tap(spot), CameraTouch.Reset)
      assertEquals(listOf(metered("0", spot, 270), metered("0", null, 270)), s.parts.meters)
    }

  @Test
  fun `a pinch after a tap puts the camera's own metering back, since the place tapped has moved`() =
    runTest {
      val s = ready()
      touched(s, CameraTouch.Tap(spot), CameraTouch.Pinch(2f))
      assertEquals(listOf(metered("0", spot, 90), metered("0", null, 90)), s.parts.meters)
      touched(s, CameraTouch.Pinch(1.5f))
      assertEquals(2, s.parts.meters.size, "no point held: nothing to put back")
    }

  @Test
  fun `a busy open's report is no open - a pinch then sets nothing - and the next accepted open is`() =
    runTest {
      val s = scene()
      s.run.open(request)
      advanceUntilIdle()
      s.parts.busyOpen("0")
      advanceUntilIdle()
      reported(s, "0") // StreamPack's report of the open that failed busy (N45)
      touched(s, CameraTouch.Pinch(2f), CameraTouch.Tap(spot))
      assertEquals(emptyList(), s.parts.zooms)
      assertEquals(emptyList(), s.parts.meters)
      s.parts.lose("0") // off air a loss rebuilds: a new open is asked
      advanceUntilIdle()
      reported(s, "0")
      touched(s, CameraTouch.Pinch(2f))
      assertEquals(listOf("0" to 2f), s.parts.zooms)
    }

  @Test
  fun `a busy word after an accepted open closes the gate until the next accepted open`() =
    runTest {
      val s = ready()
      does(s, connect(1))
      s.parts.busyOpen("0") // a reopen on air that met another app's hold
      advanceUntilIdle()
      touched(s, CameraTouch.Tap(spot))
      assertEquals(emptyList(), s.parts.meters)
      assertEquals(listOf(ignoredLine("meter", "closed")), ignored(s))
    }

  @Test
  fun `a busy word for another camera leaves the open camera's gate as it is`() =
    runTest {
      val s = ready()
      s.parts.busyOpen("1") // the front camera, not ours, met another app's hold
      advanceUntilIdle()
      touched(s, CameraTouch.Tap(spot))
      assertEquals(listOf(metered("0", spot, 90)), s.parts.meters)
      assertEquals(emptyList(), ignored(s))
    }

  @Test
  fun `a camera lost on air takes the gate with it`() =
    runTest {
      val s = ready()
      does(s, connect(1))
      s.parts.lose("0")
      advanceUntilIdle()
      touched(s, CameraTouch.Tap(spot))
      assertEquals(emptyList(), s.parts.meters)
    }

  @Test
  fun `the slate closes the gate, and its fall's open puts the zoom and the place tapped back`() =
    runTest {
      val s = ready()
      touched(s, CameraTouch.Pinch(2f), CameraTouch.Tap(spot))
      does(s, connect(1), Command.Slate(true))
      touched(s, CameraTouch.Tap(MeterPoint(0.5f, 0.5f)))
      assertEquals(listOf(metered("0", spot, 90)), s.parts.meters, "nothing set over the slate")
      does(s, Command.ReopenCamera)
      touched(s, CameraTouch.Tap(MeterPoint(0.5f, 0.5f))) // the camera is the source again, its open not yet reported
      assertEquals(listOf(metered("0", spot, 90)), s.parts.meters, "nothing set before the reopen's open is accepted")
      reported(s, "0")
      assertEquals(listOf("0" to 2f, "0" to 2f), s.parts.zooms, "the new source opens at 1x; the zoom held goes back on")
      assertEquals(listOf(metered("0", spot, 90), metered("0", spot, 90)), s.parts.meters)
    }

  @Test
  fun `Settings closing the capture keeps the pairing's zoom and place tapped for the next open`() =
    runTest {
      val s = ready()
      touched(s, CameraTouch.Pinch(2.5f), CameraTouch.Tap(spot))
      s.run.close() // Settings covers the viewfinder: BridgeCore closes the capture
      advanceUntilIdle()
      s.run.open(request) // back to the viewfinder: the same pairing, the same PairRequest object
      advanceUntilIdle()
      reported(s, "0")
      assertEquals(listOf("0" to 2.5f, "0" to 2.5f), s.parts.zooms)
      assertEquals(listOf(metered("0", spot, 90), metered("0", spot, 90)), s.parts.meters)
    }

  @Test
  fun `a new pairing opens at 1x with the camera's own metering`() =
    runTest {
      val s = ready()
      touched(s, CameraTouch.Pinch(2f), CameraTouch.Tap(spot))
      s.run.close()
      advanceUntilIdle()
      s.run.open(Configs.pairRequest()) // another scan: a new PairRequest
      advanceUntilIdle()
      reported(s, "0")
      assertEquals(listOf("0" to 2f), s.parts.zooms, "nothing put back")
      assertEquals(listOf(metered("0", spot, 90)), s.parts.meters)
      touched(s, CameraTouch.Pinch(1.5f))
      assertEquals("0" to 1.5f, s.parts.zooms.last(), "from 1x")
    }

  @Test
  fun `a re-pair over the held capture puts the open camera back to 1x and its own metering at once`() =
    runTest {
      val s = ready()
      touched(s, CameraTouch.Pinch(2f), CameraTouch.Tap(spot))
      s.run.open(Configs.pairRequest()) // PV20: the held capture serves the new pairing, with no new open
      advanceUntilIdle()
      assertEquals(listOf("0" to 2f, "0" to 1f), s.parts.zooms)
      assertEquals(listOf(metered("0", spot, 90), metered("0", null, 90)), s.parts.meters)
    }

  @Test
  fun `the same pairing opened again over a held capture changes nothing`() =
    runTest {
      val s = ready()
      touched(s, CameraTouch.Pinch(2f))
      s.run.open(request)
      advanceUntilIdle()
      assertEquals(listOf("0" to 2f), s.parts.zooms)
    }

  @Test
  fun `a camera switch opens the other camera at 1x, and the switch back is 1x too`() =
    runTest {
      val s = ready()
      touched(s, CameraTouch.Pinch(2f), CameraTouch.Tap(spot))
      does(s, Command.SwitchCamera)
      touched(s, CameraTouch.Pinch(2f))
      assertEquals(listOf("0" to 2f), s.parts.zooms, "between the switch and the new camera's open nothing is open")
      reported(s, "1")
      touched(s, CameraTouch.Pinch(1.5f))
      does(s, Command.SwitchCamera)
      reported(s, "0")
      assertEquals(listOf("0" to 2f, "1" to 1.5f), s.parts.zooms, "nothing put back on either camera")
      assertEquals(listOf(metered("0", spot, 90)), s.parts.meters)
    }

  @Test
  fun `air starts with one zoom line of the ratio in use, once a broadcast, and again for the next`() =
    runTest {
      val s = ready()
      touched(s, CameraTouch.Pinch(2.46f))
      does(s, connect(1))
      does(s, Command.Rebuild(1, connect(2))) // a reconnect is the same broadcast
      assertEquals(listOf(RecordEntry("zoom", listOf("state" to 2.5))), zoomLines(s))
      does(s, Command.StopBroadcast, connect(3))
      assertEquals(2, zoomLines(s).size, "the next broadcast says it again")
    }

  @Test
  fun `a start that fails says no zoom, and the attempt that starts does`() =
    runTest {
      val s = ready()
      s.parts.failing[1] = IllegalStateException("no route")
      does(s, connect(1))
      assertEquals(emptyList(), zoomLines(s))
      does(s, connect(2))
      assertEquals(listOf(RecordEntry("zoom", listOf("state" to 1.0))), zoomLines(s))
    }

  @Test
  fun `a release ends the broadcast's zoom line and the opening's ignored lines`() =
    runTest {
      val s = scene()
      s.run.open(request)
      advanceUntilIdle()
      touched(s, CameraTouch.Pinch(2f))
      s.parts.opened("0")
      advanceUntilIdle()
      does(s, connect(1))
      s.run.close()
      advanceUntilIdle()
      s.run.open(request)
      advanceUntilIdle()
      touched(s, CameraTouch.Pinch(2f))
      reported(s, "0")
      does(s, connect(2))
      assertEquals(listOf(ignoredLine("zoom", "closed"), ignoredLine("zoom", "closed")), ignored(s), "one an opening")
      assertEquals(2, zoomLines(s).size)
    }

  @Test
  fun `a setter that throws is one capture-call-failed line, never the session's failure, and the queue goes on`() =
    runTest {
      val s = ready()
      s.parts.zoomFailure = IllegalStateException("session closed")
      s.parts.meterFailure = IllegalStateException("session closed")
      touched(s, CameraTouch.Pinch(2f), CameraTouch.Tap(spot))
      assertEquals(emptyList(), s.told.failures)
      assertEquals(
        listOf(
          RecordEntry("capture-call-failed", listOf("action" to "zoom", "error" to "IllegalStateException")),
          RecordEntry("capture-call-failed", listOf("action" to "metering", "error" to "IllegalStateException")),
        ),
        s.told.lines.filter { it.kind == "capture-call-failed" },
      )
      s.parts.zoomFailure = null
      touched(s, CameraTouch.Pinch(2f))
      assertEquals(listOf("0" to 2f), s.parts.zooms, "from the ratio last set, 1x")
    }

  @Test
  fun `a range that reads nothing sets no ratio, and a camera that closes before the ratio keeps the ratio held`() =
    runTest {
      val s = ready()
      s.parts.rangeKnown = false
      touched(s, CameraTouch.Pinch(2f))
      assertEquals(emptyList(), s.parts.zooms, "no range: nothing set")
      s.parts.rangeKnown = true
      s.parts.zoomable = false
      touched(s, CameraTouch.Pinch(2f))
      s.parts.zoomable = true
      touched(s, CameraTouch.Pinch(2f))
      assertEquals(listOf("0" to 2f), s.parts.zooms, "the refused ratio was never held: the next pinch goes on from 1x")
      assertEquals(listOf(ignoredLine("zoom", "closed")), ignored(s))
    }

  @Test
  fun `pinches wait as one scale until the queue takes them`() {
    val buffer = PinchBuffer()
    assertEquals(true, buffer.offer(2f), "the first asks the queue for a turn")
    assertEquals(false, buffer.offer(1.5f), "the rest ride on that turn")
    assertEquals(3f, buffer.take())
    assertEquals(true, buffer.offer(0.5f), "taken: the next asks again")
    assertEquals(0.5f, buffer.take(), "from 1 again")
  }

  @Test
  fun `a part that finds no open camera of that id sets nothing and says so`() =
    runTest {
      val s = ready()
      s.parts.zoomRanges.remove("0") // the adapter's: the source held is not that camera, or not open now
      s.parts.meterable = false
      touched(s, CameraTouch.Pinch(2f), CameraTouch.Tap(spot))
      assertEquals(emptyList(), s.parts.zooms)
      assertEquals(emptyList(), s.parts.meters)
      assertEquals(listOf(ignoredLine("zoom", "closed"), ignoredLine("meter", "closed")), ignored(s))
    }
}
