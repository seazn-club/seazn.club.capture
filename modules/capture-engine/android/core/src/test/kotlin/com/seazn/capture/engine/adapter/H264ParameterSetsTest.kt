package com.seazn.capture.engine.adapter

import com.seazn.capture.engine.core.BitrateMode
import com.seazn.capture.engine.core.RecordEntry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Spec 2026-11-25-encoder-diagnostics §2. The vectors are real parameter sets: libx264's, from ffmpeg 7
 * (`-f lavfi -i testsrc=size=1280x720 -pix_fmt yuv420p -c:v libx264 -profile:v P -level L -coder C -f h264`), read out of
 * the Annex-B file. Each is handed over as MediaCodec hands `csd-0` and `csd-1`: one NAL unit after a four-byte start
 * code. Expected values are the encoder's settings, never this parser's output.
 */
class H264ParameterSetsTest {
  private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

  private fun csd(nal: String): ByteArray = hex("00000001$nal")

  private data class Vector(val name: String, val sps: String, val pps: String, val profileIdc: Int, val levelIdc: Int, val cabac: Boolean, val word: String)

  private val vectors =
    listOf(
      // -profile:v baseline -level 3.0: constraint_set0 and set1 (0xc0), so Constrained Baseline; Baseline has no CABAC.
      Vector("baseline", "6742c01edb014016ec0440000003004000000f03c58bb8", "68ca83cb20", 66, 30, false, "constrained-baseline-3.0-cavlc"),
      // -profile:v main -level 3.1 -coder 1
      Vector("main", "674d401feca02802dd8088000003000800000301e078c18cb0", "68ebe3cb20", 77, 31, true, "main-3.1-cabac"),
      // -profile:v high -level 4.1 -coder 1
      Vector("high cabac", "67640029acd9405005bb0110000003001000000303c0f1831960", "68ebe3cb22c0", 100, 41, true, "high-4.1-cabac"),
      // -profile:v high -level 4.0 -coder 0: High with CAVLC, the switch the CBR hypothesis is about.
      Vector("high cavlc", "67640028acd9405005bb0110000003001000000303c0f1831960", "68cbe3cb22c0", 100, 40, false, "high-4.0-cavlc"),
    )

  @Test
  fun `each real SPS reads its profile and level, and each real PPS its entropy coding`() {
    for (v in vectors) {
      val csd = listOf(csd(v.sps), csd(v.pps))
      assertEquals(v.profileIdc, H264ParameterSets.sps(csd)?.profileIdc, v.name)
      assertEquals(v.levelIdc, H264ParameterSets.sps(csd)?.levelIdc, v.name)
      assertEquals(v.cabac, H264ParameterSets.pps(csd)?.cabac, v.name)
      assertEquals(v.word, EncoderFormat.word(H264ParameterSets.sps(csd), H264ParameterSets.pps(csd)), v.name)
    }
  }

  @Test
  fun `both sets in one buffer, three-byte start codes, are read as two NAL units`() {
    val v = vectors[2]
    val one = hex("000001${v.sps}000001${v.pps}")
    assertEquals(2, H264ParameterSets.nalUnits(one).size)
    assertEquals("high-4.1-cabac", EncoderFormat.word(H264ParameterSets.sps(listOf(one)), H264ParameterSets.pps(listOf(one))))
  }

  @Test
  fun `four-byte start codes split cleanly - the next code's leading zero is not the unit before's`() {
    val units = H264ParameterSets.nalUnits(hex("0000000167aa" + "0000000168bb" + "00000165cc"))
    assertEquals(listOf("67aa", "68bb", "65cc"), units.map { u -> u.joinToString("") { "%02x".format(it) } })
  }

  @Test
  fun `a bare NAL unit with no start code is read whole`() {
    val v = vectors[1]
    assertEquals(77, H264ParameterSets.sps(listOf(hex(v.sps)))?.profileIdc)
    assertEquals(true, H264ParameterSets.pps(listOf(hex(v.pps)))?.cabac)
  }

  @Test
  fun `the PPS flag is read after two Exp-Golomb numbers, not at a fixed bit`() {
    // pps_id 1 (010), sps_id 0 (1), entropy_coding_mode_flag 1, then the stop bit: 0101 1100. A fixed third bit reads 0.
    assertEquals(true, H264ParameterSets.pps(listOf(csd("685c")))?.cabac)
    // pps_id 2 (011), sps_id 0 (1), flag 0, stop bit: 0111 0100.
    assertEquals(false, H264ParameterSets.pps(listOf(csd("6874")))?.cabac)
  }

