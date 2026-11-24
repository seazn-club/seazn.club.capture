package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N48 (R-310M, staging, 2026-11-23): the Seazn-hosted start answered after ~10.6 s, past the client's 8 s, and the
 * phone showed the start failed while the server had started it; the next beat's `Open` armed it. The owner's ruling
 * (2026-11-23, "Pending until beat"): a start with no answer stays pending, `starting` in the snapshot, until a beat
 * arms the phone, or until 20 s after `start-asked`, when it is the start failure it was. An answer that is a failure
 * stays one at once: the server answered, so it did not start.
 */
class StartPendingTest {
  private val noAnswer = StartAnswer.Failed("no-answer:SocketTimeoutException")

  private fun ready(): MachineRig {
    val rig = MachineRig().paired()
    rig.hear("waiting")
    rig.ready()
    return rig
  }

  private fun starting(rig: MachineRig): Boolean? = rig.snapshot.pairing?.starting

  private fun paired(rig: MachineRig) = assertIs<Phase.Paired>(rig.phase).pairing

  /** The hold at [rig]'s now, answered with no answer [afterMs] later. Returns when the start was asked. */
  private fun unanswered(rig: MachineRig, afterMs: Long = 8_000): Long {
    val askedAt = rig.mono
    rig.send(Input.Start)
    rig.at(askedAt + afterMs)
    rig.send(Input.StartAnswered(rig.sent<Command.PostStart>().last().requestId, noAnswer))
    return askedAt
  }

  @Test fun `a start with no answer is pending, starting, with no refusal, and the record says so`() {
    val rig = ready()
    unanswered(rig)
    assertEquals(SnapshotState.Paired, rig.state)
    assertEquals(true, starting(rig), "still starting: the server may have started it")
    assertNull(rig.snapshot.refusal)
    assertNull(paired(rig).startFailed)
    val answered = rig.records("start-answered").single()
    assertEquals(listOf<Any?>("Failed", "no-answer:SocketTimeoutException"), listOf(answered.field("answer"), answered.field("error")))
    assertEquals(1, rig.records("start-pending").size)
    assertTrue(rig.records("start-unconfirmed").isEmpty())
  }

  @Test fun `a beat's Open within 20 s arms it, cause operator, and no refusal is ever shown`() {
    val rig = ready()
    rig.codes = null
    val askedAt = unanswered(rig)
    assertNull(rig.snapshot.refusal, "the no-answer shows no refusal")
    var refusals = 0
    rig.hear("go-live", sid = "sess_42", startedBy = "operator")
    assertTrue(rig.mono < askedAt + StartAsks.START_CONFIRM_MS, "the beat came inside the 20 s")
    val arming = assertIs<Phase.Arming>(rig.phase)
    assertEquals(Cause.OPERATOR, arming.cause)
    assertEquals(false, starting(rig))
    rig.advance(30_000) { if (rig.snapshot.refusal != null) refusals++ }
    assertEquals(0, refusals)
    assertTrue(rig.records("start-unconfirmed").isEmpty())
  }

  @Test fun `waiting beats during the pending do not end it`() {
    val rig = ready()
    val askedAt = unanswered(rig, afterMs = 1_000)
    rig.hear("waiting", pollSeconds = 5)
    rig.hear("waiting", pollSeconds = 5)
    assertTrue(rig.mono < askedAt + StartAsks.START_CONFIRM_MS, "both beats inside the 20 s")
    assertEquals(true, starting(rig))
    assertNull(rig.snapshot.refusal)
  }

  @Test fun `20 s after start-asked with nothing armed it is the start failure, once, and 19,999 ms is not`() {
    val rig = ready()
    val askedAt = unanswered(rig)
    rig.at(askedAt + StartAsks.START_CONFIRM_MS - 1)
    rig.send(Input.Tick)
    assertEquals(true, starting(rig))
    assertNull(rig.snapshot.refusal)
    rig.at(askedAt + StartAsks.START_CONFIRM_MS)
    rig.send(Input.Tick)
    assertEquals(false, starting(rig))
    assertEquals(Refusal.START_FAILED, rig.snapshot.refusal)
    assertEquals(StartFailure.START_ERROR, paired(rig).startFailed)
    rig.advance(30_000)
    assertEquals(1, rig.records("start-unconfirmed").size, "one line, never again")
    assertEquals(1, rig.sent<Command.PostStart>().size, "never retried on its own")
  }

