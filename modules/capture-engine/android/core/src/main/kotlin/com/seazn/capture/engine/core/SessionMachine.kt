package com.seazn.capture.engine.core

/**
 * One step of the machine: the next phase and what to ask of the platform. [leftLog] is the beat log of a pairing the
 * step left for Idle, as the step last held it ([Pairings.unpaired]), so the answer that unpaired the phone is in the
 * minute [BeatLogs.closed] writes (plan D §3.4).
 */
data class Step(val phase: Phase, val commands: List<Command> = emptyList(), val leftLog: BeatLog? = null)

private fun record(kind: String, vararg fields: Pair<String, Any?>): Command = Command.Record(RecordEntry(kind, fields.toList()))

/**
 * The aggregate (spec §3, `SessionMachine`), as a pure reducer: `(phase, input, now) → (phase,
 * commands)`. Native owns the session (AGENTS §2); this is that ownership, with every rule runnable
 * on the JVM. It holds no clock, thread or I/O — [Engine] drives it.
 */
object SessionMachine {
  /** Between attempts (P5 spike: every 2 s). */
  const val RETRY_MS = 2_000L

  /** A connect that answers nothing in this long has failed (the spike's half-open guard). */
  const val CONNECT_TIMEOUT_MS = 15_000L

  /** A regulator cut this recent shows as `poor-uplink`, the same span a raise waits after a cut. */
  const val POOR_UPLINK_MS = BitrateRegulator.RAISE_AFTER_CUT_MS

  /**
   * The tick runs every time-based rule in its own order. Any other input is applied only after the
   * phase as it stands has been judged the way the tick would (B6 ruling C2): a hold that has run out
   * ends the session first (B7 N1), and then the stall watchdog judges it, so nothing lands between
   * the LIVE gate closing and the rebuild, no snapshot reads connecting there, and a switch in that
   * gap cannot re-open LIVE. A stop is not judged (B6 fix 2): it ends the session whatever the
   * picture does, so a rebuild first would only be torn down by the end. A permanent platform failure
   * is judged for the hold only (final review M-5, N-4): the watchdog is skipped for the stop's reason.
   *
   * So once the hold has run out, any input but a stop that lands before the tick ends the session
   * hold-window-expired, a picture that comes back, an organiser stop and a platform failure included
   * (B7 ruling m2, final review N-4): the hold is the published limit, and the tick at that instant
   * would end it the same way. A stop in that gap reads operator-stopped.
   *
   * Plan D §3.4: whatever the input, a pairing that stops beating writes its open minute of beats ([BeatLogs.closed]).
   * N49 fix round 1: a session that ends writes its floor cuts no line has said yet ([FloorCuts.closed]).
   */
  fun reduce(phase: Phase, input: Input, now: Now): Step {
    // R-1PG4: the first step after which the held broadcast's viewers are confirmed says which fact did it.
    val step = ViewersConfirmed.closed(reduced(phase, input, now), now)
    return Nudges.closed(phase, BeatLogs.closed(phase, NeverLiveRetries.closed(phase, FloorCuts.closed(phase, step))), now)
  }

  private fun reduced(phase: Phase, input: Input, now: Now): Step {
    if (input == Input.Tick || input == Input.Stop) return apply(phase, input, now)
    val judged = if (input is Input.PlatformFailed) Timers.holdRanOut(phase, now) ?: Step(phase) else Timers.judged(phase, now)
    val step = apply(judged.phase, input, now)
    val merged = if (judged.commands.isEmpty()) step else step.copy(commands = judged.commands + step.commands)
    // A11: whatever the input, an Armed phase that is ready connects now.
    return Pairings.gate(merged, now)
  }

  private fun apply(phase: Phase, input: Input, now: Now): Step {
    if (phase is Phase.Ended && phase.finalBeatId != null) finalBeat(phase, input, now)?.let { return it }
    return when (input) {
      is Input.Pair -> Pairings.pair(phase, input, now)
      is Input.PermissionsAnswered -> Pairings.permissions(phase, input.granted, now)
      Input.Unpair -> Pairings.unpair(phase)
      Input.Forget -> Pairings.forget(phase)
      is Input.SetAutomatic -> Pairings.automatic(phase, input.on)
      is Input.SetLanguage -> Pairings.language(phase, input.language)
      is Input.SetMatchSound -> Pairings.matchSound(phase, input.on)
      is Input.SetConstantBitrate -> Pairings.constantBitrate(phase, input.on)
      is Input.ReadinessChanged -> Pairings.readiness(phase, input.readiness)
      Input.Start -> Verdicts.postStart(phase, now)
      Input.Stop -> stop(phase, now)
      Input.Reset -> if (phase is Phase.Ended) reset(phase) else ignored(phase, "reset")
      Input.SwitchCamera -> Devices.switched(phase, now)
      Input.Tick -> Heartbeats.tick(Timers.tick(phase, now), now)
      is Input.Connected -> Transports.connected(phase, input.attemptId, now)
      is Input.ConnectFailed -> Transports.connectFailed(phase, input, now)
      is Input.Dropped -> Transports.dropped(phase, input, now)
      is Input.Frames -> Transports.frames(phase, input, now)
      is Input.Link -> Transports.link(phase, input, now)
      is Input.Network -> network(phase, input, now)
      Input.CameraContended -> Devices.contended(phase)
      Input.CameraReleased -> Devices.released(phase)
      is Input.CameraReopened -> Devices.reopened(phase, input.ok, now)
      is Input.MicSilenced -> Devices.mic(phase, input.silenced)
      is Input.Device -> Devices.sampled(phase, input.sample)
      is Input.PlaylistFetched -> Deliveries.fetched(phase, input, now)
      is Input.HeartbeatAnswered -> Heartbeats.answered(phase, input, now)
      is Input.Nudge -> Nudges.heard(phase, input, now)
      is Input.NudgeLink -> Nudges.reported(phase, input, now)
      is Input.CodeAnswered -> Verdicts.code(phase, input, now)
      is Input.StartAnswered -> Verdicts.started(phase, input, now)
      is Input.PlatformFailed -> platformFailed(phase, input, now)
    }
  }

  /** A8: the final beat's answer, or its timeout, releases the service. Null for any other input. */
  private fun finalBeat(ended: Phase.Ended, input: Input, now: Now): Step? {
    val result =
      when {
        input is Input.HeartbeatAnswered && input.beatId == ended.finalBeatId ->
          record("final-beat", "result" to "answered", "status" to (input.response as? HeartbeatResponse.Answered)?.status)
        input == Input.Tick && now.monoMs >= ended.finalBeatAtMs + Heartbeat.TIMEOUT_MS -> record("final-beat", "result" to "timeout", "status" to null)
        else -> return null
      }
    return Step(ended.copy(finalBeatId = null), listOf(Command.Release, result))
  }

  /** Back to Idle; a final beat still pending is let go, and the service with it. */
  private fun reset(ended: Phase.Ended): Step {
    val release = if (ended.finalBeatId != null) listOf(Command.Release) else emptyList()
    return Step(Phase.Idle(ended.ids, ended.networkValidated, ended.micSilenced, defaultNetwork = ended.defaultNetwork), release + record("reset"))
  }

  internal fun ignored(phase: Phase, intent: String): Step =
    Step(phase, listOf(record("intent-ignored", "intent" to intent, "phase" to phase.name)))

  /**
   * The operator's Stop (A3), only while a broadcast is held. From a broadcast that went live it ends the pairing
   * ([end], A8's final beat). Owner decision 1 (2026-10-05) supersedes A8's silence for one that never went live
   * (Arming, Armed, or Connecting before its first frame): the broadcast ends and the pairing stays Paired, so Go
   * live can be tried again, and A17's record goes out on a beat at once ([stoppedHere]). A 401 heard while it was
   * held ends the pairing with it, as before (the 401 deferral): its code is dead, so no beat could deliver.
   *
   * Court codes (court stream spec 2026-11-14 §6.3, O5): a court pairing's Stop ends this match only, live or not. It
   * takes [stoppedHere], never [end]: the sid is refused, the pairing stays Paired for the court's next match, and the
   * beat's `stopped: <sid>` latches the match on the server (§5.4). The 401 deferral still ends a court's pairing.
   */
  private fun stop(phase: Phase, now: Now): Step {
    val pairing = phase.pairing
    val sid = phase.heldSid
    if (pairing == null || sid == null) return ignored(phase, "stop")
    if (pairing.codeEndedPending) return end(phase, EndReason.OPERATOR_STOPPED, now)
    if (wentLive(phase) && !pairing.isCourt) return end(phase, EndReason.OPERATOR_STOPPED, now)
    return stoppedHere(phase, pairing, sid, LastEnd.STOPPED, now)
  }

