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
 * The engine host every platform uses, driven through the real engine, the real mapping and the
 * real bodies, on the core's fake clock and scheduler. [RecordingPlatform] plays a phone that grants
 * and connects, and [PairWire]'s server.
 *
 * The fixtures the `BridgeCore*Test` files share (S11: one class split by concern, each under the
 * 1500-line rule). Each test class extends it, so each test gets its own engine, clock and store.
 */
abstract class BridgeCoreTestKit {
  protected val clock = FakeClock()
  protected val scheduler = FakeScheduler(clock)
  protected val store = MemoryStore()
  protected val platform = RecordingPlatform()
  protected val core = build(platform)

  protected fun build(on: RecordingPlatform, over: PrivateStore = store, scheduler: Scheduler = this.scheduler, newId: () -> String = { Configs.PHONE_ID }) =
    BridgeCore(clock, scheduler, on, over, "1.0.0", "Test Phone", newId).also { on.core = it }

  protected fun state(of: BridgeCore = core) = (of.current()["state"] as Map<*, *>)["kind"]

  protected fun telemetry(of: BridgeCore = core) = of.current()["telemetry"] as Map<*, *>

  protected fun lines(of: RecordingPlatform = platform) = of.lines.map(::JSONObject)

  protected fun kinds(of: RecordingPlatform = platform) = lines(of).map { it.getString("kind") }

  /** The tail's numbered lines (N38: beside its `pins`). */
  @Suppress("UNCHECKED_CAST")
  protected fun tailLines(of: BridgeCore = core) = of.tail()["lines"] as List<Map<String, Any?>>

  protected fun readyOn(of: BridgeCore) = (of.current()["ready"] as Map<*, *>).mapKeys { it.key as String }

  /**
   * Ready on every check. Sound is ready at its first reading, whatever its level (spec §4); the second, a
   * tick on, is the glue's next meter reading, kept so the tests stay on the tick they were written on.
   */
  protected fun ready(of: BridgeCore = core) {
    of.readiness(cameraFrames = true, audioPeak = 0.4, pose = Pose.Held(Side.LEFT))
    scheduler.advanceBy(Engine.TICK_MS)
    of.readiness(audioPeak = 0.4)
  }

  protected fun pair(of: BridgeCore = core, wire: Map<String, Any?> = PairWire.valid()) {
    of.send(wire)
    scheduler.advanceBy(0)
  }

  protected fun live() {
    core.start()
    core.report(Input.Network(true))
    ready()
    pair()
    core.send(mapOf("kind" to "start"))
    scheduler.advanceBy(1_500)
  }

  @AfterTest
  fun forgetShared() = BridgeCore.resetShared()

  protected fun checksOf(line: JSONObject) = listOf("camera", "sound", "held").associateWith(line::getBoolean)

  protected fun machineChecks() = readyOn(core).filterKeys { it != "network" }

  /** The camera's frames, read 300 ms into a second and held to its end: a turn once a second. */
  protected fun turnCamera(frames: Boolean) {
    scheduler.advanceBy(TURN_AT_MS)
    core.readiness(cameraFrames = frames)
    scheduler.advanceBy(1_000 - TURN_AT_MS)
  }

  protected companion object {
    /** Where in each second [turnCamera] reads: the 300 ms of the hand count the six-a-minute tests were written to. */
    const val TURN_AT_MS = 300L

    /** C0b's backstop, twice the client's 8 s timeout (review C4a I-4): a literal, never `BridgeCore`'s constant. */
    const val BACKSTOP_MS = 16_000L
  }
}