  @Test fun `the deadline counts from start-asked, not from the no-answer`() {
    val rig = ready()
    val askedAt = unanswered(rig, afterMs = 15_000)
    rig.at(askedAt + StartAsks.START_CONFIRM_MS)
    rig.send(Input.Tick)
    assertEquals(Refusal.START_FAILED, rig.snapshot.refusal)
  }

  @Test fun `an answer that is a failure still fails at once`() {
    val rig = ready()
    rig.starts = { StartAnswer.Failed("503") }
    rig.send(Input.Start)
    assertEquals(Refusal.START_FAILED, rig.snapshot.refusal)
    assertEquals(false, starting(rig))
    assertTrue(rig.records("start-pending").isEmpty())
  }

  @Test fun `a second hold while pending is ignored, recorded, and one post`() {
    val rig = ready()
    unanswered(rig)
    rig.send(Input.Start)
    assertEquals(1, rig.sent<Command.PostStart>().size)
    assertEquals("start", rig.records("intent-ignored").last().field("intent"))
    assertEquals(true, starting(rig))
  }

  @Test fun `after the failure the next hold posts again and is pending again`() {
    val rig = ready()
    val askedAt = unanswered(rig)
    rig.at(askedAt + StartAsks.START_CONFIRM_MS)
    rig.send(Input.Tick)
    unanswered(rig)
    assertEquals(2, rig.sent<Command.PostStart>().size)
    assertEquals(true, starting(rig))
    assertNull(rig.snapshot.refusal)
  }

  // M1 (2026-10-12) holds for a pending start too: with no answer, the server may already have started it, and a start
  // it accepted after an unpair would be a broadcast with no phone.
  @Test fun `an unpair while pending is ignored and recorded, accepted once the deadline passes, and leaves nothing behind`() {
    val rig = ready()
    val askedAt = unanswered(rig)
    rig.send(Input.Unpair)
    assertIs<Phase.Paired>(rig.phase, "ignored while pending")
    assertEquals("unpair", rig.records("intent-ignored").last().field("intent"))
    assertEquals(true, starting(rig))
    rig.at(askedAt + StartAsks.START_CONFIRM_MS)
    rig.send(Input.Tick)
    rig.send(Input.Unpair)
    assertIs<Phase.Idle>(rig.phase, "accepted once the pending is the failure")
    rig.at(askedAt + StartAsks.START_CONFIRM_MS + 5_000)
    rig.send(Input.Tick)
    assertEquals(1, rig.records("start-unconfirmed").size, "the deadline's line, and no other")
    // A new pairing starts with no pending of the old one's.
    rig.paired()
    rig.hear("waiting")
    rig.ready()
    assertEquals(false, starting(rig))
  }

  @Test fun `the notification's Forget while pending is ignored and recorded`() {
    val rig = ready()
    unanswered(rig)
    rig.send(Input.Forget)
    assertIs<Phase.Paired>(rig.phase)
    assertEquals("forget", rig.records("intent-ignored").last().field("intent"))
    assertTrue(rig.sent<Command.WriteForgotten>().isEmpty())
  }

  @Test fun `a beat that ends the pairing while pending leaves no late failure`() {
    val answers = listOf("replaced" to 200, "taken" to 200, "code-ended" to 401, "app-outdated" to 426)
    for ((state, status) in answers) {
      val rig = ready()
      val askedAt = unanswered(rig, afterMs = 1_000)
      rig.hear(state.takeIf { status == 200 }, status = status)
      assertIs<Phase.Idle>(rig.phase, state)
      rig.at(maxOf(rig.mono, askedAt + StartAsks.START_CONFIRM_MS + 1_000))
      rig.send(Input.Tick)
      assertTrue(rig.records("start-unconfirmed").isEmpty(), state)
    }
  }

  @Test fun `a broadcast armed and ended while pending leaves no pending behind`() {
    val rig = ready()
    val askedAt = unanswered(rig, afterMs = 1_000)
    rig.hear("go-live", sid = "sess_42", startedBy = "operator")
    rig.hear("waiting")
    assertIs<Phase.Paired>(rig.phase)
    rig.at(maxOf(rig.mono, askedAt + StartAsks.START_CONFIRM_MS + 1_000))
    rig.send(Input.Tick)
    assertEquals(false, starting(rig))
    assertTrue(rig.records("start-unconfirmed").isEmpty())
  }

  // The coordinator's fix to the first report's concern 1: at a 60 s poll no beat might come inside the 20 s. While a
  // start is pending the beats confirm it: one at once, then at the poll floor (BeatCadence.RETRY_MS).

