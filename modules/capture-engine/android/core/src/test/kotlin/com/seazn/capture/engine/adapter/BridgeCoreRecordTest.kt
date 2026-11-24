package com.seazn.capture.engine.adapter

import com.seazn.capture.engine.core.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * [BridgeCore]: the code's ask, stored lines, the JS lines, the intents, the allow-list, ticks and the tail's seq.
 */
class BridgeCoreRecordTest : BridgeCoreTestKit() {
  @Test
  fun `G0-d the code is asked with its slot and this phone's id`() {
    live()
    val asked = platform.http.single { it.url.startsWith("${PairWire.ORIGIN}?") }
    assertEquals("${PairWire.ORIGIN}?slot=1&phone=${Configs.PHONE_ID}", asked.url)
  }

  @Test
  fun `A17 the operator's Stop is stored before the end, and rides the next pairing's first beat`() {
    live()
    var atEnd: String? = null
    platform.onExecute = { if (it is Command.End) atEnd = store.files[PairingStore.STOPPED] }
    core.send(mapOf("kind" to "stop"))
    scheduler.advanceBy(0)
    assertTrue(PairWire.SID in atEnd.orEmpty(), "written, synchronously, before the End ran: $atEnd")
    core.send(mapOf("kind" to "reset"))
    scheduler.advanceBy(0)
    platform.http.clear()
    pair()
    assertEquals(PairWire.SID, JSONObject(platform.beats().first().body!!).getString("stopped"))
  }

  @Test
  fun `a refused write is a record line, and the end still happens`() {
    live()
    store.refuseWrites = true
    core.send(mapOf("kind" to "stop"))
    scheduler.advanceBy(0)
    assertEquals("ended", state())
    // N38: the end also deletes the record's pins, which the same store refuses on its own line, named by its key.
    val failed = lines().single { it.getString("kind") == "store-failed" && it.getString("key") == PairingStore.STOPPED }
    assertEquals(listOf("write", "IOException"), listOf(failed.getString("op"), failed.getString("error")))
    assertTrue(platform.executed.any { it is Command.End }, "the End behind the refused write still ran")
    // Engine.execute would run the End past a throw anyway, as `command-failed`: the refusal is its own line instead.
    assertEquals(emptyList(), kinds().filter { it == "command-failed" || it == "engine-error" }, "one line for the refusal, named")
  }

  @Test
  fun `PV21 the phone id is made once and kept across engines over the same storage`() {
    var made = 0
    val counting = { made += 1; "phone-test-id-000$made" }
    val first = RecordingPlatform()
    val one = build(first, newId = counting)
    one.start()
    pair(one)
    val again = RecordingPlatform()
    val two = build(again, newId = counting)
    two.start()
    pair(two)
    val ids = (first.beats() + again.beats()).map { JSONObject(it.body!!).getString("phone") }
    assertEquals(2, ids.size)
    assertEquals(setOf("phone-test-id-0001"), ids.toSet(), "the restarted engine reads the id the first one made")
    assertEquals(1, made)
  }

  @Test
  fun `carry 12 a forwarded JS line is written through the scheduler, its level kept and a level field renamed`() {
    core.start()
    core.log("warn", "intent.stop", mapOf("level" to "x", "count" to 3.0, "ms" to 2.25))
    assertTrue(platform.lines.none { "intent.stop" in it }, "not written before the scheduler runs it")
    scheduler.advanceBy(0)
    val line = JSONObject(platform.lines.single { "intent.stop" in it })
    assertEquals("warn", line.getString("level"))
    // Renamed, and masked: a field called `level` is on no allow-list (Task 10).
    assertEquals("***", line.getString("field_level"))
    // P6: `get`, not `getInt`, which would coerce 3.0 and let the mutant through.
    assertEquals(3, line.get("count"))
    // Decimal.tenths: Math.round(2.25 × 10) = Math.round(22.5) = 23, so 2.3.
    assertEquals(2.3, line.getDouble("ms"), "a fraction is written to tenths, like every native number")
  }

