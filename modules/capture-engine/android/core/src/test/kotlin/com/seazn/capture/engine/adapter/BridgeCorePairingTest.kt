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
 * [BridgeCore]: the snapshot, the intents' acks, and the pairing's ask, readiness replay, pose and waiting beat.
 */
class BridgeCorePairingTest : BridgeCoreTestKit() {
  @Test
  fun `Review Focus 2 the current snapshot is idle before any tick, and live once live`() {
    assertEquals("idle", state())
    live()
    assertEquals("publishing", state())
    // PV19: the broadcast crosses as `broadcast`; the wire has no `session` key.
    assertEquals(PairWire.SID, (core.current()["broadcast"] as Map<*, *>)["sid"])
  }

  @Test
  fun `CD8 an intent's ack comes after the snapshot and the lines it caused, and an unreadable intent is acked too`() {
    core.start()
    core.send(PairWire.valid() + ("seq" to 1.0))
    core.send(mapOf("kind" to "explode", "seq" to 2.0))
    scheduler.advanceBy(0)
    val events = platform.events
    val ack1 = events.indexOf("ack:1")
    val ack2 = events.indexOf("ack:2")
    assertTrue(events.indexOf("snapshot") in 0 until ack1, "the pairing snapshot is out before its ack: $events")
    assertTrue(events.indexOf("line:pair") in 0 until ack1, "and the pair's own line: $events")
    assertTrue(events.indexOf("line:intent-unreadable") in ack1 + 1 until ack2, "the unreadable intent's line, then its ack: $events")
    assertEquals(listOf("ack:1", "ack:2"), events.filter { it.startsWith("ack:") })
  }

  /** S8: while nothing shown changes, JS hears about once a second, not every tick; a change goes up at once. */
  @Test
  fun `S8 the snapshot goes up to JS once a second while nothing shown changes`() {
    core.start()
    core.report(Input.Network(true))
    ready()
    pair()
    scheduler.advanceBy(2_000)
    val before = platform.snapshots.size
    scheduler.advanceBy(10_000)
    assertEquals(10, platform.snapshots.size - before, "paired and waiting: 20 ticks, 10 sent")
    assertEquals(1_000L, BridgeCore.EMIT_MS)
  }

  @Test
  fun `S8 a change goes up to JS at once, between the once-a-second sends`() {
    live()
    scheduler.advanceBy(Engine.TICK_MS / 2)
    val sent = platform.snapshots.size
    core.send(mapOf("kind" to "stop"))
    scheduler.advanceBy(0)
    assertEquals(sent + 1, platform.snapshots.size, "a stop shows at once")
    assertEquals("ended", (platform.snapshots.last()["state"] as Map<*, *>)["kind"])
  }

  @Test
  fun `carry 27 native answers a stop within one tick`() {
    live()
    core.send(mapOf("kind" to "stop"))
    scheduler.advanceBy(Engine.TICK_MS)
    assertEquals("ended", state())
  }

  @Test
  fun `A5 an accepted pair asks once and holds the service once granted, and a refused pair asks nothing`() {
    core.start()
    pair()
    pair()
    assertEquals(1, platform.executed.count { it == Command.AskPermissions }, "the second pair of the same code is ignored")
    assertEquals(1, platform.executed.count { it == Command.Hold })
    assertEquals("paired", state())
    val other = RecordingPlatform()
    val refusing = build(other)
    refusing.start()
    pair(refusing, PairWire.valid() + ("token" to ""))
    assertEquals("ended", state(refusing))
    assertTrue(Command.AskPermissions !in other.executed, "a malformed pair never reaches the platform")
  }

  @Test
  fun `CD21 a refused permission unpairs, and the next pair asks again`() {
    core.start()
    platform.grant = false
    pair()
    assertEquals("idle", state())
    assertEquals("permissions", ((core.current()["unpaired"]) as Map<*, *>)["reason"])
    platform.grant = true
    pair()
    assertEquals(2, platform.executed.count { it == Command.AskPermissions })
    assertEquals("paired", state())
  }

  @Test
  fun `A11 readiness reported before the pair is replayed into it, so Go live needs no new report`() {
    core.start()
    core.report(Input.Network(true))
    ready()
    pair()
    core.send(mapOf("kind" to "start"))
    scheduler.advanceBy(1_500)
    assertEquals("publishing", state())
  }

  @Test
  fun `P5 re-pairing the same code after an unpair replays readiness again`() {
    core.start()
    core.report(Input.Network(true))
    ready()
    pair()
    core.send(mapOf("kind" to "unpair"))
    scheduler.advanceBy(0)
    pair()
    // Final review I-1: the pose is the pairing's; the new pairing's tracker reports the phone held sideways.
    core.readiness(pose = Pose.Held(Side.LEFT))
    core.send(mapOf("kind" to "start"))
    scheduler.advanceBy(1_500)
    assertEquals("publishing", state(), "a new PairRequest of the same name is a new pairing")
  }

