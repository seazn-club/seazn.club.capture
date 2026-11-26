package com.seazn.capture.engine.capture

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import com.seazn.capture.engine.core.Pan
import com.seazn.capture.engine.core.PanCentre
import com.seazn.capture.engine.core.ZoomRange
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource

/**
 * Spec 2026-11-25-zoom-and-metering §3.5 (owner, 2026-11-26, option A): all zoom is a digital crop of the current camera.
 * `CONTROL_ZOOM_RATIO` stays 1.0 and the zoom and the pan are `SCALER_CROP_REGION` alone, in active-array coordinates:
 * with a ratio other than 1 the crop region is overridden and the coordinates are post-zoom (CaptureRequest docs), and a
 * zoom ratio is also what hands a logical camera to another lens, a jump on air. StreamPack 3.2.1's
 * `RZoom.setZoomRatio` is `settings.set(..)` then `applyRepeatingSession()` (javap), and `Meters` sets its keys the same
 * way, so the crop is one more key in the same repeating request: no session or encoder restart. Asked only of a camera
 * open now (the adapter's gate), as a setter against a closed device opens it.
 */
internal object Pans {
  /** 1x to the camera's own digital zoom limit; a camera that lists none stays at 1x. */
  fun range(characteristics: CameraCharacteristics): ZoomRange =
    ZoomRange(1f, characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)

  suspend fun set(camera: ICameraSource, ratio: Float, centre: PanCentre, frameDegrees: Int) {
    val settings = camera.settings
    val characteristics = settings.characteristics
    val active = checkNotNull(characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)) { "no active array" }
    val frame = Pan.frame(centre, ratio, frameDegrees, Meters.sensor(characteristics))
    settings.set(CaptureRequest.CONTROL_ZOOM_RATIO, frame.ratio)
    val crop = frame.crop?.let { Rect(active.left + it.left, active.top + it.top, active.left + it.left + it.width, active.top + it.top + it.height) } ?: active
    settings.set(CaptureRequest.SCALER_CROP_REGION, crop)
    settings.applyRepeatingSession()
  }
}
