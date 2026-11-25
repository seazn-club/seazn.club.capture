package com.seazn.capture.engine.adapter

import com.seazn.capture.engine.core.BitrateMode
import com.seazn.capture.engine.core.RecordEntry

/**
 * Spec 2026-11-25-encoder-diagnostics §3: the dev twin's dump of a broadcast's first seconds of encoded video, as
 * Annex-B H.264, for ffprobe on a laptop. The rules are here, pure; the glue (`endpoints/EncoderDump.kt`) does the
 * copy and the file. The production app never builds one: [allowed] is true for the dev twin's package alone, and the
 * production app is `com.seazn.capture` (`app.config.ts`, AGENTS §11).
 */
object EncoderDumpRules {
  /** The dev twin's application id: `app.config.ts`'s `appVariant` adds `.dev` to the production id. */
  const val DEV_PACKAGE = "com.seazn.capture.dev"

  /** A dump covers this much of the stream, from its first key frame... */
  const val SPAN_US = 10_000_000L

  /** ...or this many bytes, whichever comes first. */
  const val MAX_BYTES = 8L * 1024 * 1024

  /** Bytes copied and not yet written: past this the writer has fallen behind, and the dump ends ([Take.OVERFLOW]). */
  const val MAX_QUEUED_BYTES = 2L * 1024 * 1024

  /** Dumps kept in the files dir, the new one included; older ones are deleted first. */
  const val KEEP = 4

  const val KIND = "encoder-dump"

  private val NAME = Regex("^encoder-dump-(vbr|cbr|unsupported)-([0-9]{1,9})\\.h264$")
  private val START_CODE = byteArrayOf(0, 0, 0, 1)

  fun allowed(packageName: String?): Boolean = packageName == DEV_PACKAGE

  /** The new dump's name, `encoder-dump-<mode>-<n>.h264`, numbered past every dump on disk, and the old ones to delete. */
  data class Files(val name: String, val delete: List<String>)

  fun files(existing: List<String>, mode: BitrateMode): Files {
    val numbered = existing.mapNotNull { name -> NAME.matchEntire(name)?.let { name to it.groupValues[2].toInt() } }.sortedBy { it.second }
    val next = (numbered.lastOrNull()?.second ?: 0) + 1
    val delete = numbered.dropLast(KEEP - 1).map { it.first }
    return Files("encoder-dump-${mode.wire}-$next.h264", delete)
  }

  /**
   * What one video frame adds to the file: a key frame is preceded by the parameter sets ([csd], each after a start
   * code), so the file decodes from its first byte; StreamPack strips them from a key frame's own buffer (3.2.1,
   * `FrameFactory.createFrame` calls `removePrefixes`, javap). A buffer that already starts with a start code keeps it.
   */
  fun chunk(key: Boolean, csd: List<ByteArray>, frame: ByteArray): ByteArray {
    if (!key) return frame
    val out = java.io.ByteArrayOutputStream(csd.sumOf { it.size + START_CODE.size } + frame.size)
    for (set in csd) {
      if (!startCoded(set)) out.write(START_CODE)
      out.write(set)
    }
    out.write(frame)
    return out.toByteArray()
  }

  /** [chunk]'s size, without building it: what the budget decides on before a byte is copied. */
  fun chunkSize(key: Boolean, csd: List<ByteArray>, frameSize: Int): Int =
    if (!key) frameSize else csd.sumOf { it.size + if (startCoded(it)) 0 else START_CODE.size } + frameSize

  /** An attempt that stopped before its first frame wrote no file, and says nothing: a failed connect is not a dump. */
  fun said(end: End, frames: Int): Boolean = !(end == End.CUT && frames == 0)

  private fun startCoded(bytes: ByteArray): Boolean =
    (bytes.size >= 3 && bytes[0].toInt() == 0 && bytes[1].toInt() == 0 && bytes[2].toInt() == 1) ||
      (bytes.size >= 4 && bytes[0].toInt() == 0 && bytes[1].toInt() == 0 && bytes[2].toInt() == 0 && bytes[3].toInt() == 1)

