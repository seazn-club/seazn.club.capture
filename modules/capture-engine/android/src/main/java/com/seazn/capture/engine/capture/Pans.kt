package com.seazn.capture.engine.capture

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import com.seazn.capture.engine.core.Pan
import com.seazn.capture.engine.core.PanCentre
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource

/**
 * Spec 2026-11-25-zoom-and-metering §3.5 (owner, 2026-11-26): the zoomed crop moved off centre. StreamPack 3.2.1's
 * `RZoom.setZoomRatio` is `settings.set(CONTROL_ZOOM_RATIO, ..)` then `applyRepeatingSession()` (javap), and `Meters`
 * sets its keys the same way, so a crop region goes as one more key in the same repeating request: no session restart,
 * no encoder restart, and the picture never takes a frame with the ratio and not the crop. Asked only of a camera open
 * now (the adapter's gate), as a setter against a closed device opens it.
 */
internal object Pans {
  suspend fun set(camera: ICameraSource, ratio: Float, centre: PanCentre, frameDegrees: Int) {
    val settings = camera.settings
    val characteristics = settings.characteristics
    val active = checkNotNull(characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)) { "no active array" }
    val frame = Pan.frame(centre, ratio, frameDegrees, Meters.sensor(characteristics))
    val range = settings.zoom.availableRatioRange
    settings.set(CaptureRequest.CONTROL_ZOOM_RATIO, frame.ratio.coerceIn(range.lower, range.upper))
    val crop = frame.crop?.let { Rect(active.left + it.left, active.top + it.top, active.left + it.left + it.width, active.top + it.top + it.height) } ?: active
    settings.set(CaptureRequest.SCALER_CROP_REGION, crop)
    settings.applyRepeatingSession()
  }
}
