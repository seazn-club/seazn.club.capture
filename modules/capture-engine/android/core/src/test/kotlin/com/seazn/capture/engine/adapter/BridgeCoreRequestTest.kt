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
 * [BridgeCore]: every POST answered once, the backstop, and the beats' fields.
 */
class BridgeCoreRequestTest : BridgeCoreTestKit() {
  @Test
  fun `C0b every POST start is answered - a throw at the send, or no answer at all, frees the next hold once no beat armed it`() {
    core.start()
    core.report(Input.Network(true))
    ready()
    pair()
    platform.throwOnHttp = { if (it.url.endsWith("/start")) java.io.IOException("reset") else null }
    core.send(mapOf("kind" to "start"))
    scheduler.advanceBy(Engine.TICK_MS)
    // N48 (owner, 2026-11-23, "Pending until beat"): a throw at the send is no answer, pending until a beat or 20 s.
    assertEquals(null, core.current()["refusal"], "a throw at the send is pending, not yet failed")
    scheduler.advanceBy(StartAsks.START_CONFIRM_MS)
    assertEquals("start-failed", core.current()["refusal"], "no beat armed it in 20 s: the start's failure")
    platform.throwOnHttp = { null }
    platform.unanswered = { it.url.endsWith("/start") }
    val pendingBefore = lines().count { it.getString("kind") == "start-pending" }
    core.send(mapOf("kind" to "start"))
    scheduler.advanceBy(0)
    core.send(mapOf("kind" to "start"))
    // Review C4a I-4: the spec's 16 s, typed here: a backstop of ten minutes would hold every start that long.
    scheduler.advanceBy(BACKSTOP_MS - 1)
    assertEquals(2, platform.http.count { it.url.endsWith("/start") }, "a start in flight holds the next")
    assertEquals(pendingBefore, lines().count { it.getString("kind") == "start-pending" }, "no answer yet before the backstop")
    scheduler.advanceBy(1)
    // Only the backstop's no-answer makes a start pending, so its line pins the 16 s.
    assertEquals(pendingBefore + 1, lines().count { it.getString("kind") == "start-pending" }, "the backstop answered at 16 s")
    core.send(mapOf("kind" to "start"))
    scheduler.advanceBy(0)
    assertEquals(2, platform.http.count { it.url.endsWith("/start") }, "the backstop's no-answer is pending a beat, and holds the next")
    scheduler.advanceBy(StartAsks.START_CONFIRM_MS - BACKSTOP_MS)
    core.send(mapOf("kind" to "start"))
    scheduler.advanceBy(0)
    assertEquals(3, platform.http.count { it.url.endsWith("/start") }, "20 s after its ask the pending is the failure, so the hold is free")
  }

  @Test
  fun `C0b an answer is given once - the platform's own, after the backstop answered, never reaches the engine`() {
    core.start()
    core.report(Input.Network(true))
    ready()
    pair()
    platform.unanswered = { it.url.endsWith("/start") }
    core.send(mapOf("kind" to "start"))
    scheduler.advanceBy(BACKSTOP_MS)
    val (request, late) = platform.owed.single()
    late(PairWire.answer(request))
    scheduler.advanceBy(Engine.TICK_MS)
    val answered = lines().filter { it.getString("kind") == "start-answered" }
    assertEquals(1, answered.size, "the backstop's answer only: $answered")
    assertFalse(answered.single().has("late"), "the late open answer would arm a broadcast the operator gave up on")
    assertEquals("paired", state())
  }

  @Test
  fun `C0b the backstop sits past the client's timeout and every core give-up, so a real answer comes first`() {
    // HttpRequests.TIMEOUT_MS is the client's; Heartbeat.TIMEOUT_MS and DeliveryWatch's are the core's.
    // Review C4a I-4: twice the client's 8 s, as a literal, so the bound has a top as well as a bottom.
    assertEquals(BACKSTOP_MS, BridgeCore.ANSWER_BACKSTOP_MS)
    assertTrue(BACKSTOP_MS > HttpRequests.TIMEOUT_MS)
    assertTrue(BACKSTOP_MS > Heartbeat.TIMEOUT_MS)
    assertTrue(BACKSTOP_MS > DeliveryWatch.REQUEST_TIMEOUT_MS)
  }

  /** Review C4a m-2: the build runs inside the guard, so a request that cannot be built is still answered, once. */
  @Test
  fun `m-2 a request whose build throws is answered once, as a failure, and never sent`() {
    core.start()
    val answers = mutableListOf<HttpOutcome>()
    core.ask({ throw IllegalStateException("build") }) { answers += it }
    scheduler.advanceBy(BACKSTOP_MS)
    assertEquals(listOf<HttpOutcome>(HttpOutcome.Failed("IllegalStateException")), answers, "the throw is the answer; the backstop's is dropped")
    assertEquals(emptyList(), platform.http)
  }

  @Test
  fun `C0b the tick runs on after the end until the final beat releases the service`() {
    live()
    platform.unanswered = { it.url.endsWith("/beats") }
    core.send(mapOf("kind" to "stop"))
    scheduler.advanceBy(Heartbeat.TIMEOUT_MS - Engine.TICK_MS)
    assertEquals("ended", state())
    assertTrue(Command.Release !in platform.executed, "the final beat holds the service")
    scheduler.advanceBy(Engine.TICK_MS)
    assertEquals(1, platform.executed.count { it == Command.Release })
    assertEquals("timeout", lines().single { it.getString("kind") == "final-beat" }.getString("result"))
    scheduler.advanceBy(BACKSTOP_MS)
    assertEquals(1, platform.executed.count { it == Command.Release }, "the backstop's late answer releases nothing twice")
  }

  @Test
  fun `C0b a paired beat carries battery null until a device sample`() {
    core.start()
    pair()
    val beat = JSONObject(platform.beats().single().body!!)
    assertTrue(beat.has("battery") && beat.isNull("battery"), "$beat")
  }

  /**
   * Review C4a m-4: a beat's answer names no zone (capture-beat.v1), so the zone the code's answer gave
   * the broadcast is the one every later beat keeps (WaitingFields.over). The pair itself named none.
   */
  @Test
  fun `m-4 the zone the code's answer gave is kept through every zone-less beat`() {
    live()
    platform.answer = PairWire::answerLive
    val beats = platform.beats().size
    scheduler.advanceBy(60_000)
    assertTrue(platform.beats().size - beats >= 5, "beats answered with no zone: ${platform.beats().size - beats}")
    assertTrue("venueTimezone" !in PairWire.beat("live", PairWire.SID), "the beats answered name no zone")
    assertEquals("Europe/London", (core.current()["pairing"] as Map<*, *>)["venueTimezone"])
  }

  @Test
  fun `G4 a connect failure the machine ignores is recorded as an ignored input`() {
    live()
    core.report(Input.ConnectFailed(platform.attempt, ConnectFailure.OTHER, "late"))
    scheduler.advanceBy(0)
    val ignored = lines().single { it.getString("kind") == "connect-failed-ignored" }
    assertEquals(listOf(platform.attempt, "other", "on-air"), listOf(ignored.getInt("attempt"), ignored.getString("failure"), ignored.getString("phase")))
    assertEquals("publishing", state())
  }
}