  /** How a dump ended, as its record line says it. */
  enum class End(val wire: String) {
    /** The span was written. */
    WRITTEN("written"),

    /** [MAX_BYTES] came first. */
    FULL("full"),

    /** The writer fell [MAX_QUEUED_BYTES] behind: the rest is dropped, never waited for. */
    OVERFLOW("overflow"),

    /** The attempt stopped first. */
    CUT("cut"),

    /** The file could not be written. */
    FAILED("failed"),
  }

  fun entry(end: End, mode: BitrateMode, frames: Int, bytes: Long, spanMs: Long, error: String? = null): RecordEntry =
    RecordEntry(KIND, listOf("state" to end.wire, "mode" to mode.wire, "count" to frames, "bytes" to bytes, "ms" to spanMs) + listOfNotNull(error?.let { "error" to it }))
}

/**
 * Spec §3: one dump a broadcast. Each attempt's connect asks [attempt]; the first of a broadcast dumps. An attempt that
 * took no frame (a connect that failed) leaves the next one to dump; once a dump took frames, the broadcast's later
 * attempts (reconnects) dump nothing, until [broadcastOver]: the broadcast ended, or the capture was released.
 */
class DumpArming {
  private var armed = true

  fun attempt(): Boolean = armed

  fun stopped(took: Boolean) {
    if (took) armed = false
  }

  fun broadcastOver() {
    armed = true
  }
}

/**
 * Spec §3: which frames a dump takes, decided on the endpoint's thread before any byte is copied. It waits for a key
 * frame, then takes every video frame until [EncoderDumpRules.SPAN_US] of presentation time, [EncoderDumpRules.MAX_BYTES]
 * or, the bound that keeps the encode path from ever waiting, [EncoderDumpRules.MAX_QUEUED_BYTES] copied and not yet
 * written. A frame past a bound ends the dump: a dump with a hole in it would decode wrongly from the hole on, so the
 * file is the stream up to the first frame it could not take. Not thread-safe: the glue holds one lock around it.
 */
class DumpBudget(
  private val spanUs: Long = EncoderDumpRules.SPAN_US,
  private val maxBytes: Long = EncoderDumpRules.MAX_BYTES,
  private val maxQueuedBytes: Long = EncoderDumpRules.MAX_QUEUED_BYTES,
) {
  sealed interface Take {
    /** Not taken, and the dump goes on: no key frame yet. */
    data object Wait : Take

    /** Copy it and queue it. */
    data object Copy : Take

    /** Not taken, and the dump has ended now; the next frames are [Done]. */
    data class Ended(val end: EncoderDumpRules.End) : Take

    /** The dump ended before. */
    data object Done : Take
  }

  private var firstUs: Long? = null
  private var ended: EncoderDumpRules.End? = null
  private var lastUs: Long = 0

  var frames = 0
    private set

  var bytes = 0L
    private set

  var queued = 0L
    private set

  val spanMs: Long
    get() = firstUs?.let { (lastUs - it) / 1000 } ?: 0

  fun frame(ptsUs: Long, key: Boolean, size: Int): Take {
    if (ended != null) return Take.Done
    val first = firstUs ?: if (key) ptsUs.also { firstUs = it } else return Take.Wait
    val bound =
      when {
        ptsUs - first >= spanUs -> EncoderDumpRules.End.WRITTEN
        bytes + size > maxBytes -> EncoderDumpRules.End.FULL
        queued + size > maxQueuedBytes -> EncoderDumpRules.End.OVERFLOW
        else -> null
      }
    if (bound != null) return end(bound) ?: Take.Done
    frames += 1
    bytes += size
    queued += size
    lastUs = ptsUs
    return Take.Copy
  }

  /** The writer wrote [size] queued bytes. */
  fun written(size: Int) {
    queued -= size
  }

  /** Ends the dump with [end], unless it ended already: null then. */
  fun end(end: EncoderDumpRules.End): Take? {
    if (ended != null) return null
    ended = end
    return Take.Ended(end)
  }

  val done: Boolean
    get() = ended != null
}
