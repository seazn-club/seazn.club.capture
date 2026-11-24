package com.seazn.capture.engine.core

/** A stuck verdict: [readings] stuck in a row, over [stuckMs] from the first of them. */
data class Stuck(val readings: Int, val stuckMs: Long)

/**
 * The cause split (owner, 2026-11-24): the phone's own link stuck is the phone's to fix at once, before the delivery
 * watch's 20 s verdict ([DeliveryWatch.NOT_DELIVERED_MS]), which stays as the safety net. One attempt's run of stuck
 * readings, from the link samples the regulator reads, about once a second ([Input.Link]).
 *
 * A reading is stuck when the target is already at the floor, so the regulator has nothing left to cut, and SRT's send
 * buffer still holds [STUCK_BUFFER_PERCENT] of the latency: the link does not carry even the floor. Stuck for
 * [STUCK_READINGS] readings in a row over at least [STUCK_MS] is the verdict, and the run starts again after it.
 * A reading that drains, or has no usable buffer, ends the run. RTMPS reports no buffer, so it is never stuck by this rule.
 *
 * The numbers, DEVICE-UNVERIFIED (R-RTRF, N49, at Cloudflare's SRT latency of 2000 ms):
 * - The 4G death: buffer 1131 ms at 1354k, 2049 at 677k, 1902 at the floor (09:55:12), and the playlist verdict only at
 *   09:55:30. Held at the floor from 09:55:12, the rule says stuck at 09:55:17. The record has no reading after
 *   09:55:12, so that the buffer stayed high is INFERRED.
 * - The 2.4 GHz Wi-Fi run (2026-11-24): transient backlogs of 1020 ms (lost 28) and 627 (dropped 174), then stable at
 *   the floor. Both are under 1500, so the rule never fires on it.
 */
data class StuckWatch(
  /** When the run of stuck readings began; null while the last reading was not stuck. */
  val sinceMs: Long? = null,
  val readings: Int = 0,
) {
  /** One reading, judged after the regulator: [targetBps] is the target it left. */
  fun read(sample: LinkSample, targetBps: Int, transport: Transport, srtLatencyMs: Int, nowMs: Long): Pair<StuckWatch, Stuck?> {
    if (!stuck(sample, targetBps, transport, srtLatencyMs)) return StuckWatch() to null
    val since = sinceMs ?: nowMs
    val count = readings + 1
    if (count < STUCK_READINGS || nowMs - since < STUCK_MS) return StuckWatch(since, count) to null
    return StuckWatch() to Stuck(count, nowMs - since)
  }

  private fun stuck(sample: LinkSample, targetBps: Int, transport: Transport, srtLatencyMs: Int): Boolean {
    if (transport != Transport.SRT || targetBps > Encode.FLOOR_BPS) return false
    // A negative reading (the record has -336) is never 75% of the latency, so it ends the run like any drained one.
    val buffer = sample.sendBufferMs ?: return false
    return buffer.toLong() * 100 >= srtLatencyMs.toLong() * STUCK_BUFFER_PERCENT
  }

  companion object {
    /** 1500 ms at 2000: over the Wi-Fi run's worst transient (1020), under the 4G death's floor reading (1902). */
    const val STUCK_BUFFER_PERCENT = 75

    /** Five readings in a row, so one late or bunched reading is never a verdict. */
    const val STUCK_READINGS = 5

    /** And at least 5 s of them: the 4G death is said 13 s before its playlist verdict. */
    const val STUCK_MS = 5_000L
  }
}
