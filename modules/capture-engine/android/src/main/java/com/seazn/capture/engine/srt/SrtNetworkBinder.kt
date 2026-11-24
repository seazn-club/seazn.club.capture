package com.seazn.capture.engine.srt

import com.seazn.capture.engine.srt.connect.SrtBinderPort

/**
 * N49: the JNI shim (`src/main/cpp/srt_bind.c`, `libseazn_srt_bind.so`) as [SrtBinderPort]. The rule, the record line and
 * the fallback to an unbound connect are `SrtBind`'s, which core's tests run; this only loads the shim and calls it. A
 * shim that does not load throws UnsatisfiedLinkError at each bind, which SrtBind says as `unavailable`. Device-only.
 */
object SrtNetworkBinder : SrtBinderPort {
  @Volatile private var loaded = false

  override fun bind(srtSocket: Int, network: Long, ipv6: Boolean): Int {
    if (!loaded) {
      System.loadLibrary("seazn_srt_bind")
      loaded = true
    }
    return nativeBind(srtSocket, network, ipv6)
  }

  @JvmStatic private external fun nativeBind(srtSocket: Int, network: Long, ipv6: Boolean): Int
}
