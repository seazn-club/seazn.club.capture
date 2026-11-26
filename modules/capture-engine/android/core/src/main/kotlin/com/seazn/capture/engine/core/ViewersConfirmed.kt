package com.seazn.capture.engine.core

/**
 * R-1PG4 (stg, 2026-11-26): PR #78's plate reads red once the viewers' picture is confirmed (`viewersConfirmed.ts`), and
 * the record said nothing of which fact confirmed it. This writes `viewers-confirmed {sid, source}` once per broadcast
 * ([Session.viewersConfirmed]), the first step after which the latch's own rule holds on the snapshot the step projects:
 * the state publishing or degraded, and the playlist check's delivery ok (`source: playlist`) or the server's programme
 * phase live (`source: beat`; T46's facts). Both in one step name the playlist, the phone's own sight of the picture.
 *
 * A record line only: the latch stays JavaScript's projection, and nothing native reads the flag but this. Called on
 * every step the machine takes.
 */
internal object ViewersConfirmed {
  fun closed(step: Step, now: Now): Step {
    val session = step.phase.session ?: return step
    if (session.viewersConfirmed) return step
    val source = source(step.phase, session, now) ?: return step
    val line = Command.Record(RecordEntry("viewers-confirmed", listOf("sid" to session.sid, "source" to source)))
    return step.copy(phase = step.phase.withSession(session.copy(viewersConfirmed = true)), commands = step.commands + line)
  }

  private fun source(phase: Phase, session: Session, now: Now): String? {
    val state = Projection.state(phase, now)
    if (state !is SnapshotState.Publishing && state !is SnapshotState.Degraded) return null
    if (Projection.delivery(session, phase as? Phase.OnAir) == Delivery.OK) return PLAYLIST
    return BEAT.takeIf { session.pairing.server?.delivery?.programmePhase == PROGRAMME_LIVE }
  }

  const val PLAYLIST = "playlist"
  const val BEAT = "beat"
  private const val PROGRAMME_LIVE = "live"
}
