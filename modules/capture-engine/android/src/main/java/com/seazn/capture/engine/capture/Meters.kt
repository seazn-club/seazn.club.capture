package com.seazn.capture.engine.capture

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import com.seazn.capture.engine.core.MeterArea
import com.seazn.capture.engine.core.MeterPoint
import com.seazn.capture.engine.core.Metering
import com.seazn.capture.engine.core.PanCentre
import com.seazn.capture.engine.core.SensorFacts
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource

/**
 * Spec 2026-11-25-zoom-and-metering §4: focus and exposure metered at [MeterPoint] on [camera], or the camera's own
 * metering (a region of no weight, [Metering.NONE]). Only the regions: the focus mode StreamPack 3.2.1 opens with,
 * `CONTROL_AF_MODE_CONTINUOUS_VIDEO` where listed (javap: `CameraSource.defaultCaptureRequest`), stays, and so does the
 * exposure mode, so a tap never locks either and never sweeps the lens on air. A region is set only where the camera
 * meters by region at all (`CONTROL_MAX_REGIONS_AF` and `_AE`). StreamPack's `set` only sets the key, so the repeating
 * request is applied after, once. Asked only of a camera open now: against a closed device the calls open it.
 */
internal object Meters {
  suspend fun set(camera: ICameraSource, point: MeterPoint?, frameDegrees: Int, ratio: Float, centre: PanCentre) {
    val settings = camera.settings
    val sensor = sensor(settings.characteristics)
    val focus = point?.let { Metering.area(it, frameDegrees, sensor, Metering.FOCUS_SIZE, ratio, centre) } ?: Metering.NONE
    val exposure = point?.let { Metering.area(it, frameDegrees, sensor, Metering.EXPOSURE_SIZE, ratio, centre) } ?: Metering.NONE
    val focuses = settings.focus.maxNumOfMeteringRegions > 0
    val exposes = settings.exposure.maxNumOfMeteringRegions > 0
    if (focuses) settings.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rectangle(focus)))
    if (exposes) settings.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(rectangle(exposure)))
    if (focuses || exposes) settings.applyRepeatingSession()
  }

  fun sensor(characteristics: CameraCharacteristics): SensorFacts {
    val active = checkNotNull(characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)) { "no active array" }
    return SensorFacts(
      orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
      front = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT,
      width = active.width(),
      height = active.height(),
    )
  }

  private fun rectangle(area: MeterArea) = MeteringRectangle(area.left, area.top, area.width, area.height, area.weight)
}
