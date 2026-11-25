package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Start drain retry (spec 2026-11-25-start-drain-retry): a `POST start` that meets the phone's OWN session still ending
 * gets 503 `unavailable` with a Retry-After of 1 to 20 s (web #1301). The owner's ruling (2026-11-25): "Retry once by
 * itself". The phone stays Paired saying "Getting ready — going live shortly", asks again once after the Retry-After
 * and [StartAsks.RETRY_MARGIN_MS], re-checking readiness then (A11), and a second refusal is the start failure.
 */
class StartDrainTest {
  private val drain = StartAnswer.Draining(5)

  /** Five seconds and the margin: when the one retry is due after a Draining(5) landed. */
  private val wait = 5_000 + StartAsks.RETRY_MARGIN_MS

  /** The longest wait the contract allows, so a beat (every 10 s here) lands inside it. */
  private val longDrain = StartAnswer.Draining(20)

  private fun ready(): MachineRig {
    val rig = MachineRig().paired()
    rig.hear("waiting")
    rig.ready()
    return rig
  }

  /** Each `POST start` answered in turn by [answers]; past their end a post is left unanswered. */
  private fun MachineRig.answering(vararg answers: StartAnswer): MachineRig {
    val queue = ArrayDeque(answers.toList())
    starts = { queue.removeFirstOrNull() }
    return this
  }

  private fun posts(rig: MachineRig) = rig.sent<Command.PostStart>()

  private fun pairing(rig: MachineRig) = assertIs<Phase.Paired>(rig.phase).pairing

  /** The operator's hold, answered Draining at once. Returns when it landed. */
  private fun drained(rig: MachineRig): Long {
    rig.send(Input.Start)
    return rig.mono
  }

  @Test fun `a draining answer stays Paired, says getting ready, not starting, and records the wait`() {
    val rig = ready().answering(drain)
    drained(rig)
    assertEquals(SnapshotState.Paired, rig.state)
    assertEquals(Refusal.DRAINING, rig.snapshot.refusal)
    assertEquals(false, rig.snapshot.pairing?.starting, "the hold is free: a second hold is a fresh start")
    assertNull(pairing(rig).startFailed, "no failure rides a beat")
    assertEquals("Draining", rig.records("start-answered").single().field("answer"))
    assertEquals(5, rig.records("start-draining").single().field("retryAfterS"))
    assertEquals(1, posts(rig).size)
  }

  @Test fun `the one retry goes at Retry-After plus the margin, not a tick before, with a new request id`() {
    val rig = ready().answering(drain)
    val at = drained(rig)
    rig.at(at + wait - 1)
    rig.send(Input.Tick)
    assertEquals(1, posts(rig).size, "not before")
    rig.at(at + wait)
    rig.send(Input.Tick)
    assertEquals(2, posts(rig).size, "at the deadline")
    val (first, retry) = posts(rig)
    assertTrue(retry.requestId != first.requestId, "a new request id")
    assertEquals(first.url, retry.url)
    assertEquals(first.body, retry.body)
    assertEquals(1, rig.records("start-retry").size)
    assertNull(rig.records("start-retry").single().fields.firstOrNull { it.first == "notReady" }?.second)
    assertEquals(true, rig.snapshot.pairing?.starting, "the retry in flight says what a hold's does")
    assertNull(rig.snapshot.refusal)
  }

  @Test fun `the retry answered Started arms the broadcast as the operator's`() {
    val rig = ready().answering(drain, StartAnswer.Started("sess_42"))
    drained(rig)
    rig.advance(wait.toLong())
    assertEquals("sess_42", rig.phase.heldSid)
    assertEquals(Cause.OPERATOR, rig.session.cause)
  }

  @Test fun `a second draining on the retry is the start failure, and nothing asks again`() {
    val rig = ready().answering(drain, StartAnswer.Draining(3))
    drained(rig)
    rig.advance(wait.toLong())
    assertEquals(2, posts(rig).size)
    assertEquals(Refusal.START_FAILED, rig.snapshot.refusal)
    assertEquals(StartFailure.START_ERROR, pairing(rig).startFailed)
    assertEquals(1, rig.records("start-draining").size, "only the wait that was kept is recorded as one")
    rig.advance(60_000)
    assertEquals(2, posts(rig).size, "retried once, never a loop")
  }

  @Test fun `any failure on the retry is the start failure`() {
    val rig = ready().answering(drain, StartAnswer.Failed("500"))
    drained(rig)
    rig.advance(wait.toLong())
    assertEquals(Refusal.START_FAILED, rig.snapshot.refusal)
    assertEquals(StartFailure.START_ERROR, pairing(rig).startFailed)
    rig.advance(60_000)
    assertEquals(2, posts(rig).size)
  }

  @Test fun `the retry with no answer is pending as any start is (N48), and never draining again`() {
    val rig = ready().answering(drain, StartAnswer.Failed(StartAnswer.Failed.NO_ANSWER + "SocketTimeoutException"))
    drained(rig)
    rig.advance(wait.toLong())
    assertEquals(true, rig.snapshot.pairing?.starting)
    assertEquals(1, rig.records("start-pending").size)
    rig.advance(StartAsks.START_CONFIRM_MS)
    assertEquals(Refusal.START_FAILED, rig.snapshot.refusal)
    assertEquals(2, posts(rig).size)
  }

  @Test fun `a second hold during the wait is a fresh start - one post at once, and none at the old deadline`() {
    val rig = ready().answering(drain)
    val at = drained(rig)
    rig.at(at + 2_000)
    rig.send(Input.Start)
    assertEquals(2, posts(rig).size, "the hold posts at once")
    assertTrue(rig.records("intent-ignored").isEmpty())
    assertEquals(true, rig.snapshot.pairing?.starting)
    rig.at(at + wait)
    rig.send(Input.Tick)
    rig.advance(30_000)
    assertEquals(2, posts(rig).size, "the old retry went with the fresh start")
    assertTrue(rig.records("start-retry").isEmpty())
  }

  @Test fun `a fresh hold after a drain that failed has its own one retry`() {
    val rig = ready().answering(drain, drain, drain)
    drained(rig)
    rig.advance(wait.toLong())
    assertEquals(Refusal.START_FAILED, rig.snapshot.refusal)
    rig.send(Input.Start)
    assertEquals(3, posts(rig).size)
    assertEquals(Refusal.DRAINING, rig.snapshot.refusal, "the fresh hold waits once again")
    rig.advance(wait.toLong())
    assertEquals(4, posts(rig).size)
  }

  @Test fun `a beat that arms during the wait ends it - no retry, here or after the broadcast`() {
    val rig = ready().answering(longDrain)
    val at = drained(rig)
    rig.hear("go-live", sid = "sess_42", startedBy = "organiser")
    assertTrue(rig.mono < at + 20_000, "the beat came inside the wait")
    assertEquals("sess_42", rig.phase.heldSid)
    assertNull(rig.phase.pairing!!.starts.retryAtMs)
    assertNull(rig.snapshot.refusal, "arming clears the getting-ready line")
    rig.hear("waiting")
    assertIs<Phase.Paired>(rig.phase)
    rig.at(maxOf(rig.mono, at + 20_500))
    rig.advance(30_000)
    assertEquals(1, posts(rig).size)
    assertTrue(rig.records("start-retry").isEmpty())
  }

  @Test fun `an unpair during the wait is accepted, not ignored, and nothing asks after`() {
    val rig = ready().answering(drain)
    val at = drained(rig)
    rig.send(Input.Unpair)
    assertIs<Phase.Idle>(rig.phase, "the wait is not a start in flight")
    rig.at(at + wait)
    rig.advance(30_000)
    assertEquals(1, posts(rig).size)
    assertTrue(rig.records("start-retry").isEmpty())
  }

  @Test fun `the notification's Forget during the wait is accepted, and nothing asks after`() {
    val rig = ready().answering(drain)
    val at = drained(rig)
    rig.send(Input.Forget)
    assertIs<Phase.Idle>(rig.phase)
    assertEquals(1, rig.sent<Command.WriteForgotten>().size)
    rig.at(at + wait)
    rig.advance(30_000)
    assertEquals(1, posts(rig).size)
  }

  @Test fun `a beat that ends the pairing during the wait leaves no retry`() {
    for ((state, status) in listOf("replaced" to 200, "code-ended" to 401)) {
      val rig = ready().answering(longDrain)
      val at = drained(rig)
      rig.hear(state.takeIf { status == 200 }, status = status)
      assertIs<Phase.Idle>(rig.phase, state)
      assertTrue(rig.mono < at + 20_000, "the beat came inside the wait")
      rig.at(maxOf(rig.mono, at + 20_500))
      rig.advance(30_000)
      assertEquals(1, posts(rig).size, state)
    }
  }

  @Test fun `not ready when the retry falls due - no start (A11), the start failure, and the record names why`() {
    val rig = ready().answering(drain)
    val at = drained(rig)
    rig.ready(Configs.READY.copy(micAvailable = false))
    rig.at(at + wait)
    rig.send(Input.Tick)
    assertEquals(1, posts(rig).size, "no start while not ready")
    assertEquals(Refusal.START_FAILED, rig.snapshot.refusal)
    assertEquals(StartFailure.START_ERROR, pairing(rig).startFailed)
    assertEquals(NotReady.SOUND, rig.records("start-retry").single().field("notReady"))
    rig.ready()
    rig.advance(30_000)
    assertEquals(1, posts(rig).size, "readiness back does not start by itself")
  }

  @Test fun `no validated network when the retry falls due is not ready too`() {
    val rig = ready().answering(drain)
    val at = drained(rig)
    rig.send(Input.Network(false))
    rig.at(at + wait)
    rig.send(Input.Tick)
    assertEquals(1, posts(rig).size)
    assertEquals(NotReady.NETWORK, rig.records("start-retry").single().field("notReady"))
  }

  @Test fun `readiness lost and back before the retry falls due - the retry goes`() {
    val rig = ready().answering(drain)
    val at = drained(rig)
    rig.ready(Configs.READY.copy(micAvailable = false))
    rig.ready()
    rig.at(at + wait)
    rig.send(Input.Tick)
    assertEquals(2, posts(rig).size)
  }

  @Test fun `a process death mid-wait comes back with no retry - the cold start's pairing starts nothing by itself`() {
    val before = ready().answering(drain)
    drained(before)
    // Native keeps no start state across a process death: JS pairs the saved code again, and that pairing is new.
    val after = ready()
    after.advance(60_000)
    assertTrue(posts(after).isEmpty())
    assertNull(after.snapshot.refusal)
    assertNull(StartAsks().retryAtMs, "a new pairing's asks hold no retry")
  }

  @Test fun `a late draining answer moves nothing`() {
    val rig = ready()
    rig.codes = null
    rig.send(Input.Start)
    val id = posts(rig).single().requestId
    rig.hear("go-live", sid = "sess_42", startedBy = "operator")
    rig.send(Input.StartAnswered(id, drain))
    assertIs<Phase.Arming>(rig.phase)
    assertEquals(true, rig.records("start-answered").last().field("late"))
    assertTrue(rig.records("start-draining").isEmpty())
  }
}