  @Test
  fun `emulation prevention bytes are dropped from the payload`() {
    assertContentEquals(hex("0000000001"), H264ParameterSets.unescaped(hex("00000300000301"), from = 0))
    // The header byte is skipped first, and a 03 after only one zero is data.
    assertContentEquals(hex("0003"), H264ParameterSets.unescaped(hex("670003"), from = 1))
  }

  @Test
  fun `no parameter sets, an empty buffer, or a truncated one read as unread, never a throw`() {
    assertNull(H264ParameterSets.sps(emptyList()))
    assertNull(H264ParameterSets.pps(listOf(ByteArray(0))))
    assertNull(H264ParameterSets.sps(listOf(csd("6764"))), "an SPS of two bytes")
    assertNull(H264ParameterSets.pps(listOf(csd("68"))), "a PPS with no payload")
    assertNull(H264ParameterSets.pps(listOf(csd("6800"))), "a PPS whose ue runs off the end")
    assertNull(H264ParameterSets.sps(listOf(csd("68ebe3cb20"))), "a PPS alone has no SPS")
    assertEquals("unread-unread-unread", EncoderFormat.word(null, null))
  }

  @Test
  fun `the level word reads level_idc as tenths, and Level 1b both ways`() {
    fun level(profile: Int, constraints: Int, idc: Int) = EncoderFormat.level(H264ParameterSets.Sps(profile, constraints, idc))
    assertEquals("3.0", level(100, 0, 30))
    assertEquals("5.2", level(100, 0, 52))
    assertEquals("1b", level(100, 0, 9))
    assertEquals("1b", level(66, 0x10, 11), "constraint_set3 below High")
    assertEquals("1.1", level(66, 0, 11))
    assertEquals("1.1", level(100, 0x10, 11), "High's constraint_set3 is not 1b")
  }

  /**
   * Review of #72, Minor 3: libx264's High 4.1 SPS above with only its constraint byte changed (libx264 does not write
   * these flags): 0x08 is constraint_set4 (Progressive High, A.2.4.1), 0x0c set4 and set5 (Constrained High, A.2.4.2).
   */
  @Test
  fun `High with constraint_set4 is Progressive High, and with set5 too Constrained High`() {
    val pps = csd("68ebe3cb22c0")
    fun word(constraintByte: String) =
      EncoderFormat.word(H264ParameterSets.sps(listOf(csd("6764${constraintByte}29acd9405005bb0110000003001000000303c0f1831960"))), H264ParameterSets.pps(listOf(pps)))
    assertEquals("progressive-high-4.1-cabac", word("08"))
    assertEquals("constrained-high-4.1-cabac", word("0c"))
    assertEquals("high-4.1-cabac", word("04"), "set5 alone names no subset of High")
    assertEquals("high-4.1-cabac", word("00"))
  }

  @Test
  fun `the profile word names Annex A's profiles, and an unknown one by number`() {
    fun profile(idc: Int, constraints: Int = 0) = EncoderFormat.profile(H264ParameterSets.Sps(idc, constraints, 40))
    assertEquals("baseline", profile(66))
    assertEquals("constrained-baseline", profile(66, 0x40))
    assertEquals("extended", profile(88))
    assertEquals("high10", profile(110))
    assertEquals("high422", profile(122))
    assertEquals("high444", profile(244))
    assertEquals("profile118", profile(118))
  }

  @Test
  fun `the line carries the mode, the format word and the codec name`() {
    val v = vectors[3]
    assertEquals(
      RecordEntry("encoder-format", listOf("mode" to "cbr", "state" to "high-4.0-cavlc", "source" to "c2.qti.avc.encoder")),
      EncoderFormat.entry(BitrateMode.CBR, "c2.qti.avc.encoder", listOf(csd(v.sps), csd(v.pps))),
    )
  }

  @Test
  fun `one line for the first frame of an attempt, and only when it differs from the last said`() {
    val lines = mutableListOf<RecordEntry>()
    val format = EncoderFormatLine { lines += it }
    val csd = listOf(csd(vectors[2].sps), csd(vectors[2].pps))
    format.read(BitrateMode.VBR, "enc", csd)
    assertEquals(0, lines.size, "no attempt yet")
    format.newAttempt()
    assertEquals(true, format.wanted)
    format.read(BitrateMode.VBR, "enc", csd)
    format.read(BitrateMode.CBR, "enc", csd)
    assertEquals(listOf("vbr"), lines.map { it.fields.first().second }, "the second frame of the attempt is not read")
    assertEquals(false, format.wanted)
    format.newAttempt()
    format.read(BitrateMode.VBR, "enc", csd)
    assertEquals(1, lines.size, "a reconnect on the same format writes none")
    format.newAttempt()
    format.read(BitrateMode.CBR, "enc", csd)
    assertEquals(listOf("vbr", "cbr"), lines.map { it.fields.first().second }, "a change of mode writes one")
  }
}
