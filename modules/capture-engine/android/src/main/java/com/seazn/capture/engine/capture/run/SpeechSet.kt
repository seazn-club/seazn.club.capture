package com.seazn.capture.engine.capture.run

import com.seazn.capture.engine.core.SpeechState

/**
 * Spec 2026-11-24-capture-tuning §3.1: the speech effects held on one recording, with no Android type in it so the core
 * tests run it. [makers] build each effect for a session (null: the phone has none); [enable] turns one on; [free]
 * releases one. The glue's `SpeechEffects` gives it `AcousticEchoCanceler` and `NoiseSuppressor`.
 *
 * Not thread-safe: called only on the run's one consumer.
 */
class SpeechSet<E : Any>(
  private val makers: List<(Int) -> E?>,
  private val enable: (E) -> Unit,
  private val free: (E) -> Unit,
) {
  private var session: Int? = null
  private val effects = mutableListOf<E>()

  /** On or off for the recording [sessionId]; a new session drops the old one's effects first. */
  fun set(sessionId: Int, on: Boolean): SpeechState {
    if (session != sessionId || !on) release()
    if (!on) return SpeechState.OFF
    if (session != sessionId) effects += built(sessionId)
    session = sessionId
    effects.forEach(enable)
    return if (effects.isEmpty()) SpeechState.UNAVAILABLE else SpeechState.ON
  }

  /** Releases every effect held; the recording runs on with none. */
  fun release() {
    effects.forEach { runCatching { free(it) } }
    effects.clear()
    session = null
  }

  /**
   * Review minor 6: every effect, or a throw with none held. One built before another threw is released, so a
   * half-built set never stands as ON and the next call builds them all afresh.
   */
  private fun built(sessionId: Int): List<E> {
    val made = mutableListOf<E>()
    try {
      for (make in makers) make(sessionId)?.let(made::add)
    } catch (e: Exception) {
      made.forEach { runCatching { free(it) } }
      throw e
    }
    return made
  }
}
