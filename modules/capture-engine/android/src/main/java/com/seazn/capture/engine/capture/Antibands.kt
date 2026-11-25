package com.seazn.capture.engine.capture

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import com.seazn.capture.engine.core.Antibanding
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spec 2026-11-24-capture-tuning §3.3: antibanding AUTO on [camera], where its characteristics list it, then what the
 * next capture result says. StreamPack 3.2.1 (javap: `CameraSettings.set` only sets the key, as for OIS) needs the
 * repeating request applied; `applyRepeatingSessionSync` waits for the result, bounded by [RESULT_MS] so a stalled
 * session never holds the run's queue. Asked only after an accepted open: against a closed device it opens it.
 */
internal object Antibands {
  const val RESULT_MS = 1_000L

  suspend fun set(camera: ICameraSource): Antibanding {
    val settings = camera.settings
    val available = settings.characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)
    if (!Antibanding.autoListed(available)) return Antibanding.UNAVAILABLE
    settings.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, Antibanding.MODE_AUTO)
    val result = withTimeoutOrNull(RESULT_MS) { settings.applyRepeatingSessionSync() }
    return Antibanding.of(result?.get(CaptureResult.CONTROL_AE_ANTIBANDING_MODE))
  }
}
