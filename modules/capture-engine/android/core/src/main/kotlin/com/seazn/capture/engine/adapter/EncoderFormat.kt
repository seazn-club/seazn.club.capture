package com.seazn.capture.engine.adapter

import com.seazn.capture.engine.core.BitrateMode
import com.seazn.capture.engine.core.RecordEntry

/**
 * Spec 2026-11-25-encoder-diagnostics §2: one `encoder-format` line, `{mode, state, source}`, from the encoder's output
 * format. The web's record contract (vendored, `contracts/capture-record.v1.json`) admits only the keys it lists, so
 * the line uses three of them, each a plain word:
 * - `mode`: the bitrate mode the encoder was asked for ([BitrateMode]: vbr, cbr or unsupported);
 * - `state`: the stream's format as its parameter sets say it, `<profile>-<level>-<entropy>`, such as
 *   `high-4.1-cabac`, each part `unread` when the SPS or PPS is missing or too short;
 * - `source`: the encoder's codec name (`MediaCodec.getName`, e.g. `c2.qti.avc.encoder`), or null when none is held.
 */
object EncoderFormat {
  const val KIND = "encoder-format"
  const val UNREAD = "unread"

  fun entry(mode: BitrateMode, codec: String?, csd: List<ByteArray>): RecordEntry =
    RecordEntry(KIND, listOf("mode" to mode.wire, "state" to word(H264ParameterSets.sps(csd), H264ParameterSets.pps(csd)), "source" to codec))

  fun word(sps: H264ParameterSets.Sps?, pps: H264ParameterSets.Pps?): String =
    listOf(sps?.let(::profile) ?: UNREAD, sps?.let(::level) ?: UNREAD, pps?.let { if (it.cabac) "cabac" else "cavlc" } ?: UNREAD).joinToString("-")

  /** Annex A's profile_idc values; constraint_set1 on Baseline is Constrained Baseline (A.2.1.1). */
  fun profile(sps: H264ParameterSets.Sps): String =
    when (sps.profileIdc) {
      66 -> if ((sps.constraints and CONSTRAINT_SET1) != 0) "constrained-baseline" else "baseline"
      77 -> "main"
      88 -> "extended"
      100 -> high(sps.constraints)
      110 -> "high10"
      122 -> "high422"
      244 -> "high444"
      else -> "profile${sps.profileIdc}"
    }

  /**
   * Review of #72, Minor 3: High's own subsets by its constraint flags. constraint_set4 is Progressive High (A.2.4.1,
   * no field coding); with constraint_set5 too it is Constrained High (A.2.4.2, no B slices either). Both cut what a
   * decoder must do, so the relay's cost reads differently for each.
   */
  private fun high(constraints: Int): String =
    when {
      (constraints and CONSTRAINT_SET4) == 0 -> "high"
      (constraints and CONSTRAINT_SET5) != 0 -> "constrained-high"
      else -> "progressive-high"
    }

  /** level_idc is ten times the level (41 is 4.1); Level 1b is 9, or 11 with constraint_set3 below High (A.3.1). */
  fun level(sps: H264ParameterSets.Sps): String =
    when {
      sps.levelIdc == 9 -> "1b"
      sps.levelIdc == 11 && sps.profileIdc in BELOW_HIGH && (sps.constraints and CONSTRAINT_SET3) != 0 -> "1b"
      else -> "${sps.levelIdc / 10}.${sps.levelIdc % 10}"
    }

  private const val CONSTRAINT_SET1 = 0x40
  private const val CONSTRAINT_SET3 = 0x10
  private const val CONSTRAINT_SET4 = 0x08
  private const val CONSTRAINT_SET5 = 0x04
  private val BELOW_HIGH = setOf(66, 77, 88)
}

/**
 * Spec §2: the first video frame of each attempt reads the encoder's format; a line is written when it differs from
 * the last one said, so a reconnect on the same encoder writes none and a change of mode writes one. The endpoint
 * thread asks [wanted] on every video frame (one volatile read) and calls [read] only on the first.
 */
class EncoderFormatLine(private val line: (RecordEntry) -> Unit) {
  @Volatile private var pending = false
  private var lastSaid: RecordEntry? = null

  val wanted: Boolean
    get() = pending

  fun newAttempt() {
    pending = true
  }

  @Synchronized
  fun read(mode: BitrateMode, codec: String?, csd: List<ByteArray>) {
    if (!pending) return
    pending = false
    val entry = EncoderFormat.entry(mode, codec, csd)
    if (entry == lastSaid) return
    lastSaid = entry
    line(entry)
  }
}