  private fun reply(state: String, sid: String? = null, startedBy: String? = null) =
    HeartbeatResponse.Answered(200, BeatReply(state, sid, startedBy, null, Configs.waiting(60)))

  /** Paired and ready at a 60 s poll, every beat after it answered by [answer] and its time kept in [sent]. */
  private fun slow(sent: MutableList<Long>, answer: (Long) -> HeartbeatResponse): MachineRig {
    val rig = MachineRig().paired()
    rig.hear("waiting", pollSeconds = 60)
    rig.ready()
    rig.beats = { sent += rig.mono; answer(rig.mono) }
    return rig
  }

  @Test fun `at a 60 s poll the no-answer beats at once, then every 5 s, and a beat's Open at 18 s arms with no failure`() {
    val sent = mutableListOf<Long>()
    var askedAt = Long.MAX_VALUE
    val rig = slow(sent) { if (it >= askedAt + 18_000) reply("go-live", "sess_42", "operator") else reply("waiting") }
    askedAt = rig.mono
    unanswered(rig)
    assertEquals(listOf(askedAt + 8_000), sent, "one beat at once with the no-answer")
    rig.advance(10_000)
    assertEquals(listOf(8_000L, 13_000L, 18_000L), sent.map { it - askedAt }, "then at the poll floor")
    assertEquals(Cause.OPERATOR, rig.session.cause, "armed by the 18 s beat, cause operator")
    assertNull(rig.snapshot.refusal)
    rig.advance(30_000)
    assertTrue(rig.records("start-unconfirmed").isEmpty())
  }

  @Test fun `the 5 s beats hold tick by tick, none early and none late`() {
    val sent = mutableListOf<Long>()
    val rig = slow(sent) { reply("waiting") }
    val askedAt = rig.mono
    unanswered(rig)
    val due = listOf(8_000L, 13_000L, 18_000L)
    rig.advance(StartAsks.START_CONFIRM_MS - 8_000 - 500) {
      val at = rig.mono - askedAt
      assertEquals(due.filter { it <= at }, sent.map { it - askedAt }, "at $at ms")
    }
  }

  // A 429's Retry-After holds the next beat even while confirming: the server asked. One that runs past the 20 s leaves
  // the start unconfirmed at the deadline, the start failure, said once.
  @Test fun `a 429 whose Retry-After passes the deadline fails the pending at the deadline, with start-unconfirmed`() {
    val sent = mutableListOf<Long>()
    val rig = slow(sent) { HeartbeatResponse.Answered(429, retryAfterS = 30) }
    val askedAt = unanswered(rig)
    assertEquals(listOf(askedAt + 8_000), sent, "the beat at once, answered 429")
    rig.advance(StartAsks.START_CONFIRM_MS - 8_000 - 500)
    assertEquals(1, sent.size, "the Retry-After holds the 5 s beats")
    assertEquals(true, starting(rig))
    rig.advance(500)
    assertEquals(Refusal.START_FAILED, rig.snapshot.refusal)
    assertEquals(1, rig.records("start-unconfirmed").size)
  }

  @Test fun `once the deadline clears the pending the server's 60 s cadence is back`() {
    val sent = mutableListOf<Long>()
    val rig = slow(sent) { reply("waiting") }
    val askedAt = rig.mono
    unanswered(rig)
    rig.advance(StartAsks.START_CONFIRM_MS - 8_000)
    assertEquals(Refusal.START_FAILED, rig.snapshot.refusal)
    val last = sent.last()
    assertEquals(askedAt + 18_000, last)
    rig.advance(last + 60_000 - 500 - rig.mono)
    assertEquals(last, sent.last(), "no beat before the 60 s cadence")
    rig.advance(500)
    assertEquals(last + 60_000, sent.last())
  }

  @Test fun `once a beat arms it the held cadence is back, 10 s, not the 5 s floor`() {
    val sent = mutableListOf<Long>()
    var askedAt = Long.MAX_VALUE
    val rig = slow(sent) { if (it >= askedAt + 8_000) reply("go-live", "sess_42", "operator") else reply("waiting") }
    askedAt = rig.mono
    unanswered(rig)
    assertEquals("sess_42", rig.phase.heldSid, "armed by the beat at once")
    rig.advance(20_000)
    val gaps = sent.zipWithNext { a, b -> b - a }
    assertEquals(listOf(BeatCadence.HELD_MAX_MS, BeatCadence.HELD_MAX_MS), gaps)
  }
}
