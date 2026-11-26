package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R-1PG4 (stg, 2026-11-26): the plate turned red (PR #78's latch, `viewersConfirmed.ts`) and no line said which fact did
 * it. Native now writes `viewers-confirmed {sid, source}` once per broadcast, the first time the latch's own rule holds:
 * on air (publishing or degraded) and either the playlist check reads delivery ok (`source: playlist`) or the server's
 * programme phase is live (`source: beat`).
 */
class ViewersConfirmedLineTest {
  private val masterUrl = Configs.PLAYBACK_URL + "?clientBandwidthHint=0.1"
  private val masterText = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3128000\nstream_720/video.m3u8\n"

  private fun media(head: Long) =
    "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:${head - 2}\n" + (head - 2..head).joinToString("") { "#EXTINF:2.000,\ns$it.ts\n" }

  private fun MachineRig.confirmed(): List<Pair<Any?, Any?>> = records("viewers-confirmed").map { it.field("sid") to it.field("source") }

  /** Playlists that advance on every fetch. */
  private fun MachineRig.advancing(): MachineRig {
    var head = 100L
    playlists = { url -> FetchResult.Body(if (url == masterUrl) masterText else media(++head)) }
    return this
  }

  @Test
  fun `the playlist check seeing delivery confirms it, once, naming the playlist`() {
    val rig = MachineRig().advancing().live()
    rig.advance(10_000)
    assertEquals(Delivery.OK, rig.snapshot.delivery)
    assertEquals(listOf<Pair<Any?, Any?>>("sess_42" to "playlist"), rig.confirmed())
    rig.advance(10_000)
    assertEquals(1, rig.confirmed().size, "once per broadcast")
  }

  @Test
  fun `the beat's programme live confirms it, once, naming the beat`() {
    val rig = MachineRig().live()
    rig.hear("live", sid = "sess_42", delivery = ServerDelivery(programmePhase = "live"))
    assertEquals(listOf<Pair<Any?, Any?>>("sess_42" to "beat"), rig.confirmed())
    rig.hear("live", sid = "sess_42", delivery = ServerDelivery(programmePhase = "live"))
    assertEquals(1, rig.confirmed().size)
  }

  @Test
  fun `the first of the two names it, and the other later writes nothing`() {
    val rig = MachineRig().live()
    rig.hear("live", sid = "sess_42", delivery = ServerDelivery(programmePhase = "live"))
    rig.advancing()
    rig.advance(10_000)
    assertEquals(listOf<Pair<Any?, Any?>>("sess_42" to "beat"), rig.confirmed())
  }

  @Test
  fun `a programme phase that is not live, and no delivery, confirm nothing`() {
    val rig = MachineRig().live()
    rig.hear("live", sid = "sess_42", delivery = ServerDelivery(programmePhase = "waiting"))
    rig.advance(5_000)
    assertTrue(rig.confirmed().isEmpty())
  }

  @Test
  fun `off air nothing confirms - programme live heard while reconnecting waits for the picture`() {
    val rig = MachineRig().live()
    rig.send(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, null))
    rig.hear("live", sid = "sess_42", delivery = ServerDelivery(programmePhase = "live"))
    assertTrue(rig.confirmed().isEmpty(), "reconnecting: ${rig.state}")
    rig.advance(2_000)
    rig.send(Input.Connected(2))
    rig.advance(1_000)
    assertEquals(listOf<Pair<Any?, Any?>>("sess_42" to "beat"), rig.confirmed())
  }

  @Test
  fun `a drop and a resume after confirmation write nothing more`() {
    val rig = MachineRig().live()
    rig.hear("live", sid = "sess_42", delivery = ServerDelivery(programmePhase = "live"))
    rig.send(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, null))
    rig.advance(2_000)
    rig.send(Input.Connected(2))
    rig.advance(1_000)
    assertEquals(1, rig.confirmed().size)
  }
}
