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
 * [BridgeCore]: failures at the pairing, the task boundary and the facts read, and what each ends.
 */
class BridgeCoreFailureTest : BridgeCoreTestKit() {
  @Test
  fun `P11 a failure in work posted for the pairing ends it fatal-error and named, and releases`() {
    core.start()
    pair()
    core.viewfinder(true)
    scheduler.advanceBy(0)
    core.pairFailed(platform.captureRequests.last()!!, IllegalStateException("camera open"))
    scheduler.advanceBy(0)
    val state = core.current()["state"] as Map<*, *>
    assertEquals(listOf("ended", "fatal-error"), listOf(state["kind"], state["reason"]))
    assertTrue(Command.Release in platform.executed)
  }

  @Test
  fun `I4 a camera busy at open is not ready, camera - the pairing stays, and the release asks a reopen`() {
    core.start()
    core.report(Input.Network(true))
    ready()
    pair()
    core.viewfinder(true)
    scheduler.advanceBy(0)
    core.cameraBusy(platform.captureRequests.last()!!)
    // Paired stays Paired, so the camera crosses at the next tick's snapshot.
    scheduler.advanceBy(Engine.TICK_MS)
    assertEquals("paired", state())
    assertEquals("taken", core.current()["camera"])
    assertEquals(false, (core.current()["ready"] as Map<*, *>)["camera"])
    assertTrue("camera-busy" in kinds())
    core.cameraBusy(Configs.pairRequest())
    scheduler.advanceBy(0)
    assertEquals("camera-busy", lines().single { it.getString("kind") == "pair-step-late" }.getString("error"), "another pairing's busy camera is a line only")
    core.report(Input.CameraReleased)
    scheduler.advanceBy(0)
    assertTrue(Command.ReopenCamera in platform.executed)
  }

  @Test
  fun `P11 a late failure for a pairing no longer held is recorded and ends nothing`() {
    live()
    core.pairFailed(Configs.pairRequest(), IllegalStateException("late"))
    scheduler.advanceBy(0)
    assertEquals("publishing", state())
    assertEquals(1, kinds().count { it == "pair-step-late" })
    // The pairing's own request, once it has ended: late too, never a failure of the ended pairing.
    val own = platform.captureRequests.last()!!
    core.send(mapOf("kind" to "stop"))
    scheduler.advanceBy(0)
    assertEquals("ended", state())
    val failedBefore = kinds().count { it == "platform-failed" }
    core.pairFailed(own, IllegalStateException("after the end"))
    scheduler.advanceBy(0)
    assertEquals(2, kinds().count { it == "pair-step-late" })
    assertEquals(failedBefore, kinds().count { it == "platform-failed" })
  }

  @Test
  fun `CD11 a pairing failure heals at the next pair of the same code`() {
    core.start()
    core.report(Input.Network(true))
    ready()
    pair()
    core.viewfinder(true)
    scheduler.advanceBy(0)
    core.pairFailed(platform.captureRequests.last()!!, IllegalStateException("camera open"))
    scheduler.advanceBy(0)
    core.send(mapOf("kind" to "reset"))
    scheduler.advanceBy(0)
    pair()
    // The failed pairing's end closed the capture and silenced sound (C4a carry, Task 33); the re-pair
    // over the shown viewfinder opens it again, and its meter reads before the hold.
    ready()
    core.send(mapOf("kind" to "start"))
    scheduler.advanceBy(1_500)
    assertEquals("publishing", state())
  }

  @Test
  fun `carry 18 a linkage error from a connect ends the pairing fatal-error and stays sticky`() {
    platform.throwOnConnect = UnsatisfiedLinkError("libsrt.so not found")
    live()
    assertEquals("ended", state())
    val ended = lines().single { it.getString("kind") == "ended" }
    assertEquals("UnsatisfiedLinkError: libsrt.so not found", ended.getString("message"))
    assertTrue("command-failed" !in kinds(), "classified where the command is routed, never counted as a command's failure")
    // Review C4a m-8: the failure held crosses to Diagnostics, and no command is counted as failed.
    assertEquals(listOf(true, 0.0), listOf(telemetry()["stickyFailure"], telemetry()["commandFailures"]))
  }

