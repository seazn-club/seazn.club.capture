package com.seazn.capture.engine.adapter

import com.seazn.capture.engine.core.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * The Realtime grant lives in memory only (design 2026-11-24 §2.2): nothing the store keeps names it, so a process
 * death starts with no grant and the first beat brings one. That is what lets the cold-start test in `NudgesTest`
 * model the restart with a fresh machine.
 */
class PairingStoreGrantTest {
  private val clock = FakeClock()
  private val scheduler = FakeScheduler(clock)
  private val store = MemoryStore()

  private val token = "made-up.realtime-token.0001"
  private val apikey = "anon.example-key_0123456789"
  private val topic = "capture:${PairWire.SID}"

  private fun granted(beat: String): String =
    JSONObject(beat)
      .put(
        "realtime",
        JSONObject()
          .put("url", "https://example-ref.supabase.co")
          .put("apikey", apikey)
          .put("topic", topic)
          .put("token", token)
          .put("expiresAt", clock.wall / 1_000 + 1_800),
      )
      .toString()

  @AfterTest fun forgetShared() = BridgeCore.resetShared()

  @Test
  fun `a held grant is never written to the store - on air, at Stop, or at Forget`() {
    val platform = RecordingPlatform()
    platform.answer = { request ->
      val plain = PairWire.answerLive(request)
      if (request.url.endsWith("/beats") && plain is HttpOutcome.Answered) HttpOutcome.Answered(plain.status, granted(plain.body)) else plain
    }
    val core = BridgeCore(clock, scheduler, platform, store, "1.0.0", "Test Phone") { Configs.PHONE_ID }.also { platform.core = it }
    core.start()
    core.report(Input.Network(true))
    core.readiness(cameraFrames = true, audioPeak = 0.4, pose = Pose.Held(Side.LEFT))
    scheduler.advanceBy(Engine.TICK_MS)
    core.readiness(audioPeak = 0.4)
    core.send(PairWire.valid())
    scheduler.advanceBy(0)
    core.send(mapOf("kind" to "start"))
    scheduler.advanceBy(30_000)
    assertTrue(platform.executed.any { it is Command.Nudges && it.grant.token == token }, "the grant was held")
    core.send(mapOf("kind" to "stop"))
    scheduler.advanceBy(1_000)
    core.send(PairWire.valid())
    scheduler.advanceBy(1_000)
    core.forget()
    scheduler.advanceBy(1_000)

    assertTrue(store.writes > 0, "the store was written")
    val leaks = store.files.filterValues { text -> listOf(token, apikey, topic, "realtime").any { it in text } }
    assertEquals(emptyMap(), leaks)
  }
}