  /** The held broadcast went live: its first frame came ([firstLiveEpochMs]). */
  private fun wentLive(phase: Phase): Boolean = phase.firstLiveEpochMs != null

  /**
   * The held broadcast [sid] ends for this phone, which stays Paired with [lastEnd]: a Stop never live (owner decision
   * 1), or a hold that has run out (N37; the owner's ruling, 2026-10-13). A17's record is written, as [end] writes it,
   * and the Paired pairing holds it and refuses its sid, so a late start answer or a server word naming it never starts
   * or rejoins it. The beat that goes now, not at the cadence, holds no sid, so it carries `stopped: <sid>` (RR3), which
   * the server takes as this phone's stop of that session, open or live, ending it `operator_stopped` and keeping the
   * pairing (T23; web lane, stg da3297a33). Never `state: ended` with the sid (T21): that ends the server's pairing.
   * It is the late path's beat, early: its 2xx delivers the record (`stopped-delivered`, and no later beat carries it),
   * and one that fails leaves it for the next beat, or the next pair. A beat already in flight, or a 429's wait, goes
   * first.
   */
  internal fun stoppedHere(phase: Phase, pairing: PairingState, sid: String, lastEnd: LastEnd, now: Now): Step {
    val stopped = StoppedRecord.of(pairing.name, sid, now.wallMs, phase.session?.broadcast?.maxDurationMinutes)
    val over = Verdicts.over(phase, lastEnd, now)
    val paired = over.phase as? Phase.Paired ?: return over
    val held = paired.pairing.copy(stopped = stopped, refused = paired.pairing.refused + sid)
    val step = Step(paired.copy(pairing = held), over.commands + Command.WriteStopped(stopped))
    return Heartbeats.atOnce(step, now)
  }

  /**
   * The pairing is over: the operator's Stop from a broadcast that went live (A3), or a fatal error. A Stop writes
   * A17's record before End, sends A8's one final beat, and holds the service for it.
   */
  internal fun end(phase: Phase, reason: EndReason, now: Now, vararg fields: Pair<String, Any?>): Step {
    val pairing = phase.pairing
    val sid = phase.heldSid
    val wasLive = wentLive(phase)
    val ended = phase.ended(reason, now)
    val commands = mutableListOf(endedLine(phase, reason, *fields))
    if (reason == EndReason.OPERATOR_STOPPED && pairing != null && sid != null) {
      commands += Command.WriteStopped(StoppedRecord.of(pairing.name, sid, now.wallMs, phase.session?.broadcast?.maxDurationMinutes))
    }
    commands += Command.End(reason)
    if (reason == EndReason.OPERATOR_STOPPED && wasLive && pairing != null) {
      val (beat, id) = finalBeat(phase, pairing, now)
      // Plan D §3.4: the final beat is a beat sent too, off the cadence: counted, with no interval line.
      val (beatLog, lines) = pairing.beatLog.send(id, intervalMs = null, held = true, now)
      commands += lines.map { Command.Record(it) }
      commands += beat
      // The final beat's id is spent: the next pairing's beats start after it (plan B: ids never repeat).
      val beating = pairing.copy(heartbeat = pairing.heartbeat.copy(nextId = id + 1, inFlightId = null), beatLog = beatLog)
      return Step(ended.copy(ids = ended.ids.copy(beat = id + 1), pairing = beating, finalBeatId = id, finalBeatAtMs = now.monoMs), commands)
    }
    if (phase.holdsService) commands += Command.Release
    return Step(ended, commands)
  }

  /**
   * The `ended` line, written on every way into Ended: [end], the engine's fatal step when the machine throws, and a
   * refused pair (Batch 13, the Batch 12 review's I1, the controller's). So `unpaired` and `ended` alone mark every end
   * of a pairing, and the web's record window needs no other kind; `engine-error` also has writers that end nothing.
   */
  internal fun endedLine(phase: Phase, reason: EndReason, vararg fields: Pair<String, Any?>): Command =
    record("ended", "reason" to reason, "wasLive" to wentLive(phase), *fields)

  /** A8: `{sid, state:"ended", endReason:"operator-stopped"}`, once, never retried. */
  private fun finalBeat(phase: Phase, pairing: PairingState, now: Now): Pair<Command, Int> {
    val id = pairing.heartbeat.nextId
    // State ended: nothing publishes, so audioOk is null (D14), and with no claim device is null (D1).
    val facts = Projection.beatFacts(phase, pairing, now).copy(state = "ended", claim = null, deviceModel = null, audioOk = null, endReason = EndReason.OPERATOR_STOPPED)
    return Command.PostHeartbeat(id, pairing.request.heartbeatUrl, pairing.token, Beats.payload(facts)) to id
  }

  /**
   * Recorded when it changes, in any phase: with no session it is kept for the next (final review I-1). A return while
   * Paired with A17's record undelivered sends the stopped sid at once (N37, fix round 1): the server holds that session
   * open until it hears it, and Go live is offered from this step, so a beat at the cadence would lose the race to the
   * operator's hold. A held beat never carries it (RR3), so a return while held keeps the cadence.
   *
   * N49: then the default network it names is held, and a validated one is judged against the attempt in hand
   * ([Transports.network]), against the default before it only while that one was validated. A validated report that
   * names none leaves it as it was. Review of 98c12ffa (c): a not-validated report's network is held too, unjudged, so an
   * attempt asked on it keeps its true network ([Session.attemptNetwork]); a loss names none and forgets it.
   */
  private fun network(phase: Phase, input: Input.Network, now: Now): Step {
    val handle = input.handle ?: if (input.validated) phase.defaultNetwork else null
    val told = validatedFact(phase, input.validated, now)
    if (!input.validated) return told.copy(phase = told.phase.withDefaultNetwork(handle))
    val previous = if (phase.networkValidated) phase.defaultNetwork else null
    val reset = if (relinkedElsewhere(phase, handle)) Transports.newNetwork(told.phase) else Step(told.phase)
    val judged = Transports.network(reset.phase, previous, handle, now)
    return judged.copy(commands = told.commands + reset.commands + judged.commands)
  }

  /**
   * N53: validation ends a stretch in which the link was made again while the network was not validated: a connection
   * made in it (N52's provisional one), or one still to be made. When validation names another network than the one the
   * attempt was asked on ([Session.attemptNetwork]), or either is unknown (as N52's rebind treats it), it is a new link,
   * so the regulator restarts for it ([BitrateRegulator.newNetwork]). Review of #80: on the same handle it is the link
   * that just failed (a data stall loses validation, then the drop, then validation again), so its failed rate and its
   * repeats stand and the low cap's hold escalates. A connection that outlived the stretch, made before it, is the link
   * the regulator was judging, and a flap of validation alone moves nothing.
   */
  private fun relinkedElsewhere(phase: Phase, handle: Long?): Boolean {
    val relinked = !phase.networkValidated && ((phase is Phase.OnAir && phase.provisional) || phase is Phase.Connecting)
    if (!relinked) return false
    val on = phase.session?.attemptNetwork
    return handle == null || on == null || on != handle
  }

  private fun validatedFact(phase: Phase, validated: Boolean, now: Now): Step {
    if (phase.networkValidated == validated) return Step(phase)
    val step = Step(phase.withNetwork(validated), listOf(record("network", "validated" to validated)))
    val pending = validated && phase is Phase.Paired && phase.pairing.stopped != null
    return if (pending) Heartbeats.atOnce(step, now) else step
  }

  /**
   * A failure the platform knows is permanent (amendment, _Permanent failures_; final review M-5) ends
   * the pairing fatal-error, from Pairing on, when it comes from the attempt in hand. In Idle there is
   * no pairing to end: the amendment's diagram says "from any phase but Idle" and its section "from any
   * phase", and the ruling (review C0b M3) is the diagram's. One from an older attempt is stale (final
   * review N-4), and one after the end comes too late. All three are only recorded.
   */
  private fun platformFailed(phase: Phase, input: Input.PlatformFailed, now: Now): Step {
    val fields = arrayOf("attempt" to input.attemptId, "message" to input.message)
    if (phase is Phase.Idle || phase is Phase.Ended || input.attemptId < phase.attemptInHand) return Step(phase, listOf(record("platform-failed", *fields)))
    return end(phase, EndReason.FATAL_ERROR, now, *fields)
  }
}

/** Connects, drops, frames and link readings: the attempt's life. */
internal object Transports {
  /** What the platform's counters read at a connect: they restart at zero on every one ([LinkCounters]). */
  private val CONNECT_COUNTERS = LinkCounters(0, 0, 0, 0, 0, null, null, null)

