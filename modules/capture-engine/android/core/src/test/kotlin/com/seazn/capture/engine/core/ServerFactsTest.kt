package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * T46 (design 2026-11-24 §3): the relay's and Cloudflare's last word on the held broadcast. Held while a broadcast is,
 * replaced by each 2xx and cleared by one without them, a `server-delivery` line for each word that changed, and never
 * the phone's own truth (DeliveryWatch's `delivery` stays as it was).
 */
class ServerFactsTest {
  private val all = ServerDelivery("connected", "live", 30, 2_800, "connected", "connected")

  private fun lines(rig: MachineRig) = rig.records("server-delivery").map { it.field("source") to it.field("state") }

  @Test
  fun `a live answer's facts reach the snapshot with when they were heard, and each known word is one line`() {
    val rig = MachineRig().live()
    rig.hear("live", sid = "sess_42", delivery = all)
    assertEquals(ServerFacts(all, rig.wall), rig.snapshot.server)
    assertEquals(listOf<Pair<Any?, Any?>>("relay" to "connected", "programme" to "live", "camera-ingest" to "connected", "programme-ingest" to "connected"), lines(rig))
  }

  @Test
  fun `the same words again write nothing, a changed one writes its line, and fps or kbps alone write none`() {
    val rig = MachineRig().live()
    rig.hear("live", sid = "sess_42", delivery = all)
    rig.hear("live", sid = "sess_42", delivery = all.copy(fps = 25, kbps = 1_900))
    assertEquals(4, lines(rig).size)
    assertEquals(25L, rig.snapshot.server?.delivery?.fps)
    rig.hear("live", sid = "sess_42", delivery = all.copy(programmePhase = "frozen"))
    assertEquals("programme" to "frozen", lines(rig).last())
    assertEquals(5, lines(rig).size)
  }

  @Test
  fun `an answer without them clears them, and the words known before read unknown`() {
    val rig = MachineRig().live()
    rig.hear("live", sid = "sess_42", delivery = ServerDelivery(cameraIngest = "connected"))
    rig.hear("live", sid = "sess_42")
    assertNull(rig.snapshot.server)
    assertEquals(listOf<Pair<Any?, Any?>>("camera-ingest" to "connected", "camera-ingest" to "unknown"), lines(rig))
  }

  @Test
  fun `a failed beat keeps them`() {
    val rig = MachineRig().live()
    rig.hear("live", sid = "sess_42", delivery = all)
    rig.hear(null, status = 503)
    assertEquals(all, rig.snapshot.server?.delivery)
  }

  @Test
  fun `off air they go, with no line - the broadcast's end, and a go-live heard while Paired holds none`() {
    val rig = MachineRig().live()
    rig.hear("live", sid = "sess_42", delivery = all)
    val before = lines(rig).size
    rig.hear("over", sid = "sess_42", endReason = "stopped")
    assertIs<Phase.Paired>(rig.phase)
    assertNull(rig.snapshot.server)
    assertNull(rig.phase.pairing?.server, "not kept in the pairing for the next broadcast")
    assertEquals(before, lines(rig).size)

    val paired = MachineRig().paired()
    paired.hear("go-live", sid = "sess_42", startedBy = "organiser", delivery = all)
    // The code answers at once, so the go-live heard in Paired has armed a broadcast that holds none of its facts.
    assertIs<Phase.Armed>(paired.phase)
    assertNull(paired.snapshot.server)
    assertEquals(emptyList(), lines(paired))
  }

  @Test
  fun `the phone's own delivery word is untouched by the server's`() {
    val rig = MachineRig().live()
    val own = rig.snapshot.delivery
    rig.hear("live", sid = "sess_42", delivery = all.copy(programmePhase = "frozen", cameraIngest = "errored"))
    assertEquals(own, rig.snapshot.delivery)
  }

  @Test
  fun `the facts never decide anything - the phase and the state are what they were without them`() {
    val with = MachineRig().live()
    val without = MachineRig().live()
    with.hear("live", sid = "sess_42", delivery = all.copy(programmePhase = "final", relayOutput = "backoff", cameraIngest = "disconnected"))
    without.hear("live", sid = "sess_42")
    assertEquals(without.state, with.state)
    assertEquals(without.phase.name, with.phase.name)
  }
}
