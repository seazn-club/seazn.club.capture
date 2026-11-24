package com.seazn.capture.engine.adapter

import com.seazn.capture.engine.core.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Review of batch 2, item 3: each facts read takes the meter's peak and resets it (`PeakMeter.take`), and S8 sends
 * every other tick's snapshot while nothing shown changes. The permanent meter (AGENTS §6) must still see every peak: one
 * read on a tick that is not sent rides on the next send.
 */
class BridgeCoreMeterTest {
  private val clock = FakeClock()
  private val scheduler = FakeScheduler(clock)
  private val platform = RecordingPlatform()
  private val core = BridgeCore(clock, scheduler, platform, MemoryStore(), "1.0.0", "Test Phone") { Configs.PHONE_ID }.also { platform.core = it }

  @AfterTest fun forgetShared() = BridgeCore.resetShared()

  private fun level(wire: Map<String, Any?>) = (wire["telemetry"] as Map<*, *>)["audioLevel"]

  @Test
  fun `a peak read on a tick that is not sent reaches the next sent snapshot, and the one after starts again`() {
    core.start()
    core.report(Input.Network(true))
    core.send(PairWire.valid())
    scheduler.advanceBy(2_000)
    // Find the phase of the once-a-second sends: a tick that sends, then one that does not.
    var before = platform.snapshots.size
    scheduler.advanceBy(Engine.TICK_MS)
    if (platform.snapshots.size == before) scheduler.advanceBy(Engine.TICK_MS)
    before = platform.snapshots.size
    // The skipped tick reads 0.9; the next, sent, reads 0.1.
    platform.audioLevels.addAll(listOf(0.9, 0.1))
    scheduler.advanceBy(Engine.TICK_MS)
    assertEquals(before, platform.snapshots.size, "this tick is not sent")
    scheduler.advanceBy(Engine.TICK_MS)
    assertEquals(before + 1, platform.snapshots.size)
    assertEquals(0.9, level(platform.snapshots.last()), "the skipped tick's peak")
    platform.audioLevels.addAll(listOf(0.2, 0.3))
    scheduler.advanceBy(2 * Engine.TICK_MS)
    assertEquals(0.3, level(platform.snapshots.last()), "the held peak went with its send")
  }
}
