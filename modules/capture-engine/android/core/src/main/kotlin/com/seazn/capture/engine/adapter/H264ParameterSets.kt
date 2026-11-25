package com.seazn.capture.engine.adapter

/**
 * Spec 2026-11-25-encoder-diagnostics §2: what the video encoder's parameter sets say about the stream it makes, read
 * from MediaCodec's codec-specific data (`csd-0`, `csd-1`: Annex-B NAL units, each after a start code). Only the
 * fields the relay's decode cost turns on: the SPS's `profile_idc`, its constraint flags and `level_idc` (ITU-T H.264
 * §7.3.2.1.1, the first three bytes of its payload), and the PPS's `entropy_coding_mode_flag` (§7.3.2.2, the bit after
 * two Exp-Golomb numbers): CABAC (1) or CAVLC (0). Pure: no Android, so the JVM tests run it on real byte vectors.
 */
object H264ParameterSets {
  const val SPS = 7
  const val PPS = 8

  /** [constraints] is the SPS's second byte: constraint_set0_flag is its top bit (0x80), set5 is 0x04. */
  data class Sps(val profileIdc: Int, val constraints: Int, val levelIdc: Int)

  data class Pps(val cabac: Boolean)

  /** The first SPS among [buffers]' NAL units, or null when none is there or it is too short to read. */
  fun sps(buffers: List<ByteArray>): Sps? {
    val rbsp = first(buffers, SPS) ?: return null
    if (rbsp.size < 3) return null
    return Sps(rbsp[0].toInt() and 0xff, rbsp[1].toInt() and 0xff, rbsp[2].toInt() and 0xff)
  }

  /** The first PPS among [buffers]' NAL units: pic_parameter_set_id and seq_parameter_set_id, then the flag. */
  fun pps(buffers: List<ByteArray>): Pps? {
    val bits = Bits(first(buffers, PPS) ?: return null)
    bits.ue() ?: return null
    bits.ue() ?: return null
    return bits.bit()?.let { Pps(cabac = it == 1) }
  }

  /** The payload (after the one-byte header) of the first NAL unit of [type], emulation prevention removed. */
  private fun first(buffers: List<ByteArray>, type: Int): ByteArray? =
    buffers.asSequence().flatMap { nalUnits(it).asSequence() }.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1f) == type }?.let { unescaped(it, from = 1) }

  /**
   * The NAL units of an Annex-B buffer, start codes (`00 00 01`, or `00 00 00 01`) removed. A buffer with no start
   * code is read as one NAL unit, so an encoder that hands its parameter sets bare is still read.
   */
  fun nalUnits(bytes: ByteArray): List<ByteArray> {
    val starts = mutableListOf<Int>() // the index just past each start code
    var i = 0
    while (i + 2 < bytes.size) {
      if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0 && bytes[i + 2].toInt() == 1) {
        starts += i + 3
        i += 3
      } else {
        i += 1
      }
    }
    if (starts.isEmpty()) return if (bytes.isEmpty()) emptyList() else listOf(bytes)
    return starts.mapIndexed { n, start ->
      var end = if (n + 1 < starts.size) starts[n + 1] - 3 else bytes.size
      while (end > start && bytes[end - 1].toInt() == 0) end -= 1 // a four-byte start code's leading zero, or trailing zeros
      bytes.copyOfRange(start, end)
    }
  }

  /** §7.4.1: `00 00 03` carries `00 00`; the 03 is dropped. */
  internal fun unescaped(nal: ByteArray, from: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(nal.size)
    var zeros = 0
    for (k in from until nal.size) {
      val b = nal[k].toInt() and 0xff
      if (zeros >= 2 && b == 3) {
        zeros = 0
        continue
      }
      out.write(b)
      zeros = if (b == 0) zeros + 1 else 0
    }
    return out.toByteArray()
  }

  /** A big-endian bit reader over an RBSP; null past its end. */
  private class Bits(private val bytes: ByteArray) {
    private var at = 0

    fun bit(): Int? {
      if (at >= bytes.size * 8) return null
      val value = (bytes[at / 8].toInt() shr (7 - at % 8)) and 1
      at += 1
      return value
    }

    /** ue(v), §9.1: leading zeros, a one, then that many bits. More than 31 zeros is no number this reads. */
    fun ue(): Int? {
      var zeros = 0
      while (true) {
        val b = bit() ?: return null
        if (b == 1) break
        zeros += 1
        if (zeros > 31) return null
      }
      var rest = 0
      repeat(zeros) { rest = (rest shl 1) or (bit() ?: return null) }
      return (1 shl zeros) - 1 + rest
    }
  }
}
