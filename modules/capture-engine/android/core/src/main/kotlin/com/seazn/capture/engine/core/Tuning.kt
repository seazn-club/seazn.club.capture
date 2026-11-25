package com.seazn.capture.engine.core

/**
 * Spec 2026-11-24-capture-tuning §2 (owner, 2026-11-24): the operator's two capture choices, held by the pairing as
 * the automatic switch is. [matchSound] on keeps the ground's sound (crowd, ball, umpire): the microphone runs with no
 * speech processing. Off turns speech processing back on (StreamPack's own default: echo cancelling and noise
 * suppression). [constantBitrate] asks the video encoder for MediaFormat's CBR mode where it lists it; it changes only
 * while no broadcast is held ([Pairings.constantBitrate]).
 */
data class Tuning(val matchSound: Boolean = true, val constantBitrate: Boolean = false) {
  companion object {
    val DEFAULT = Tuning()
  }
}

/** Speech processing on the broadcast's microphone, as the record says it: on, off, or not on this phone. */
enum class SpeechState(val wire: String) {
  ON("on"),
  OFF("off"),
  UNAVAILABLE("unavailable"),
}

/** Spec §3.1: speech processing (AEC and NS) is wanted only with match sound off. */
object SpeechProcessing {
  const val KIND = "speech-processing"

  fun wanted(tuning: Tuning): Boolean = !tuning.matchSound

  fun entry(state: SpeechState): RecordEntry = RecordEntry(KIND, listOf("state" to state.wire))
}

/**
 * Spec §3.2: the mode the video encoder was asked for. [UNSUPPORTED] is CBR asked for on an encoder that does not list
 * it (`EncoderCapabilities.isBitrateModeSupported`), which stays VBR: never a switch to another encoder.
 */
enum class BitrateMode(val wire: String) {
  VBR("vbr"),
  CBR("cbr"),
  UNSUPPORTED("unsupported"),
  ;

  fun entry(): RecordEntry = RecordEntry(KIND, listOf("state" to wire))

  companion object {
    const val KIND = "bitrate-mode"

    /** CBR only when the operator asked and the encoder StreamPack picks lists it; [supported] null: none was found. */
    fun of(constant: Boolean, supported: Boolean?): BitrateMode =
      when {
        !constant -> VBR
        supported == true -> CBR
        else -> UNSUPPORTED
      }
  }
}

/**
 * Spec §3.3: the camera's auto-exposure antibanding (`CONTROL_AE_ANTIBANDING_MODE`), as the capture result said it.
 * [UNAVAILABLE]: the camera does not list AUTO, so nothing is set. [UNREAD]: AUTO was set and no capture result came
 * back within the bound.
 */
enum class Antibanding(val wire: String) {
  OFF("off"),
  HZ50("50hz"),
  HZ60("60hz"),
  AUTO("auto"),
  UNAVAILABLE("unavailable"),
  UNREAD("unread"),
  ;

  fun entry(): RecordEntry = RecordEntry(KIND, listOf("state" to wire))

  companion object {
    const val KIND = "antibanding"

    /** Camera2's `CONTROL_AE_ANTIBANDING_MODE_AUTO`. */
    const val MODE_AUTO = 3

    /** Whether the camera's `CONTROL_AE_AVAILABLE_ANTIBANDING_MODES` lists AUTO. */
    fun autoListed(available: IntArray?): Boolean = available?.contains(MODE_AUTO) == true

    /** Camera2's mode values (OFF 0, 50HZ 1, 60HZ 2, AUTO 3) in words; anything else, or none, is unread. */
    fun of(mode: Int?): Antibanding =
      when (mode) {
        0 -> OFF
        1 -> HZ50
        2 -> HZ60
        MODE_AUTO -> AUTO
        else -> UNREAD
      }
  }
}
