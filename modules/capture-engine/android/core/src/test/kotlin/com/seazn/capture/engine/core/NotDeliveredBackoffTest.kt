package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * S2, the owner's ruling (2026-11-24): "Back off, never stop", doubling. After a not-delivered verdict whose own link is
 * healthy, the restart waits 0, 4, 8, 16, 32, then 64 s and stays at 64, publishing meanwhile; `delivered` resets it.
 * The cause split: a link that failed lately (a regulator cut inside [SessionMachine.POOR_UPLINK_MS]) restarts at once.
 * Each verdict's line carries its number since `delivered` (`count`) and the wait (`retryMs`).
 */
class NotDeliveredBackoffTest {
  private val masterUrl = Configs.PLAYBACK_URL + "?clientBandwidthHint=0.1"
  private val masterText = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3128000\nstream_720/video.m3u8\n"

  private fun media(head: Long) =
    "#EXTM3U\n#EXT-X-TARGETDURATION:3\n#EXT-X-MEDIA-SEQUENCE:${head - 2}\n" + (head - 2..head).joinToString("") { "#EXTINF:3.000,\ns$it.ts\n" }

  /** Live with a playlist that moves only while [moving] says so; every new attempt connects at once. */
  private class Driven(val rig: MachineRig, var moving: Boolean = false) {
    var head = 100L

    fun run(ms: Long, onStep: () -> Unit = {}) =
      rig.advance(ms) {
        if (rig.phase is Phase.Connecting && (rig.phase as Phase.Connecting).step is ConnectStep.Requested) rig.send(Input.Connected(rig.attempt!!))
        onStep()
      }
  }

  private fun driven(): Driven {
    val rig = MachineRig()
    lateinit var driven: Driven
    rig.playlists = { url -> FetchResult.Body(if (url == masterUrl) masterText else media(if (driven.moving) ++driven.head else driven.head)) }
    driven = Driven(rig)
    rig.live()
    return driven
  }

  private fun MachineRig.verdicts(): List<RecordEntry> = records("not-delivered")

  /** When each verdict and each restart came, over [ms] of a playlist that never moves. */
  private fun frozenFor(driven: Driven, ms: Long): Pair<List<Long>, List<Long>> {
    val verdicts = mutableListOf<Long>()
    val restarts = mutableListOf<Long>()
    val rig = driven.rig
    driven.run(ms) {
      if (rig.verdicts().size > verdicts.size) verdicts += rig.mono
      if (rig.sent<Command.StartNewSession>().size > restarts.size) restarts += rig.mono
    }
    return verdicts to restarts
  }

  @Test
  fun `the ladder - 0, 4, 8, 16, 32, then 64 s, and it stays at 64`() {
    assertEquals(listOf(0L, 4_000, 8_000, 16_000, 32_000, 64_000, 64_000, 64_000), (1..8).map { DeliveryWatch.restartWaitMs(it) })
  }

  @Test
  fun `a healthy link's restarts back off by doubling, and the stream publishes through each wait`() {
    val driven = driven()
    val (verdicts, restarts) = frozenFor(driven, 400_000)
    val lines = driven.rig.verdicts()
    assertTrue(lines.size >= 7, "${lines.size} verdicts")
    val waits = listOf(0L, 4_000, 8_000, 16_000, 32_000, 64_000, 64_000)
    for (i in waits.indices) {
      assertEquals(i + 1, lines[i].field("count"), "verdict ${i + 1}'s count")
      assertEquals(waits[i], lines[i].field("retryMs"), "verdict ${i + 1}'s wait")
      assertEquals(verdicts[i] + waits[i], restarts[i], "restart ${i + 1} came its wait after its verdict")
    }
    assertEquals(lines.size, verdicts.size, "one line a verdict: the looks inside a wait say nothing more")
  }

  @Test
  fun `inside a wait the session publishes, shows not delivered, and has no reconnect`() {
    val driven = driven()
    val rig = driven.rig
    // Run to just after the second verdict, inside its 4 s wait.
    while (rig.verdicts().size < 2) driven.run(500)
    driven.run(2_000)
    assertEquals(1, rig.sent<Command.StartNewSession>().size, "the first restarted at once, the second waits")
    val degraded = assertIs<SnapshotState.Degraded>(rig.state)
    assertEquals(listOf(DegradeReason.NOT_DELIVERED), degraded.reasons)
  }

  @Test
  fun `delivered during a wait cancels its restart and resets the ladder`() {
    val driven = driven()
    val rig = driven.rig
    while (rig.verdicts().size < 2) driven.run(500)
    assertEquals(4_000L, rig.verdicts().last().field("retryMs"))
    driven.moving = true
    driven.run(3_500)
    assertEquals(1, rig.records("delivered").size)
    assertEquals(1, rig.sent<Command.StartNewSession>().size, "the waiting restart never went")
    driven.run(10_000)
    assertEquals(1, rig.sent<Command.StartNewSession>().size)
    driven.moving = false
    while (rig.verdicts().size < 3) driven.run(500)
    val third = rig.verdicts().last()
    assertEquals(1, third.field("count"), "counted again from delivered")
    assertEquals(0L, third.field("retryMs"))
    assertEquals(2, rig.sent<Command.StartNewSession>().size, "restarted at once")
  }

  @Test
  fun `the cause split - a link cut inside 30 s restarts at once, whatever the ladder says`() {
    val driven = driven()
    val rig = driven.rig
    while (rig.verdicts().isEmpty()) driven.run(500)
    driven.run(15_000)
    // A cut on sender drops, 4 s before the next verdict at 20 s after the first restart.
    rig.send(Input.Link(rig.attempt!!, LinkCounters(250_000, 400, 0, 3, 0, 20, 40, null)))
    assertTrue(rig.records("bitrate").isNotEmpty(), "the regulator cut")
    while (rig.verdicts().size < 2) driven.run(500)
    val second = rig.verdicts().last()
    assertEquals(2, second.field("count"))
    assertEquals(0L, second.field("retryMs"), "the own link failed lately: no wait")
    assertEquals(2, rig.sent<Command.StartNewSession>().size)
  }

  @Test
  fun `a cut longer ago than 30 s is a healthy link again - the ladder applies`() {
    assertEquals(30_000L, SessionMachine.POOR_UPLINK_MS)
    val driven = driven()
    val rig = driven.rig
    rig.send(Input.Link(rig.attempt!!, LinkCounters(250_000, 400, 0, 3, 0, 20, 40, null)))
    while (rig.verdicts().size < 2) driven.run(500)
    assertEquals(4_000L, rig.verdicts().last().field("retryMs"), "the cut was over 30 s before the second verdict")
  }
}