  @Test
  fun `review 13 I1 a process failure held refuses the next pairing at its ask, before any Hold or beat`() {
    platform.throwOnConnect = UnsatisfiedLinkError("libsrt.so not found")
    live()
    val beats = platform.beats().size
    core.send(mapOf("kind" to "reset"))
    scheduler.advanceBy(0)
    pair()
    assertEquals(listOf("ended", "fatal-error"), (core.current()["state"] as Map<*, *>).let { listOf(it["kind"], it["reason"]) })
    assertEquals(1, platform.executed.count { it == Command.AskPermissions }, "the second ask never reaches the platform")
    assertEquals(1, platform.executed.count { it == Command.Hold })
    assertEquals(beats, platform.beats().size, "no claim beat for a slot it cannot broadcast on")
  }

  @Test
  fun `Task 13 m4 a command's ordinary failure is counted where it ran, and the pairing carries on`() {
    platform.throwOn = { if (it == Command.Hold) IllegalStateException("no service") else null }
    core.start()
    assertEquals(listOf(0.0, false), listOf(telemetry()["commandFailures"], telemetry()["stickyFailure"]))
    pair()
    assertEquals("paired", state())
    assertTrue(lines().single { it.getString("kind") == "command-failed" }.getString("message").startsWith("Hold: java.lang.IllegalStateException"))
    // Review C4a m-8: counted where Diagnostics reads it; an ordinary failure holds nothing.
    assertEquals(listOf(1.0, false), listOf(telemetry()["commandFailures"], telemetry()["stickyFailure"]))
  }

  @Test
  fun `I-2 a facts read that throws every time keeps the snapshots going on the last good facts, with one line, and counts each`() {
    live()
    assertEquals(listOf(0.5, 0.0), listOf(telemetry()["audioLevel"], telemetry()["snapshotFailures"]), "the good facts, before")
    platform.throwOnFacts = IllegalStateException("thermal")
    val before = platform.snapshots.size
    scheduler.advanceBy(20 * Engine.TICK_MS)
    val sent = platform.snapshots.size - before
    assertTrue(sent >= 10, "a snapshot a second at least, still (S8): $sent")
    val counted = telemetry()["snapshotFailures"] as Double
    assertTrue(counted >= 20, "each one built over the last good facts is counted, sent or not: $counted")
    assertEquals(0.5, telemetry()["audioLevel"], "the last good facts, never the defaults")
    val written = lines().filter { it.getString("kind") == "engine-error" }
    assertEquals(listOf("facts: IllegalStateException: thermal"), written.map { it.getString("message") }, "one line a minute (G3)")
    assertTrue(lines().none { it.getString("kind") == "ended" }, "a facts read that throws ends nothing, so writes no ended (Batch 13, I1)")
    platform.throwOnFacts = null
    scheduler.advanceBy(Engine.TICK_MS)
    assertEquals(counted, telemetry()["snapshotFailures"], "a good read again counts nothing")
  }

  @Test
  fun `I-2 a snapshot the bridge refused is counted, and the next one says so`() {
    core.start()
    platform.throwOnSnapshotOnce = IllegalStateException("bridge")
    scheduler.advanceBy(Engine.TICK_MS)
    val before = platform.snapshots.size
    scheduler.advanceBy(Engine.TICK_MS)
    assertEquals(before + 1, platform.snapshots.size)
    assertEquals(1.0, telemetry()["snapshotFailures"])
  }

  @Test
  fun `carry 15 an Error at the task boundary is recorded and the scheduler lives on`() {
    core.start()
    platform.throwOnFactsOnce = StackOverflowError("facts")
    scheduler.advanceBy(Engine.TICK_MS)
    assertTrue(lines().any { it.getString("kind") == "engine-error" && "StackOverflowError" in it.getString("message") })
    assertTrue(lines().none { it.getString("kind") == "ended" }, "a failure at the task boundary that is not permanent ends nothing (Batch 13, I1)")
    val before = platform.snapshots.size
    scheduler.advanceBy(Engine.TICK_MS * 4)
    assertEquals(before + 2, platform.snapshots.size, "the later ticks still publish, once a second (S8)")
    assertEquals(1.0, telemetry()["taskFailures"], "the guard's count crosses to Diagnostics")
  }