  fun connect(session: Session, transport: Transport, outage: Outage?, now: Now): Step {
    val (asked, connect) = connectCommand(session, transport, now) ?: return missingTarget(session, transport, now)
    // S6: a retry whose failure line was held back is asked with no line of its own either.
    val quiet = asked.retries.quiet
    val next = if (quiet) asked.copy(retries = asked.retries.copy(quiet = false)) else asked
    val phase = Phase.Connecting(next, transport, ConnectStep.Requested(connect.attemptId, now.monoMs), outage)
    return Step(phase, listOfNotNull(connect, record("connecting", "transport" to transport, "attempt" to connect.attemptId).takeIf { !quiet }))
  }

  /**
   * A new attempt that replaces a live one: a [Command.Rebuild] or a [Command.StartNewSession]. Both
   * reconnect the link, so both ask the descriptor again (spec §1: on every reconnect), spaced as a
   * refusal's asks are (B6 ruling C1, B7). S4: the attempt replaced proved the path if it published
   * [FallbackPolicy.SHORT_ATTEMPT_MS], as a drop after that long does ([FallbackPolicy.proved]).
   */
  fun replace(onAir: Phase.OnAir, session: Session, outage: Outage?, now: Now, wrap: (Command.Connect) -> Command): Step {
    val proved = session.copy(fallback = session.fallback.proved(onAir.framesAtMs?.let { now.monoMs - it } ?: 0))
    val (connected, connect) = connectCommand(proved, onAir.transport, now) ?: return missingTarget(proved, onAir.transport, now)
    val (next, fetch) = if (connected.descriptor.mayAsk(now.monoMs)) askDescriptor(connected, now) else connected to null
    val phase = Phase.Connecting(next, onAir.transport, ConnectStep.Requested(connect.attemptId, now.monoMs), outage)
    val connecting = record("connecting", "transport" to onAir.transport, "attempt" to connect.attemptId)
    return Step(phase, listOfNotNull(wrap(connect), connecting, fetch))
  }

  private fun connectCommand(session: Session, transport: Transport, now: Now): Pair<Session, Command.Connect>? {
    val target = session.config.target(transport) ?: return null
    val bps = session.regulation.targetBps
    val maxBw = if (transport == Transport.SRT) SrtBandwidth.maxBwBytesPerSecond(bps) else null
    val id = session.nextAttemptId
    // N49: the attempt is asked on the default network the machine holds, and keeps it until the next is asked.
    val asked = session.copy(nextAttemptId = id + 1, attemptNetwork = session.defaultNetwork)
    return graced(asked, now) to Command.Connect(id, target, bps, maxBw, asked.attemptNetwork)
  }

  /**
   * Review of 98c12ffa (b), N49: Cloudflare is read to hold the last publisher as the input's source until its read
   * timeout, so an attempt asked on another network than the last one that connected ([Session.publisherNetwork]) gets
   * the switch's grace: the watch starts its session again with no verdict for [DeliveryWatch.SWITCH_GRACE_MS]. After a
   * drop, a stall, a stuck link or a switch alike; an attempt asked on no known network gets none.
   */
  private fun graced(session: Session, now: Now): Session {
    val publisher = session.publisherNetwork ?: return session
    val asked = session.attemptNetwork ?: return session
    return if (asked == publisher) session else session.copy(delivery = session.delivery.switched(now.monoMs))
  }

  /** Unreachable after arm's checks; kept total so a bug ends the session visibly rather than throwing. */
  private fun missingTarget(session: Session, transport: Transport, now: Now): Step =
    SessionMachine.end(Phase.Armed(session), EndReason.FATAL_ERROR, now, "transport" to transport)

  fun connected(phase: Phase, attemptId: Int, now: Now): Step {
    val step = (phase as? Phase.Connecting)?.step
    if (phase is Phase.Connecting && step is ConnectStep.Requested && step.attemptId == attemptId) {
      // Review of 98c12ffa (b): the publisher is now this attempt, on the network it was asked on.
      val started = phase.session.copy(regulation = BitrateRegulator.attemptStarted(phase.session.regulation))
      val session = started.copy(publisherNetwork = started.attemptNetwork)
      // Seeded at the connect (carry 13): the first reading's bytes, drops and egress count from here.
      val meter = LinkMeter(CONNECT_COUNTERS, now.monoMs)
      // N52: connected while the default network is not validated, it is provisional, and the record's `validated` says so.
      val validated = session.networkValidated
      val onAir = Phase.OnAir(session, attemptId, phase.transport, now.monoMs, StallWatchdog(now.monoMs), meter, outage = phase.outage, provisional = !validated)
      val connected = record("connected", "transport" to phase.transport, "attempt" to attemptId, "validated" to validated)
      // N49 fix 2: asked on a network that is no longer the default (a switch while it connected): replaced now.
      val asked = session.attemptNetwork
      val default = session.defaultNetwork
      // One asked with none validated adopted the next default ([network]), so a null asked network has a null default.
      if (default == null || asked == default) return Step(onAir, listOf(connected))
      val step = switchedOver(onAir, onAir.outage ?: outageFor(session, phase.transport, now, ReconnectCause.UPLINK_LOST), now)
      return step.copy(commands = listOf(connected) + step.commands)
    }
    if (phase is Phase.OnAir && phase.attemptId == attemptId) return Step(phase)
    // A stream this machine no longer wants: close it, so nothing publishes behind the session's back.
    return Step(phase, listOf(Command.Disconnect(attemptId), record("stale-connected", "attempt" to attemptId)))
  }

  fun connectFailed(phase: Phase, input: Input.ConnectFailed, now: Now): Step {
    val step = (phase as? Phase.Connecting)?.step
    // G4: ignored, but recorded: a connect failure no one hears is a failure the record never shows.
    if (phase !is Phase.Connecting || step !is ConnectStep.Requested || step.attemptId != input.attemptId) {
      val fields = arrayOf("attempt" to input.attemptId, "failure" to input.failure, "phase" to phase.name, "message" to input.message)
      return Step(phase, listOf(record("connect-failed-ignored", *fields)))
    }
    return failed(phase, input.failure, input.message, now)
  }

  /**
   * A failed connect: counted or not by the fallback policy, then a retry [SessionMachine.RETRY_MS]
   * later. A [drop] before the connect answered is judged by the policy's drop rule with nothing
   * published: the far end closing counts (F-P5-2's collapse), a local stop does not (B6 review m5).
   * S6: before live and past its first minute of failing, the retry backs off ([NeverLiveRetries.waitMs]) and the line is
   * said at most once a minute, with the failures it stands for (`count`); a fall-back is always said.
   */
  fun failed(phase: Phase.Connecting, failure: ConnectFailure, message: String?, now: Now, drop: DropReason? = null): Step {
    val session = phase.session
    val validated = session.networkValidated
    val decision = if (drop == null) session.fallback.connectFailed(failure, validated) else session.fallback.dropped(drop, validated, publishedMs = 0)
    val neverLive = session.liveSinceEpochMs == null
    val (retries, said) = if (neverLive) session.retries.failed(now.monoMs) else session.retries to null
    val commands = mutableListOf<Command>()
    if (!neverLive || said != null) commands += failedRecord(phase.transport, failure, drop, validated, decision.counted, message, said?.count)
    if (decision.fellBack) commands += record("fell-back", "from" to phase.transport, "to" to decision.policy.current)
    var next = session.copy(fallback = decision.policy, retries = retries)
    // Ruling 15: refused ingest may mean the organiser stopped the session. B6 ruling C1 spaces them.
    if (failure == ConnectFailure.REFUSED && next.descriptor.mayAsk(now.monoMs)) {
      val (asked, fetch) = askDescriptor(next, now)
      next = asked
      commands += fetch
    }
    val waiting = ConnectStep.Waiting(now.monoMs + retryWaitMs(next))
    return Step(phase.copy(session = next, transport = decision.policy.current, step = waiting), commands)
  }

  /** S6: before live the ladder's wait for the failures so far; after, [SessionMachine.RETRY_MS], the hold bounding it. */
  private fun retryWaitMs(session: Session): Long = if (session.liveSinceEpochMs == null) session.retries.waitMs() else SessionMachine.RETRY_MS

  private fun failedRecord(transport: Transport, failure: ConnectFailure, drop: DropReason?, validated: Boolean, counted: Boolean, message: String?, count: Int?): Command {
    val fields =
      listOfNotNull(
        "transport" to transport,
        "failure" to failure,
        drop?.let { "reason" to it },
        "validated" to validated,
        "counted" to counted,
        "message" to message,
        count?.let { "count" to it },
      )
    return record("connect-failed", *fields.toTypedArray())
  }

  /** The code asked again on a reconnect (spec §1), spaced as plan B's descriptor asks were. */
  private fun askDescriptor(session: Session, now: Now): Pair<Session, Command> {
    val (asks, id) = session.descriptor.asked(now.monoMs)
    return session.copy(descriptor = asks) to Starts.fetchCode(session.pairing, id)
  }

