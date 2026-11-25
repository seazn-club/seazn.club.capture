package com.seazn.capture.engine.endpoints

import com.seazn.capture.engine.adapter.DumpArming
import com.seazn.capture.engine.adapter.DumpBudget
import com.seazn.capture.engine.adapter.EncoderDumpRules
import com.seazn.capture.engine.core.BitrateMode
import com.seazn.capture.engine.core.RecordEntry
import io.github.thibaultbee.streampack.core.elements.data.Frame
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Spec 2026-11-25-encoder-diagnostics §3, the dev twin only: a broadcast's first 10 s of encoded video, as Annex-B
 * H.264, to `files/encoder-dump-<mode>-<n>.h264`. Built only when `EncoderDumpRules.allowed(context.packageName)`
 * (StreamerAdapter; GlueRulesTest pins it), so the production app never holds one.
 *
 * The endpoint's thread decides ([DumpBudget]) and copies the frame's bytes; nothing else happens there. The file is
 * opened, written and closed on one writer thread of its own. The copies waiting for it are bounded
 * ([EncoderDumpRules.MAX_QUEUED_BYTES]): past that the dump ends as an overflow, so the encode path never waits for a
 * disk. The name carries the mode and a number only, and the file holds the encoder's bytes only.
 */
class EncoderDump(private val dir: File, private val line: (RecordEntry) -> Unit) {
  private val writer: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, "encoder-dump").apply { isDaemon = true } }
  private val arming = DumpArming()
  private var run: Run? = null

  /** One dump. [budget] is read and written under the dump's lock; [out] and [failed] on the writer thread alone. */
  private class Run(val mode: BitrateMode) {
    val budget = DumpBudget()
    var out: FileOutputStream? = null
    var failed = false
  }

  /** An attempt's connect: the first of a broadcast starts a dump. */
  @Synchronized
  fun attempt(mode: BitrateMode) {
    stopRun()
    if (arming.attempt()) run = Run(mode)
  }

  /** The attempt stopped: a dump still running is cut. */
  @Synchronized
  fun stopped() = stopRun()

  /** The broadcast is over, or the capture was released: the next attempt dumps again. */
  @Synchronized
  fun broadcastOver() {
    stopRun()
    arming.broadcastOver()
  }

  @Synchronized
  fun frame(frame: Frame) {
    val current = run ?: return
    if (current.budget.done) return
    val key = frame.isKeyFrame
    val csd = if (key) EncoderTap.csd(frame.format) else emptyList()
    val size = EncoderDumpRules.chunkSize(key, csd, frame.rawBuffer.remaining())
    when (val take = current.budget.frame(frame.ptsInUs, key, size)) {
      DumpBudget.Take.Copy -> {
        val chunk = EncoderDumpRules.chunk(key, csd, EncoderTap.remaining(frame.rawBuffer))
        writer.execute { write(current, chunk) }
      }
      is DumpBudget.Take.Ended -> finish(current, take.end)
      DumpBudget.Take.Wait, DumpBudget.Take.Done -> Unit
    }
  }

  private fun stopRun() {
    val current = run ?: return
    run = null
    arming.stopped(took = current.budget.frames > 0)
    current.budget.end(EncoderDumpRules.End.CUT)?.let { finish(current, EncoderDumpRules.End.CUT) }
  }

  /** Under the lock: the line's numbers are read now, the file closed and the line said on the writer, after its writes. */
  private fun finish(current: Run, end: EncoderDumpRules.End) {
    val entry = EncoderDumpRules.entry(end, current.mode, current.budget.frames, current.budget.bytes, current.budget.spanMs)
    val said = EncoderDumpRules.said(end, current.budget.frames)
    writer.execute {
      close(current)
      if (said && !current.failed) line(entry)
    }
  }

  /** On the writer: opens the file at the first chunk, then appends. A throw ends the dump as failed, said once. */
  private fun write(current: Run, chunk: ByteArray) {
    if (current.failed) return
    try {
      val out = current.out ?: open(current.mode).also { current.out = it }
      out.write(chunk)
    } catch (e: Exception) {
      current.failed = true
      close(current)
      val entry = synchronized(this) { EncoderDumpRules.entry(EncoderDumpRules.End.FAILED, current.mode, current.budget.frames, current.budget.bytes, current.budget.spanMs, e.javaClass.simpleName).also { current.budget.end(EncoderDumpRules.End.FAILED) } }
      line(entry)
    } finally {
      synchronized(this) { current.budget.written(chunk.size) }
    }
  }

  private fun open(mode: BitrateMode): FileOutputStream {
    val files = EncoderDumpRules.files(dir.list()?.toList().orEmpty(), mode)
    for (old in files.delete) File(dir, old).delete()
    return FileOutputStream(File(dir, files.name))
  }

  private fun close(current: Run) {
    try {
      current.out?.close()
    } catch (_: Exception) {
      // The bytes written are the dump; a close that throws loses nothing more.
    }
    current.out = null
  }
}
