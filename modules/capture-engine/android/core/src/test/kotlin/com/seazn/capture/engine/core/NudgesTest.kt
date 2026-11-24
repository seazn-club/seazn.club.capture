package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Design 2026-11-24 §2.2 and the web's ruling of the same day: the grant the beats hand over, the socket the core asks
 * for, and a nudge as one beat at once. The server mints a fresh token on every beat, so the phone keeps the token it
 * joined with until it has less than 10 minutes left, the channel fails, or the channel changes.
 */
class NudgesTest {
  private val topic = "capture:6f1c2b7a-3d4e-4f50-8a9b-0c1d2e3f4a5b"

  /** A made-up grant with [leftMs] to run from [rig]'s wall clock. */
  private fun grant(rig: MachineRig, token: String, leftMs: Long = 30 * 60_000L, topic: String = this.topic, url: String = "https://example-ref.supabase.co") =
    RealtimeGrant(url, "anon.example-key", topic, token, (rig.wall + leftMs) / 1_000)

  private fun told(commands: List<Command>): List<Command> = commands.filter { it is Command.Nudges || it is Command.NudgesOff }

  private fun MachineRig.toldAll(): List<Command> = told(commands)

  private fun joined(): Pair<MachineRig, RealtimeGrant> {
    val rig = MachineRig().paired()
    val first = grant(rig, "token-1")
    rig.hear("waiting", realtime = first)
    return rig to first
  }

  @Test
  fun `the first 2xx's grant asks the socket for its channel, once`() {
    val (rig, first) = joined()
    assertEquals(listOf<Command>(Command.Nudges(first)), rig.toldAll())
    assertEquals(first, rig.phase.pairing?.realtime)
  }

  @Test
  fun `the web's ruling - ten beats with fresh tokens while the held one has 25 minutes left send nothing to the socket`() {
    val rig = MachineRig().paired()
    rig.hear("waiting", realtime = grant(rig, "token-0", leftMs = 25 * 60_000L))
    for (n in 1..10) rig.hear("waiting", realtime = grant(rig, "token-$n"))
    assertEquals(1, rig.toldAll().size, "only the first grant: ${rig.toldAll()}")
    assertEquals("token-0", rig.phase.pairing?.realtime?.token)
  }

  @Test
  fun `the held token with under 10 minutes left is swapped for the beat's, on the same channel`() {
    val rig = MachineRig().paired()
    val heldUntil = rig.wall + 11 * 60_000L
    rig.hear("waiting", realtime = grant(rig, "token-0", leftMs = 11 * 60_000L))
    rig.hear("waiting", realtime = grant(rig, "token-1"))
    assertTrue(heldUntil - rig.wall >= 10 * 60_000L, "still 10 minutes or more left")
    assertEquals(1, rig.toldAll().size, "10 minutes or more left: kept")
    // A minute on, the held one has under 10 minutes.
    rig.at(rig.mono + 60_000)
    val fresh = grant(rig, "token-2")
    val heard = rig.hear("waiting", realtime = fresh)
    assertTrue(heldUntil - rig.wall < 10 * 60_000L)
    assertEquals(listOf<Command>(Command.Nudges(fresh)), told(heard))
    assertEquals("token-2", rig.phase.pairing?.realtime?.token)
  }

  @Test
  fun `another topic, origin or key is another channel - taken at once, whatever the held one has left`() {
    for (name in listOf("topic", "url")) {
      val (rig, _) = joined()
      val next = if (name == "topic") grant(rig, "t2", topic = "capture:other-pairing") else grant(rig, "t2", url = "https://other-ref.supabase.co")
      assertEquals(listOf<Command>(Command.Nudges(next)), told(rig.hear("waiting", realtime = next)), name)
    }
    val (rig, _) = joined()
    val rekeyed = RealtimeGrant("https://example-ref.supabase.co", "another-key", topic, "t2", (rig.wall + 30 * 60_000L) / 1_000)
    assertEquals(listOf<Command>(Command.Nudges(rekeyed)), told(rig.hear("waiting", realtime = rekeyed)), "apikey")
  }

