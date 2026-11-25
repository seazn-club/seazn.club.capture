package com.seazn.capture.engine.endpoints

import android.media.MediaFormat
import com.seazn.capture.engine.adapter.EncoderFormatLine
import com.seazn.capture.engine.adapter.QuietAfterThrow
import com.seazn.capture.engine.core.BitrateMode
import com.seazn.capture.engine.core.RecordEntry
import io.github.thibaultbee.streampack.core.elements.data.Frame
import java.nio.ByteBuffer

/**
 * Spec 2026-11-25-encoder-diagnostics: what the endpoint hands the diagnostics of each video frame, before it writes
 * the frame on. StreamPack 3.2.1 (javap, `MediaCodecEncoder$FrameFactory`): a frame's `format` is the codec's
 * `getOutputFormat()`, which holds `csd-0` and `csd-1`, and its raw buffer is the codec's output buffer, the parameter
 * sets removed from a key frame. Both are read through duplicates, so the frame's position and limit, which the
 * network endpoint reads, never move. Nothing here may stop the frame: the format read and the dump each have a guard of
 * their own ([QuietAfterThrow], review of #72), so a throw of either, an `Error` included, is said once
 * (`capture-call-failed {action: encoder-format}` or `{action: encoder-dump}`) and only that one goes quiet for the
 * process. The endpoint writes the frame on whatever happens here (`tapThenWrite`).
 */
class EncoderTap(
  private val format: EncoderFormatLine,
  /** Null in every build but the dev twin's (`EncoderDumpRules.allowed`). */
  private val dump: EncoderDump?,
  private val mode: () -> BitrateMode,
  private val codec: () -> String?,
  private val line: (RecordEntry) -> Unit,
) {
  private val formatGuard = QuietAfterThrow()
  private val dumpGuard = QuietAfterThrow()

  fun videoFrame(frame: Frame) {
    formatGuard.run { if (format.wanted) format.read(mode(), codec(), csd(frame.format)) }?.let { said("encoder-format", it) }
    dump?.let { held -> dumpGuard.run { held.frame(frame) }?.let { said("encoder-dump", it) } }
  }

  private fun said(action: String, thrown: Throwable) {
    line(RecordEntry("capture-call-failed", listOf("action" to action, "error" to thrown.javaClass.simpleName)))
  }

  companion object {
    /** The output format's parameter sets, `csd-0` then `csd-1`, whichever it holds, as copies. */
    fun csd(format: MediaFormat): List<ByteArray> =
      listOf("csd-0", "csd-1").mapNotNull { key -> if (format.containsKey(key)) format.getByteBuffer(key)?.let(::whole) else null }

    /** All of [buffer], from 0 to its limit, read through a duplicate. */
    private fun whole(buffer: ByteBuffer): ByteArray {
      val view = buffer.duplicate()
      view.position(0)
      return ByteArray(view.remaining()).also { view.get(it) }
    }

    /** What is left of [buffer] to read, as the endpoint will read it, through a duplicate. */
    fun remaining(buffer: ByteBuffer): ByteArray {
      val view = buffer.duplicate()
      return ByteArray(view.remaining()).also { view.get(it) }
    }
  }
}
