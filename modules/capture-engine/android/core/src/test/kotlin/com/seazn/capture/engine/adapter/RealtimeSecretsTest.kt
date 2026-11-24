package com.seazn.capture.engine.adapter

import com.seazn.capture.engine.core.Command
import com.seazn.capture.engine.core.Input
import com.seazn.capture.engine.core.MachineRig
import com.seazn.capture.engine.core.NudgeState
import com.seazn.capture.engine.core.RealtimeGrant
import com.seazn.capture.engine.core.SessionRecord
import com.seazn.capture.engine.core.ServerDelivery
import com.seazn.capture.engine.core.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Design 2026-11-24 §2 and §5: the nudge token, and the topic, origin and key it rides with, never reach the snapshot,
 * the record, a command's or an input's print. A sweep over a whole pairing: grants taken and swapped, nudges, the
 * socket's reports, T46's facts, an expiry and the end.
 */
class RealtimeSecretsTest {
  private val secrets = listOf("made-up.realtime-token", "6f1c2b7a-3d4e-4f50-8a9b-0c1d2e3f4a5b", "example-ref.supabase.co", "anon.example-key")

  private fun grant(rig: MachineRig, n: Int, leftMs: Long) =
    RealtimeGrant("https://example-ref.supabase.co", "anon.example-key_01", "capture:6f1c2b7a-3d4e-4f50-8a9b-0c1d2e3f4a5b", "made-up.realtime-token.$n", (rig.wall + leftMs) / 1_000)

  @Test
  fun `no token, topic, origin or key in any snapshot, record line, command or input of a pairing`() {
    val rig = MachineRig()
    val seen = mutableListOf<String>()
    val inputs = mutableListOf<Input>()
    val record = SessionRecord { seen += it }
    fun snap(s: Snapshot) {
      seen += SnapshotWire.of(s, PlatformFacts(), RecordCounters(0, 0, 0, 0)).toString()
      seen += s.toString()
    }
    fun send(input: Input) {
      inputs += input
      rig.send(input)
      snap(rig.snapshot)
    }
    rig.paired()
    rig.hear("waiting", realtime = grant(rig, 1, 25 * 60_000L))
    send(Input.NudgeLink(NudgeState.JOINED))
    send(Input.Nudge("go-live"))
    rig.hear("go-live", sid = "sess_42", startedBy = "organiser", realtime = grant(rig, 2, 30 * 60_000L))
    rig.ready()
    send(Input.Connected(rig.attempt!!))
    rig.advance(1_000) { snap(rig.snapshot) }
    rig.hear("live", sid = "sess_42", realtime = grant(rig, 3, 30 * 60_000L), delivery = ServerDelivery("connected", "live", 30, 2_800, "connected", "connected"))
    send(Input.NudgeLink(NudgeState.REFUSED, 4001))
    rig.hear("live", sid = "sess_42", realtime = grant(rig, 4, 15_000), delivery = ServerDelivery(programmePhase = "frozen"))
    rig.advance(70_000) { snap(rig.snapshot) }
    rig.hear("over", sid = "sess_42", endReason = "stopped", realtime = grant(rig, 5, 30 * 60_000L))
    send(Input.Unpair)
    val told = rig.commands.filterIsInstance<Command.Nudges>()
    assertTrue(told.size >= 3, "the sweep saw ${told.size} grants told to the socket")
    assertTrue(rig.commands.contains(Command.NudgesOff), "an expiry or the end closed the socket")
    assertTrue(rig.records("nudges").any { it.fields.first().second == NudgeState.EXPIRED }, "the expiry was swept")
    assertTrue(rig.records("nudges").isNotEmpty() && rig.records("nudge").isNotEmpty() && rig.records("server-delivery").isNotEmpty(), "every new kind written")
    for (command in rig.commands) {
      seen += command.toString()
      if (command is Command.Record) record.append(rig.wall, command.entry)
    }
    seen += inputs.map { it.toString() }
    val leaks = seen.filter { text -> secrets.any { it in text } }
    assertEquals(emptyList(), leaks)
  }
}