  @Test
  fun `after a channel error or a refusal the next beat's grant is taken, and the one after is kept again`() {
    for (state in listOf(NudgeState.CLOSED, NudgeState.REFUSED)) {
      val (rig, _) = joined()
      rig.send(Input.NudgeLink(state, 1006))
      val next = grant(rig, "token-after-$state")
      assertEquals(listOf<Command>(Command.Nudges(next)), told(rig.hear("waiting", realtime = next)), "$state")
      assertEquals(emptyList(), told(rig.hear("waiting", realtime = grant(rig, "token-later"))), "$state: the swap is spent")
    }
  }

  @Test
  fun `a rejoin after a failure leaves the swap due until a beat swaps it`() {
    val (rig, _) = joined()
    rig.send(Input.NudgeLink(NudgeState.CLOSED, 1006))
    rig.send(Input.NudgeLink(NudgeState.JOINED))
    val next = grant(rig, "token-after-rejoin")
    assertEquals(listOf<Command>(Command.Nudges(next)), told(rig.hear("waiting", realtime = next)))
  }

  @Test
  fun `an expired grant heard never replaces a held one, even one due a swap`() {
    val rig = MachineRig().paired()
    val held = grant(rig, "held", leftMs = 5 * 60_000L)
    rig.hear("waiting", realtime = held)
    assertEquals(emptyList(), told(rig.hear("waiting", realtime = grant(rig, "stale", leftMs = -60_000))))
    assertEquals(held, rig.phase.pairing?.realtime)
  }

  @Test
  fun `joined is no failure - the held token is kept`() {
    val (rig, _) = joined()
    rig.send(Input.NudgeLink(NudgeState.JOINED))
    assertEquals(emptyList(), told(rig.hear("waiting", realtime = grant(rig, "token-2"))))
  }

  @Test
  fun `an answer without a grant keeps the held one, and so does a failed beat`() {
    val (rig, first) = joined()
    assertEquals(emptyList(), told(rig.hear("waiting")))
    assertEquals(emptyList(), told(rig.hear("waiting", status = 500, realtime = grant(rig, "never"))))
    assertEquals(first, rig.phase.pairing?.realtime)
  }

  @Test
  fun `an expired grant heard is no grant`() {
    val rig = MachineRig().paired()
    rig.hear("waiting", realtime = grant(rig, "stale", leftMs = 0))
    rig.hear("waiting", realtime = grant(rig, "stale", leftMs = -60_000))
    assertEquals(emptyList(), rig.toldAll())
    assertNull(rig.phase.pairing?.realtime)
  }

  @Test
  fun `a held grant past its expiry closes the socket and says expired, and the next beat's grant opens it again`() {
    val rig = MachineRig().paired()
    val short = grant(rig, "short", leftMs = 3_000)
    rig.hear("waiting", realtime = short, pollSeconds = 60)
    rig.at(rig.mono + 2_999)
    assertEquals(emptyList(), told(rig.send(Input.Tick)), "1 ms left")
    rig.at(rig.mono + 1)
    val tick = rig.send(Input.Tick)
    assertEquals(listOf<Command>(Command.NudgesOff), told(tick))
    assertEquals(listOf<Any?>(NudgeState.EXPIRED, null, 0), rig.records("nudges").single().fields.map { it.second })
    assertNull(rig.phase.pairing?.realtime)
    val next = grant(rig, "next")
    assertEquals(listOf<Command>(Command.Nudges(next)), told(rig.hear("waiting", realtime = next, pollSeconds = 60)))
  }

  @Test
  fun `every way the pairing ends closes the socket`() {
    val wrong = mutableListOf<String>()
    for (name in listOf("unpair", "forget", "replaced", "taken", "code ended", "a new pair")) {
      val (rig, _) = joined()
      val before = rig.commands.size
      endBy(rig, name)
      val after = told(rig.commands.drop(before))
      if (after != listOf<Command>(Command.NudgesOff)) wrong += "$name: $after in ${rig.phase.name}"
    }
    assertEquals(emptyList(), wrong)
  }

  private fun endBy(rig: MachineRig, name: String) {
    when (name) {
      "unpair" -> rig.send(Input.Unpair)
      "forget" -> rig.send(Input.Forget)
      "replaced", "taken" -> rig.hear(name)
      "code ended" -> rig.hear(null, status = 401)
      // Another slot of the code is another pairing (the same name is ignored, and keeps its socket).
      else -> rig.send(Input.Pair(Configs.pairRequest(slot = 2), Configs.PHONE, stopped = null))
    }
  }

