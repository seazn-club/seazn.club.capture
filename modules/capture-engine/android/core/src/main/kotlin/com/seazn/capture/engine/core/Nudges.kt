package com.seazn.capture.engine.core

private fun line(kind: String, vararg fields: Pair<String, Any?>): Command = Command.Record(RecordEntry(kind, fields.toList()))

/**
 * Design 2026-11-24 §2.4: the `nudges` line on a change of the socket's state only, and a reconnect loop held to one
 * line a minute. A change within [MINUTE_MS] of the last line written waits, counted in [suppressed], and goes once
 * the minute is up ([flushed]), carrying the count; a change after a quiet minute goes at once. Pure, held in the
 * pairing.
 */
data class NudgeLines(
  /** The state and status of the last line written, or null before the first. */
  val written: Pair<NudgeState, Int?>? = null,
  val writtenAtMs: Long? = null,
  /** The newest change held back, written when the minute is up. */
  val pending: Pair<NudgeState, Int?>? = null,
  val suppressed: Int = 0,
) {
  /** The socket reported [state] (and [status]) at [nowMs]: the line to write now, if any. */
  fun heard(state: NudgeState, status: Int?, nowMs: Long): Pair<NudgeLines, RecordEntry?> {
    val reading = state to status
    if (reading == (pending ?: written)) return this to null
    if (writtenAtMs != null && nowMs - writtenAtMs < MINUTE_MS) return copy(pending = reading, suppressed = suppressed + 1) to null
    return written(reading, nowMs)
  }

  /** The change held back, once its minute is up. */
  fun flushed(nowMs: Long): Pair<NudgeLines, RecordEntry?> {
    val held = pending ?: return this to null
    if (writtenAtMs != null && nowMs - writtenAtMs < MINUTE_MS) return this to null
    return written(held, nowMs)
  }

  private fun written(reading: Pair<NudgeState, Int?>, nowMs: Long): Pair<NudgeLines, RecordEntry?> =
    NudgeLines(reading, nowMs) to RecordEntry("nudges", listOf("state" to reading.first, "status" to reading.second, "suppressed" to suppressed))

  companion object {
    const val MINUTE_MS = 60_000L
  }
}

/**
 * The capture nudge, the core's part (design 2026-11-24 §2.2): the grant the beats hand over, the socket it asks of
 * the glue, and a heard nudge as one beat at once. Plus T46's delivery facts (§3), which ride the same answers.
 *
 * - The grant is held in the pairing and never projected. The server mints a fresh token on every beat (the web's
 *   ruling, 2026-11-24), so the held one is kept until it has less than [RealtimeGrant.SWAP_UNDER_MS] left, the
 *   channel failed or was refused, or the answer names another channel ([taken]).
 * - [Command.Nudges] goes whenever the grant the socket should hold changes, and [Command.NudgesOff] when it goes:
 *   the pairing ended (unpair, Forget, replaced, taken, the code's end, Ended) or its token expired ([closed]).
 * - A nudge is one beat at once ([Heartbeats.atOnce]), at most one per [NUDGE_GAP_MS]. It never moves the cadence,
 *   the phase or the start-pending clock (N48).
 */
internal object Nudges {
  /** Web constraint 2: at most one immediate beat per 2 s; a nudge inside it is folded into the beat already due. */
  const val NUDGE_GAP_MS = 2_000L

  /** The grant the socket should hold in [phase]: the beating pairing's, unless a 401 has ended its code. */
  fun grantOf(phase: Phase): RealtimeGrant? = phase.beating?.takeUnless { it.codeEndedPending }?.realtime

  /**
   * A nudge heard: one beat at once unless one went for a nudge less than [NUDGE_GAP_MS] ago. The `nudge` line goes
   * only with a beat, carrying in `suppressed` the nudges folded since the last line (a beat inside the gap, in flight
   * or held by a 429), so a looping server writes at most one line per beat and never floods the record (the
   * controller's ruling, 2026-11-24, from review). With no pairing beating the nudge is a line alone.
   */
  fun heard(phase: Phase, input: Input.Nudge, now: Now): Step {
    val reason = NudgeReason.of(input.reason)
    val pairing = phase.beating ?: return Step(phase, listOf(line("nudge", "reason" to reason, "suppressed" to 0)))
    val folded = pairing.copy(nudgesFolded = pairing.nudgesFolded + 1)
    val last = pairing.nudgedAtMs
    if (last != null && now.monoMs - last < NUDGE_GAP_MS) return Step(phase.withPairing(folded))
    val beat = Heartbeats.atOnce(Step(phase.withPairing(pairing.copy(nudgedAtMs = now.monoMs))), now)
    val after = beat.phase.beating ?: return beat
    if (beat.commands.none { it is Command.PostHeartbeat }) return beat.copy(phase = beat.phase.withPairing(after.copy(nudgesFolded = folded.nudgesFolded)))
    val noted = line("nudge", "reason" to reason, "suppressed" to pairing.nudgesFolded)
    return beat.copy(phase = beat.phase.withPairing(after.copy(nudgesFolded = 0)), commands = listOf(noted) + beat.commands)
  }

