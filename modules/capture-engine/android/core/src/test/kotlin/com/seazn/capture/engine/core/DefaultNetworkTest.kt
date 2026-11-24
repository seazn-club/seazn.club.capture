package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * N49: the network watch's rule. The validated fact is told exactly as before (on a change, and not validated on any
 * loss); a switch of the validated default network is told as [Input.NetworkSwitched], stamped with the attempt in hand.
 */
class DefaultNetworkTest {
  private val cellular = 100L
  private val wifi = 101L
  private var attempt = 7

  /** Every report in order, from the start, and the inputs each was told as. */
  private fun heard(vararg reports: Pair<String, Pair<Long, Boolean>>): List<List<Input>> {
    var network = DefaultNetwork()
    return reports.map { (callback, report) ->
      val (handle, validated) = report
      val heard = if (callback == "caps") network.capabilities(handle, validated) { attempt } else network.lost(handle)
      network = heard.next
      heard.inputs
    }
  }

  private fun caps(handle: Long, validated: Boolean = true) = "caps" to (handle to validated)

  private fun lost(handle: Long) = "lost" to (handle to false)

  @Test
  fun `the first validated network is told as validated, never as a switch`() {
    assertEquals(listOf(listOf<Input>(Input.Network(true))), heard(caps(cellular)))
  }

  @Test
  fun `the same network reported again tells nothing`() {
    // onCapabilitiesChanged comes on every capability change, signal strength included.
    assertEquals(listOf(listOf<Input>(Input.Network(true)), emptyList(), emptyList()), heard(caps(cellular), caps(cellular), caps(cellular)))
  }

  @Test
  fun `N49 another validated default network is a switch, with the attempt in hand, and no validated line`() {
    attempt = 4
    assertEquals(listOf(listOf(Input.Network(true)), listOf<Input>(Input.NetworkSwitched(4))), heard(caps(cellular), caps(wifi)))
  }

  @Test
  fun `two switches in a row are two switches, and back again is a third`() {
    val told = heard(caps(cellular), caps(wifi), caps(wifi), caps(cellular))
    assertEquals(listOf(listOf(Input.Network(true)), listOf(Input.NetworkSwitched(7)), emptyList(), listOf<Input>(Input.NetworkSwitched(7))), told)
  }

  @Test
  fun `a network validated after the last one was lost is no switch - its socket died with it`() {
    val told = heard(caps(cellular), lost(cellular), caps(wifi))
    assertEquals(listOf(listOf(Input.Network(true)), listOf(Input.Network(false)), listOf<Input>(Input.Network(true))), told)
  }

  @Test
  fun `a new default not yet validated tells not validated, and its validation then is a switch`() {
    // The socket is still on the old network, which was never lost.
    val told = heard(caps(cellular), caps(wifi, validated = false), caps(wifi))
    assertEquals(listOf(listOf(Input.Network(true)), listOf(Input.Network(false)), listOf(Input.Network(true), Input.NetworkSwitched(7))), told)
  }

  @Test
  fun `a loss tells not validated whichever network it names, as before, and only its own forgets the handle`() {
    // The old network lost after the switch: not validated, as the watch always said, and the handle is Wi-Fi's still,
    // so Wi-Fi validating again is no switch, and 4G validating is one.
    val told = heard(caps(cellular), caps(wifi), lost(cellular), caps(wifi), caps(cellular))
    assertEquals(
      listOf(listOf(Input.Network(true)), listOf(Input.NetworkSwitched(7)), listOf(Input.Network(false)), listOf(Input.Network(true)), listOf<Input>(Input.NetworkSwitched(7))),
      told,
    )
  }

  @Test
  fun `the old network's loss after a switch keeps the new one's handle`() {
    // Lost 4G after the switch to Wi-Fi: Wi-Fi is still the default the socket is on, so 4G validating next is a switch.
    val told = heard(caps(cellular), caps(wifi), lost(cellular), caps(cellular))
    assertEquals(
      listOf(listOf(Input.Network(true)), listOf(Input.NetworkSwitched(7)), listOf(Input.Network(false)), listOf(Input.Network(true), Input.NetworkSwitched(7))),
      told,
    )
  }

  @Test
  fun `a loss with nothing validated tells nothing, and an unvalidated first network tells nothing`() {
    assertEquals(listOf(emptyList<Input>(), emptyList()), heard(lost(cellular), caps(cellular, validated = false)))
  }

  @Test
  fun `the attempt is read only for a switch`() {
    var reads = 0
    var network = DefaultNetwork()
    for ((handle, validated) in listOf(cellular to true, cellular to true, cellular to false, cellular to true)) {
      network = network.capabilities(handle, validated) { reads++; 1 }.next
    }
    network.lost(cellular)
    assertEquals(0, reads)
    network.capabilities(wifi, true) { reads++; 1 }
    assertEquals(1, reads)
  }
}
