package com.seazn.capture.engine.core

/**
 * The default network as ConnectivityManager's default-network callback reports it, so the glue's watch holds no rule
 * of its own. [validated] is `NET_CAPABILITY_VALIDATED`, told on a change, exactly as before N49: not reachable is the
 * start, so the first validated network is told, and a loss of any network tells not validated. [handle] is the network
 * last told validated (`Network.getNetworkHandle()`), while it is.
 *
 * N49 (stg, 2026-11-24): a switch from 4G to Wi-Fi kept the default validated, so nothing was told, and the SRT socket
 * stayed on 4G until the delivery watch's stall verdict, 22 s later. A validated report now carries its network's handle
 * ([Input.Network]), told whenever it was not validated before or names another network. Whether that is a switch is
 * the machine's to judge, against the network the attempt in hand was asked on ([Transports.network]): a loss followed
 * by a new network is one, since SRT keeps a socket until its peer-idle timeout (about 5 s) and it lives on through the
 * new route (fix round 1).
 */
data class DefaultNetwork(val validated: Boolean = false, val handle: Long? = null) {
  /** One report's outcome: the next state, and the inputs it is told as, in order. */
  data class Heard(val next: DefaultNetwork, val inputs: List<Input>)

  /** `onCapabilitiesChanged`. */
  fun capabilities(handle: Long, validated: Boolean): Heard {
    val next = DefaultNetwork(validated, if (validated) handle else null)
    val told = validated != this.validated || (validated && handle != this.handle)
    return Heard(next, if (told) listOf(Input.Network(validated, next.handle)) else emptyList())
  }

  /** `onLost`: not validated, whichever network it names, as before. */
  fun lost(): Heard = Heard(DefaultNetwork(), if (validated) listOf(Input.Network(false)) else emptyList())
}
