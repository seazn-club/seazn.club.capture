package com.seazn.capture.engine.adapter

import com.seazn.capture.engine.adapter.DumpBudget.Take
import com.seazn.capture.engine.adapter.EncoderDumpRules.End
import com.seazn.capture.engine.core.BitrateMode
import com.seazn.capture.engine.core.RecordEntry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec 2026-11-25-encoder-diagnostics §3: the dev twin's dump, its gate, its files and its bounds. */
class EncoderDumpRulesTest {
  private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

  @Test
  fun `only the dev twin's package may dump, and the production app and any other id may not`() {
    assertTrue(EncoderDumpRules.allowed("com.seazn.capture.dev"))
    assertFalse(EncoderDumpRules.allowed("com.seazn.capture"), "production (AGENTS §11)")
    assertFalse(EncoderDumpRules.allowed("com.seazn.capture.dev2"))
    assertFalse(EncoderDumpRules.allowed("com.seazn.capture.dev.x"))
    assertFalse(EncoderDumpRules.allowed(""))
    assertFalse(EncoderDumpRules.allowed(null))
  }

  @Test
  fun `the first dump is number 1, named by its mode`() {
    assertEquals(EncoderDumpRules.Files("encoder-dump-cbr-1.h264", emptyList()), EncoderDumpRules.files(emptyList(), BitrateMode.CBR))
    assertEquals("encoder-dump-vbr-1.h264", EncoderDumpRules.files(listOf("record.jsonl", "encoder-dump-cbr-x.h264"), BitrateMode.VBR).name)
    assertEquals("encoder-dump-unsupported-1.h264", EncoderDumpRules.files(emptyList(), BitrateMode.UNSUPPORTED).name)
  }

  @Test
  fun `a later dump is numbered past every dump on disk, whatever its mode, and keeps four`() {
    val existing = listOf("encoder-dump-vbr-2.h264", "encoder-dump-cbr-10.h264", "encoder-dump-cbr-3.h264", "encoder-dump-vbr-9.h264", "phone-id")
    val files = EncoderDumpRules.files(existing, BitrateMode.VBR)
    assertEquals("encoder-dump-vbr-11.h264", files.name, "numbers compare as numbers: 10 is past 9")
    assertEquals(listOf("encoder-dump-vbr-2.h264"), files.delete, "the oldest goes, so three old and the new one stay")
    assertEquals(emptyList(), EncoderDumpRules.files(existing.drop(1), BitrateMode.VBR).delete, "three old and the new one: none goes")
  }

  @Test
  fun `a key frame is preceded by its parameter sets after start codes, and any other frame is its bytes alone`() {
    val sps = hex("6742c01e")
    val pps = hex("00000001" + "68ca83cb20")
    val frame = hex("0000000165888400")
    assertContentEquals(hex("000000016742c01e" + "0000000168ca83cb20" + "0000000165888400"), EncoderDumpRules.chunk(true, listOf(sps, pps), frame))
    assertContentEquals(hex("000001" + "68"), EncoderDumpRules.chunk(true, listOf(hex("00000168")), ByteArray(0)), "a three-byte start code is kept as it is")
    assertContentEquals(frame, EncoderDumpRules.chunk(false, listOf(sps, pps), frame))
  }

  @Test
  fun `the chunk's size is known before it is built`() {
    val bare = hex("6742c01e")
    val coded = hex("0000000168ca83cb20")
    for (key in listOf(true, false)) {
      for (csd in listOf(emptyList(), listOf(bare), listOf(bare, coded), listOf(hex("00000168")))) {
        assertEquals(EncoderDumpRules.chunk(key, csd, ByteArray(17)).size, EncoderDumpRules.chunkSize(key, csd, 17), "key $key, ${csd.size} sets")
      }
    }
    assertEquals(4 + 4 + 9 + 17, EncoderDumpRules.chunkSize(true, listOf(bare, coded), 17))
  }

