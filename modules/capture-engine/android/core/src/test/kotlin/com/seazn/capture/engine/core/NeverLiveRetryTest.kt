package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S6: before a broadcast's first frame there is no outage and so no hold, and a failed connect retried every 2 s until
 * the server ended the session, writing `connect-failed` and `connecting` each time (N37: 73 start failures in 183 s).
 * For a minute it is as before; after it the retry backs off, 4, 8, 16, then 30 s, and the failures are said at most
 * once a minute, with how many each line stands for. After live nothing changes: the hold bounds the retries.
 */
class NeverLiveRetryTest {
  /** Armed and ready, with every connect answered failed [failure] at once, for [ms]. */
  private fun failingFor(ms: Long, failure: ConnectFailure = ConnectFailure.UNRESOLVED): MachineRig {
    val rig = MachineRig().armed()
    rig.ready()
    var answered = 0
    fun answer() {
      val attempts = rig.connects()
      while (answered < attempts.size) rig.send(Input.ConnectFailed(attempts[answered++].attemptId, failure, null))
    }
    answer()
    rig.advance(ms) { answer() }
    return rig
  }

  @Test
  fun `the numbers - a minute as before, then 4, 8, 16, then 30 s, and a line a minute`() {
    assertEquals(60_000L, NeverLiveRetries.FAST_MS)
    assertEquals(60_000L, NeverLiveRetries.LINE_MS)
    val waits = (0..6).map { NeverLiveRetries(firstAtMs = 0, slow = it).waitMs() }
    assertEquals(listOf(2_000L, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000), waits)
  }

  @Test
  fun `five minutes of failures before live are 41 connects, not 151`() {
    // Every 2 s from 0 to 60 s (31), then 64, 72, 88, and every 30 s from 118 to 298 (10).
    val starts = failingFor(300_000).connects()
    assertEquals(41, starts.size, "${starts.map { it.attemptId }}")
  }

  @Test
  fun `a minute of lines as before, then one a minute with the failures it stands for`() {
    val rig = failingFor(300_000)
    val lines = rig.records("connect-failed")
    val fast = lines.takeWhile { it.fields.none { (key, _) -> key == "count" } }
    assertEquals(30, fast.size, "failures at 0 to 58 s, each said as before, with no count")
    // Then: 60 s said (1); 64, 72, 88 and 118 held, 148 said (5); 178 held, 208 said (2); 238 held, 268 said (2); 298 held.
    assertEquals(listOf(1, 5, 2, 2), lines.drop(fast.size).map { it.field("count") })
    assertEquals(rig.connects().size - 1, fast.size + lines.drop(fast.size).sumOf { it.field("count") as Int }, "all but the last, held, are said")
    assertTrue(rig.records("connecting").size <= lines.size + 1, "a retry's connecting line goes with its failure's line")
  }

  @Test
  fun `after live a failed reconnect retries every 2 s past a minute - the hold bounds it`() {
    val rig = MachineRig().live()
    rig.send(Input.Dropped(1, DropReason.ENDPOINT_CLOSED, null))
    val asked = mutableListOf<Long>()
    repeat(4) {
      rig.advance(SessionMachine.RETRY_MS)
      asked += rig.mono
      rig.send(Input.ConnectFailed(rig.attempt!!, ConnectFailure.UNRESOLVED, null))
    }
    assertEquals(listOf(2_000L, 2_000, 2_000), asked.zipWithNext { a, b -> b - a })
    assertEquals(4, rig.records("connect-failed").size, "after live every failure has its line")
    assertTrue(rig.records("connect-failed").all { line -> line.fields.none { it.first == "count" } })
  }

  @Test
  fun `a broadcast that went live after two minutes of failures retries every 2 s once it drops`() {
    val rig = failingFor(120_000)
    check(rig.session.retries.slow > 0) { "the ladder moved before live" }
    val waiting = (rig.phase as Phase.Connecting).step as ConnectStep.Waiting
    rig.at(waiting.connectAtMs)
    rig.send(Input.Tick)
    rig.send(Input.Connected(rig.attempt!!))
    rig.advance(1_000, videoPerStep = 15)
    assertTrue(rig.session.liveSinceEpochMs != null, "live")
    rig.send(Input.Dropped(rig.attempt!!, DropReason.ENDPOINT_CLOSED, null))
    val retry = (rig.phase as Phase.Connecting).step as ConnectStep.Waiting
    assertEquals(SessionMachine.RETRY_MS, retry.connectAtMs - rig.mono, "the hold bounds it now, not the ladder")
  }

  @Test
  fun `before live a drop of an attempt that connected backs off too, once the minute is out`() {
    val rig = MachineRig().armed()
    rig.ready()
    val waits = mutableListOf<Long>()
    val start = rig.mono
    while (waits.size < 34) {
      rig.send(Input.Connected(rig.attempt!!))
      rig.send(Input.Dropped(rig.attempt!!, DropReason.ENDPOINT_CLOSED, null))
      val waiting = (rig.phase as Phase.Connecting).step as ConnectStep.Waiting
      waits += waiting.connectAtMs - rig.mono
      rig.at(waiting.connectAtMs)
      rig.send(Input.Tick)
    }
    assertEquals(List(30) { 2_000L } + listOf(4_000L, 8_000, 16_000, 30_000), waits, "from ${rig.mono - start}")
  }
}