  fun dropped(phase: Phase, input: Input.Dropped, now: Now): Step {
    val step = (phase as? Phase.Connecting)?.step
    if (phase is Phase.Connecting && step is ConnectStep.Requested && step.attemptId == input.attemptId) {
      return failed(phase, ConnectFailure.OTHER, input.message, now, drop = input.reason)
    }
    if (phase !is Phase.OnAir || phase.attemptId != input.attemptId) return Step(phase)
    val (session, fetch) = askDescriptor(phase.session, now)
    val publishedMs = now.monoMs - phase.connectedAtMs
    // A provisional connection is on a network not validated, so the policy's own rule already counts nothing for it.
    val decision = session.fallback.dropped(input.reason, session.networkValidated, publishedMs)
    val outage = phase.outage ?: outageFor(session, phase.transport, now, ReconnectCause.UPLINK_LOST)
    val quick = if (phase.provisional) session.provisional.next(now.monoMs) else null
    val commands =
      mutableListOf(
        record(
          "dropped",
          *listOfNotNull(
              "transport" to phase.transport,
              "reason" to input.reason,
              "validated" to session.networkValidated,
              "counted" to decision.counted,
              "publishedMs" to publishedMs,
              "message" to input.message,
              quick?.let { "retryMs" to it.second },
            )
            .toTypedArray(),
        ),
        fetch,
      )
    if (decision.fellBack) commands += record("fell-back", "from" to phase.transport, "to" to decision.policy.current)
    val regulation = BitrateRegulator.afterDrop(session.regulation, now.monoMs)
    dropMoved(session.regulation.targetBps, regulation.targetBps)?.let { commands += it }
    val counted = session.copy(fallback = decision.policy, regulation = regulation)
    if (quick != null) return provisionalRetry(counted, quick, outage, commands, now)
    val retries = if (session.liveSinceEpochMs == null) session.retries.dropped(now.monoMs) else session.retries
    val next = counted.copy(retries = retries)
    val waiting = ConnectStep.Waiting(now.monoMs + retryWaitMs(next))
    return Step(Phase.Connecting(next, decision.policy.current, waiting, outage), commands)
  }

  /**
   * N52 (owner ruling, 2026-11-26, option B, item 1): a provisional connection broke inside the flap bound. The next
   * attempt is asked at once, in this step, or after the bound's spacing, and the never-live ladder does not move: no
   * backoff grows. The hold is kept as every drop keeps it: it is the platform's clock (C2), not a budget of the phone's.
   */
  private fun provisionalRetry(session: Session, quick: Pair<ProvisionalRetries, Long>, outage: Outage?, commands: List<Command>, now: Now): Step {
    val (bound, waitMs) = quick
    val next = session.copy(provisional = bound)
    val transport = session.fallback.current
    if (waitMs > 0) return Step(Phase.Connecting(next, transport, ConnectStep.Waiting(now.monoMs + waitMs), outage), commands)
    val step = connect(next, transport, outage, now)
    return step.copy(commands = commands + step.commands)
  }

  /**
   * N49: the validated default network is now [handle], [previous] before; the phase already holds [handle]. A socket
   * stays on the network it was opened on, and the stall verdict that found it on 2026-11-24 took 22 s, so an attempt
   * asked on another network than the default ([Session.attemptNetwork]) is replaced, as a new ingest session is, on the
   * transport in hand, RTMPS after a fallback included; the new socket opens on the new default.
   *
   * It is judged against the attempt's network, never the last default (fix round 1, item 1): a loss and then a new
   * network inside SRT's peer-idle timeout (about 5 s) leave the old socket alive through the new route. Connected, it is
   * replaced at once, keeping the outage it is in or opening one ([switchedOver]). Still connecting, it is replaced once
   * it connects ([connected]; item 2), and one that ends up back on its own network is not. Between attempts the next is
   * asked on the new default. An attempt asked while none was validated adopts the next one. Every switch with a network
   * before it writes `network-switched` with its result; a switch away from no network (the first, or after a loss) only
   * a replace's.
   *
   * Its outage's cause is [ReconnectCause.UPLINK_LOST]: the snapshot's word for an uplink being replaced, which JS
   * already reads. It is no failure: the fallback policy never hears it.
   */
  fun network(phase: Phase, previous: Long?, handle: Long?, now: Now): Step {
    val defaulted = phase.withDefaultNetwork(handle)
    // N52, review of #79: every validated fact ends a provisional connection, so provisional always means connected and
    // not validated since. One that names no network, or none new, confirms it here; any other is judged below.
    val unjudged = handle == null || handle == previous
    val next = if (unjudged && defaulted is Phase.OnAir && defaulted.provisional) defaulted.copy(provisional = false) else defaulted
    if (unjudged) return Step(next)
    val session = next.session
    val asked = next is Phase.OnAir || (next as? Phase.Connecting)?.step is ConnectStep.Requested
    if (session == null || !asked) {
      if (previous == null) return Step(next)
      if (next !is Phase.Connecting) return Step(next, listOf(switchLine(NetworkSwitch.NOT_ON_AIR, next)))
      // N53: the next attempt is asked on the new network, a new link.
      val reset = newNetwork(next)
      return reset.copy(commands = listOf(switchLine(NetworkSwitch.BETWEEN_ATTEMPTS, next)) + reset.commands)
    }
    if (next is Phase.OnAir && next.provisional) return provisionalValidated(next, session, handle, now)
    val on = session.attemptNetwork ?: return Step(next.withSession(session.copy(attemptNetwork = handle)))
    if (on == handle) return Step(next, listOfNotNull(switchLine(NetworkSwitch.SAME, next).takeIf { previous != null }))
    if (next !is Phase.OnAir) return Step(next, listOf(switchLine(NetworkSwitch.PENDING, next)))
    return switchedOver(next, next.outage ?: outageFor(session, next.transport, now, ReconnectCause.UPLINK_LOST), now)
  }

  /**
   * N52 (owner ruling, 2026-11-26, option B, items 2 and 3): the default network [handle] validated while the connection
   * in hand is provisional. Decided from the handle the attempt was asked on ([Session.attemptNetwork]), which is the one
   * its SRT socket is bound to ([Command.Connect.network]): the same handle confirms it, with no reconnect, and from here
   * its breaks are today's. Another handle, or none (asked while no network was known, so its route is unknown, unlike
   * N49's adopt for a connection made on a validated network), replaces it at once on the validated network, as a switch
   * does, its line saying `reason: provisional`.
   */
  private fun provisionalValidated(onAir: Phase.OnAir, session: Session, handle: Long, now: Now): Step {
    if (session.attemptNetwork == handle) return Step(onAir.copy(provisional = false))
    return switchedOver(onAir, onAir.outage ?: outageFor(session, onAir.transport, now, ReconnectCause.UPLINK_LOST), now, provisional = true)
  }

  private fun switchLine(result: NetworkSwitch, phase: Phase, provisional: Boolean = false): Command {
    val fields = listOfNotNull("result" to result, "attempt" to phase.attemptInHand, "phase" to phase.name, ("reason" to "provisional").takeIf { provisional })
    return record("network-switched", *fields.toTypedArray())
  }

  /**
   * N49: the attempt replaced for a switch, a new link on another network (fix round 1, items 3 and 4). The regulator
   * restarts for it (N53, [BitrateRegulator.newNetwork]), so the new attempt is asked at that target, and the delivery
   * watch starts its session again, with no verdict for [DeliveryWatch.SWITCH_GRACE_MS] ([DeliveryWatch.switched]).
   */
  private fun switchedOver(onAir: Phase.OnAir, outage: Outage?, now: Now, provisional: Boolean = false): Step {
    val line = switchLine(NetworkSwitch.REPLACED, onAir, provisional)
    val reset = newNetwork(onAir)
    val old = reset.phase.session ?: return Step(onAir)
    val session = old.copy(delivery = old.delivery.switched(now.monoMs))
    val step = replace(onAir, session, outage, now) { Command.StartNewSession(onAir.attemptId, it) }
    return step.copy(commands = listOf(line) + reset.commands + step.commands)
  }

  /**
   * N53: the regulator restarted for a new network ([BitrateRegulator.newNetwork]). A target it moved is said as a
   * `bitrate` line, reason `new-network`, as a drop's move is ([dropMoved]); on air the encoder hears it at once, as it
   * hears a reading's move. A phase with no session has no regulator.
   */
  fun newNetwork(phase: Phase): Step {
    val session = phase.session ?: return Step(phase)
    val regulation = BitrateRegulator.newNetwork(session.regulation)
    val next = phase.withSession(session.copy(regulation = regulation))
    val before = session.regulation.targetBps
    val after = regulation.targetBps
    if (after == before) return Step(next)
    val commands = mutableListOf<Command>()
    if (phase is Phase.OnAir) {
      commands += Command.SetBitrate(phase.attemptId, after)
      if (phase.transport == Transport.SRT) commands += Command.SetMaxBw(phase.attemptId, SrtBandwidth.maxBwBytesPerSecond(after))
    }
    commands += record("bitrate", "from" to before, "to" to after, "reason" to BitrateMove.NEW_NETWORK)
    return Step(next, commands)
  }