  @Test
  fun `one dump a broadcast - a connect that took no frame leaves the next attempt to dump`() {
    val arming = DumpArming()
    assertTrue(arming.attempt(), "the first attempt of the first broadcast")
    arming.stopped(took = false)
    assertTrue(arming.attempt(), "a failed connect wrote nothing, so the next attempt dumps")
    arming.stopped(took = true)
    assertFalse(arming.attempt(), "a reconnect in the same broadcast")
    assertFalse(arming.attempt(), "asked twice")
    arming.stopped(took = false)
    assertFalse(arming.attempt(), "a stop with nothing taken does not re-arm")
    arming.broadcastOver()
    assertTrue(arming.attempt(), "the next broadcast")
  }

  @Test
  fun `an attempt cut before its first frame says nothing, every other end is said`() {
    assertFalse(EncoderDumpRules.said(End.CUT, 0))
    assertTrue(EncoderDumpRules.said(End.CUT, 1))
    for (end in End.entries - End.CUT) assertTrue(EncoderDumpRules.said(end, 0), end.wire)
  }

  @Test
  fun `a dump waits for a key frame, then takes every frame for ten seconds`() {
    val budget = DumpBudget()
    assertEquals(Take.Wait, budget.frame(0, key = false, size = 10))
    assertEquals(Take.Copy, budget.frame(33_000, key = true, size = 100))
    assertEquals(Take.Copy, budget.frame(66_000, key = false, size = 10))
    assertEquals(Take.Copy, budget.frame(10_032_999, key = false, size = 10), "just inside ten seconds of the key frame")
    assertEquals(Take.Ended(End.WRITTEN), budget.frame(10_033_000, key = false, size = 10))
    assertEquals(Take.Done, budget.frame(10_066_000, key = true, size = 10))
    assertEquals(3, budget.frames)
    assertEquals(120, budget.bytes)
    assertEquals(9_999, budget.spanMs)
  }

  @Test
  fun `a dump ends full at its byte cap, never past it`() {
    val budget = DumpBudget(maxBytes = 100, maxQueuedBytes = 1_000)
    assertEquals(Take.Copy, budget.frame(0, key = true, size = 60))
    assertEquals(Take.Copy, budget.frame(1, key = false, size = 40), "exactly at the cap")
    assertEquals(Take.Ended(End.FULL), budget.frame(2, key = false, size = 1))
    assertEquals(100, budget.bytes)
  }

  @Test
  fun `a writer behind by the queue bound ends the dump as an overflow, once, and written bytes free the queue`() {
    val budget = DumpBudget(maxBytes = 1_000, maxQueuedBytes = 100)
    assertEquals(Take.Copy, budget.frame(0, key = true, size = 70))
    budget.written(70)
    assertEquals(0, budget.queued)
    assertEquals(Take.Copy, budget.frame(1, key = false, size = 70), "the writer caught up, so it fits again")
    assertEquals(Take.Copy, budget.frame(2, key = false, size = 30), "exactly at the bound")
    assertEquals(Take.Ended(End.OVERFLOW), budget.frame(3, key = false, size = 1))
    assertEquals(Take.Done, budget.frame(4, key = false, size = 1), "said once")
    assertTrue(budget.done)
  }

  @Test
  fun `an end is said once, by whichever comes first`() {
    val budget = DumpBudget()
    assertEquals(Take.Ended(End.CUT), budget.end(End.CUT))
    assertNull(budget.end(End.FAILED))
    assertEquals(Take.Done, budget.frame(0, key = true, size = 1))
  }

  @Test
  fun `the end line carries closed words and numbers only`() {
    assertEquals(
      RecordEntry("encoder-dump", listOf("state" to "overflow", "mode" to "cbr", "count" to 12, "bytes" to 4096L, "ms" to 396L)),
      EncoderDumpRules.entry(End.OVERFLOW, BitrateMode.CBR, 12, 4096L, 396L),
    )
    assertEquals(
      listOf("state" to "failed", "mode" to "vbr", "count" to 0, "bytes" to 0L, "ms" to 0L, "error" to "IOException"),
      EncoderDumpRules.entry(End.FAILED, BitrateMode.VBR, 0, 0L, 0L, "IOException").fields,
    )
  }
}
