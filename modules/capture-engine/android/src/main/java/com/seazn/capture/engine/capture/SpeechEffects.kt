package com.seazn.capture.engine.capture

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import com.seazn.capture.engine.core.SpeechState

/**
 * Spec 2026-11-24-capture-tuning §3.1: speech processing (echo cancelling and noise suppression) on the broadcast's
 * own recording, ours to turn on and off, since the microphone is built with no effects of StreamPack's.
 *
 * Why not StreamPack's own: 3.2.1's `MicrophoneSourceFactory.isSourceEquals` compares the audio source only, so a
 * factory with other effects is skipped (`AudioInput.setSource`: "Audio source is already set"); and an effect added
 * to its processor is enabled only in `startStream`, so on air it would wait for the next start. These effects are
 * created on the recording's session and enabled at once, with no stop or start and no new recording, so the meter's
 * session (MicWatch) is unchanged.
 *
 * Not thread-safe: called only on the run's one consumer.
 */
internal class SpeechEffects {
  private var session: Int? = null
  private val effects = mutableListOf<AudioEffect>()

  /** On or off for the recording [sessionId]; a new session drops the old one's effects first. */
  fun set(sessionId: Int, on: Boolean): SpeechState {
    if (session != sessionId || !on) release()
    if (!on) return SpeechState.OFF
    session = sessionId
    if (effects.isEmpty()) {
      if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.let(effects::add)
      if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId)?.let(effects::add)
    }
    effects.forEach { it.setEnabled(true) }
    return if (effects.isEmpty()) SpeechState.UNAVAILABLE else SpeechState.ON
  }

  /** Releases every effect held; the recording runs on with none. */
  fun release() {
    effects.forEach { runCatching { it.release() } }
    effects.clear()
    session = null
  }
}
