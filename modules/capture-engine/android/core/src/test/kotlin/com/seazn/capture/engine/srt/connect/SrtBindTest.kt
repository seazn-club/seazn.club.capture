package com.seazn.capture.engine.srt.connect

import com.seazn.capture.engine.core.RecordEntry
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * N49, binding: the glue's rule for binding an SRT socket to the network its attempt was asked on, through a fake of
 * the JNI shim ([SrtBinderPort]). The shim itself (dlsym of libsrt's `srt_bind_acquire`, `android_setsocknetwork`) is
 * device-only. Every outcome is one `srt-bind` line, and none of them stops the connect: the socket connects unbound.
 */
class SrtBindTest {
  private val v4 = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 7)), 778)
  private val v6 = InetSocketAddress(InetAddress.getByAddress(ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[2] = 0x0d; it[3] = 0xb8.toByte(); it[15] = 7 }), 778)
  private val wifi = 432_902_426_637L

  private class FakeBinder(var answer: () -> Int = { 0 }) : SrtBinderPort {
    val calls = mutableListOf<Triple<Int, Long, Boolean>>()

    override fun bind(srtSocket: Int, network: Long, ipv6: Boolean): Int {
      calls += Triple(srtSocket, network, ipv6)
      return answer()
    }
  }

  private val lines = mutableListOf<RecordEntry>()

  private fun binding(binder: SrtBinderPort, on: Boolean = true) = SrtBind(binder, { lines += it }, on)

  private fun fields(): List<List<Pair<String, Any?>>> = lines.onEach { assertEquals("srt-bind", it.kind) }.map { it.fields }

  @Test
  fun `the switch ships on`() {
    assertTrue(BIND_SRT_TO_NETWORK, "N49: binding ships on; a device test turns it off in one commit")
  }

  @Test
  fun `a socket is bound to the network its attempt was asked on, and the record says so`() {
    val binder = FakeBinder()
    binding(binder).bind(4, wifi, v4) { 812_345_678 }
    assertEquals(listOf(Triple(812_345_678, wifi, false)), binder.calls)
    assertEquals(listOf(listOf("attempt" to 4, "result" to "bound")), fields())
  }

  @Test
  fun `an IPv6 ingest address asks for an IPv6 UDP socket`() {
    val binder = FakeBinder()
    binding(binder).bind(4, wifi, v6) { 9 }
    assertEquals(listOf(Triple(9, wifi, true)), binder.calls)
  }

  @Test
  fun `each attempt is bound and said on its own`() {
    val binder = FakeBinder()
    val bind = binding(binder)
    bind.bind(4, wifi, v4) { 9 }
    bind.bind(5, 77L, v4) { 10 }
    assertEquals(listOf(Triple(9, wifi, false), Triple(10, 77L, false)), binder.calls)
    assertEquals(listOf(4, 5), fields().map { it.first().second })
  }

  @Test
  fun `switched off, nothing is bound and nothing is said`() {
    val binder = FakeBinder()
    var asked = false
    binding(binder, on = false).bind(4, wifi, v4) { asked = true; 9 }
    assertEquals(emptyList(), binder.calls)
    assertEquals(false, asked, "the socket's id is not even read")
    assertEquals(emptyList(), lines)
  }

  @Test
  fun `an attempt asked on no known network connects unbound, and says so`() {
    val binder = FakeBinder()
    binding(binder).bind(4, null, v4) { 9 }
    assertEquals(emptyList(), binder.calls)
    assertEquals(listOf(listOf("attempt" to 4, "result" to "no-network")), fields())
  }

  @Test
  fun `a shim that does not load is unavailable, by its class name, and never thrown`() {
    val binder = FakeBinder { throw UnsatisfiedLinkError("dlopen failed: library \"libseazn_srt_bind.so\" not found") }
    binding(binder).bind(4, wifi, v4) { 9 }
    assertEquals(listOf(listOf("attempt" to 4, "result" to "unavailable", "op" to "shim", "error" to "UnsatisfiedLinkError")), fields())
  }

  @Test
  fun `a shim that throws is unavailable, by its class name, and never thrown`() {
    val binder = FakeBinder { throw IllegalStateException("anything") }
    binding(binder).bind(4, wifi, v4) { 9 }
    assertEquals(listOf(listOf("attempt" to 4, "result" to "unavailable", "op" to "shim", "error" to "IllegalStateException")), fields())
  }

  @Test
  fun `a socket whose id cannot be read is unavailable, and the shim is never called`() {
    val binder = FakeBinder()
    binding(binder).bind(4, wifi, v4) { throw NoSuchFieldException("srtsocket") }
    assertEquals(emptyList(), binder.calls)
    assertEquals(listOf(listOf("attempt" to 4, "result" to "unavailable", "op" to "socket-id", "error" to "NoSuchFieldException")), fields())
  }

  @Test
  fun `a socket id read that fails to link is unavailable too`() {
    val binder = FakeBinder()
    binding(binder).bind(4, wifi, v4) { throw NoClassDefFoundError("SrtSocket") }
    assertEquals(listOf(listOf("attempt" to 4, "result" to "unavailable", "op" to "socket-id", "error" to "NoClassDefFoundError")), fields())
  }

  /**
   * A failed `srt_bind_acquire` leaves the UDP socket's owner unknown (libsrt may have closed it already), so the shim
   * leaves it open rather than risk closing another file's descriptor, and binding stops for the process: at most one
   * socket is left behind.
   */
  @Test
  fun `after a failed acquire, binding stops for good and each attempt says so`() {
    val binder = FakeBinder { (6 shl 16) or 5001 }
    val bind = binding(binder)
    bind.bind(4, wifi, v4) { 9 }
    binder.answer = { 0 }
    bind.bind(5, wifi, v4) { 10 }
    bind.bind(6, null, v4) { 11 }
    assertEquals(listOf(Triple(9, wifi, false)), binder.calls, "the shim is called once")
    assertEquals(
      listOf(
        listOf("attempt" to 4, "result" to "failed", "op" to "acquire", "error" to "srt-5001"),
        listOf("attempt" to 5, "result" to "unavailable", "op" to "disabled"),
        listOf("attempt" to 6, "result" to "unavailable", "op" to "disabled"),
      ),
      fields(),
    )
  }

  @Test
  fun `any other failure leaves binding on for the next attempt`() {
    val binder = FakeBinder { (4 shl 16) or 64 }
    val bind = binding(binder)
    bind.bind(4, wifi, v4) { 9 }
    binder.answer = { 0 }
    bind.bind(5, wifi, v4) { 10 }
    assertEquals(listOf("failed", "bound"), fields().map { it[1].second })
  }

  /** The shim's answer: 0 bound, else its step in the high 16 bits and the step's errno (or libsrt's code) in the low. */
  @Test
  fun `the shim's answers, each a word`() {
    val answers =
      listOf(
        (1 shl 16) to listOf("result" to "unavailable", "op" to "library"),
        (2 shl 16) to listOf("result" to "unavailable", "op" to "symbol"),
        ((3 shl 16) or 24) to listOf("result" to "failed", "op" to "socket", "error" to "errno-24"),
        ((4 shl 16) or 64) to listOf("result" to "failed", "op" to "network", "error" to "errno-64"),
        ((5 shl 16) or 98) to listOf("result" to "failed", "op" to "bind", "error" to "errno-98"),
        ((6 shl 16) or 5001) to listOf("result" to "failed", "op" to "acquire", "error" to "srt-5001"),
        ((9 shl 16) or 3) to listOf("result" to "failed", "op" to "unknown", "error" to "code-589827"),
        -1 to listOf("result" to "failed", "op" to "unknown", "error" to "code--1"),
      )
    val said =
      answers.map { (code, _) ->
        lines.clear()
        binding(FakeBinder { code }).bind(4, wifi, v4) { 9 }
        fields().single().drop(1)
      }
    assertEquals(answers.map { it.second }, said, "row each")
  }
}