  /** An outage only exists for a session that has been live: before that, there is nothing to hold. */
  fun outageFor(session: Session, transport: Transport, now: Now, cause: ReconnectCause): Outage? {
    if (session.liveSinceEpochMs == null) return null
    val hold = HoldClock.started(session.config.holdWindowSeconds, transport, now.monoMs) ?: Hold(transport, 0, now.monoMs)
    return Outage(hold, cause)
  }

  fun frames(phase: Phase, input: Input.Frames, now: Now): Step {
    if (phase !is Phase.OnAir || phase.attemptId != input.attemptId) return Step(phase)
    val session = phase.session
    val (watchdog, verdict) =
      phase.watchdog.frames(input.videoFrames, input.audioFrames, now.monoMs, session.camera.slateOnAir, session.micSilenced, session.device?.shed)
    var next = phase.copy(watchdog = watchdog)
    // S1: a window at the floors starts the below-floor count again.
    if (watchdog.ratedHealthy && session.belowFloor.count > 0) next = next.copy(session = session.copy(belowFloor = BelowFloorRebuilds()))
    val commands = mutableListOf<Command>()
    if (watchdog.advancing(now.monoMs)) {
      if (next.framesAtMs == null) next = next.copy(framesAtMs = now.monoMs)
      if (next.session.liveSinceEpochMs == null) {
        // Re-review of d006c2dd, item 3: going live says the start's failures the minute held back.
        val (retries, held) = next.session.retries.said(phase.transport)
        next = next.copy(session = next.session.copy(liveSinceEpochMs = now.wallMs, retries = retries))
        commands += record("live", "transport" to phase.transport)
        held?.let { commands += it }
      }
      // A reopened or switched camera is ours again once it delivers a frame.
      if (next.session.camera == CameraState.RESUMING || next.session.camera == CameraState.SWITCHING) {
        next = next.copy(session = next.session.copy(camera = CameraState.OWN))
        commands += record("camera-resumed")
      }
      next.outage?.let { outage ->
        commands += record("resumed", "transport" to phase.transport, "cause" to outage.cause, "outageMs" to now.monoMs - outage.hold.startedAtMs)
        next = next.copy(outage = null)
      }
    }
    if (verdict !is StallVerdict.Rebuild) return Step(next, commands)
    // S1: a frames reading rebuilds only below the floor (the time rules are the tick's), and past three in a row it
    // waits its turn, the slow picture publishing meanwhile.
    val floor = next.session.belowFloor
    if (!floor.mayRebuild(now.monoMs)) return Step(next, commands)
    next = next.copy(session = next.session.copy(belowFloor = floor.rebuilt(now.monoMs)))
    val rebuilt = Timers.rebuild(next, verdict, now)
    return rebuilt.copy(commands = commands + rebuilt.commands)
  }

  fun link(phase: Phase, input: Input.Link, now: Now): Step {
    if (phase !is Phase.OnAir || phase.attemptId != input.attemptId) return Step(phase)
    val session = phase.session
    val (meter, sample) = phase.meter.read(input.counters, now.monoMs)
    val latency = (session.config.target(Transport.SRT) as? SrtTarget)?.latencyMs ?: 0
    val regulated = BitrateRegulator.regulate(session.regulation, sample, now.monoMs, phase.transport, latency)
    val regulation = regulated.state
    val counters = input.counters
    val srt = if (phase.transport == Transport.SRT) SrtTelemetry(counters.packetsSent, counters.packetsRetransmitted, counters.packetsDropped, counters.rttMs) else null
    val next =
      phase.copy(
        session = session.copy(regulation = regulation, dataUsedBytes = session.dataUsedBytes + sample.bytes),
        meter = meter,
        srt = srt,
        egressBps = sample.egressBps ?: phase.egressBps,
      )
    val commands = regulatedCommands(phase, session.regulation.targetBps, regulated, sample)
    val (stuckWatch, stuck) = phase.stuck.read(sample, regulation.targetBps, phase.transport, latency, now.monoMs)
    val advancing = session.delivery.sinceAdvanceMs < StuckReplaces.STUCK_UNDELIVERED_MS
    val replaces = session.stuckReplaces.read(stuckWatch.readings > 0 || stuck != null, advancing, now.monoMs)
    val watched = next.copy(stuck = stuckWatch, session = next.session.copy(stuckReplaces = replaces))
    if (stuck == null) return Step(watched, commands)
    // Review of batch 2, item 1: a picture the playlist still delivers is never replaced, and replaces back off. A
    // verdict refused here keeps its run (re-review of d006c2dd, item 4): it acts at the first reading the gate allows.
    if (advancing || !replaces.mayReplace(now.monoMs)) return Step(watched.copy(stuck = phase.stuck.refused(stuck, now.monoMs)), commands)
    val replaced = linkStuck(watched, stuck, sample, now)
    return replaced.copy(commands = commands + replaced.commands)
  }

  /** The regulator's move, its held raise and its floor cut, as commands and lines. */
  private fun regulatedCommands(phase: Phase.OnAir, before: Int, regulated: Regulated, sample: LinkSample): MutableList<Command> {
    val target = regulated.state.targetBps
    val commands = mutableListOf<Command>()
    if (target != before) {
      commands += Command.SetBitrate(phase.attemptId, target)
      if (phase.transport == Transport.SRT) commands += Command.SetMaxBw(phase.attemptId, SrtBandwidth.maxBwBytesPerSecond(target))
      commands += bitrateLine(before, regulated, sample)
    }
    regulated.held?.let { commands += heldLine(it, phase.watchdog.videoFps) }
    regulated.floor?.let { commands += floorLine(it, sample) }
    return commands
  }

  /**
   * The cause split (owner, 2026-11-24): the phone's own link is stuck ([StuckWatch]), so it is replaced at once, as a new
   * ingest session, on the transport in hand, with no backoff and before the playlist's verdict. The outage it is in is
   * kept, or one opens now: the hold bounds it as any reconnect. It is no failure: the fallback policy never hears it.
   * Its outage's cause is [ReconnectCause.UPLINK_LOST], the snapshot's word for an uplink being replaced.
   */
  private fun linkStuck(onAir: Phase.OnAir, stuck: Stuck, sample: LinkSample, now: Now): Step {
    // Review of batch 2, item 2: the new ingest session proves itself from zero, as a not-delivered restart's does.
    val replaces = onAir.session.stuckReplaces.replaced(now.monoMs)
    val session = onAir.session.copy(delivery = onAir.session.delivery.newSession(), stuckReplaces = replaces)
    val line =
      record(
        "link-stuck",
        "attempt" to onAir.attemptId,
        "count" to stuck.readings,
        "retryMs" to StuckReplaces.waitMs(replaces.count + 1),
        "target" to session.regulation.targetBps,
        "sendBufferMs" to sample.sendBufferMs,
        "egressBps" to sample.egressBps,
        "dropped" to sample.droppedPackets,
        "lost" to sample.lostPackets,
      )
    val outage = onAir.outage ?: outageFor(session, onAir.transport, now, ReconnectCause.UPLINK_LOST)
    val step = replace(onAir, session, outage, now) { Command.StartNewSession(onAir.attemptId, it) }
    return step.copy(commands = listOf(line) + step.commands)
  }

  /** S3: a drop that moved the target says so, as a reading's move does, with no reading to cite. */
  private fun dropMoved(before: Int, after: Int): Command? {
    if (after == before) return null
    val move = if (after < before) BitrateMove.DROP_HALVED else BitrateMove.DROP_RESTORED
    return record("bitrate", "from" to before, "to" to after, "reason" to move)
  }

  /**
   * R-990P: the move, the rule that made it, and the reading it was made on. A raise was decided on its interval's mean
   * egress, not the reading's, so its line carries that mean, and the due raises held before it (`count`).
   */
  private fun bitrateLine(before: Int, regulated: Regulated, sample: LinkSample): Command {
    val tested = regulated.tested
    val fields =
      listOf(
        "from" to before,
        "to" to regulated.state.targetBps,
        "reason" to regulated.move,
        "egressBps" to (tested?.egressBps ?: sample.egressBps),
        "dropped" to sample.droppedPackets,
        "lost" to sample.lostPackets,
        "sendBufferMs" to sample.sendBufferMs,
        "bandwidthBps" to sample.bandwidthBps,
      )
    return Command.Record(RecordEntry("bitrate", fields + listOfNotNull(tested?.let { "count" to it.heldBefore })))
  }

