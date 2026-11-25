package com.seazn.capture.engine.capture.run

import com.seazn.capture.engine.core.SpeechState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** [SpeechSet]: the speech effects on one recording (spec 2026-11-24-capture-tuning §3.1; review minor 6). */
class SpeechSetTest {
  private class Effect(val name: String, val session: Int) {
    var enabled = false
    var freed = false
  }

  private val made = mutableListOf<Effect>()
  private var nsThrows = false
  private var nsMissing = false

  private val set =
    SpeechSet<Effect>(
      makers =
        listOf(
          { s -> Effect("aec", s).also(made::add) },
          { s ->
            if (nsThrows) throw IllegalStateException("no ns")
            if (nsMissing) null else Effect("ns", s).also(made::add)
          },
        ),
      enable = { it.enabled = true },
      free = { it.freed = true },
    )

  @Test
  fun `on builds both on the session and enables them, and a second call builds nothing more`() {
    assertEquals(SpeechState.ON, set.set(7, on = true))
    assertEquals(SpeechState.ON, set.set(7, on = true))
    assertEquals(listOf("aec", "ns"), made.map { it.name })
    assertEquals(listOf(7, 7), made.map { it.session })
    assertEquals(listOf(true, true), made.map { it.enabled })
  }

  @Test
  fun `off releases them, and on again builds afresh`() {
    set.set(7, on = true)
    assertEquals(SpeechState.OFF, set.set(7, on = false))
    assertEquals(listOf(true, true), made.map { it.freed })
    set.set(7, on = true)
    assertEquals(4, made.size)
  }

  @Test
  fun `a new session drops the old effects and builds on the new one`() {
    set.set(7, on = true)
    set.set(9, on = true)
    assertEquals(listOf(true, true, false, false), made.map { it.freed })
    assertEquals(listOf(9, 9), made.drop(2).map { it.session })
  }

  @Test
  fun `a phone with neither says unavailable, and with one says on`() {
    nsMissing = true
    assertEquals(SpeechState.ON, set.set(7, on = true))
    val none = SpeechSet<Effect>(listOf({ _ -> null }), enable = {}, free = {})
    assertEquals(SpeechState.UNAVAILABLE, none.set(7, on = true))
  }

  @Test
  fun `a throw after the first effect frees it, holds none, and the next call builds both afresh`() {
    nsThrows = true
    assertFailsWith<IllegalStateException> { set.set(7, on = true) }
    assertEquals(listOf(true), made.map { it.freed }, "the echo canceller built first is released")
    nsThrows = false
    assertEquals(SpeechState.ON, set.set(7, on = true))
    assertEquals(listOf("aec", "aec", "ns"), made.map { it.name })
    assertEquals(listOf(false, true, true), made.drop(0).map { it.enabled })
  }
}
