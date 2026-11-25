package com.seazn.capture.engine.capture

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import com.seazn.capture.engine.capture.run.SpeechSet

/**
 * Spec 2026-11-24-capture-tuning §3.1: speech processing (echo cancelling and noise suppression) on the broadcast's
 * own recording, ours to turn on and off, since the microphone is built with no effects of StreamPack's. The rule is
 * [SpeechSet]'s; this gives it the platform's two effects.
 *
 * Why not StreamPack's own: 3.2.1's `MicrophoneSourceFactory.isSourceEquals` compares the audio source only, so a
 * factory with other effects is skipped (`AudioInput.setSource`: "Audio source is already set"); and an effect added
 * to its processor is enabled only in `startStream`, so on air it would wait for the next start. These effects are
 * created on the recording's session and enabled at once, with no stop or start and no new recording, so the meter's
 * session (MicWatch) is unchanged.
 */
internal object SpeechEffects {
  fun create(): SpeechSet<AudioEffect> =
    SpeechSet<AudioEffect>(
      makers =
        listOf(
          { session: Int -> if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session) else null },
          { session: Int -> if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session) else null },
        ),
      enable = { it.setEnabled(true) },
      free = { it.release() },
    )
}