  /**
   * Final review I-1: the pose is the pairing's. The glue's pose watch stops at Release and starts at Hold with a
   * tracker that reports only changes, so a second pairing in one process hears no pose until the phone moves.
   * It must start not held: match 2's phone laid flat is never read as held, and Go live waits for a held report.
   */
  @Test
  fun `final I-1 a second pairing starts not held, and Go live waits for a held report of its own`() {
    core.start()
    core.report(Input.Network(true))
    ready()
    pair()
    assertEquals("paired", state())
    assertEquals(true, readyOn(core)["held"], "pairing 1 is held")
    core.send(mapOf("kind" to "unpair"))
    scheduler.advanceBy(0)
    val after = lines().size
    pair()
    scheduler.advanceBy(Engine.TICK_MS)
    assertEquals("paired", state())
    assertEquals(mapOf("camera" to true, "sound" to true, "network" to true, "held" to false), readyOn(core), "pairing 2: held alone is not ready")
    val heard = lines().drop(after).filter { it.getString("kind") == "readiness" }
    assertTrue(heard.isNotEmpty(), "pairing 2 heard its readiness")
    assertTrue(heard.none { it.getBoolean("held") }, "pairing 2 never heard pairing 1's pose: $heard")
    core.send(mapOf("kind" to "start"))
    scheduler.advanceBy(1_500)
    assertEquals("paired", state(), "Go live refused on held")
    // The positive pair: a held report of pairing 2's own opens it. F4: the glue's meter reading comes first, as it
    // does every 500 ms while the capture is open; without one for NO_AUDIO_MS the mic is not available (spec §4).
    core.readiness(audioPeak = 0.4)
    core.readiness(pose = Pose.Held(Side.RIGHT))
    scheduler.advanceBy(Engine.TICK_MS)
    assertEquals(true, readyOn(core)["held"], "held once pairing 2's tracker says so")
    core.send(mapOf("kind" to "start"))
    scheduler.advanceBy(1_500)
    assertEquals("publishing", state())
  }

  /**
   * Final review round 2, minor 2: the pose watch's sensor callback runs on its own thread, so a report already
   * posted when Release unregisters it lands after `forgetPose`. Between a Release and the next Hold no watch is
   * the pairing's, so such a report is dropped; the next pairing hears only its own. (Before the first Hold, a
   * report is A11's replay into the first pair, kept.)
   */
  @Test
  fun `final r2 a pose report that lands after Release is dropped, and the next Hold hears its own`() {
    core.start()
    core.report(Input.Network(true))
    ready()
    pair()
    assertEquals(true, readyOn(core)["held"], "pairing 1, held from the replay before its Hold")
    core.send(mapOf("kind" to "unpair"))
    scheduler.advanceBy(0)
    assertTrue(Command.Release in platform.executed, "the Release ran")
    core.readiness(pose = Pose.Held(Side.LEFT))
    scheduler.advanceBy(0)
    pair()
    scheduler.advanceBy(Engine.TICK_MS)
    assertEquals("paired", state())
    assertEquals(false, readyOn(core)["held"], "pairing 2 never hears pairing 1's late report")
    core.readiness(pose = Pose.Held(Side.RIGHT))
    scheduler.advanceBy(Engine.TICK_MS)
    assertEquals(true, readyOn(core)["held"], "its own watch's report, after its Hold")
  }

  /**
   * Final review I-2b: staging's captured waiting beat ([StgBeat]) through the real Bodies.beat and the machine:
   * the pairing's label, start, destination and poll are the server's.
   */
  @Test
  fun `final I-2b staging's real waiting beat fills the pairing, and nothing is unreadable`() {
    val stg = StgBeat.body
    platform.unanswered = { it.url.endsWith("/beats") }
    core.start()
    core.report(Input.Network(true))
    ready()
    pair()
    assertEquals("", (platform.snapshots.last()["pairing"] as Map<*, *>)["label"], "not yet known")
    val (beat, answer) = platform.owed.first()
    assertTrue(beat.url.endsWith("/beats"))
    answer(HttpOutcome.Answered(200, stg.toString()))
    scheduler.advanceBy(0)
    val known = platform.snapshots.last()["pairing"] as Map<*, *>
    assertEquals(stg.getString("label"), known["label"])
    assertEquals(stg.getLong("scheduledStart").toDouble(), known["scheduledStart"], "epoch seconds across the bridge")
    assertEquals(stg.getString("destinationName"), known["destinationName"])
    // D2 §4.1: the captured body names an overlay, and none crosses.
    assertTrue("overlayUrl" !in known, "no overlay in the pairing")
    assertEquals(stg.getInt("pollSeconds").toDouble(), known["pollSeconds"])
    assertEquals(emptyList(), kinds().filter { it == "waiting-unreadable" })
  }