  @Test
  fun `a live Stop ends the pairing and closes the socket, and a 401 on air closes it while the broadcast runs on`() {
    val stopped = MachineRig().live()
    stopped.hear("live", sid = "sess_42", realtime = grant(stopped, "token-1"))
    val before = stopped.commands.size
    stopped.send(Input.Stop)
    assertIs<Phase.Ended>(stopped.phase)
    assertEquals(listOf<Command>(Command.NudgesOff), told(stopped.commands.drop(before)))

    val pending = MachineRig().live()
    pending.hear("live", sid = "sess_42", realtime = grant(pending, "token-1"))
    val mark = pending.commands.size
    pending.hear(null, status = 401)
    assertTrue(pending.phase.pairing!!.codeEndedPending)
    assertEquals(listOf<Command>(Command.NudgesOff), told(pending.commands.drop(mark)))
  }

  @Test
  fun `the broadcast's own end keeps the pairing and its socket`() {
    val rig = MachineRig().live()
    rig.hear("live", sid = "sess_42", realtime = grant(rig, "token-1"))
    val before = rig.commands.size
    rig.hear("over", sid = "sess_42", endReason = "stopped")
    assertIs<Phase.Paired>(rig.phase)
    assertEquals(emptyList(), told(rig.commands.drop(before)))
  }

  @Test
  fun `the grant rides the pairing through Arming and into the session`() {
    val rig = MachineRig().paired()
    val first = grant(rig, "token-1")
    rig.hear("go-live", sid = "sess_42", startedBy = "organiser", realtime = first)
    rig.ready()
    rig.send(Input.Connected(rig.attempt!!))
    rig.advance(1_000)
    assertEquals(first, rig.phase.pairing?.realtime)
    assertEquals(listOf<Command>(Command.Nudges(first)), rig.toldAll())
  }

  // --- the nudge itself ---

  /** Paired, the claim answered, at the 60 s cadence, with no beat in flight. */
  private fun quiet(): MachineRig {
    val rig = MachineRig().paired()
    rig.hear("waiting", pollSeconds = 60, realtime = grant(rig, "token-1"))
    rig.advance(5_000)
    assertNull(rig.phase.beating?.heartbeat?.inFlightId)
    return rig
  }

  private fun beats(commands: List<Command>) = commands.filterIsInstance<Command.PostHeartbeat>()

  @Test
  fun `a nudge beats at once and records its reason`() {
    val rig = quiet()
    val step = rig.send(Input.Nudge("go-live"))
    assertEquals(1, beats(step).size)
    assertEquals("go-live", rig.records("nudge").single().field("reason"))
  }

  @Test
  fun `every reason the server names is recorded as itself, anything else and none as other`() {
    val rig = quiet()
    val heard = listOf("go-live", "stop", "replaced", "over", "next-match", "score", "GO-LIVE", "", null)
    for (reason in heard) {
      rig.at(rig.mono + 2_000)
      rig.send(Input.Nudge(reason))
    }
    assertEquals(listOf("go-live", "stop", "replaced", "over", "next-match", "other", "other", "other", "other"), rig.records("nudge").map { it.field("reason") })
  }

  @Test
  fun `two nudges inside 2 s give one beat, and one at 2 s gives the next`() {
    val rig = quiet()
    rig.send(Input.Nudge("go-live"))
    rig.send(Input.HeartbeatAnswered(rig.inFlightBeat(), HeartbeatResponse.Answered(200, BeatReply("waiting", null, null, null, Configs.waiting(60)))))
    rig.at(rig.mono + 1_999)
    assertEquals(emptyList(), beats(rig.send(Input.Nudge("stop"))), "folded: 1,999 ms after the last")
    rig.at(rig.mono + 1)
    assertEquals(1, beats(rig.send(Input.Nudge("stop"))).size, "2 s after the last")
    assertEquals(3, rig.records("nudge").size, "every nudge is recorded, folded or not")
  }

  @Test
  fun `a nudge while a beat is in flight sends nothing more in that step`() {
    val rig = quiet()
    rig.send(Input.Nudge("go-live"))
    val id = rig.inFlightBeat()
    rig.at(rig.mono + 3_000)
    assertEquals(emptyList(), beats(rig.send(Input.Nudge("stop"))))
    assertEquals(id, rig.inFlightBeat())
  }

