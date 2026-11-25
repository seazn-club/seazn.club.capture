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
 * [BridgeCore]: when the capture is wanted, and the permission ask.
 */
class BridgeCoreCaptureTest : BridgeCoreTestKit() {
  @Test
  fun `CD37 capture is wanted on the viewfinder while paired, and whenever a broadcast is held`() {
    core.start()
    core.report(Input.Network(true))
    // Ready, so the hold's POST start goes (A11): a start the machine ignores holds nothing.
    ready()
    pair()
    assertEquals(emptyList(), platform.captures, "paired with the phone locked: closed")
    core.viewfinder(true)
    scheduler.advanceBy(0)
    core.viewfinder(false)
    scheduler.advanceBy(0)
    assertEquals(listOf(true, false), platform.captures)
    // The close silenced sound (C4a carry, Task 33): the meter reads again before the go-live.
    ready()
    core.send(mapOf("kind" to "start"))
    scheduler.advanceBy(0)
    assertEquals(listOf(true, false, true), platform.captures, "a go-live opens them from the service")
    assertSame(platform.captureRequests[0], platform.captureRequests[2], "one PairRequest object, which pairFailed compares")
  }

  @Test
  fun `CD37 PV20 a re-pair over a shown viewfinder opens the capture again, for the new pairing, through Pairing`() {
    core.start()
    core.report(Input.Network(true))
    pair()
    core.viewfinder(true)
    scheduler.advanceBy(0)
    pair(wire = PairWire.valid() + ("slot" to 2.0))
    assertEquals("paired", state())
    assertEquals(listOf(true, true), platform.captures, "wanted throughout, so only the pairing changed")
    assertNotSame(platform.captureRequests[0], platform.captureRequests[1], "the new PairRequest, which a later pairFailed names")
    assertEquals(2, platform.captureRequests[1]!!.slot)
  }

  /**
   * Review C4a m-1: a tick can end a broadcast with no input after it. Here the outage hold runs out:
   * the connects all fail and no request is ever answered, so nothing but the tick moves the phase,
   * and the end must still close the capture the broadcast held open.
   */
  @Test
  fun `m-1 a tick that ends the broadcast closes the capture, with no input after it`() {
    live()
    assertEquals(listOf(true), platform.captures, "the go-live opened it")
    platform.throwOnConnect = IllegalStateException("no route")
    platform.unanswered = { true }
    platform.dropCurrent()
    scheduler.advanceBy(0)
    assertTrue(core.current()["broadcast"] != null, "the drop holds the broadcast")
    var ticks = 0
    while (core.current()["broadcast"] != null) {
      scheduler.advanceBy(Engine.TICK_MS)
      ticks += 1
      assertTrue(ticks < 2_000, "the hold never ran out: ${state()}")
    }
    assertEquals(listOf(true, false), platform.captures, "the tick that ended it closed it: ${state()} after $ticks ticks")
  }

  /**
   * Spec 2026-11-25-zoom-and-metering §3: the pinch zoom is locked from Arming on, on the machine's own word
   * (`Phase.holdsBroadcast`), read by the capture from any thread. A tick that ends the broadcast with no input after it
   * (review C4a m-1) frees it too.
   */
  @Test
  fun `the capture reads a broadcast held from Arming on, and free again once it ends`() {
    core.start()
    core.report(Input.Network(true))
    ready()
    pair()
    assertFalse(core.broadcastHeld(), "paired and waiting")
    platform.unanswered = { true } // the POST start waits for its answer
    core.send(mapOf("kind" to "start"))
    scheduler.advanceBy(0)
    assertEquals("paired", state())
    assertFalse(core.broadcastHeld(), "a start asked and not answered holds no broadcast yet")
    platform.owed.single().let { (asked, answer) -> answer(platform.answer(asked)) }
    scheduler.advanceBy(0)
    assertEquals("arming", state(), "the answer arms it")
    assertTrue(core.broadcastHeld(), "from Arming on, before anything is on air")
    scheduler.advanceBy(1_500)
    assertTrue(core.broadcastHeld(), "on air: ${state()}")
    core.send(mapOf("kind" to "stop"))
    scheduler.advanceBy(0)
    assertFalse(core.broadcastHeld(), "stopped: ${state()}")
  }

  @Test
  fun `a tick that ends the broadcast frees the capture's zoom with no input after it`() {
    live()
    assertTrue(core.broadcastHeld())
    platform.throwOnConnect = IllegalStateException("no route")
    platform.unanswered = { true }
    platform.dropCurrent()
    scheduler.advanceBy(0)
    var ticks = 0
    while (core.current()["broadcast"] != null) {
      scheduler.advanceBy(Engine.TICK_MS)
      ticks += 1
      assertTrue(ticks < 2_000, "the hold never ran out: ${state()}")
    }
    assertFalse(core.broadcastHeld(), "the tick that ended it: ${state()}")
  }

  @Test
  fun `CD37 PV20 the capture is held only for a re-pair - a first pair waits for the grant, and a refused re-pair closes it`() {
    core.start()
    core.viewfinder(true)
    platform.ask = false
    pair()
    assertEquals("pairing", state())
    assertEquals(emptyList(), platform.captures, "no camera before the permissions are granted")
    core.send(mapOf("kind" to "unpair"))
    platform.ask = true
    pair()
    assertEquals(listOf(true), platform.captures, "granted and paired, over the shown viewfinder")
    platform.grant = false
    pair(wire = PairWire.valid() + ("slot" to 2.0))
    assertEquals("idle", state())
    assertEquals(listOf(true, true, false), platform.captures, "held for the re-pair, closed by its refusal")
  }

  @Test
  fun `CD21 a request that could not be asked ends the pairing fatal-error and named, never as a refusal`() {
    core.start()
    platform.ask = false
    pair()
    assertEquals("pairing", state())
    core.askFailed(IllegalStateException("no activity"))
    scheduler.advanceBy(0)
    val snapshot = core.current()
    val ended = snapshot["state"] as Map<*, *>
    assertEquals(listOf("ended", "fatal-error"), listOf(ended["kind"], ended["reason"]))
    assertNull(snapshot["unpaired"], "not the permissions panel: nothing was refused")
    assertEquals("IllegalStateException: no activity", lines().single { it.getString("kind") == "ended" }.getString("message"))
  }

  @Test
  fun `CD21 a late askFailed, with no pairing asking, is recorded and ends nothing`() {
    core.start()
    pair()
    core.askFailed(IllegalStateException("late"))
    scheduler.advanceBy(0)
    assertEquals("paired", state())
    assertEquals("IllegalStateException", lines().single { it.getString("kind") == "pair-step-late" }.getString("error"))
  }
}
