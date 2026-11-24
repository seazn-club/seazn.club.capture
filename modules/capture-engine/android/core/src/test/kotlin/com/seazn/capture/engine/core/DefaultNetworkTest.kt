package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * N49: the network watch's rule. The validated fact is told exactly as before (on a change, and not validated on any
 * loss), and a validated report names its network: [Input.Network] carries the handle whenever it is validated and
 * either was not, or names another network. Which network an attempt opened on is the machine's ([Transports.network]).
 */
class DefaultNetworkTest {
  private val cellular = 100L
  private val wifi = 101L

  /** Every report in order, from the start, and the inputs each was told as. */
  private fun heard(vararg reports: Pair<String, Pair<Long, Boolean>>): List<List<Input>> {
    var network = DefaultNetwork()
    return reports.map { (callback, report) ->
      val (handle, validated) = report
      val heard = if (callback == "caps") network.capabilities(handle, validated) else network.lost()
      network = heard.next
      heard.inputs
    }
  }

  private fun caps(handle: Long, validated: Boolean = true) = "caps" to (handle to validated)

  private fun lost(handle: Long) = "lost" to (handle to false)

  private fun on(handle: Long): List<Input> = listOf(Input.Network(true, handle))

  private val notValidated: List<Input> = listOf(Input.Network(false))

  @Test
  fun `the first validated network is told as validated, with its handle`() {
    assertEquals(listOf(on(cellular)), heard(caps(cellular)))
  }

  @Test
  fun `the same network reported again tells nothing`() {
    // onCapabilitiesChanged comes on every capability change, signal strength included.
    assertEquals(listOf(on(cellular), emptyList(), emptyList()), heard(caps(cellular), caps(cellular), caps(cellular)))
  }

  @Test
  fun `N49 another validated default network is told with its handle, and validated stays true`() {
    assertEquals(listOf(on(cellular), on(wifi)), heard(caps(cellular), caps(wifi)))
  }

  @Test
  fun `two switches in a row are told once each, and back again is told again`() {
    assertEquals(listOf(on(cellular), on(wifi), emptyList(), on(cellular)), heard(caps(cellular), caps(wifi), caps(wifi), caps(cellular)))
  }

  @Test
  fun `N49 fix 1 a loss then a new network is told - the machine judges it against the attempt's network`() {
    // The fast hand-off: 4G lost, Wi-Fi validated under SRT's 5 s peer-idle timeout. The handle is told, never dropped.
    assertEquals(listOf(on(cellular), notValidated, on(wifi)), heard(caps(cellular), lost(cellular), caps(wifi)))
  }

  @Test
  fun `the same network validated again after a loss is told again, with its handle`() {
    assertEquals(listOf(on(cellular), notValidated, on(cellular)), heard(caps(cellular), lost(cellular), caps(cellular)))
  }

  @Test
  fun `a new default not yet validated tells not validated, and its validation then tells its handle`() {
    assertEquals(listOf(on(cellular), notValidated, on(wifi)), heard(caps(cellular), caps(wifi, validated = false), caps(wifi)))
  }

  @Test
  fun `a loss tells not validated whichever network it names, as before`() {
    assertEquals(listOf(on(cellular), on(wifi), notValidated, on(wifi)), heard(caps(cellular), caps(wifi), lost(cellular), caps(wifi)))
  }

  @Test
  fun `a loss with nothing validated tells nothing, and an unvalidated first network tells nothing`() {
    assertEquals(listOf(emptyList<Input>(), emptyList()), heard(lost(cellular), caps(cellular, validated = false)))
  }
}
