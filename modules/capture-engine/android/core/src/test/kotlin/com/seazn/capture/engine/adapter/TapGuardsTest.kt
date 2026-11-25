package com.seazn.capture.engine.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Review of #72: the frame is always written on, and each diagnostic goes quiet on its own throw only. */
class TapGuardsTest {
  @Test
  fun `a tap that throws an Error still lets the frame be written, once, and the Error is thrown after`() {
    val writes = mutableListOf<String>()
    val oom = OutOfMemoryError("dump copy")
    val thrown = assertFailsWith<OutOfMemoryError> { tapThenWrite({ throw oom }, { writes += "frame" }) }
    assertSame(oom, thrown)
    assertEquals(listOf("frame"), writes)
  }

  @Test
  fun `a quiet tap writes once and hands back the write's answer, the tap first`() {
    val order = mutableListOf<String>()
    assertEquals(7, tapThenWrite({ order += "tap" }, { order += "write"; 7 }))
    assertEquals(listOf("tap", "write"), order)
  }

  @Test
  fun `a write that throws is the write's failure, as before the tap`() {
    assertFailsWith<IllegalStateException> { tapThenWrite({}, { error("closed") }) }
  }

  @Test
  fun `a guard hands its first throw back once, Errors included, then skips its block`() {
    val guard = QuietAfterThrow()
    var runs = 0
    val error = StackOverflowError()
    assertSame(error, guard.run { runs += 1; throw error })
    assertTrue(guard.quiet)
    assertNull(guard.run { runs += 1 })
    assertEquals(1, runs, "the block is never run again")
  }

  @Test
  fun `one guard's throw leaves another going - the dump's failure never silences the format line`() {
    val format = QuietAfterThrow()
    val dump = QuietAfterThrow()
    var formatRuns = 0
    dump.run { throw IllegalStateException("disk") }
    format.run { formatRuns += 1 }
    format.run { formatRuns += 1 }
    assertFalse(format.quiet)
    assertEquals(2, formatRuns)
    assertNull(format.run {}, "a block that does not throw hands back nothing")
  }
}