  /** R-RTRF (N49): cuts that found the target at the floor, which no `bitrate` line says, and the reading said on. */
  private fun floorLine(floor: FloorCut, sample: LinkSample): Command =
    record(
      "bitrate-floor",
      "reason" to floor.reason,
      "count" to floor.count,
      "egressBps" to sample.egressBps,
      "dropped" to sample.droppedPackets,
      "lost" to sample.lostPackets,
      "sendBufferMs" to sample.sendBufferMs,
    )

  /** R-990P: F-P5-7's gate held due raises, and the encoded frame rate while it did (r2's question). */
  private fun heldLine(held: HeldRaise, videoFps: Double?): Command =
    record(
      "bitrate-held",
      "target" to held.targetBps,
      "egressBps" to held.egressBps,
      "gateBps" to held.gateBps,
      "count" to held.count,
      "videoFps" to videoFps,
    )
}

/**
 * N49 fix round 1, item 5: cuts at the floor since the last `bitrate-floor` line ([Regulation.floorCuts]) are said by the
 * next one, a minute on, and a session that ends before it would never say them. So the step that ends a session (no
 * session after it, or another sid's) writes them, under the last such cut's rule, with no reading: none was said on.
 * Called on every step the machine takes, and by the [Engine] on the fatal step it builds when the machine throws.
 */
internal object FloorCuts {
  fun closed(before: Phase, step: Step): Step {
    val session = before.session ?: return step
    val regulation = session.regulation
    val reason = regulation.floorReason
    if (regulation.floorCuts == 0 || reason == null || step.phase.session?.sid == session.sid) return step
    return step.copy(commands = step.commands + record("bitrate-floor", "reason" to reason, "count" to regulation.floorCuts))
  }
}

/** Everything the tick decides: retries, connect timeouts, hold expiry, the stall watchdog, playlist polls. */
internal object Timers {
  fun tick(phase: Phase, now: Now): Step =
    when (phase) {
      is Phase.Connecting -> connecting(phase, now)
      is Phase.OnAir -> onAir(phase, now)
      is Phase.Arming -> Starts.tick(phase, now)
      is Phase.Paired -> Verdicts.tick(phase, now)
      is Phase.Idle, is Phase.Pairing, is Phase.Armed, is Phase.Ended -> Step(phase)
    }

  private fun connecting(connecting: Phase.Connecting, now: Now): Step {
    if (connecting.outage?.hold?.expired(now.monoMs) == true) return expired(connecting, connecting.session, connecting.outage, now)
    // Off air: the delivery watch must see it, or the gap would count as a delivery stall.
    val offAir = connecting.session.delivery.tick(now.monoMs, onAirNow = false).first
    val phase = connecting.copy(session = connecting.session.copy(delivery = offAir))
    return when (val step = phase.step) {
      is ConnectStep.Waiting ->
        if (now.monoMs >= step.connectAtMs) Transports.connect(phase.session, phase.transport, phase.outage, now) else Step(phase)
      is ConnectStep.Requested ->
        if (now.monoMs - step.atMs < SessionMachine.CONNECT_TIMEOUT_MS) Step(phase)
        else {
          val failed = Transports.failed(phase, ConnectFailure.TIMEOUT, "no answer in ${SessionMachine.CONNECT_TIMEOUT_MS} ms", now)
          failed.copy(commands = listOf(Command.Disconnect(step.attemptId)) + failed.commands)
        }
    }
  }

  /**
   * What the tick would judge first, before an input other than the tick or a stop (B6 ruling C2), in
   * the tick's own order: a hold that has run out ends the session (B7 N1), so nothing is sent into a
   * hold that is already over, and the input lands on the ended session, where it can reopen nothing
   * (B7 ruling m2); then the no-video judgement. A platform failure is judged by [holdRanOut] alone.
   */
  fun judged(phase: Phase, now: Now): Step {
    holdRanOut(phase, now)?.let { return it }
    if (phase !is Phase.OnAir) return Step(phase)
    val verdict = phase.watchdog.tick(now.monoMs, phase.session.camera.slateOnAir).second
    return if (verdict is StallVerdict.Rebuild) rebuild(phase, verdict, now) else Step(phase)
  }

  /** The session ended by a hold that has run out (B7 N1), or null while none has. */
  fun holdRanOut(phase: Phase, now: Now): Step? =
    when (phase) {
      is Phase.OnAir -> phase.outage?.takeIf { it.hold.expired(now.monoMs) }?.let { expired(phase, phase.session, it, now) }
      is Phase.Connecting -> phase.outage?.takeIf { it.hold.expired(now.monoMs) }?.let { expired(phase, phase.session, it, now) }
      is Phase.Idle, is Phase.Pairing, is Phase.Paired, is Phase.Arming, is Phase.Armed, is Phase.Ended -> null
    }

  private fun onAir(phase: Phase.OnAir, now: Now): Step {
    val outage = phase.outage
    if (outage != null && outage.hold.expired(now.monoMs)) return expired(phase, phase.session, outage, now)
    val (watchdog, verdict) = phase.watchdog.tick(now.monoMs, phase.session.camera.slateOnAir)
    val watched = phase.copy(watchdog = watchdog)
    if (verdict is StallVerdict.Rebuild) return rebuild(watched, verdict, now)
    // S2: a not-delivered restart whose backoff is over.
    // Review of batch 2, item 5: a link that turned poor inside the wait is the phone's, so it goes at once.
    if (watched.restartAtMs?.let { now.monoMs >= it || Deliveries.poorLinkDue(watched, now) } == true) {
      return Deliveries.restart(watched.copy(restartAtMs = null, recoveredAtMs = null), now)
    }
    Deliveries.recovered(watched, now)?.let { return it }
    // Publishing time counts only while frames advance: our own reopen or switch pauses it until the
    // camera's first frame, so a pause of ours never reads as Cloudflare falling behind.
    val (delivery, requests) = watched.session.delivery.tick(now.monoMs, watchdog.advancing(now.monoMs))
    val polled = watched.copy(session = watched.session.copy(delivery = delivery))
    return Step(polled, requests.map { Command.FetchPlaylist(it.id, it.url) })
  }

  /**
   * The hold ran out: the phone ends the broadcast itself, back to Paired (N37; the owner's ruling, 2026-10-13, option
   * a). The server may still hold it open (its own lost-phone end is 15 min), so the phone stops it as it stops one never
   * live ([SessionMachine.stoppedHere]): its sid refused, A17's record written, and `stopped: <sid>` on a beat now and
   * on every beat until a 2xx. A `live` or an `already_live` naming it later starts nothing: a restored network waits
   * for a new Go live, and never rejoins it.
   */
  private fun expired(phase: Phase, session: Session, outage: Outage, now: Now): Step {
    val over = SessionMachine.stoppedHere(phase, session.pairing, session.sid, LastEnd.HOLD_WINDOW_EXPIRED, now)
    return over.copy(commands = listOf(record("hold-expired", "transport" to outage.hold.transport, "cause" to outage.cause)) + over.commands)
  }

  /**
   * The stall watchdog asked for a rebuild (F-P5-6, F-P5-9). The record's `msSinceAdvance` counts
   * from the last frame; for `no-first-frame`, from the connect; and when `rebaselined` is true, from
   * our own camera reopen, which is not a frame (carry 7), or for a switch from the tap or the last
   * frame before it ([Devices.switched]). S1: a below-floor rebuild also says how many in a row it is (`count`) and how
   * long the next must wait (`retryMs`, [BelowFloorRebuilds.waitMs]).
   */
  fun rebuild(onAir: Phase.OnAir, verdict: StallVerdict.Rebuild, now: Now): Step {
    val outage = onAir.outage ?: Transports.outageFor(onAir.session, onAir.transport, now, ReconnectCause.VIDEO_STALLED)
    val watchdog = onAir.watchdog
    val floor = onAir.session.belowFloor.takeIf { verdict.cause == StallCause.BELOW_FLOOR }
    val fields =
      listOf(
        "cause" to verdict.cause,
        "msSinceAdvance" to verdict.msSinceAdvance,
        "rebaselined" to (watchdog.lastAdvanceAtMs == null && watchdog.stallFromMs != null),
        "videoFps" to verdict.videoFps,
        "audioFps" to verdict.audioFps,
      ) + listOfNotNull(floor?.let { "count" to it.count }, floor?.let { "retryMs" to BelowFloorRebuilds.waitMs(it.count + 1) })
    val stalled = Command.Record(RecordEntry("video-stalled", fields))
    val step = Transports.replace(onAir, onAir.session, outage, now) { Command.Rebuild(onAir.attemptId, it) }
    return step.copy(commands = listOf(stalled) + step.commands)
  }
}

