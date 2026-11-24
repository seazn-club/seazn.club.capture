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
 * Whether a failed `srt_bind_acquire` has stopped binding. [PROCESS] is the one every sink's [SrtBind] shares, so one
 * failure stops binding for the process; a test passes its own.
 */
class AcquireStop {
  @Volatile var stopped = false

  companion object {
    val PROCESS = AcquireStop()
  }
}

/**
 * N49: binds our SRT socket to the network its attempt was asked on (`Command.Connect.network`), so its packets, its
 * shutdown included, leave over that network from that network's address after the default moves: the old socket's
 * close then goes out over 4G while 4G lingers, and the new socket is pinned to Wi-Fi. Best effort and never fatal:
 * every outcome is one `srt-bind` line, and anything short of `bound` connects unbound, as before.
 * - `bound`.
 * - `no-network`: the attempt was asked while no default network was known.
 * - `unavailable`, with `op`: `shim` (ours did not load, or threw, with its class in `error`), `socket-id` (srtdroid's
 *   socket id could not be read, likewise), `library` (libsrtdroid is not loaded) or `symbol` (no `srt_bind_acquire`).
 * - `failed`, with `op` (`socket`, `network`, `bind`, `acquire`, or `unknown` for an answer the shim never gives) and
 *   `error` (`errno-<n>`, `srt-<n>` for libsrt's code, or `code-<n>`, the whole answer, for `unknown`).
 * - `failed` at `acquire` also carries `action: fresh-socket`: libsrt's state for that SRT socket is unknown, so it is
 *   closed (a close that throws adds its class as `rootError`) and a fresh socket, made as the first was, connects
 *   unbound. The shim cannot tell whether libsrt closed the UDP socket it was handed, so it leaves it open rather than
 *   risk closing another file's descriptor, and binding stops for the process ([AcquireStop.PROCESS]): at most one
 *   UDP socket is left behind. Every later attempt says `unavailable` with `op` `disabled`.
 * Off ([on] false), it reads nothing and says nothing.
 */
class SrtBind(
  private val binder: SrtBinderPort,
  private val line: (RecordEntry) -> Unit,
  private val on: Boolean = BIND_SRT_TO_NETWORK,
  private val stop: AcquireStop = AcquireStop.PROCESS,
) {
  /**
   * The socket attempt [attemptId] connects: one [fresh] makes, bound to [network] with a UDP socket of [target]'s
   * family ([socketId] reads its id), or, after a failed acquire, a second [fresh] one, unbound, the first [close]d.
   */
  fun <S> open(attemptId: Int, network: Long?, target: InetSocketAddress, fresh: () -> S, socketId: (S) -> Int, close: (S) -> Unit): S {
    val opened = fresh()
    val answer = bind(attemptId, network, target) { socketId(opened) } ?: return opened
    if (answer ushr 16 != ACQUIRE) {
      line(RecordEntry("srt-bind", listOf("attempt" to attemptId) + said(answer)))
      return opened
    }
    stop.stopped = true
    val closing =
      try {
        close(opened)
        null
      } catch (e: Exception) {
        e
      }
    line(RecordEntry("srt-bind", listOf("attempt" to attemptId) + said(answer) + listOfNotNull("action" to "fresh-socket", closing?.let { "rootError" to it.javaClass.simpleName })))
    return fresh()
  }

  /** The shim's answer, or null once the attempt is said (off, stopped, no network, or a throw). */
  private fun bind(attemptId: Int, network: Long?, target: InetSocketAddress, socketId: () -> Int): Int? {
    if (!on) return null
    if (stop.stopped) return null.also { line(RecordEntry("srt-bind", listOf("attempt" to attemptId, "result" to "unavailable", "op" to "disabled"))) }
    if (network == null) return null.also { line(RecordEntry("srt-bind", listOf("attempt" to attemptId, "result" to "no-network"))) }
    val id = guarded(attemptId, "socket-id", socketId) ?: return null
    return guarded(attemptId, "shim") { binder.bind(id, network, target.address is Inet6Address) }
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