  @Test
  fun `a nudge during a 429's wait sends nothing until the wait is over`() {
    val rig = quiet()
    rig.send(Input.Nudge("go-live"))
    rig.send(Input.HeartbeatAnswered(rig.inFlightBeat(), HeartbeatResponse.Answered(429, null, retryAfterS = 30)))
    rig.at(rig.mono + 3_000)
    assertEquals(emptyList(), beats(rig.send(Input.Nudge("stop"))))
    assertNull(rig.phase.beating?.heartbeat?.inFlightId)
  }

  @Test
  fun `a nudge moves neither the cadence nor the phase - the next beat is a cadence after the nudged one`() {
    val rig = quiet()
    val before = rig.phase
    rig.send(Input.Nudge("next-match"))
    assertIs<Phase.Paired>(rig.phase)
    assertEquals(before.pairing!!.waiting, rig.phase.pairing!!.waiting)
    val sentAt = rig.mono
    rig.send(Input.HeartbeatAnswered(rig.inFlightBeat(), HeartbeatResponse.Answered(200, BeatReply("waiting", null, null, null, Configs.waiting(60)))))
    val times = mutableListOf<Long>()
    val mark = rig.commands.size
    rig.advance(70_000) { if (beats(rig.commands.drop(mark)).size > times.size) times += rig.mono }
    assertEquals(sentAt + 60_000, times.first(), "the 60 s cadence from the nudged beat")
  }

  @Test
  fun `a nudge never touches a start pending a beat (N48)`() {
    val rig = MachineRig().paired()
    rig.hear("waiting", realtime = grant(rig, "token-1"))
    rig.ready()
    rig.send(Input.Start)
    rig.at(rig.mono + 8_000)
    rig.send(Input.StartAnswered(rig.sent<Command.PostStart>().last().requestId, StartAnswer.Failed("no-answer:SocketTimeoutException")))
    val starts = rig.phase.pairing!!.starts
    assertTrue(starts.pending)
    rig.at(rig.mono + 3_000)
    rig.send(Input.Nudge("go-live"))
    assertEquals(starts, rig.phase.pairing!!.starts)
  }

  @Test
  fun `a nudge with no pairing beating is recorded and does nothing else`() {
    val idle = MachineRig()
    assertEquals(listOf("nudge"), idle.send(Input.Nudge("go-live")).map { (it as Command.Record).entry.kind })
    assertIs<Phase.Idle>(idle.phase)
  }

  // --- the nudges line ---

  @Test
  fun `the nudges line is written on a change only, and a loop is one line a minute with a count`() {
    val (rig, _) = joined()
    rig.send(Input.NudgeLink(NudgeState.JOINED))
    rig.send(Input.NudgeLink(NudgeState.JOINED))
    assertEquals(1, rig.records("nudges").size, "joined twice is one line")
    rig.at(rig.mono + 61_000)
    rig.send(Input.NudgeLink(NudgeState.CLOSED, 1006))
    assertEquals(listOf<Any?>(NudgeState.CLOSED, 1006, 0), rig.records("nudges").last().fields.map { it.second }, "a close after a quiet minute goes at once")
    // A reconnect loop: joined, closed, joined, closed... every few seconds.
    for (i in 1..10) {
      rig.at(rig.mono + 3_000)
      rig.send(Input.NudgeLink(if (i % 2 == 1) NudgeState.JOINED else NudgeState.CLOSED, if (i % 2 == 1) null else 1006))
    }
    assertEquals(2, rig.records("nudges").size, "held back inside the minute")
    rig.at(rig.mono + 31_000)
    rig.send(Input.Tick)
    assertEquals(listOf<Any?>(NudgeState.CLOSED, 1006, 10), rig.records("nudges").last().fields.map { it.second }, "the minute's last state, with the count")
  }

  @Test
  fun `a socket report with no pairing beating is dropped`() {
    val idle = MachineRig()
    assertEquals(emptyList(), idle.send(Input.NudgeLink(NudgeState.JOINED)))
  }
}

/** The beat in flight's id; fails when none is. */
internal fun MachineRig.inFlightBeat(): Int = phase.beating?.heartbeat?.inFlightId ?: error("no beat in flight in ${phase.name}")
