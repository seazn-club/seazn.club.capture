package com.seazn.capture.engine.core

/**
 * N52 (owner ruling, 2026-11-26, option B): a connection made while the default network is not validated is provisional
 * ([Phase.OnAir.provisional]), and its break is retried at once, counting toward nothing. This is the flap bound on those
 * immediate retries (item 4): at most [MAX_IMMEDIATE] in a [WINDOW_MS] window, which opens at the first of them, each
 * asked at least [MIN_SPACING_MS] after the one before. Past the cap [next] answers null and the break is today's: the
 * retry wait (2 s on air, the never-live ladder before) and every budget as before. So a socket that breaks as soon as
 * it connects, on a network that never validates, costs at most three quick retries every 30 s, never a storm.
 *
 * Why these numbers: R-SAV6 broke twice in 6 s on one network that took about 21 s to validate, so three in 30 s covers
 * that outage with one to spare, and a second's spacing is more than SRT's connect took there (about 0.9 s). Kept per
 * session, never stored: a new process starts a new session, and with it an empty bound.
 */
data class ProvisionalRetries(
  /** When the window opened: the first immediate retry in it. Null before any. */
  val windowStartMs: Long? = null,
  /** Immediate retries in the window. */
  val count: Int = 0,
  /** When the last immediate retry was asked for. Null before any. */
  val lastAtMs: Long? = null,
) {
  /** A provisional connection broke at [nowMs]: the bound after its retry and the wait before it, or null past the cap. */
  fun next(nowMs: Long): Pair<ProvisionalRetries, Long>? {
    val start = windowStartMs
    val window = if (start == null || nowMs - start >= WINDOW_MS) ProvisionalRetries(nowMs, 0, lastAtMs) else this
    if (window.count >= MAX_IMMEDIATE) return null
    val atMs = lastAtMs?.let { maxOf(nowMs, it + MIN_SPACING_MS) } ?: nowMs
    return window.copy(count = window.count + 1, lastAtMs = atMs) to atMs - nowMs
  }

  companion object {
    const val MAX_IMMEDIATE = 3
    const val WINDOW_MS = 30_000L
    const val MIN_SPACING_MS = 1_000L
  }
}