  /** Review C4a m-7: 2^53 is the last whole Double read as a whole number; 2^53 + 2, the next, stays a Double. */
  @Test
  fun `m-7 a whole JS number past 2 to the 53 is not read as a whole number`() {
    core.start()
    core.log("info", "probe.big", mapOf("count" to 9_007_199_254_740_992.0, "ms" to 9_007_199_254_740_994.0))
    scheduler.advanceBy(0)
    val raw = platform.lines.single { "probe.big" in it }
    assertTrue("\"count\":9007199254740992," in raw, raw)
    // Past 2^53 a Double is no longer every whole number: it is written as a native number, never as 9007199254740994.
    assertFalse("9007199254740994" in raw, raw)
    assertFalse(JSONObject(raw).get("ms") is Long, raw)
  }

  @Test
  fun `C1 M3 a level outside the logger's four is info, never masked, and each of the four is kept`() {
    core.start()
    for (level in listOf("loud", "debug", "info", "warn", "error")) core.log(level, "probe.$level", emptyMap())
    scheduler.advanceBy(0)
    val levels = lines().filter { it.getString("kind").startsWith("probe.") }.associate { it.getString("kind") to it.optString("level", "info") }
    assertEquals(mapOf("probe.loud" to "info", "probe.debug" to "debug", "probe.info" to "info", "probe.warn" to "warn", "probe.error" to "error"), levels)
  }

  @Test
  fun `Task 11 an intent that maps to nothing is recorded by its kind, never dropped`() {
    core.start()
    pair()
    core.send(mapOf("kind" to "explode"))
    core.send(mapOf("kind" to "setAutomatic"))
    core.send(mapOf("kind" to "setLanguage", "language" to 7.0))
    scheduler.advanceBy(0)
    assertEquals(listOf("explode", "setAutomatic", "setLanguage"), lines().filter { it.getString("kind") == "intent-unreadable" }.map { it.getString("intent") })
    assertEquals("paired", state())
  }

  @Test
  fun `Task 11 gap 1 an intent whose own task throws is still acked`() {
    core.start()
    store.readFailure = StackOverflowError("storage")
    core.send(PairWire.valid() + ("seq" to 7.0))
    scheduler.advanceBy(0)
    assertTrue("ack:7" in platform.events, "the kit never waits on a timeout: ${platform.events}")
    assertTrue(lines().any { it.getString("kind") == "engine-error" && "StackOverflowError" in it.getString("message") })
  }

  @Test
  fun `m7 an input's message never prints, and reaches a line only masked`() {
    val url = Configs.rtmps.url + Configs.STREAM_KEY
    val inputs = listOf(Input.ConnectFailed(3, ConnectFailure.OTHER, url), Input.Dropped(3, DropReason.ENDPOINT_CLOSED, url), Input.PlatformFailed(3, url))
    for (input in inputs) {
      assertFalse(Configs.STREAM_KEY in input.toString(), "$input")
      assertTrue("attemptId=3" in input.toString() && "<${url.length} chars>" in input.toString(), "$input")
    }
    live()
    core.report(Input.Dropped(platform.attempt, DropReason.ENDPOINT_CLOSED, url))
    scheduler.advanceBy(0)
    assertTrue(platform.lines.none { Configs.STREAM_KEY in it })
    assertEquals(Configs.rtmps.url + "***", lines().single { it.getString("kind") == "dropped" }.getString("message"))
  }

