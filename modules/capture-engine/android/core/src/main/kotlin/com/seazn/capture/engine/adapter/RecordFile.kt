package com.seazn.capture.engine.adapter

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The record on disk, for Share after a crash (CD7), and since N53 the record Send and Share read ([read]). Its own
 * thread: the record's IO stays off the scheduler thread (CD9; A17's store is the one exception). Every line reaches it
 * already scrubbed: the core's `SessionRecord.append` scrubs a line before its sink, `Platform.recordLine`, hands it
 * here, JavaScript's forwarded lines included (CD7), so the file holds nothing the record may not.
 *
 * N53 moved it from the Android glue to the core: it imports nothing Android, so its order is tested on the JVM.
 */
class RecordFile(
  private val dir: File,
  private val maxBytes: Long = MAX_BYTES,
  private val readMaxBytes: Long = READ_MAX_BYTES,
  private val io: Executor = Executors.newSingleThreadExecutor { task -> Thread(task, "capture-record") },
) {
  /** Lines the disk refused, counted and never thrown. Written on the record thread only. */
  @Volatile
  var failures = 0
    private set

  fun append(line: String) {
    io.execute { write(line) }
  }

  /**
   * N53: the record's two files, the rotation then the current one, as one text, oldest line first, handed to [answer]
   * on the record's thread. That thread writes every line, so a read runs after every line appended before it and before
   * any appended after: it never sees half a line, or a rotation midway. At most [readMaxBytes], the newest, from the
   * start of a line: a current file the rotation failed to move can grow past [maxBytes], and the bridge must not carry
   * it whole. Empty with no file; null when the disk refused the read.
   */
  fun read(answer: (String?) -> Unit) {
    io.execute { answer(readNow()) }
  }

  private fun write(line: String) {
    try {
      dir.mkdirs()
      val file = File(dir, NAME)
      if (file.length() >= maxBytes) file.renameTo(File(dir, ROTATED)) // replaces the old rotation
      file.appendText(line + "\n")
    } catch (e: IOException) {
      failures += 1
    }
  }

  private fun readNow(): String? =
    try {
      val files = listOf(File(dir, ROTATED), File(dir, NAME)).filter { it.isFile }
      val over = files.sumOf { it.length() } > readMaxBytes
      // Over the bound, one byte more is read, so a line that starts exactly at the bound is kept whole below.
      var skip = if (over) files.sumOf { it.length() } - readMaxBytes - 1 else 0
      val out = ByteArrayOutputStream()
      for (file in files) {
        val length = file.length()
        if (skip >= length) {
          skip -= length
          continue
        }
        out.write(tail(file, skip))
        skip = 0
      }
      val text = out.toString(Charsets.UTF_8.name())
      // The first line read over the bound is cut at its start, or is the byte before a whole line: either goes.
      if (over) text.substringAfter('\n', "") else text
    } catch (e: IOException) {
      null
    }

  /** [file]'s bytes from [from] on. */
  private fun tail(file: File, from: Long): ByteArray =
    RandomAccessFile(file, "r").use { raf ->
      val bytes = ByteArray((raf.length() - from).coerceAtLeast(0).toInt())
      raf.seek(from)
      raf.readFully(bytes)
      bytes
    }

  companion object {
    const val NAME = "session-record.ndjson"
    const val ROTATED = "session-record.1.ndjson"
    const val MAX_BYTES = 5L * 1024 * 1024

    /** N53: both files at their size, so a read under the rotation's own bound is always whole. */
    const val READ_MAX_BYTES = 2 * MAX_BYTES
  }
}