  @Test
  fun `CD11 a permanent failure at the task boundary ends the pairing fatal-error, and holds for the process`() {
    live()
    platform.throwOnLink = UnsatisfiedLinkError("libbistats.so not found")
    scheduler.advanceBy(Engine.TICK_MS * 2)
    assertEquals(listOf("ended", "fatal-error"), (core.current()["state"] as Map<*, *>).let { listOf(it["kind"], it["reason"]) })
    assertTrue(lines().any { it.getString("kind") == "engine-error" && "UnsatisfiedLinkError" in it.getString("message") })
    // Batch 13, I1: the end that the permanent failure causes writes `ended`, as every end does.
    assertEquals(listOf("fatal-error"), lines().filter { it.getString("kind") == "ended" }.map { it.getString("reason") })
    core.send(mapOf("kind" to "reset"))
    scheduler.advanceBy(0)
    pair()
    assertEquals("ended", state(), "sticky for the process: the next pairing is refused at its ask")
    assertEquals(1, platform.executed.count { it == Command.AskPermissions })
  }

  @Test
  fun `review 13 I2 a record sink that throws an Error is caught once and its reporter once - the queue drains`() {
    core.start()
    scheduler.advanceBy(0)
    platform.throwOnLine = StackOverflowError("sink")
    core.log("info", "probe", emptyMap())
    // A reporter that posted its own line would come back here for ever; the scheduler's task cap ends that as a failure.
    scheduler.advanceBy(0)
    platform.throwOnLine = null
    scheduler.advanceBy(Engine.TICK_MS)
    assertEquals(listOf(1.0, 1.0), listOf(telemetry()["taskFailures"], telemetry()["reporterFailures"]))
    assertEquals(1, scheduler.pending, "only the next tick waits")
  }

  @Test
  fun `G3 a reader that throws on every read writes one engine-error line a minute, with the count it held back`() {
    live()
    platform.answer = PairWire::answerLive
    platform.throwOnLink = IllegalStateException("bistats")
    // Link reads run every 1 000 ms from the Connected at 0: the first to throw is at 2 000, the last
    // within ten minutes at 601 000. 600 throws; a window opens at 2 000 + 60 000 k, k = 0 to 9.
    scheduler.advanceBy(600_000)
    assertTrue(state() in setOf("publishing", "degraded"), "the reader's failure is not the broadcast's: ${state()}")
    val written = lines().filter { it.getString("kind") == "engine-error" }
    assertEquals(List(10) { "IllegalStateException: bistats" }, written.map { it.getString("message") })
    assertEquals(listOf(0) + List(9) { 59 }, written.map { it.getInt("suppressed") })
    assertTrue(lines().none { it.getString("kind") == "ended" }, "a reader's failure ends nothing, so writes no ended (Batch 13, I1)")
    assertEquals(600.0, telemetry()["taskFailures"], "every throw is still counted")
  }

  @Test
  fun `G3 at most sixteen distinct failures are written a minute, and the rest are counted on the next window's first line`() {
    val errors = ErrorLines()
    val written = (1..20).mapNotNull { n -> errors.line(n.toLong(), "failure $n") }
    assertEquals((1..ErrorLines.MAX_DISTINCT).map { "failure $it" }, written.map { it.fields.toMap()["message"] })
    assertEquals(null, errors.line(ErrorLines.WINDOW_MS, "failure 1"), "one window from the first line, less a millisecond: still held")
    val next = errors.line(ErrorLines.WINDOW_MS + 1, "failure 1")
    assertEquals(mapOf("message" to "failure 1", "suppressed" to 5), next?.fields?.toMap(), "four over the cap, then the repeat")
  }
}