  /**
   * Final review I-2b: a 2xx whose waiting fields the parser refuses leaves the pairing's as they were, as it
   * must; it was silent, so a label that never came looked like a slow server. One
   * `waiting-unreadable` warn a pairing names the field, never its value; a re-pair is a new pairing.
   */
  @Test
  fun `final I-2b a beat with one unreadable waiting field writes one line naming it, once a pairing`() {
    var body = StgBeat.with("pollSeconds", "10")
    platform.answer = { request -> if (request.url.endsWith("/beats")) HttpOutcome.Answered(200, body) else PairWire.answer(request) }
    val unreadable = { lines().filter { it.getString("kind") == "waiting-unreadable" } }
    core.start()
    core.report(Input.Network(true))
    ready()
    pair()
    scheduler.advanceBy(60_000)
    val beats = platform.beats().size
    assertTrue(beats >= 3, "beats answered unreadable: $beats")
    val line = unreadable().single()
    assertEquals(listOf("beat", "pollSeconds", "warn"), listOf(line.getString("source"), line.getString("field"), line.getString("level")))
    assertEquals(setOf("at", "kind", "level", "source", "field"), line.keySet(), "the field's name, never its value: $line")
    assertEquals("", (core.current()["pairing"] as Map<*, *>)["label"], "the pairing's fields are not the bad body's")
    // The server's next readable answer fills them, with no more lines.
    body = StgBeat.body.toString()
    scheduler.advanceBy(30_000)
    assertEquals(StgBeat.body.getString("label"), (core.current()["pairing"] as Map<*, *>)["label"])
    body = StgBeat.with("label", "")
    scheduler.advanceBy(60_000)
    assertEquals(1, unreadable().size, "once a pairing, whichever field")
    // A re-pair is a new pairing: its first unreadable answer is written again.
    core.send(mapOf("kind" to "unpair"))
    scheduler.advanceBy(0)
    pair()
    scheduler.advanceBy(30_000)
    assertEquals(listOf("pollSeconds", "label"), unreadable().map { it.getString("field") })
  }

  /**
   * Review C4a I-3: each check alone, on a core of its own, paired with nothing ready. Each row turns its
   * check and repeats it: one line, and the machine hears the turn (the snapshot's check turns, alone).
   * One line is one send: `sendReadiness` writes the line and sends the input together.
   */
  @Test
  fun `A11 I-3 each check alone reaches the machine once, as one line, and a repeat of it reaches nothing`() {
    val rows: List<Pair<String, (BridgeCore) -> Unit>> =
      listOf(
        "camera" to { c ->
          c.readiness(cameraFrames = true)
          c.readiness(cameraFrames = true)
        },
        "sound" to { c ->
          c.readiness(audioPeak = 0.40)
          scheduler.advanceBy(Engine.TICK_MS)
          c.readiness(audioPeak = 0.41)
          scheduler.advanceBy(Engine.TICK_MS)
          c.readiness(audioPeak = 0.42)
        },
        "held" to { c ->
          c.readiness(pose = Pose.Held(Side.RIGHT))
          c.readiness(pose = Pose.Held(Side.RIGHT))
        },
      )
    var checked = 0
    for ((check, turn) in rows) {
      val on = RecordingPlatform()
      val row = build(on, MemoryStore())
      row.start()
      pair(row)
      val before = on.lines.size
      val was = readyOn(row)
      assertEquals(mapOf("camera" to false, "sound" to false, "network" to false, "held" to false), was, check)
      turn(row)
      // The readiness is not in the snapshot's state, so the next tick publishes it.
      scheduler.advanceBy(Engine.TICK_MS)
      val sent = lines(on).drop(before).filter { it.getString("kind") == "readiness" }
      assertEquals(1, sent.size, "$check: one line for the turn, none for the repeat")
      val checks = listOf("camera", "sound", "held")
      assertEquals(checks.associateWith { it == check }, checks.associateWith(sent.single()::getBoolean), check)
      assertEquals(was + (check to true), readyOn(row), "$check: the machine heard it, and only it")
      checked += 1
    }
    assertEquals(3, checked, "the table's own count")
  }

  @Test
  fun `P3 the notification's language is the pairing's, then setLanguage's`() {
    core.start()
    assertNull(core.language())
    pair()
    scheduler.advanceBy(1_000)
    assertEquals("fr", core.language())
    core.send(mapOf("kind" to "setLanguage", "language" to "nl"))
    scheduler.advanceBy(1_000)
    assertEquals("nl", core.language())
  }

  @Test
  fun `carry 18 one engine per process`() {
    BridgeCore.resetShared()
    var built = 0
    val first = BridgeCore.shared { built += 1; core }
    val second = BridgeCore.shared { built += 1; core }
    assertSame(first, second)
    assertEquals(1, built)
  }
}
