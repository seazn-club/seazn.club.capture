package com.seazn.capture.engine.capture

import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Spec 2026-11-24-capture-tuning §3.2: whether the encoder StreamPack will pick for [format] lists CBR.
 *
 * StreamPack 3.2.1 (`MediaCodecUtils.createCodec`, javap) builds the format, our customize keys included, then takes
 * `MediaCodecList.findEncoderForFormat(format)`. A CBR key added unasked would filter out every encoder without CBR, and
 * the pick could fall to a software encoder. So the pick is made here first, without the key, and CBR is asked for only
 * when that encoder lists it: it then stays the first match. Null when no encoder is found.
 */
internal object EncoderModes {
  fun cbrSupported(format: MediaFormat): Boolean? {
    val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    val name = list.findEncoderForFormat(format) ?: return null
    val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
    val info = list.codecInfos.firstOrNull { it.name == name } ?: return null
    return info.getCapabilitiesForType(mime).encoderCapabilities?.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_CBR)
  }
}
