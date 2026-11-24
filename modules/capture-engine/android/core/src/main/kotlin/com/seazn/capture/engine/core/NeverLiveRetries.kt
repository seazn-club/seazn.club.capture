package com.seazn.capture.engine.core

/**
 * S6: before a broadcast's first frame there is no outage, so no hold bounds its retries, and a failed connect was tried
 * again every [SessionMachine.RETRY_MS] until the server ended the session, with a `connect-failed` and a `connecting`
 * line each time (N37 measured 73 start failures in 183 s): thousands of lines an hour toward a record that rotates, and
 * a radio that never rests.
 *
 * For the first [FAST_MS] of failing it is as before: a retry every 2 s, C1's three failures to the fallback at the same
 * pace, and every line said. After it the retry waits [waitMs], 4 s doubling to [MAX_RETRY_MS], and the failures are said
 * at most once a [LINE_MS], each such line with how many it stands for (`count`), as `bitrate-held` is. A retry whose
 * failure was held back is asked with no `connecting` line ([quiet]). After live nothing changes: the hold bounds it.
 *
 * The server's warming deadline (`warmingDeadline`) is not read: the server ends a warming session itself and says so on
 * a beat, the one authority (AGENTS §2), and a bounded retry costs the phone little until it does.
 */
data class NeverLiveRetries(
  /** When the first attempt before live failed; null before one has. */
  val firstAtMs: Long? = null,
  /** Failures since [FAST_MS] ran out: the ladder's step. */
  val slow: Int = 0,
  /** When the last `connect-failed` line was said after [FAST_MS]; null before the first. */
  val saidAtMs: Long? = null,
  /** Failures held back since that line. */
  val unsaid: Int = 0,
  /** The next connect's `connecting` line is held back with the failure before it. */
  val quiet: Boolean = false,
) {
  /** Whether a `connect-failed` line is said, and the count it carries (null: none, as every line before [FAST_MS]). */
  data class Said(val count: Int?)

  /** One more failure before live: the next state, and its line, or null when the line is held back. */
  fun failed(nowMs: Long): Pair<NeverLiveRetries, Said?> {
    val first = firstAtMs ?: nowMs
    if (nowMs - first < FAST_MS) return copy(firstAtMs = first) to Said(null)
    val said = saidAtMs
    if (said != null && nowMs - said < LINE_MS) return copy(slow = slow + 1, unsaid = unsaid + 1, quiet = true) to null
    return copy(slow = slow + 1, saidAtMs = nowMs, unsaid = 0, quiet = false) to Said(unsaid + 1)
  }

  /** One more attempt ended before live with its own line (a drop), so only the ladder moves. */
  fun dropped(nowMs: Long): NeverLiveRetries {
    val first = firstAtMs ?: nowMs
    return copy(firstAtMs = first, slow = if (nowMs - first < FAST_MS) slow else slow + 1)
  }

  /** The wait before the next retry: 2 s inside [FAST_MS], then 4, 8, 16, then 30 s. */
  fun waitMs(): Long = if (slow == 0) SessionMachine.RETRY_MS else minOf(SessionMachine.RETRY_MS shl slow.coerceAtMost(MAX_DOUBLINGS), MAX_RETRY_MS)

  companion object {
    /**
     * Review of batch 2, item 4: a session that ends writes the failures no line has said yet, as [FloorCuts.closed]
     * does its cuts: `connect-failed {transport, count}`.
     */
    fun closed(before: Phase, step: Step): Step {
      val session = before.session ?: return step
      val unsaid = session.retries.unsaid
      if (unsaid == 0 || step.phase.session?.sid == session.sid) return step
      return step.copy(commands = step.commands + Command.Record(RecordEntry("connect-failed", listOf("transport" to session.fallback.current, "count" to unsaid))))
    }

    /** A minute of trying as before: a fix at the ground (a hotspot turned on, a cable) is usually that quick. */
    const val FAST_MS = 60_000L

    /** At most one `connect-failed` line a minute after [FAST_MS], as `bitrate-held` and `bitrate-floor` are. */
    const val LINE_MS = 60_000L

    /** Then 4 s doubling to 30 s, N29's ceiling: an hour of a dead uplink is about 120 attempts, not 1800. */
    const val MAX_RETRY_MS = 30_000L

    /** 2 s doubled four times passes 30 s; the cap also keeps the shift from overflowing. */
    private const val MAX_DOUBLINGS = 4
  }
}