/** Camera, microphone and device conditions. */
internal object Devices {
  /** I2: the slate is a picture sent in our camera's place, so it goes up only while sending. Armed sends nothing. */
  private fun sending(phase: Phase) = phase is Phase.Connecting || phase is Phase.OnAir

  fun contended(phase: Phase): Step {
    val session = phase.session ?: return onPairing(phase, CameraState.TAKEN, record("camera-taken")) { it != CameraState.TAKEN }
    if (session.camera == CameraState.TAKEN) return Step(phase)
    val next = phase.withSession(session.copy(camera = CameraState.TAKEN))
    // Taken again mid-reopen: the slate never came down, so it is not put up twice.
    val slate = if (!sending(phase) || session.camera.slateOnAir) emptyList() else listOf(Command.Slate(on = true))
    return Step(next, slate + record("camera-taken"))
  }

  fun released(phase: Phase): Step {
    val session = phase.session ?: return onPairing(phase, CameraState.REOPENING, Command.ReopenCamera, record("camera-released")) { it == CameraState.TAKEN }
    if (session.camera != CameraState.TAKEN) return Step(phase)
    return Step(phase.withSession(session.copy(camera = CameraState.REOPENING)), listOf(Command.ReopenCamera, record("camera-released")))
  }

  fun reopened(phase: Phase, ok: Boolean, now: Now): Step {
    val session = phase.session ?: return onPairing(phase, CameraState.OWN, record("camera-reopened", "ok" to ok)) { it == CameraState.REOPENING }
    if (session.camera != CameraState.REOPENING) return Step(phase)
    val own = phase.withSession(session.copy(camera = CameraState.OWN))
    val commands = (if (sending(phase)) listOf(Command.Slate(on = false)) else emptyList()) + record("camera-reopened", "ok" to ok)
    if (own !is Phase.OnAir) return Step(own, commands)
    if (ok) {
      // A fresh stall window from now, and camera-taken until the camera's first frame (carry 6).
      val resuming = own.copy(session = own.session.copy(camera = CameraState.RESUMING), watchdog = own.watchdog.rebaselined(now.monoMs))
      return Step(resuming, commands)
    }
    // The spike's forced rebuild: a reopen that failed leaves the stream's own restart as the recovery.
    val rebuilt = Timers.rebuild(own, StallVerdict.Rebuild(StallCause.NO_VIDEO, 0, null, null), now)
    return rebuilt.copy(commands = commands + rebuilt.commands)
  }

  /** I3: no broadcast held, so the pairing keeps the camera. No slate: nothing is sent. Ended keeps nothing. */
  private fun onPairing(phase: Phase, to: CameraState, vararg commands: Command, from: (CameraState) -> Boolean): Step {
    val pairing = phase.pairing?.takeIf { phase !is Phase.Ended && from(it.camera) } ?: return Step(phase)
    return Step(phase.withPairing(pairing.copy(camera = to)), commands.toList())
  }

  /**
   * The operator's switch (ruling 14). On air, the stall watchdog starts a fresh rate window, so the
   * switch's frame pause is not a rate-floor rebuild, and the session's LIVE state holds until the
   * new camera's first frame. Only while frames advance is a switch handled that way (B6 review
   * I1): before the first frame, F-P5-6's 5 s grace already covers the pause, and a picture already
   * past its stall window was judged a rebuild before this input (B6 ruling C2). A re-baseline
   * clears the last frame, and the camera is ours again in the step that brings the next one, so a
   * second switch before the new camera's first frame keeps the first one's window.
   *
   * The owner-visible bound (B6 review m4, B6 fix 2, B7 N3): a healthy switch, one taken within
   * [StallWatchdog.FRAME_SAMPLE_MS] of a frame, has its full 3 s from the tap. Any other counts from
   * the last frame before the first switch, so a switch in the middle of a stall cannot stretch LIVE.
   * The next input at or after those 3 s rebuilds, and the tick is an input every [Engine.TICK_MS]. So
   * LIVE outlasts the last real frame by at most 3.5 s while Frames readings come every tick, and by
   * under 4 s when no reading follows the tap and the ticks fall off the frame grid (B7 ruling m1,
   * amended).
   * Plan C keeps the readings coming through a switch, flat or not.
   */
  fun switched(phase: Phase, now: Now): Step {
    val session = phase.session ?: return SessionMachine.ignored(phase, "switch-camera")
    if (session.camera.slateOnAir) return SessionMachine.ignored(phase, "switch-camera")
    val commands = listOf(Command.SwitchCamera, record("camera-switched"))
    if (phase !is Phase.OnAir || !phase.watchdog.advancing(now.monoMs)) return Step(phase, commands)
    // Advancing means a frame has arrived, so the fallback to now never applies.
    val lastFrameMs = phase.watchdog.lastAdvanceAtMs ?: now.monoMs
    val fromMs = if (now.monoMs - lastFrameMs < StallWatchdog.FRAME_SAMPLE_MS) now.monoMs else lastFrameMs
    val switching = phase.copy(session = session.copy(camera = CameraState.SWITCHING), watchdog = phase.watchdog.rebaselined(fromMs))
    return Step(switching, commands)
  }

  /** Recorded when it changes, in any phase: with no session it is kept for the next (review 18 I1). */
  fun mic(phase: Phase, silenced: Boolean): Step {
    if (phase.micSilenced == silenced) return Step(phase)
    val kind = if (silenced) "mic-silenced" else "mic-restored"
    return Step(phase.withMic(silenced), listOf(record(kind)))
  }

  /** Relayed, and recorded when it changes (AGENTS §11). Never a session state (AGENTS §8). */
  fun sampled(phase: Phase, sample: DeviceSample): Step {
    val session = phase.session ?: return Step(phase)
    val previous = session.device
    val commands = mutableListOf<Command>()
    if (previous?.thermalStatus != sample.thermalStatus) {
      commands += record("thermal", "status" to sample.thermalStatus, "headroom" to sample.thermalHeadroom)
    }
    if (previous?.shed != sample.shed) commands += record("shed", "shed" to sample.shed)
    return Step(phase.withSession(session.copy(device = sample)), commands)
  }
}

/** The delivered-playlist watch (F-P5-13) wired to the session. */
internal object Deliveries {
  /**
   * An answer is evidence only while publishing (carry 11): one that lands off air — after a drop,
   * or while frames do not advance — is dropped here, before the watch, so it can neither clear
   * not-delivered nor set a delivery. The watch's own off-air tick then abandons the request.
   */
  fun fetched(phase: Phase, input: Input.PlaylistFetched, now: Now): Step {
    if (phase !is Phase.OnAir || !phase.watchdog.advancing(now.monoMs)) return Step(phase)
    val session = phase.session
    val (watch, requests, verdict) = session.delivery.fetched(input.requestId, input.result, now.monoMs)
    val commands = requests.map<PlaylistRequest, Command> { Command.FetchPlaylist(it.id, it.url) }.toMutableList()
    // Re-review 6: a rendition refused off the playback host leaves delivery unknown; the record says why, once.
    if (watch.refusedOffHost && !session.delivery.refusedOffHost) commands += record("rendition-refused", "reason" to DeliveryWatch.OFF_HOST)
    var next = session.copy(delivery = watch)
    var waiting = phase.restartAtMs
    if (next.notDelivered && watch.delivery == Delivery.OK) {
      // S2: delivered again, so the backoff starts over, and a restart waiting for it never goes.
      next = next.copy(notDelivered = false, deliveryRestarts = 0)
      waiting = null
      commands += record("delivered")
    }
    val updated = phase.copy(session = next, restartAtMs = waiting, recoveredAtMs = phase.recoveredAtMs?.takeIf { waiting != null })
    // A restart already waits: the looks inside its wait say nothing more.
    if (verdict == null || waiting != null) return Step(updated, commands)
    val judged = notDelivered(updated, verdict, now)
    return judged.copy(commands = commands + judged.commands)
  }

