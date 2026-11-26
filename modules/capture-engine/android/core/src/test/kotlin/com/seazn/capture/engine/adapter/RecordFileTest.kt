package com.seazn.capture.engine.adapter

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N53: Send record and Share record read the record file (`readRecord`, AGENTS §2's third call that answers). The read
 * runs on the record's own thread, so it sees every line appended before it, whole, across the rotation, oldest first.
 */
class RecordFileTest {
  private val dir: File = Files.createTempDirectory("record-file-test").toFile()
  private val pools = mutableListOf<java.util.concurrent.ExecutorService>()

  @AfterTest
  fun cleanUp() {
    pools.forEach { it.shutdownNow() }
    dir.deleteRecursively()
  }

  private fun file(maxBytes: Long = RecordFile.MAX_BYTES, readMaxBytes: Long = RecordFile.READ_MAX_BYTES): RecordFile {
    val pool = Executors.newSingleThreadExecutor()
    pools += pool
    return RecordFile(dir, maxBytes, readMaxBytes, pool)
  }

  private fun RecordFile.readSync(): String? {
    val done = CountDownLatch(1)
    var text: String? = "unanswered"
    read { answer ->
      text = answer
      done.countDown()
    }
    assertTrue(done.await(5, TimeUnit.SECONDS))
    return text
  }

  private fun line(n: Int) = """{"at":"2026-11-26T20:00:00.000Z","kind":"beats","n":$n}"""

  @Test
  fun `no file reads as empty, never null`() {
    assertEquals("", file().readSync())
  }

  @Test
  fun `an empty file reads as empty`() {
    File(dir, RecordFile.NAME).writeText("")
    assertEquals("", file().readSync())
  }

  @Test
  fun `a read sees every line appended before it, in order`() {
    val record = file()
    (1..50).forEach { record.append(line(it)) }
    assertEquals((1..50).joinToString("") { line(it) + "\n" }, record.readSync())
  }

  @Test
  fun `the rotation is read first, then the current file, so lines run oldest first`() {
    val each = line(10).length + 1L
    val record = file(maxBytes = each * 3)
    (10..16).forEach { record.append(line(it)) }
    // 10..12 filled the first file, which rotated at 13; 13..15 filled the next, which replaced the rotation at 16.
    assertEquals((13..16).joinToString("") { line(it) + "\n" }, record.readSync())
    assertTrue(File(dir, RecordFile.ROTATED).isFile)
  }

  @Test
  fun `a second read gets the same lines and more, never fewer`() {
    val record = file()
    (1..3).forEach { record.append(line(it)) }
    val first = record.readSync()
    record.append(line(4))
    assertEquals(first + line(4) + "\n", record.readSync())
  }

  @Test
  fun `over the read bound, only the newest whole lines are read`() {
    val each = line(10).length + 1L
    // A bound of 2.5 lines: the half line before the newest two is cut, never sent torn.
    val record = file(readMaxBytes = each * 5 / 2)
    (10..19).forEach { record.append(line(it)) }
    assertEquals(line(18) + "\n" + line(19) + "\n", record.readSync())
  }

  @Test
  fun `a bound that falls exactly on a line start keeps that line whole`() {
    val each = line(10).length + 1L
    val record = file(readMaxBytes = each * 2)
    (10..19).forEach { record.append(line(it)) }
    assertEquals(line(18) + "\n" + line(19) + "\n", record.readSync())
  }

  @Test
  fun `the bound spans the rotation and the current file`() {
    val each = line(10).length + 1L
    val record = file(maxBytes = each * 3, readMaxBytes = each * 4)
    (10..15).forEach { record.append(line(it)) }
    // Rotation holds 10..12, current 13..15: the newest four are 12..15.
    assertEquals((12..15).joinToString("") { line(it) + "\n" }, record.readSync())
  }

  @Test
  fun `appends racing a read leave it whole and contiguous (the rotation mid-read cannot happen)`() {
    val each = line(1000).length + 1L
    val record = file(maxBytes = each * 40)
    val writers = Executors.newFixedThreadPool(4)
    pools += writers
    val counter = java.util.concurrent.atomic.AtomicInteger(1000)
    repeat(4) { writers.execute { repeat(250) { record.append(line(counter.getAndIncrement())) } } }
    val reads = (1..20).map { record.readSync()!! }
    writers.shutdown()
    assertTrue(writers.awaitTermination(10, TimeUnit.SECONDS))
    for (text in reads) {
      assertTrue(text.isEmpty() || text.endsWith("\n"))
      text.lineSequence().filter { it.isNotEmpty() }.forEach { assertTrue(Regex("""\{"at":"[^"]+","kind":"beats","n":\d+}""").matches(it), it) }
    }
  }

  @Test
  fun `a read the disk refuses answers null and the next read still works`() {
    File(dir, RecordFile.NAME).mkdirs() // a directory where the file should be: not a file, so skipped
    File(dir, RecordFile.ROTATED).writeText(line(1) + "\n")
    val record = file()
    assertEquals(line(1) + "\n", record.readSync())
    // An unreadable file: refused, so null.
    val rotated = File(dir, RecordFile.ROTATED)
    rotated.setReadable(false)
    val honoured = !rotated.canRead() // root reads anything: then the refusal cannot be made here
    val refused = record.readSync()
    rotated.setReadable(true)
    if (!honoured) return
    assertNull(refused)
    assertEquals(line(1) + "\n", record.readSync())
  }

  @Test
  fun `the record thread runs a read after the appends queued before it`() {
    val queued = mutableListOf<Runnable>()
    val manual = Executor { queued += it }
    val record = RecordFile(dir, io = manual)
    record.append(line(1))
    var text: String? = null
    record.read { text = it }
    record.append(line(2))
    queued.forEach { it.run() }
    assertEquals(line(1) + "\n", text)
  }
}
