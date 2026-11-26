package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionConfigTest {
  @Test
  fun `a complete config has no problems`() {
    assertEquals(emptyList(), Configs.valid().problems())
  }

  @Test
  fun `an empty descriptor field is a problem, not a crash`() {
    val empty =
      SessionConfig(
        sid = "",
        token = "",
        primary = SrtTarget("", "", "", 0),
        fallback = RtmpsTarget("", ""),
        holdWindowSeconds = emptyMap(),
        playbackUrl = "",
        heartbeatUrl = "",
        appVersion = "",
      )
    assertEquals(
      listOf(
        "sid is blank",
        "token is blank",
        "playbackUrl is not https",
        "heartbeatUrl is not https",
        "no hold window for srt",
        "srt url is blank",
        "srt streamId is blank",
        "srt passphrase is not 10–79 characters",
        "srt latencyMs is not positive",
        "no hold window for rtmps",
        "rtmps url is blank",
        "rtmps streamKey is blank",
      ),
      empty.problems(),
    )
  }

  @Test
  fun `a fallback on the same transport as the primary is a problem`() {
    assertTrue("fallback repeats the primary transport" in Configs.valid(fallback = Configs.srt).problems())
  }

  @Test
  fun `no secret appears in any toString`() {
    val printed = listOf(Configs.valid().toString(), Configs.srt.toString(), Configs.rtmps.toString())
    for (secret in listOf(Configs.TOKEN, Configs.PASSPHRASE, Configs.STREAM_ID, Configs.STREAM_KEY)) {
      assertFalse(printed.any { secret in it }, "leaked $secret")
    }
  }

  @Test
  fun `an RTMPS url that carries its key never prints it - the url is masked`() {
    val keyed = RtmpsTarget(Configs.rtmps.url + Configs.STREAM_KEY, Configs.STREAM_KEY)
    val printed = listOf(keyed.toString(), Configs.valid(fallback = keyed).toString(), Configs.descriptor(fallback = keyed).toString())
    for (text in printed) assertFalse(Configs.STREAM_KEY in text, text)
  }

  @Test
  fun `secrets are the token, the SRT passphrase and stream id, and the RTMPS stream key`() {
    assertEquals(
      setOf(Configs.TOKEN, Configs.PASSPHRASE, Configs.STREAM_ID, Configs.STREAM_KEY),
      Configs.valid().secrets().toSet(),
    )
  }

  // Every string contains "", so a blank secret would mask whatever the record met.
  @Test
  fun `a blank value is not a secret`() {
    val blankKey = Configs.valid(fallback = RtmpsTarget("rtmps://live.cloudflare.com:443/live/", ""))
    assertEquals(setOf(Configs.TOKEN, Configs.PASSPHRASE, Configs.STREAM_ID), blankKey.secrets().toSet())
  }

  // The heartbeat carries the Bearer tok, and playbackUrl is https (spec, "Ask: for the web side").
  @Test
  fun `a plain-http playback or heartbeat URL is a problem`() {
    val plain =
      SessionConfig(
        sid = "sess_42",
        token = Configs.TOKEN,
        primary = Configs.srt,
        fallback = Configs.rtmps,
        holdWindowSeconds = mapOf(Transport.SRT to 183, Transport.RTMPS to 180),
        playbackUrl = "http://customer-x.cloudflarestream.com/${Configs.STREAM_ID}/manifest/video.m3u8",
        heartbeatUrl = "http://stg.seazn.club/api/capture/heartbeat",
        appVersion = "1.0.0",
      )
    assertEquals(listOf("playbackUrl is not https", "heartbeatUrl is not https"), plain.problems())
  }

  @Test
  fun `a hold window that is not positive is a problem`() {
    val windows = mapOf(Transport.SRT to 0, Transport.RTMPS to -1)
    assertEquals(
      listOf("no hold window for srt", "no hold window for rtmps"),
      Configs.valid(holdWindowSeconds = windows).problems(),
    )
  }

  // The window is looked up per transport: SRT's does not stand in for RTMPS's.
  @Test
  fun `each transport needs its own hold window`() {
    assertEquals(
      listOf("no hold window for rtmps"),
      Configs.valid(holdWindowSeconds = mapOf(Transport.SRT to 183)).problems(),
    )
  }

  @Test
  fun `an RTMPS-preferred config has the same four secrets and no problems`() {
    val rtmpsFirst = Configs.valid(primary = Configs.rtmps, fallback = Configs.srt)
    assertEquals(
      setOf(Configs.TOKEN, Configs.PASSPHRASE, Configs.STREAM_ID, Configs.STREAM_KEY),
      rtmpsFirst.secrets().toSet(),
    )
    assertEquals(emptyList(), rtmpsFirst.problems())
  }

  @Test
  fun `an SRT fallback's passphrase is checked like a primary's`() {
    val shortFallback = SrtTarget(Configs.srt.url, Configs.STREAM_ID, "p".repeat(9), 2_000)
    assertEquals(
      listOf("srt passphrase is not 10–79 characters"),
      Configs.valid(primary = Configs.rtmps, fallback = shortFallback).problems(),
    )
  }

  // libsrt takes a passphrase of 10–79 characters (P5 results, F-P5-1's ruled-out list).
  @Test
  fun `an SRT passphrase of 10 or 79 characters is fine, and 9 or 80 is a problem`() {
    fun problemsWith(length: Int) =
      Configs.valid(primary = SrtTarget(Configs.srt.url, Configs.STREAM_ID, "p".repeat(length), 2_000)).problems()
    val outOfRange = listOf("srt passphrase is not 10–79 characters")
    assertEquals(outOfRange, problemsWith(9))
    assertEquals(emptyList(), problemsWith(10))
    assertEquals(emptyList(), problemsWith(79))
    assertEquals(outOfRange, problemsWith(80))
  }

  @Test
  fun `target is the transport's own, and none when there is no fallback`() {
    assertSame(Configs.rtmps, Configs.valid().target(Transport.RTMPS))
    assertSame(Configs.srt, Configs.valid().target(Transport.SRT))
    assertNull(Configs.valid(fallback = null).target(Transport.RTMPS))
  }

  private fun tagFor(url: String) = Configs.valid().let { c ->
    SessionConfig(c.sid, c.token, c.primary, c.fallback, c.holdWindowSeconds, url, c.heartbeatUrl, c.appVersion)
  }.playbackTag()

  // ff25d2 is `printf %s a1b2c3d4e5f60718293a4b5c6d7e8f90 | shasum -a 256`, first six hex digits.
  @Test
  fun `the playback tag is the host and the first six hex digits of the SHA-256 of the uid`() {
    assertEquals(
      listOf("host" to "customer-x.cloudflarestream.com", "tag" to "ff25d2"),
      Configs.valid().playbackTag(),
    )
  }

  @Test
  fun `the tag shares no six-character run with the uid`() {
    val tag = Configs.valid().playbackTag().single { it.first == "tag" }.second
    assertFalse(tag in Configs.STREAM_ID)
    assertFalse(Configs.STREAM_ID.takeLast(6) == tag)
  }

  @Test
  fun `a segment that is not a 32 hex uid yields neither host nor tag`() {
    val host = "https://customer-x.cloudflarestream.com"
    val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhIn0.c2lnbmF0dXJlLXBhcnQ"
    assertEquals(emptyList(), tagFor("$host/$jwt/manifest/video.m3u8"))
    assertEquals(emptyList(), tagFor("$host/abc12/manifest/video.m3u8"))
    assertEquals(emptyList(), tagFor("$host/a1b2c3d4e5f60718293a4b5c6d7e8f9z/manifest/video.m3u8"))
    assertEquals(emptyList(), tagFor("$host/A1B2C3D4E5F60718293A4B5C6D7E8F90/manifest/video.m3u8"))
    assertEquals(emptyList(), tagFor("$host/a1b2c3d4e5f60718293a4b5c6d7e8f900/manifest/video.m3u8"))
  }

  @Test
  fun `a playback URL that does not parse into a host and a uid yields no tag`() {
    assertEquals(emptyList(), tagFor(""))
    assertEquals(emptyList(), tagFor("https://customer-x.cloudflarestream.com/"))
    assertEquals(emptyList(), tagFor("https://customer-x.cloudflarestream.com/manifest/video.m3u8"))
    assertEquals(emptyList(), tagFor("https://${"h".repeat(70)}.example/${Configs.STREAM_ID}/manifest/video.m3u8"))
  }
}
