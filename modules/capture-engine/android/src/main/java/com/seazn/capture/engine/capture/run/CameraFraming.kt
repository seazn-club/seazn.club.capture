package com.seazn.capture.engine.capture.run

import com.seazn.capture.engine.core.CameraTouch
import com.seazn.capture.engine.core.MeterPoint
import com.seazn.capture.engine.core.Pan
import com.seazn.capture.engine.core.PanCentre
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

  /** Where the zoomed crop is centred (spec §3.5); the middle at 1x. */
  private var centre = Pan.CENTRE

  /** The open camera has been given a crop region, so a centred frame must put the whole array back. */
  private var cropped = false
  private var point: MeterPoint? = null

  /** The camera whose open was last accepted, until a new open is asked, a busy word or a loss is heard, or the release. */
  private var open: String? = null

  /** The ignored lines said in this opening, by gesture and reason: one each. */
  private val ignoredSaid = mutableSetOf<Pair<String, String>>()

  /** A pinch has moved the ratio since the last gesture ended. */
  private var reframed = false

  /** Whether this broadcast's zoom line is written. */
  private var aired = false

  /** A pairing opens the capture. A new one starts at 1x with the camera's own metering, at once on a camera held open. */
  suspend fun paired(asked: PairRequest, frameDegrees: Int) {
    if (asked === pairing) return
    pairing = asked
    val id = open
    val zoomed = ratio != Zoom.DEFAULT
    val pointed = point != null
    val wasCropped = cropped
    ratio = Zoom.DEFAULT
    centre = Pan.CENTRE
    cropped = false
    point = null
    camera = id
    if (id == null) return
    if (wasCropped) guarded("zoom") { parts.pan(id, Zoom.DEFAULT, Pan.CENTRE, frameDegrees) }
    else if (zoomed) guarded("zoom") { parts.zoom(id, Zoom.DEFAULT) }
    if (pointed) guarded("metering") { parts.meter(id, null, frameDegrees) }
  }

  /** StreamPack reported camera [cameraId] open, and the run accepted it. Its own camera gets back what it had. */
  suspend fun opened(cameraId: String, frameDegrees: Int) {
    open = cameraId
    // A new source opens at the camera's defaults: whole array, 1x.
    cropped = false
    if (camera != cameraId) {
      camera = cameraId
      ratio = Zoom.DEFAULT
      centre = Pan.CENTRE
      point = null
      return
    }
    if (ratio != Zoom.DEFAULT) guarded("zoom") { framed(cameraId, frameDegrees) }
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
      CameraTouch.PinchEnd -> pinchEnded()
      is CameraTouch.Drag -> drag(touch.dx, touch.dy, frameDegrees)
      is CameraTouch.Tap -> meter(touch.point, frameDegrees)
      CameraTouch.Reset -> meter(null, frameDegrees)
    }
  }

  /**
   * Before air and on it alike (owner, 2026-11-26, superseding 2026-11-24's "before air only"): the same range, no
   * easing, no cap. On air the ratio a gesture ends on is one record line ([pinchEnded]).
   */
  private suspend fun pinch(scale: Float, frameDegrees: Int) {
    val id = open ?: return ignored("zoom", "closed")
    guarded("zoom") {
      val range = parts.zoomRange(id) ?: return@guarded ignored("zoom", "closed")
      val next = Zoom.next(ratio, scale, range)
      if (next == ratio) return@guarded
      val held = Pan.clamp(centre, next)
      if (!framed(id, frameDegrees, next, held)) return@guarded ignored("zoom", "closed")
      ratio = next
      centre = held
      reframed = true
      // The place tapped has moved in the picture: the camera's own metering again.
      if (point != null) {
        point = null
        parts.meter(id, null, frameDegrees)
      }
    }
  }

  /**
   * Spec §3.5 (owner, 2026-11-26): above 1x one finger moves the crop, 1:1, before air and on it. At 1x it does nothing.
   * The place tapped has moved in the picture, as for a pinch. No record line: no contract key carries a pan.
   */
  private suspend fun drag(dx: Float, dy: Float, frameDegrees: Int) {
    if (ratio <= Zoom.DEFAULT) return
    val id = open ?: return ignored("zoom", "closed")
    val moved = Pan.drag(centre, dx, dy, ratio)
    if (moved == centre) return
    guarded("zoom") {
      if (!framed(id, frameDegrees, ratio, moved)) return@guarded ignored("zoom", "closed")
      centre = moved
      if (point != null) {
        point = null
        parts.meter(id, null, frameDegrees)
      }
    }
  }

  /** The ratio and centre set on the open camera: the plain zoom while the crop is whole, else with the crop region. */
  private suspend fun framed(id: String, frameDegrees: Int, at: Float = ratio, around: PanCentre = centre): Boolean {
    if (around == Pan.CENTRE && !cropped) return parts.zoom(id, at)
    if (!parts.pan(id, at, around, frameDegrees)) return false
    cropped = around != Pan.CENTRE
    return true
  }

  /**
   * A gesture ended: on air, and only if it moved the ratio, one `zoom` line says where the picture was reframed to, so a
   * post-match record shows when. Once a gesture, never a frame. Before air the line at air start says the zoom in use.
   */
  private fun pinchEnded() {
    val moved = reframed
    reframed = false
    if (moved && broadcastHeld()) line(Zoom.entry(ratio))
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

/** Spec §3.5: drags the queue has not taken yet, as one move (their sum), by [PinchBuffer]'s rule. Any thread. */
class DragBuffer {
  private var dx = 0f
  private var dy = 0f
  private var queued = false

  /** True when the queue must be asked to [take]. */
  @Synchronized
  fun offer(moveX: Float, moveY: Float): Boolean {
    dx += moveX
    dy += moveY
    if (queued) return false
    queued = true
    return true
  }

  @Synchronized
  fun take(): Pair<Float, Float> {
    val taken = dx to dy
    dx = 0f
    dy = 0f
    queued = false
    return taken
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