  /**
   * Not delivered: say so until the playlist moves again, and force a new ingest session. S2, the owner's ruling
   * (2026-11-24, "Back off, never stop"): with the phone's own link healthy the fault is past it, so the restart waits
   * [DeliveryWatch.restartWaitMs], 0 for the first verdict since `delivered` and doubling to 64 s, and the stream
   * publishes meanwhile. The cause split: a link that failed lately, what the operator sees as Weak signal (a cut in the
   * last [SessionMachine.POOR_UPLINK_MS], [DegradeReason.POOR_UPLINK]), is the phone's, so it restarts at once. The line
   * carries the verdict's number since `delivered` (`count`) and its wait (`retryMs`).
   */
  private fun notDelivered(onAir: Phase.OnAir, verdict: NotDelivered, now: Now): Step {
    val ownLinkFailing = ownLinkFailing(onAir, now)
    // Review of batch 2, item 5: a restart for a poor link is not a step of the delivery ladder. One fault, one ladder
    // (re-review of d006c2dd, item 2, the controller's ruling): it waits out the stuck ladder instead, as its step.
    val restarts = if (ownLinkFailing) onAir.session.deliveryRestarts else onAir.session.deliveryRestarts + 1
    val waitMs = if (ownLinkFailing) onAir.session.stuckReplaces.waitLeftMs(now.monoMs) else DeliveryWatch.restartWaitMs(restarts)
    val noted =
      record(
        "not-delivered",
        "cause" to verdict.cause,
        "stalledMs" to verdict.stalledMs,
        "lagGrowthMs" to verdict.lagGrowthMs,
        "count" to restarts,
        "retryMs" to waitMs,
      )
    val counted = onAir.copy(session = onAir.session.copy(notDelivered = true, deliveryRestarts = restarts))
    if (waitMs > 0) {
      // A healthy wait is the delivery ladder's already, never later than this, so it is only ever a poor wait's.
      val recoveredAt = now.monoMs + DeliveryWatch.restartWaitMs(restarts + 1)
      return Step(counted.copy(restartAtMs = now.monoMs + waitMs, recoveredAtMs = recoveredAt), listOf(noted))
    }
    val step = restart(counted, now)
    return step.copy(commands = listOf(noted) + step.commands)
  }

  /** The cause split: the phone's own link failed lately, what the operator sees as Weak signal. */
  fun ownLinkFailing(onAir: Phase.OnAir, now: Now): Boolean = DegradeReason.POOR_UPLINK in Projection.reasons(onAir.session, now)

  /**
   * Re-review of 28f46d54: a restart waiting out the stuck ladder whose link is no longer poor goes at the delivery
   * ladder's time when that is sooner, as that ladder's step. Null while it waits on.
   */
  fun recovered(onAir: Phase.OnAir, now: Now): Step? {
    val at = onAir.recoveredAtMs ?: return null
    if (onAir.restartAtMs == null || now.monoMs < at || ownLinkFailing(onAir, now)) return null
    val stepped = onAir.session.copy(deliveryRestarts = onAir.session.deliveryRestarts + 1)
    return restart(onAir.copy(session = stepped, restartAtMs = null, recoveredAtMs = null), now)
  }

  /** A waiting restart whose link turned poor goes once the stuck ladder allows it, not at the delivery ladder's time. */
  fun poorLinkDue(onAir: Phase.OnAir, now: Now): Boolean = ownLinkFailing(onAir, now) && onAir.session.stuckReplaces.mayReplace(now.monoMs)

  /** The restart a not-delivered verdict asked for: a new ingest session, which the watch follows from zero. */
  fun restart(onAir: Phase.OnAir, now: Now): Step {
    // One fault, one ladder: a restart for a poor link is a step of the stuck ladder.
    val replaces = onAir.session.stuckReplaces.let { if (ownLinkFailing(onAir, now)) it.replaced(now.monoMs) else it }
    val session = onAir.session.copy(delivery = onAir.session.delivery.newSession(), stuckReplaces = replaces)
    val outage = onAir.outage ?: Transports.outageFor(session, onAir.transport, now, ReconnectCause.NOT_DELIVERED)
    return Transports.replace(onAir, session, outage, now) { Command.StartNewSession(onAir.attemptId, it) }
  }
}

/** The pairing's beats (amendment, _Contract shapes_): Paired to On air, never gating anything. */
internal object Heartbeats {
  /**
   * The next beat due now, not at the cadence, and sent in this step unless a beat is in flight or a 429's wait holds
   * (both go first, and the next beat follows them at once). For A17's stopped sid, which rides only a beat ([tick]).
   */
  fun atOnce(step: Step, now: Now): Step {
    val pairing = step.phase.beating ?: return step
    val due = pairing.copy(heartbeat = pairing.heartbeat.copy(lastSentAtMonoMs = null))
    return tick(step.copy(phase = step.phase.withPairing(due)), now)
  }

  fun tick(step: Step, now: Now): Step {
    val phase = step.phase
    val pairing = phase.beating ?: return step
    val held = phase.heldSid
    val interval = BeatCadence.intervalMs(pairing.waiting.pollSeconds, held = held != null, confirming = pairing.starts.pending)
    val (heartbeat, id) = Heartbeat.due(pairing.heartbeat, now.monoMs, now.wallMs, interval)
    val commands = step.commands.toMutableList()
    val timedOut = heartbeat.failures > pairing.heartbeat.failures
    if (timedOut) commands += record("heartbeat", "result" to HeartbeatResult.FAILED, "message" to "no answer")
    // Plan D §3.4: a beat given up is a failed one in the minute's count.
    val counted = if (timedOut) pairing.beatLog.heard(word = null, ok = false) else pairing.beatLog
    if (id == null) return Step(phase.withPairing(pairing.copy(heartbeat = heartbeat, beatLog = counted)), commands)
    // RR3: the stopped sid rides only a beat that holds none.
    val carried = if (held == null) pairing.stopped?.sid else null
    val (beatLog, lines) = counted.send(id, interval, held = held != null, now)
    val next = pairing.copy(heartbeat = heartbeat, stoppedInFlight = carried, beatLog = beatLog)
    val nextPhase = phase.withPairing(next)
    val body = Beats.payload(Projection.beatFacts(nextPhase, next, now))
    commands += lines.map { Command.Record(it) }
    commands += Command.PostHeartbeat(id, pairing.request.heartbeatUrl, pairing.token, body)
    return Step(nextPhase, commands)
  }

  fun answered(phase: Phase, input: Input.HeartbeatAnswered, now: Now): Step {
    val pairing = phase.beating ?: return Step(phase)
    val heartbeat = Heartbeat.answered(pairing.heartbeat, input.beatId, input.response, now.monoMs)
    if (heartbeat == pairing.heartbeat) return Step(phase)
    val response = input.response as? HeartbeatResponse.Answered
    val word = ServerWord.ofBeat(input.response, named = phase.heldSid ?: pairing.stoppedInFlight)
    val ok = response != null && response.status in 200..299
    // Final review I-2b: a 2xx whose waiting fields cannot be read leaves the pairing's as they were, and says so.
    val unreadable = WaitingUnread.line(pairing, response?.reply?.waitingUnreadable?.takeIf { ok }, "beat")
    val refreshed =
      pairing.copy(
        heartbeat = heartbeat,
        claim = if (ok) null else pairing.claim,
        waiting = response?.reply?.waiting?.takeIf { ok }?.overBeat(pairing.waiting) ?: pairing.waiting,
        stoppedInFlight = null,
        waitingUnreadableSaid = pairing.waitingUnreadableSaid || unreadable != null,
        beatLog = pairing.beatLog.heard(word::class.simpleName, ok),
      )
    // The beat line. Its claim is the one the answered beat carried, before this answer cleared it
    // (PV11). Paired or held, it is written only when it changes, message aside (N25, owner
    // 2026-10-09: count beats, don't list them): the minute's `beats` line counts every beat, so
    // hours of waiting or of air cost a few lines.
    val fields =
      listOf(
        "result" to heartbeat.lastResult,
        "status" to response?.status,
        "word" to word::class.simpleName,
        "claim" to pairing.claim,
        "message" to (input.response as? HeartbeatResponse.Failed)?.message,
      )
    val compared = fields.filter { it.first != "message" }
    val changed = compared != pairing.lastBeatLine
    val noted = if (changed) listOf(Command.Record(RecordEntry("heartbeat", fields))) else emptyList()
    // A17: a beat that carried the stopped record and was heard delivers it (G0-g); a 401 ends its code.
    val status = response?.status
    val delivered = pairing.stoppedInFlight != null && StoppedRecord.delivered(status)
    val codeDead = word == ServerWord.CodeEnded && pairing.stopped != null
    val deletes = if (delivered || codeDead) listOf(Command.DeleteStopped(pairing.name.code, pairing.name.slot), record("stopped-delivered", "status" to status)) else emptyList()
    // The refused set keeps the sid for the pairing's life (A17): a server that names it later is still ignored.
    val kept = (if (delivered || codeDead) refreshed.copy(stopped = null) else refreshed).copy(lastBeatLine = compared)
    // Design 2026-11-24 §2.2, §3: a 2xx's grant and T46's facts, before the word moves the phase.
    val (heard, facts) = Nudges.answered(phase, kept, response?.reply?.takeIf { ok }, now)
    val decided = Verdicts.beat(phase.withPairing(heard), word, now)
    return decided.copy(commands = noted + listOfNotNull(unreadable) + deletes + facts + decided.commands)
  }
}
