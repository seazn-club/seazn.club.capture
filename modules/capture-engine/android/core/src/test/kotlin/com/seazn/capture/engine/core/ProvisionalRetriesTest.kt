package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The flap bound on a provisional connection's immediate retries (owner ruling, 2026-11-26, option B, item 4): at most
 * [ProvisionalRetries.MAX_IMMEDIATE] in a [ProvisionalRetries.WINDOW_MS] window, each at least
 * [ProvisionalRetries.MIN_SPACING_MS] after the one before; past the cap, null, and the caller backs off as today.
 */
class ProvisionalRetriesTest {
  @Test
  fun `the numbers - three in thirty seconds, a second apart`() {
    assertEquals(3, ProvisionalRetries.MAX_IMMEDIATE)
    assertEquals(30_000L, ProvisionalRetries.WINDOW_MS)
    assertEquals(1_000L, ProvisionalRetries.MIN_SPACING_MS)
  }

  @Test
  fun `the first break retries at once`() {
    val (next, waitMs) = ProvisionalRetries().next(10_000)!!
    assertEquals(0L, waitMs)
    assertEquals(1, next.count)
  }

  @Test
  fun `a break under a second after the last retry waits out the spacing`() {
    val first = ProvisionalRetries().next(10_000)!!.first
    val (_, waitMs) = first.next(10_300)!!
    assertEquals(700L, waitMs)
  }

  @Test
  fun `a break a second or more after the last retry goes at once`() {
    val first = ProvisionalRetries().next(10_000)!!.first
    assertEquals(0L, first.next(11_000)!!.second)
    assertEquals(0L, first.next(15_700)!!.second)
  }

  @Test
  fun `the fourth inside the window is past the cap`() {
    var retries = ProvisionalRetries()
    repeat(3) { retries = retries.next(10_000L + it * 2_000)!!.first }
    assertNull(retries.next(16_000))
    assertNull(retries.next(39_999), "still inside the window that opened at 10 s")
  }

  @Test
  fun `a new window opens once the last has run out`() {
    var retries = ProvisionalRetries()
    repeat(3) { retries = retries.next(10_000L + it * 2_000)!!.first }
    val (next, waitMs) = retries.next(40_000)!!
    assertEquals(0L, waitMs)
    assertEquals(1, next.count)
  }

  @Test
  fun `a storm of breaks costs at most three immediate retries a window`() {
    var retries = ProvisionalRetries()
    var immediate = 0
    for (at in 0L until 30_000L step 100) {
      val step = retries.next(at) ?: continue
      retries = step.first
      immediate++
    }
    assertEquals(3, immediate)
  }
}
