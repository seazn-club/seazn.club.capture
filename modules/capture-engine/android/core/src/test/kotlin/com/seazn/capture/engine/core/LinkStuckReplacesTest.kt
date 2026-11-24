package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review of batch 2, items 1 and 2. A stuck replace opens a new hold once frames clear the outage, and the target stays
 * at the floor, so a link that cannot carry the floor is stuck again after every reconnect: the replaces back off after
 * two in a row. A picture the playlist still delivers is never replaced. A replace starts the delivery watch's ingest
 * session again, so the old attempt's stall is never judged against the new one.
 */
class LinkStuckReplacesTest {
  private val floor = Encode.FLOOR_BPS
  private val masterUrl = Configs.PLAYBACK_URL + "?clientBandwidthHint=0.1"
  private val masterText = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3128000\nstream_720/video.m3u8\n"

  private fun media(head: Long) =
    "#EXTM3U\n#EXT-X-TARGETDURATION:3\n#EXT-X-MEDIA-SEQUENCE:${head - 2}\n" + (head - 2..head).joinToString("") { "#EXTINF:3.000,\ns$it.ts\n" }

  /** Live at the floor, the link's buffer [bufferAt] each second, the playlist moving while [moving] says so. */
  private class Driven(val rig: MachineRig) {
    var moving = false
    var buffer = 40
    var head = 100L
    val replacedAt = mutableListOf<Long>()

    fun run(ms: Long) =
      rig.advance(ms) {
        val connecting = rig.phase as? Phase.Connecting
        if (connecting?.step is ConnectStep.Requested) rig.send(Input.Connected(rig.attempt!!))
        // The stuck replaces only: a not-delivered restart is a new ingest session too.
        if (rig.records("link-stuck").size > replacedAt.size) replacedAt += rig.mono
      }
  }

  private fun driven(): Driven {
    val rig = MachineRig()
    lateinit var driven: Driven
    var bytes = 0L
    rig.link = { t ->
      bytes += 90_000
      LinkCounters(bytes, t, 0, 0, 0, 20, driven.buffer, null)
    }
    rig.playlists = { url -> FetchResult.Body(if (url == masterUrl) masterText else media(if (driven.moving) ++driven.head else driven.head)) }
    driven = Driven(rig)
    rig.live()
    rig.phase = (rig.phase as Phase.OnAir).let { it.copy(session = it.session.copy(regulation = Regulation(floor))) }
    return driven
  }

  @Test
  fun `the numbers - two at once, then 15, 30, 60 and 120 s, clear after 30 s, stuck only past 6 s undelivered`() {
    assertEquals(listOf(0L, 0, 15_000, 30_000, 60_000, 120_000, 120_000), (1..7).map { StuckReplaces.waitMs(it) })
    assertEquals(30_000L, StuckReplaces.CLEAR_MS)
    assertEquals(6_000L, StuckReplaces.STUCK_UNDELIVERED_MS)
  }

  @Test
  fun `a link stuck after every reconnect replaces twice at once, then backs off`() {
    val driven = driven()
    driven.buffer = 1_900
    driven.run(120_000)
    val gaps = driven.replacedAt.zipWithNext { a, b -> b - a }
    assertTrue(driven.replacedAt.size >= 3, "${driven.replacedAt}")
    assertTrue(gaps[0] < 15_000, "the second at the next stuck verdict: $gaps")
    assertTrue(gaps[1] >= 15_000, "the third waits 15 s from the second: $gaps")
    if (gaps.size > 2) assertTrue(gaps[2] >= 30_000, "the fourth 30 s: $gaps")
    assertTrue(driven.replacedAt.size <= 5, "not one every 6-10 s: ${driven.replacedAt}")
  }

  @Test
  fun `a choppy picture the playlist still delivers is never replaced`() {
    val driven = driven()
    driven.moving = true
    driven.buffer = 1_900
    driven.run(120_000)
    assertEquals(emptyList(), driven.replacedAt)
    assertTrue(driven.rig.records("link-stuck").isEmpty())
  }

  @Test
  fun `a stuck replace starts the delivery watch again - no second replace inside 20 s of the new attempt`() {
    val driven = driven()
    driven.buffer = 1_900
    while (driven.replacedAt.isEmpty()) driven.run(500)
    driven.buffer = 40
    val replaced = driven.replacedAt.single()
    val sessions = driven.rig.sent<Command.StartNewSession>().size
    driven.run(19_000)
    assertEquals(listOf(replaced), driven.replacedAt)
    assertEquals(sessions, driven.rig.sent<Command.StartNewSession>().size, "no restart in the new attempt's first 19 s: ${driven.rig.records("not-delivered").map { it.fields }}")
  }

  @Test
  fun `30 s of a link that carries the floor while the playlist moves starts the count again`() {
    val driven = driven()
    driven.buffer = 1_900
    while (driven.replacedAt.size < 2) driven.run(500)
    driven.buffer = 40
    driven.moving = true
    driven.run(31_000)
    assertEquals(0, driven.rig.session.stuckReplaces.count)
    driven.moving = false
    driven.buffer = 1_900
    val before = driven.replacedAt.size
    driven.run(20_000)
    assertTrue(driven.replacedAt.size > before, "a replace at once again")
    assertEquals(0L, driven.rig.records("link-stuck").last().field("retryMs"), "the first of a new run: the next goes at once too")
  }

  /** [StuckReplaces.read] over readings a second apart from [from] to [to]: [stuckAt] are stuck, all [advancing]. */
  private fun StuckReplaces.reading(from: Long, to: Long, advancing: Boolean, stuckAt: Set<Long> = emptySet()): StuckReplaces =
    (from..to step 1_000).fold(this) { replaces, t -> replaces.read(t in stuckAt, advancing, t) }

  @Test
  fun `the count starts again only after 30 s of readings not stuck, with the playlist moving`() {
    val two = StuckReplaces().replaced(0).replaced(1_000)
    assertEquals(2, two.reading(2_000, 31_000, advancing = true).count, "29 s")
    assertEquals(0, two.reading(2_000, 32_000, advancing = true).count, "30 s")
    assertEquals(2, two.reading(2_000, 60_000, advancing = false).count, "a playlist that does not move")
    val broken = two.reading(2_000, 50_000, advancing = true, stuckAt = setOf(20_000))
    assertEquals(2, broken.count, "a stuck reading at 20 s starts the 30 s again: 21 to 50 is 29 s")
    assertEquals(0, broken.reading(51_000, 51_000, advancing = true).count)
  }
}
