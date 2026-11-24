package com.seazn.capture.engine.srt.connect

import com.seazn.capture.engine.core.RecordEntry
import java.net.Inet6Address
import java.net.InetSocketAddress

/**
 * N49, the compile-time switch for [SrtBind], in the spirit of the app's `FEATURES`: ON as shipped. A device test that
 * finds the binding misbehaving turns it off in one commit, and the sink then connects every socket unbound, as
 * before, with no `srt-bind` line.
 */
const val BIND_SRT_TO_NETWORK = true

/**
 * The JNI shim (`srt/SrtNetworkBinder.kt`, `src/main/cpp/srt_bind.c`), as a port so core's tests run [SrtBind]'s rule on
 * the JVM. [bind] makes a UDP socket of [ipv6]'s family, binds it to [network] with `android_setsocknetwork`, binds it to
 * a wildcard address, and hands it to libsrt's `srt_bind_acquire` for the SRT socket [srtSocket], before its connect.
 * It answers 0 when bound, else the step that failed in the high 16 bits and that step's errno (libsrt's own error code
 * for `srt_bind_acquire`) in the low 16: 1 libsrtdroid is not loaded, 2 no `srt_bind_acquire` symbol, 3 `socket`,
 * 4 `android_setsocknetwork`, 5 `bind`, 6 `srt_bind_acquire`. A shim that cannot load throws (UnsatisfiedLinkError).
 */
interface SrtBinderPort {
  fun bind(srtSocket: Int, network: Long, ipv6: Boolean): Int
}

/**
 * N49: binds our SRT socket to the network its attempt was asked on (`Command.Connect.network`), so its packets, its
 * shutdown included, leave over that network from that network's address after the default moves: the old socket's
 * close then goes out over 4G while 4G lingers, and the new socket is pinned to Wi-Fi. Best effort and never fatal:
 * every outcome is one `srt-bind` line, and anything short of `bound` leaves the socket to connect unbound, as before.
 * - `bound`.
 * - `no-network`: the attempt was asked while no default network was known.
 * - `unavailable`, with `op`: `shim` (ours did not load, or threw, with its class in `error`), `socket-id` (srtdroid's
 *   socket id could not be read, likewise), `library` (libsrtdroid is not loaded) or `symbol` (no `srt_bind_acquire`).
 * - `failed`, with `op` (`socket`, `network`, `bind`, `acquire`, or `unknown` for an answer the shim never gives) and
 *   `error` (`errno-<n>`, `srt-<n>` for libsrt's code, or `code-<n>`, the whole answer, for `unknown`).
 * - `unavailable` with `op` `disabled`: an `acquire` failed earlier in this process. The shim cannot tell whether libsrt
 *   closed the UDP socket it was handed, so it leaves it open rather than risk closing another file's descriptor, and
 *   binding stops for good: at most one socket is left behind.
 * Off ([on] false), it reads nothing and says nothing.
 */
class SrtBind(private val binder: SrtBinderPort, private val line: (RecordEntry) -> Unit, private val on: Boolean = BIND_SRT_TO_NETWORK) {
  @Volatile private var disabled = false

  /** Binds attempt [attemptId]'s socket, whose id [socketId] reads, to [network], with a UDP socket of [target]'s family. */
  fun bind(attemptId: Int, network: Long?, target: InetSocketAddress, socketId: () -> Int) {
    if (!on) return
    if (disabled) return line(RecordEntry("srt-bind", listOf("attempt" to attemptId, "result" to "unavailable", "op" to "disabled")))
    if (network == null) return line(RecordEntry("srt-bind", listOf("attempt" to attemptId, "result" to "no-network")))
    val id = guarded(attemptId, "socket-id", socketId) ?: return
    val answer = guarded(attemptId, "shim") { binder.bind(id, network, target.address is Inet6Address) } ?: return
    if (answer ushr 16 == ACQUIRE) disabled = true
    line(RecordEntry("srt-bind", listOf("attempt" to attemptId) + said(answer)))
  }

  /** [call]'s answer, or null once its throw is said as `unavailable` at [op]. An Error other than a link's is no answer to say. */
  private fun guarded(attemptId: Int, op: String, call: () -> Int): Int? {
    val failure =
      try {
        return call()
      } catch (e: Exception) {
        e
      } catch (e: LinkageError) {
        e
      }
    line(RecordEntry("srt-bind", listOf("attempt" to attemptId, "result" to "unavailable", "op" to op, "error" to failure.javaClass.simpleName)))
    return null
  }

  private fun said(answer: Int): List<Pair<String, Any?>> {
    if (answer == 0) return listOf("result" to "bound")
    val detail = answer and 0xFFFF
    return when (answer ushr 16) {
      1 -> listOf("result" to "unavailable", "op" to "library")
      2 -> listOf("result" to "unavailable", "op" to "symbol")
      3 -> listOf("result" to "failed", "op" to "socket", "error" to "errno-$detail")
      4 -> listOf("result" to "failed", "op" to "network", "error" to "errno-$detail")
      5 -> listOf("result" to "failed", "op" to "bind", "error" to "errno-$detail")
      ACQUIRE -> listOf("result" to "failed", "op" to "acquire", "error" to "srt-$detail")
      else -> listOf("result" to "failed", "op" to "unknown", "error" to "code-$answer")
    }
  }

  private companion object {
    /** The shim's step for `srt_bind_acquire`. */
    const val ACQUIRE = 6
  }
}
