package com.seazn.capture.engine.adapter

import com.seazn.capture.engine.core.BitrateMode
import com.seazn.capture.engine.core.RecordEntry
import com.seazn.capture.engine.core.SessionRecord
import com.seazn.capture.engine.core.Shared
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spec 2026-11-25-encoder-diagnostics §4: the record lines as SessionRecord writes them are the shared file's, character
 * for character. JS validates the same file against the web's vendored record contract, so a key the contract does
 * not list (which the web drops the whole line for) reds there.
 */
class EncoderLinesVectorsTest {
  private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

  private val highCabac = listOf(hex("0000000167640029acd9405005bb0110000003001000000303c0f1831960"), hex("0000000168ebe3cb22c0"))

  private val entries =
    listOf(
      EncoderFormat.entry(BitrateMode.CBR, "c2.qti.avc.encoder", highCabac),
      EncoderFormat.entry(BitrateMode.VBR, null, emptyList()),
      EncoderDumpRules.entry(EncoderDumpRules.End.WRITTEN, BitrateMode.CBR, 300, 3_801_234L, 9_966L),
      EncoderDumpRules.entry(EncoderDumpRules.End.FAILED, BitrateMode.VBR, 0, 0L, 0L, "IOException"),
    )

  private fun written(entry: RecordEntry): String {
    val lines = mutableListOf<String>()
    SessionRecord { lines += it }.append(AT, entry)
    return lines.single()
  }

  @Test
  fun `each line is written as the shared file says`() {
    val shared = Shared.read("scrub/encoder-lines.json").getJSONArray("lines")
    assertEquals(entries.size, shared.length(), "one case a line")
    for (i in entries.indices) assertEquals(shared.getJSONObject(i).getString("line"), written(entries[i]), shared.getJSONObject(i).getString("why"))
  }

  private companion object {
    const val AT = 1_795_608_000_000L
  }
}
