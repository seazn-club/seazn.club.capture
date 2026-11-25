package com.seazn.capture.engine.capture.run

import com.seazn.capture.engine.core.CameraTouch
import com.seazn.capture.engine.core.MeterPoint
import com.seazn.capture.engine.core.PairRequest
import com.seazn.capture.engine.core.RecordEntry
import com.seazn.capture.engine.core.Zoom
import kotlinx.coroutines.CancellationException

/**
 * Spec 2026-11-25-zoom-and-metering: the zoom and the place tapped, held for the pairing and its camera, and carried
 * out only on a camera whose open was accepted (never a busy open's false report: StreamPack 3.2.1's setters open a
 * device that is not open, javap). [CaptureRun] tells it each open, each new open asked, each loss, the release and air;
 * [touch] carries out the operator's gestures. Every call is on the run's one consumer.
 *
 * A new source opens with the camera's defaults, so the zoom and the place tapped go back on at each accepted open of
 * the same camera in the same pairing: a rebuild, the slate's fall, Settings closing the capture and the viewfinder
 * opening it again. A switch to the other camera, or a new pairing, starts again at 1x with the camera's own metering.
 */
class CameraFraming(private val parts: CaptureParts, private val line: (RecordEntry) -> Unit, private val broadcastHeld: () -> Boolean) {
  /** The pairing the zoom and the place tapped are held for, by identity: a re-scan of the same code is a new one. */
  private var pairing: PairRequest? = null

  /** The camera they were set on. Another camera's open starts again. */
  private var camera: String? = null

  private var ratio = Zoom.DEFAULT
  private var point: MeterPoint? = null

  /** The camera whose open was last accepted, until a new open is asked, a busy word or a loss is heard, or the release. */
  private var open: String? = null

  /** The ignored lines said in this opening, by gesture and reason: one each. */
  private val ignoredSaid = mutableSetOf<Pair<String, String>>()

  /** Whether this broadcast's zoom line is written. */
  private var aired = false

  /** A pairing opens the capture. A new one starts at 1x with the camera's own metering, at once on a camera held open. */
  suspend fun paired(asked: PairRequest, frameDegrees: Int) {
    if (asked === pairing) return
    pairing = asked
    val id = open
    val zoomed = ratio != Zoom.DEFAULT
    val pointed = point != null
    ratio = Zoom.DEFAULT
    point = null
    camera = id
    if (id == null) return
    if (zoomed) guarded("zoom") { parts.zoom(id, Zoom.DEFAULT) }
    if (pointed) guarded("metering") { parts.meter(id, null, frameDegrees) }
  }

  /** StreamPack reported camera [cameraId] open, and the run accepted it. Its own camera gets back what it had. */
  suspend fun opened(cameraId: String, frameDegrees: Int) {
    open = cameraId
    if (camera != cameraId) {
      camera = cameraId
      ratio = Zoom.DEFAULT
      point = null
      return
    }
    if (ratio != Zoom.DEFAULT) guarded("zoom") { parts.zoom(cameraId, ratio) }
    point?.let { guarded("metering") { parts.meter(cameraId, it, frameDegrees) } }
  }

  /** A new open is asked ([cameraId] null), or camera [cameraId] was lost or met another app's hold: nothing is open. */
  fun unopened(cameraId: String? = null) {
    if (cameraId == null || cameraId == open) open = null
  }

  /** The release: nothing is open, and the next opening says its own lines. The pairing's zoom and place stay. */
  fun closed() {
    open = null
    ignoredSaid.clear()
    aired = false
  }

  /** A broadcast's first attempt started: one line of the zoom in use. */
  fun airing() {
    if (aired) return
    aired = true
    line(Zoom.entry(ratio))
  }

  /** The broadcast is over: the next one says its zoom again. */
  fun grounded() {
    aired = false
  }

  suspend fun touch(touch: CameraTouch, frameDegrees: Int) {
    when (touch) {
      is CameraTouch.Pinch -> pinch(touch.scale, frameDegrees)
      is CameraTouch.Tap -> meter(touch.point, frameDegrees)
      CameraTouch.Reset -> meter(null, frameDegrees)
    }
  }

  /** Before air only (owner, 2026-11-24): from Arming on the picture never moves by a finger. */
  private suspend fun pinch(scale: Float, frameDegrees: Int) {
    if (broadcastHeld()) return ignored("zoom", "broadcast")
    val id = open ?: return ignored("zoom", "closed")
    guarded("zoom") {
      val range = parts.zoomRange(id) ?: return@guarded ignored("zoom", "closed")
      val next = Zoom.next(ratio, scale, range)
      if (next == ratio) return@guarded
      if (!parts.zoom(id, next)) return@guarded ignored("zoom", "closed")
      ratio = next
      // The place tapped has moved in the picture: the camera's own metering again.
      if (point != null) {
        point = null
        parts.meter(id, null, frameDegrees)
      }
    }
  }

  /** On air too: a tap moves no frame. [at] null is the double tap's reset. */
  private suspend fun meter(at: MeterPoint?, frameDegrees: Int) {
    val id = open ?: return ignored("meter", "closed")
    guarded("metering") {
      if (!parts.meter(id, at, frameDegrees)) return@guarded ignored("meter", "closed")
      point = at
    }
  }

  private fun ignored(action: String, reason: String) {
    if (ignoredSaid.add(action to reason)) line(CameraTouch.ignored(action, reason))
  }

  /** A setter's throw is one line, never the session's failure: the picture goes on as it was. */
  private suspend fun guarded(action: String, block: suspend () -> Unit) {
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      line(RecordEntry("capture-call-failed", listOf("action" to action, "error" to e.javaClass.simpleName)))
    }
  }
}

/**
 * Spec §3: pinches the queue has not taken yet, as one scale (their product). A pinch reports every move of two fingers,
 * faster than a camera applies a ratio, so only the first since the last take asks the queue for a turn. Any thread.
 */
class PinchBuffer {
  private var scale = 1f
  private var queued = false

  /** True when the queue must be asked to [take]. */
  @Synchronized
  fun offer(factor: Float): Boolean {
    scale *= factor
    if (queued) return false
    queued = true
    return true
  }

  @Synchronized
  fun take(): Float {
    val taken = scale
    scale = 1f
    queued = false
    return taken
  }
}
