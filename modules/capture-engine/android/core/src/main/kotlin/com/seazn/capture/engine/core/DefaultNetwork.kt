package com.seazn.capture.engine.core

/**
 * The default network as ConnectivityManager's default-network callback reports it, so the glue's watch holds no rule
 * of its own. [validated] is `NET_CAPABILITY_VALIDATED`, told on a change only, exactly as before N49: not reachable
 * is the start, so the first validated network is told, and a loss of any network tells not validated. [validatedHandle]
 * is the last network seen validated as the default (`Network.getNetworkHandle()`), until that network is lost.
 *
 * N49 (stg, 2026-11-24): a switch from 4G to Wi-Fi kept the default validated, so nothing was told, and the SRT socket
 * stayed on 4G until the delivery watch's stall verdict, 22 s later. A validated report from another network than
 * [validatedHandle] is now told as [Input.NetworkSwitched]. The first validated network is not a switch, and nor is
 * one after the last validated network was lost: its socket dies with it, and the drop is the machine's.
 */
data class DefaultNetwork(val validated: Boolean = false, val validatedHandle: Long? = null) {
  /** One report's outcome: the next state, and the inputs it is told as, in order. */
  data class Heard(val next: DefaultNetwork, val inputs: List<Input>)

  /** `onCapabilitiesChanged`. [attemptInHand] is read only for a switch. */
  fun capabilities(handle: Long, validated: Boolean, attemptInHand: () -> Int): Heard {
    val told = if (validated != this.validated) listOf(Input.Network(validated)) else emptyList()
    val switched = validated && validatedHandle != null && validatedHandle != handle
    val next = DefaultNetwork(validated, if (validated) handle else validatedHandle)
    return Heard(next, if (switched) told + Input.NetworkSwitched(attemptInHand()) else told)
  }

  /** `onLost`: not validated, whichever network it names, as before; only the network it names forgets its handle. */
  fun lost(handle: Long): Heard {
    val next = DefaultNetwork(validated = false, validatedHandle = validatedHandle?.takeIf { it != handle })
    return Heard(next, if (validated) listOf(Input.Network(false)) else emptyList())
  }
}