  /** The socket's report: its line when it changed, and after an error or a refusal the next beat's grant is taken. */
  fun reported(phase: Phase, input: Input.NudgeLink, now: Now): Step {
    val pairing = phase.beating ?: return Step(phase)
    val (lines, entry) = pairing.nudgeLines.heard(input.state, input.status, now.monoMs)
    val failed = input.state == NudgeState.CLOSED || input.state == NudgeState.REFUSED
    val next = pairing.copy(nudgeLines = lines, realtimeSwapDue = pairing.realtimeSwapDue || failed)
    return Step(phase.withPairing(next), listOfNotNull(entry?.let { Command.Record(it) }))
  }

  /**
   * The grant to hold after a 2xx: the [heard] one when nothing is held, the held one is for another channel, has
   * under [RealtimeGrant.SWAP_UNDER_MS] left, or a failure made the swap due; otherwise the held one. An expired grant
   * heard is no grant.
   */
  fun taken(held: RealtimeGrant?, heard: RealtimeGrant?, swapDue: Boolean, now: Now): RealtimeGrant? {
    if (heard == null || heard.expired(now.wallMs)) return held
    if (held == null || swapDue || !held.sameChannel(heard) || held.leftMs(now.wallMs) < RealtimeGrant.SWAP_UNDER_MS) return heard
    return held
  }

  /**
   * A 2xx's [reply] over [pairing] (null for any other answer, which changes nothing here): the grant ([taken]), and T46's
   * facts while a broadcast is held, replaced by each answer and cleared by one without them, with a `server-delivery`
   * line for each word that changed.
   */
  fun answered(phase: Phase, pairing: PairingState, reply: BeatReply?, now: Now): Pair<PairingState, List<Command>> {
    if (reply == null) return pairing to emptyList()
    val grant = taken(pairing.realtime, reply.realtime, pairing.realtimeSwapDue, now)
    val swapped = grant != pairing.realtime
    // Only a go-live or a live answer speaks of the broadcast's delivery; any other (over, waiting) clears the facts
    // with no line, as the broadcast's end does.
    val speaks = phase.session != null && reply.state in PUBLISH
    val facts = reply.delivery?.takeIf { speaks }?.let { ServerFacts(it, now.wallMs) }
    val lines = if (speaks) deliveryLines(pairing.server?.delivery, facts?.delivery) else emptyList()
    return pairing.copy(realtime = grant, realtimeSwapDue = pairing.realtimeSwapDue && !swapped, server = facts) to lines
  }

  /** One `server-delivery {source, state}` per word that changed; a word not known is `unknown`. */
  private fun deliveryLines(was: ServerDelivery?, now: ServerDelivery?): List<Command> {
    val before = was?.words.orEmpty()
    val after = now?.words.orEmpty()
    return ServerDelivery().words.keys.mapNotNull { source ->
      val word = after[source] ?: UNKNOWN
      if (word == (before[source] ?: UNKNOWN)) null else line("server-delivery", "source" to source, "state" to word)
    }
  }

  private const val UNKNOWN = "unknown"

  /** T46: the answers that carry the facts. */
  private val PUBLISH = setOf("go-live", "live")

  /**
   * After every step: facts held with no broadcast are dropped; a held grant past its expiry is dropped (`nudges
   * expired`); a held-back `nudges` line whose minute is up is written; and the socket is told when the grant it
   * should hold ([grantOf]) changed from [before]'s.
   */
  fun closed(before: Phase, step: Step, now: Now): Step {
    val (phase, lines) = tidied(step.phase, now)
    val was = grantOf(before)
    val held = grantOf(phase)
    val told = if (held == was) emptyList() else listOf(held?.let { Command.Nudges(it) } ?: Command.NudgesOff)
    if (lines.isEmpty() && told.isEmpty() && phase === step.phase) return step
    return step.copy(phase = phase, commands = step.commands + lines + told)
  }

  private fun tidied(phase: Phase, now: Now): Pair<Phase, List<Command>> {
    val pairing = phase.pairing ?: return phase to emptyList()
    var next = if (phase.session == null && pairing.server != null) pairing.copy(server = null) else pairing
    val lines = mutableListOf<RecordEntry>()
    if (phase.beating != null && next.realtime?.expired(now.wallMs) == true) {
      val (nudgeLines, entry) = next.nudgeLines.heard(NudgeState.EXPIRED, null, now.monoMs)
      next = next.copy(realtime = null, realtimeSwapDue = false, nudgeLines = nudgeLines)
      lines += listOfNotNull(entry)
    }
    val (flushed, entry) = next.nudgeLines.flushed(now.monoMs)
    if (entry != null) next = next.copy(nudgeLines = flushed)
    lines += listOfNotNull(entry)
    return (if (next === pairing) phase else phase.withPairing(next)) to lines.map { Command.Record(it) }
  }
}
