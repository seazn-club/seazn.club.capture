package com.seazn.capture.engine.core

/**
 * N52 (owner ruling, 2026-11-26, option B): a connection made while the default network is not validated is provisional
 * ([Phase.OnAir.provisional]), and its break is retried at once, counting toward nothing. This is the flap bound on those
 * immediate retries (item 4): at most [MAX_IMMEDIATE] in any [WINDOW_MS] span, a sliding window over the last retries'
 * times (review of #79: a fixed window let six fall across its edge), each asked at least [MIN_SPACING_MS] after the one
 * before. Past the cap [next] answers null and the break is today's: the retry wait (2 s on air, the never-live ladder
 * before) and every budget as before. So a socket that breaks as soon as it connects, on a network that never validates,
 * costs at most three quick retries in any 30 s, never a storm.
 *
 * Why these numbers: R-SAV6 broke twice in 6 s on one network that took about 21 s to validate, so three in 30 s covers
 * that outage with one to spare, and a second's spacing is more than SRT's connect took there (about 0.9 s). Kept per
 * session, never stored: a new process starts a new session, and with it an empty bound.
 */
data class ProvisionalRetries(
  /** When the last immediate retries were asked for, oldest first, at most [MAX_IMMEDIATE]. */
  val recentAtMs: List<Long> = emptyList(),
) {
  /** A provisional connection broke at [nowMs]: the bound after its retry and the wait before it, or null past the cap. */
  fun next(nowMs: Long): Pair<ProvisionalRetries, Long>? {
    val inWindow = recentAtMs.filter { nowMs - it < WINDOW_MS }
    if (inWindow.size >= MAX_IMMEDIATE) return null
    val atMs = recentAtMs.lastOrNull()?.let { maxOf(nowMs, it + MIN_SPACING_MS) } ?: nowMs
    return ProvisionalRetries((inWindow + atMs).takeLast(MAX_IMMEDIATE)) to atMs - nowMs
  }

  companion object {
    const val MAX_IMMEDIATE = 3
    const val WINDOW_MS = 30_000L
    const val MIN_SPACING_MS = 1_000L
  }
}