  @Test
  fun `C1 every kind and key BridgeCore writes is decided by the allow-list`() {
    val list = Shared.read("scrub/allow-list.json")
    val decided = listOf("plain", "publicUrl", "text", "reserved").flatMap { key -> list.getJSONArray(key).let { a -> (0 until a.length()).map(a::getString) } }.toSet() + list.getJSONObject("scalars").keySet()
    store.files[PairingStore.STOPPED] = "not json"
    live()
    core.send(mapOf("kind" to "explode"))
    core.askFailed(IllegalStateException("late"))
    core.cameraBusy(platform.captureRequests.last()!!)
    core.report(Input.ConnectFailed(platform.attempt, ConnectFailure.OTHER, "late"))
    platform.throwOnFactsOnce = StackOverflowError("facts")
    scheduler.advanceBy(Engine.TICK_MS)
    store.refuseWrites = true
    core.send(mapOf("kind" to "stop"))
    scheduler.advanceBy(Engine.TICK_MS)
    val ours = setOf("readiness", "intent-unreadable", "pair-step-late", "camera-busy", "store-failed", "store-unreadable", "engine-error", "connect-failed-ignored")
    val written = lines()
    assertEquals(ours, ours.intersect(written.map { it.getString("kind") }.toSet()), "each of BridgeCore's kinds was written here")
    val undecided = written.flatMap { line -> listOfNotNull(line.getString("kind").takeUnless { it in list.getJSONObject("kinds").keySet() }) + line.keySet().filterNot { it in decided } }
    assertEquals(emptyList(), undecided, "of ${written.size} lines")
  }

  @Test
  fun `CD33 a tick that comes late while paired is recorded with its gap`() {
    var stall = false
    val slow =
      object : Scheduler {
        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable =
          scheduler.schedule(if (stall && delayMs == Engine.TICK_MS) 2_000 else delayMs, task)
      }
    val late = RecordingPlatform()
    val stalled = build(late, MemoryStore(), slow)
    stalled.start()
    stalled.send(PairWire.valid())
    scheduler.advanceBy(Engine.TICK_MS)
    stall = true
    // The tick already due runs on time and schedules the next 2 000 ms out: one gap of 2 000 ms.
    scheduler.advanceBy(Engine.TICK_MS + 2_000)
    assertEquals(listOf(2_000L), lines(late).filter { it.getString("kind") == "tick-late" }.map { it.getLong("gapMs") })
  }

  @Test
  fun `CD33 video that stops moving while live is one frames-gap line with its length`() {
    live()
    // Reads run every 500 ms from the Connected at 0; the last one at 1 500 moved the count.
    platform.frozen = true
    scheduler.advanceBy(1_500)
    platform.frozen = false
    scheduler.advanceBy(500)
    // Last moved at 1 500, moved again at 3 500: 2 000 ms.
    val gap = lines().single { it.getString("kind") == "frames-gap" }
    assertEquals(2_000L, gap.getLong("gapMs"))
  }

  /**
   * Carry C4c-I3 (Task 33): every line reaches the platform with the next seq, and the tail names the
   * same lines by the same seqs, so JS tells a new line from a tail line by number, never by text. The
   * fake clock stands still, so the thirty probe lines are equal, character for character.
   */
  @Test
  fun `C4c-I3 each record line reaches the platform with the next seq, and the tail carries the same seq for the same line`() {
    core.start()
    pair()
    repeat(30) { core.log("info", "probe.line", mapOf("n" to 1.0)) }
    scheduler.advanceBy(0)
    val n = platform.lines.size.toLong()
    assertTrue(n >= 30, "the probe lines reached the platform: $n")
    assertEquals((1L..n).toList(), platform.seqs, "numbered from 1, with no gap and no repeat")
    assertEquals(1, platform.lines.takeLast(30).toSet().size, "thirty equal lines")
    val expected = platform.seqs.zip(platform.lines).takeLast(20).map { (seq, line) -> mapOf("seq" to seq.toDouble(), "line" to line) }
    assertEquals(expected, tailLines())
  }

  @Test
  fun `C4c-I3 the tail before any line is empty, and its first line is seq 1`() {
    val fresh = RecordingPlatform()
    val idle = build(fresh, MemoryStore())
    assertEquals(mapOf("pins" to emptyList<String>(), "lines" to emptyList<Any?>()), idle.tail())
    idle.log("info", "probe.first", emptyMap())
    scheduler.advanceBy(0)
    assertEquals(listOf(1L), fresh.seqs)
    assertEquals(listOf(mapOf("seq" to 1.0, "line" to fresh.lines.single())), tailLines(idle))
  }
}
