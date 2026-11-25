package com.seazn.capture.engine.adapter

/**
 * Spec 2026-11-25-encoder-diagnostics §3 (review of #72, Important 1): the endpoint shows a frame to the diagnostics
 * tap and then writes it on, whatever the tap did. [write] runs once, after [tap], even when [tap] throws an `Error`
 * (an out-of-memory copy); what [tap] threw is thrown again only after the frame is written, so a failure is never
 * hidden and the network never misses the frame for it.
 */
inline fun <T> tapThenWrite(tap: () -> Unit, write: () -> T): T {
  val thrown =
    try {
      tap()
      null
    } catch (t: Throwable) {
      t
    }
  val written = write()
  if (thrown != null) throw thrown
  return written
}

/**
 * Review of #72, Minor 2: one diagnostic's guard. The first throw of [run]'s block, `Error`s included, is handed back
 * once to be said, and the guard then skips its block for good; another guard goes on. So the dump failing never
 * silences the format line, and the reverse.
 */
class QuietAfterThrow {
  @Volatile
  var quiet = false
    private set

  /** Runs [block] unless quiet; returns what it threw the first time, else null. */
  inline fun run(block: () -> Unit): Throwable? {
    if (quiet) return null
    return try {
      block()
      null
    } catch (t: Throwable) {
      silence()
      t
    }
  }

  fun silence() {
    quiet = true
  }
}
