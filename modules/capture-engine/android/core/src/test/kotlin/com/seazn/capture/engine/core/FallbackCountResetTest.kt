package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * S4: the fallback count was cleared only by a drop of an attempt that had published [FallbackPolicy.SHORT_ATTEMPT_MS],
 * so a long healthy attempt that a rebuild, a new ingest session or a switch ended kept it, and failures hours apart added
 * up to RTMPS for the rest of the match. An attempt that published that long proves the path however it ends.
 */
class FallbackCountResetTest {
  /** Live after one counted connect failure: attempt 1 failed, attempt 2 connected and publishing. */
  private fun liveAfterOneFailure(): MachineRig {
    val rig = MachineRig().armed()
    rig.ready()
    rig.send(Input.ConnectFailed(1, ConnectFailure.TIMEOUT, null))
    rig.advance(2_000)
    rig.send(Input.Connected(2))
    rig.advance(1_000)
    check(rig.session.fallback.count == 1) { "one counted: ${rig.session.fallback}" }
    return rig
  }

  /** Two counted failures of the attempts that follow an end by [ending], [publishedMs] after the connect. */
  private fun afterEnd(publishedMs: Long, ending: String): FallbackPolicy {
    val rig = liveAfterOneFailure()
    rig.advance(publishedMs - 1_000)
    end(rig, ending)
    repeat(2) {
      val attempt = rig.attempt ?: run { rig.advance(SessionMachine.RETRY_MS); rig.attempt!! }
      rig.send(Input.ConnectFailed(attempt, ConnectFailure.TIMEOUT, null))
      rig.advance(SessionMachine.RETRY_MS)
    }
    return rig.session.fallback
  }

  private val endings = listOf("a stall rebuild", "a switch")

  private fun end(rig: MachineRig, ending: String) {
    when (ending) {
      "a stall rebuild" -> rig.advance(4_000, videoPerStep = 0)
      else -> {
        rig.send(Input.Network(true, 100L))
        rig.send(Input.Network(true, 101L))
      }
    }
  }

  @Test
  fun `an attempt that published 30 s and ended by a rebuild or a switch clears the count - two failures later it is still SRT`() {
    for (name in endings) {
      val policy = afterEnd(30_000, name)
      assertEquals(Transport.SRT, policy.current, name)
      assertEquals(2, policy.count, "$name: the two after it")
    }
  }

  @Test
  fun `one that published under 25 s keeps the count - the third failure falls back`() {
    for (name in endings) {
      val policy = afterEnd(10_000, name)
      assertEquals(Transport.RTMPS, policy.current, name)
    }
  }
}
