package com.seazn.capture.engine.adapter

import com.seazn.capture.engine.core.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * S7: the facts read runs at every publish and guarded `Exception` only, so a `LinkageError` from an OEM's API there (a
 * `NoSuchMethodError` from a battery or thermal read) reached the task boundary, where [Failures.permanent] held it
 * against the process and the pairing ended fatal-error, on air included. A facts read is no part of the capture stack:
 * it now stands on the last good facts and counts it, as any other facts failure does.
 */
class BridgeCoreFactsLinkageTest {
  private val clock = FakeClock()
  private val scheduler = FakeScheduler(clock)
  private val platform = RecordingPlatform()
  private val core = BridgeCore(clock, scheduler, platform, MemoryStore(), "1.0.0", "Test Phone") { Configs.PHONE_ID }.also { platform.core = it }

  @AfterTest fun forgetShared() = BridgeCore.resetShared()

  private fun lines(kind: String) = platform.lines.map(::JSONObject).filter { it.getString("kind") == kind }

  @Test
  fun `a facts read that throws NoSuchMethodError leaves the pairing standing, with one line, and counts it`() {
    core.start()
    core.report(Input.Network(true))
    core.send(PairWire.valid())
    scheduler.advanceBy(0)
    platform.throwOnFacts = NoSuchMethodError("android.os.BatteryManager.getSomething")
    scheduler.advanceBy(10 * Engine.TICK_MS)
    assertEquals("paired", (core.current()["state"] as Map<*, *>)["kind"])
    assertTrue(lines("ended").isEmpty(), "nothing ended")
    assertEquals(listOf("facts: NoSuchMethodError: android.os.BatteryManager.getSomething"), lines("engine-error").map { it.getString("message") })
    val counted = (core.current()["telemetry"] as Map<*, *>)["snapshotFailures"] as Double
    assertTrue(counted >= 10, "each read counted: $counted")
    assertEquals(0.0, (core.current()["telemetry"] as Map<*, *>)["taskFailures"], "never the task boundary's")
  }
}
