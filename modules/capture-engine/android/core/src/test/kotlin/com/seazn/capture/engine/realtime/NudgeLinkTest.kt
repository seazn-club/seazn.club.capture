package com.seazn.capture.engine.realtime

import com.seazn.capture.engine.adapter.JsonReader
import com.seazn.capture.engine.core.FakeClock
import com.seazn.capture.engine.core.FakeScheduler
import com.seazn.capture.engine.core.Input
import com.seazn.capture.engine.core.NudgeState
import com.seazn.capture.engine.core.RealtimeGrant
import com.seazn.capture.engine.core.RecordEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Design 2026-11-24 §2.3: the nudge socket, Phoenix vsn 1.0.0 JSON over a fake socket. Frames are written by hand from
 * the web's confirmed wire (2026-11-24): join `realtime:{topic}` with `private: true` and `access_token`, the
 * broadcast's reason at `payload.payload.reason`, heartbeats on `phoenix` every 25 s, backoff 1 to 30 s.
 */
class NudgeLinkTest {
  private class FakeSocket(val url: String, val events: NudgeSocketEvents) : NudgeSocket {
    val sent = mutableListOf<String>()
    var closed = false

    override fun send(text: String): Boolean {
      sent += text
      return !closed
    }

    override fun close() {
      closed = true
    }

    @Suppress("UNCHECKED_CAST")
    fun frames(): List<Map<String, Any?>> = sent.map { JsonReader.parse(it) as Map<String, Any?> }
  }

  private class Rig {
    val clock = FakeClock()
    val scheduler = FakeScheduler(clock)
    val sockets = mutableListOf<FakeSocket>()
    val reports = mutableListOf<Input>()
    val lines = mutableListOf<RecordEntry>()
    var throwOnOpen = false
    val link =
      NudgeLink(
        object : NudgeSocketPort {
          override fun open(url: String, events: NudgeSocketEvents): NudgeSocket {
            if (throwOnOpen) throw IllegalStateException("no socket")
            return FakeSocket(url, events).also { sockets += it }
          }
        },
        scheduler,
        wallMs = { clock.wall },
        report = { reports += it },
        line = { lines += it },
      )

    val socket get() = sockets.last()

    fun grant(token: String = "made-up.realtime-token.0001", topic: String = TOPIC, leftMs: Long = 30 * 60_000L, url: String = "https://example-ref.supabase.co") =
      RealtimeGrant(url, "anon.example-key_01", topic, token, (clock.wall + leftMs) / 1_000)

    fun run(ms: Long = 0) = scheduler.advanceBy(ms)

    fun open() {
      socket.events.opened()
      run()
    }

    fun hear(frame: String) {
      socket.events.text(frame)
      run()
    }

    fun joinRef(): String = socket.frames().last { it["event"] == "phx_join" }["ref"] as String

    fun joined() {
      open()
      hear("""{"topic":"realtime:$TOPIC","event":"phx_reply","payload":{"status":"ok","response":{}},"ref":"${joinRef()}"}""")
    }

    fun nudge(reason: Any?, topic: String = TOPIC) =
      hear("""{"topic":"realtime:$topic","event":"broadcast","payload":{"type":"broadcast","event":"nudge","payload":{"v":1790000000000,"reason":${reason?.let { "\"$it\"" } ?: "null"},"at":"2026-09-21T15:13:20Z"}},"ref":null}""")
  }

  @Test
  fun `a grant opens wss on the grant's host with the anon key and vsn 1_0_0, never the token`() {
    val rig = Rig()
    rig.link.grant(rig.grant())
    assertEquals("wss://example-ref.supabase.co/realtime/v1/websocket?apikey=anon.example-key_01&vsn=1.0.0", rig.socket.url)
    assertFalse("made-up" in rig.socket.url)
  }

  @Test
  fun `a port on the origin is kept`() {
    val rig = Rig()
    rig.link.grant(rig.grant(url = "https://example-ref.supabase.co:8443"))
    assertTrue(rig.socket.url.startsWith("wss://example-ref.supabase.co:8443/realtime/v1/websocket?"))
  }

  @Test
  fun `once open it joins the private channel with the token in the payload, and a reply ok reports joined`() {
    val rig = Rig()
    rig.link.grant(rig.grant())
    assertTrue(rig.socket.sent.isEmpty(), "nothing before the socket opens")
    rig.open()
    val join = rig.socket.frames().single()
    assertEquals("realtime:$TOPIC", join["topic"])
    assertEquals("phx_join", join["event"])
    assertEquals(join["ref"], join["join_ref"])
    @Suppress("UNCHECKED_CAST") val payload = join["payload"] as Map<String, Any?>
    assertEquals(mapOf("broadcast" to mapOf("self" to false), "private" to true), payload["config"])
    assertEquals("made-up.realtime-token.0001", payload["access_token"])
    rig.hear("""{"topic":"realtime:$TOPIC","event":"phx_reply","payload":{"status":"ok","response":{}},"ref":"${rig.joinRef()}"}""")
    assertEquals(listOf<Input>(Input.NudgeLink(NudgeState.JOINED)), rig.reports)
  }

  @Test
  fun `a nudge broadcast reports its reason, read at payload_payload_reason`() {
    val rig = Rig()
    rig.link.grant(rig.grant())
    rig.joined()
    rig.nudge("go-live")
    rig.nudge("teleport")
    rig.nudge(null)
    assertEquals(listOf<Input>(Input.Nudge("go-live"), Input.Nudge("teleport"), Input.Nudge(null)), rig.reports.drop(1))
  }

  @Test
  fun `other events, other topics, a reply to nothing and garbled frames are ignored`() {
    val rig = Rig()
    rig.link.grant(rig.grant())
    rig.joined()
    val before = rig.reports.size
    rig.hear("""{"topic":"realtime:$TOPIC","event":"broadcast","payload":{"type":"broadcast","event":"score","payload":{"reason":"go-live"}},"ref":null}""")
    rig.nudge("go-live", topic = "capture:another")
    rig.hear("""{"topic":"realtime:$TOPIC","event":"presence_state","payload":{},"ref":null}""")
    rig.hear("""{"topic":"realtime:$TOPIC","event":"system","payload":{"status":"ok"},"ref":null}""")
    rig.hear("""{"topic":"realtime:$TOPIC","event":"phx_reply","payload":{"status":"error"},"ref":"999"}""")
    rig.hear("not json")
    rig.hear("[1,2]")
    rig.hear("""{"topic":"realtime:$TOPIC","event":"broadcast","payload":"nudge","ref":null}""")
    assertEquals(before, rig.reports.size)
    assertFalse(rig.socket.closed)
  }

  @Test
  fun `a nudge before the join's reply still reports - the server sends it only to a joined channel`() {
    val rig = Rig()
    rig.link.grant(rig.grant())
    rig.open()
    rig.nudge("stop")
    assertEquals(listOf<Input>(Input.Nudge("stop")), rig.reports)
  }

  @Test
  fun `a refused join reports refused with no status, closes, and reconnects after 1 s`() {
    val rig = Rig()
    rig.link.grant(rig.grant())
    rig.open()
    rig.hear("""{"topic":"realtime:$TOPIC","event":"phx_reply","payload":{"status":"error","response":{"reason":"Unauthorized"}},"ref":"${rig.joinRef()}"}""")
    assertEquals(listOf<Input>(Input.NudgeLink(NudgeState.REFUSED)), rig.reports)
    assertTrue(rig.socket.closed)
    rig.run(999)
    assertEquals(1, rig.sockets.size)
    rig.run(1)
    assertEquals(2, rig.sockets.size)
  }

  @Test
  fun `a channel error or close on our topic reports closed and reconnects`() {
    for (event in listOf("phx_error", "phx_close")) {
      val rig = Rig()
      rig.link.grant(rig.grant())
      rig.joined()
      rig.hear("""{"topic":"realtime:$TOPIC","event":"$event","payload":{},"ref":null}""")
      assertEquals(Input.NudgeLink(NudgeState.CLOSED), rig.reports.last(), event)
      assertTrue(rig.socket.closed, event)
      rig.run(1_000)
      assertEquals(2, rig.sockets.size, event)
    }
  }

  @Test
  fun `the socket's close reports its status, a failure none, and the backoff runs 1 2 4 8 16 30 30 s`() {
    val rig = Rig()
    rig.link.grant(rig.grant())
    rig.socket.events.closed(1006)
    rig.run()
    assertEquals(Input.NudgeLink(NudgeState.CLOSED, 1006), rig.reports.last())
    val delays = mutableListOf<Long>()
    for (i in 0 until 7) {
      val count = rig.sockets.size
      var waited = 0L
      while (rig.sockets.size == count) {
        rig.run(500)
        waited += 500
      }
      delays += waited
      rig.socket.events.closed(null)
      rig.run()
    }
    assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), delays)
    assertEquals(Input.NudgeLink(NudgeState.CLOSED, null), rig.reports.last())
  }

  @Test
  fun `a join resets the backoff to 1 s`() {
    val rig = Rig()
    rig.link.grant(rig.grant())
    rig.socket.events.closed(1006)
    rig.run(1_000)
    rig.socket.events.closed(1006)
    rig.run(2_000)
    rig.joined()
    rig.socket.events.closed(1006)
    rig.run(999)
    assertEquals(3, rig.sockets.size)
    rig.run(1)
    assertEquals(4, rig.sockets.size)
  }

  @Test
  fun `heartbeats go on the phoenix topic every 25 s, and one unanswered at the next closes and reconnects`() {
    val rig = Rig()
    rig.link.grant(rig.grant())
    rig.joined()
    rig.run(24_999)
    assertTrue(rig.socket.frames().none { it["event"] == "heartbeat" })
    rig.run(1)
    val beat = rig.socket.frames().last()
    assertEquals("phoenix", beat["topic"])
    assertEquals("heartbeat", beat["event"])
    assertEquals(emptyMap<String, Any?>(), beat["payload"])
    rig.hear("""{"topic":"phoenix","event":"phx_reply","payload":{"status":"ok","response":{}},"ref":"${beat["ref"]}"}""")
    rig.run(25_000)
    assertFalse(rig.socket.closed, "the first was answered")
    rig.run(25_000)
    assertTrue(rig.socket.closed, "the second was not")
    assertEquals(Input.NudgeLink(NudgeState.CLOSED), rig.reports.last())
    rig.run(1_000)
    assertEquals(2, rig.sockets.size)
  }

  @Test
  fun `a fresh token on the same channel goes as access_token on the joined channel, never a rejoin`() {
    val rig = Rig()
    rig.link.grant(rig.grant())
    rig.joined()
    rig.link.grant(rig.grant(token = "made-up.realtime-token.0002"))
    rig.run()
    val last = rig.socket.frames().last()
    assertEquals("realtime:$TOPIC", last["topic"])
    assertEquals("access_token", last["event"])
    assertEquals(mapOf("access_token" to "made-up.realtime-token.0002"), last["payload"])
    assertEquals(1, rig.socket.frames().count { it["event"] == "phx_join" })
    assertEquals(1, rig.sockets.size)
  }

  @Test
  fun `the same grant again sends nothing`() {
    val rig = Rig()
    val grant = rig.grant()
    rig.link.grant(grant)
    rig.joined()
    val sent = rig.socket.sent.size
    rig.link.grant(grant)
    rig.run()
    assertEquals(sent, rig.socket.sent.size)
    assertEquals(1, rig.sockets.size)
  }

  @Test
  fun `a fresh token while the join is unanswered goes once the join is ok`() {
    val rig = Rig()
    rig.link.grant(rig.grant())
    rig.open()
    rig.link.grant(rig.grant(token = "made-up.realtime-token.0002"))
    assertEquals(1, rig.socket.frames().size, "only the join so far")
    rig.hear("""{"topic":"realtime:$TOPIC","event":"phx_reply","payload":{"status":"ok","response":{}},"ref":"${rig.joinRef()}"}""")
    assertEquals(mapOf("access_token" to "made-up.realtime-token.0002"), rig.socket.frames().last()["payload"])
  }

  @Test
  fun `a new topic leaves the old channel and joins the new one on the same socket`() {
    val rig = Rig()
    rig.link.grant(rig.grant())
    rig.joined()
    rig.link.grant(rig.grant(topic = "capture:another-pairing"))
    rig.run()
    val (leave, join) = rig.socket.frames().takeLast(2)
    assertEquals("realtime:$TOPIC", leave["topic"])
    assertEquals("phx_leave", leave["event"])
    assertEquals("realtime:capture:another-pairing", join["topic"])
    assertEquals("phx_join", join["event"])
    assertEquals(1, rig.sockets.size)
    // A nudge on the old topic is no longer ours.
    val before = rig.reports.size
    rig.nudge("go-live")
    assertEquals(before, rig.reports.size)
    rig.nudge("go-live", topic = "capture:another-pairing")
    assertEquals(Input.Nudge("go-live"), rig.reports.last())
  }

  @Test
  fun `a new host opens a new socket and closes the old one, whose late events are ignored`() {
    val rig = Rig()
    rig.link.grant(rig.grant())
    rig.joined()
    val old = rig.socket
    rig.link.grant(rig.grant(url = "https://other-ref.supabase.co"))
    assertTrue(old.closed)
    assertEquals(2, rig.sockets.size)
    val before = rig.reports.size
    old.events.text("""{"topic":"realtime:$TOPIC","event":"broadcast","payload":{"type":"broadcast","event":"nudge","payload":{"reason":"stop"}},"ref":null}""")
    old.events.closed(1006)
    rig.run(60_000)
    assertEquals(before, rig.reports.size)
    assertEquals(2, rig.sockets.size)
  }

  @Test
  fun `a new key alone is a new socket`() {
    val rig = Rig()
    rig.link.grant(rig.grant())
    rig.joined()
    rig.link.grant(rig.grant().let { RealtimeGrant(it.url, "anon.other-key", it.topic, it.token, it.expiresAtS) })
    assertEquals(2, rig.sockets.size)
    assertTrue(rig.sockets.first().closed)
    assertTrue(rig.socket.url.contains("apikey=anon.other-key&"))
  }

  @Test
  fun `off closes the socket, cancels the reconnect and the heartbeat, and its late close is not reported`() {
    val rig = Rig()
    rig.link.grant(rig.grant())
    rig.joined()
    rig.link.off()
    assertTrue(rig.socket.closed)
    val before = rig.reports.size
    rig.socket.events.closed(1000)
    rig.run(120_000)
    assertEquals(before, rig.reports.size)
    assertEquals(1, rig.sockets.size)
    assertEquals(0, rig.scheduler.pending)
  }

  @Test
  fun `off while a reconnect waits cancels it, and a second off is nothing`() {
    val rig = Rig()
    rig.link.grant(rig.grant())
    rig.socket.events.closed(1006)
    rig.run()
    rig.link.off()
    rig.link.off()
    rig.run(120_000)
    assertEquals(1, rig.sockets.size)
  }

  @Test
  fun `no reconnect with an expired grant, and an expired grant opens nothing`() {
    val rig = Rig()
    rig.link.grant(rig.grant(leftMs = 2_500))
    rig.socket.events.closed(1006)
    rig.run(1_000)
    assertEquals(2, rig.sockets.size, "still in date at 1 s")
    rig.socket.events.closed(1006)
    rig.run(120_000)
    assertEquals(2, rig.sockets.size, "expired by the 2 s retry")

    val fresh = Rig()
    fresh.link.grant(fresh.grant(leftMs = -1_000))
    assertTrue(fresh.sockets.isEmpty())
  }

  @Test
  fun `a new grant while a reconnect waits connects at once`() {
    val rig = Rig()
    rig.link.grant(rig.grant())
    rig.socket.events.closed(1006)
    rig.run()
    rig.link.grant(rig.grant(token = "made-up.realtime-token.0002"))
    assertEquals(2, rig.sockets.size)
    rig.run(120_000)
    assertEquals(2, rig.sockets.size, "the waiting reconnect was cancelled")
  }

  @Test
  fun `a socket that cannot be opened costs a line and retries on the backoff`() {
    val rig = Rig()
    rig.throwOnOpen = true
    rig.link.grant(rig.grant())
    assertEquals("watch-failed", rig.lines.single().kind)
    assertEquals(mapOf<String, Any?>("action" to "nudge-link", "error" to "IllegalStateException"), rig.lines.single().fields.toMap())
    rig.throwOnOpen = false
    rig.run(1_000)
    assertEquals(1, rig.sockets.size)
  }

  @Test
  fun `no frame the link sends holds the token outside a join or access_token payload, and no line or report holds it`() {
    val rig = Rig()
    rig.link.grant(rig.grant())
    rig.joined()
    rig.link.grant(rig.grant(token = "made-up.realtime-token.0002"))
    rig.run(25_000)
    for (frame in rig.socket.frames()) {
      @Suppress("UNCHECKED_CAST") val payload = frame["payload"] as Map<String, Any?>
      val outside = frame - "payload" + payload.filterKeys { it != "access_token" }
      assertFalse("made-up" in outside.toString(), frame.toString())
    }
    assertFalse((rig.lines + rig.reports).joinToString().contains("made-up"))
    assertNull(rig.lines.firstOrNull())
  }

  private companion object {
    const val TOPIC = "capture:6f1c2b7a-3d4e-4f50-8a9b-0c1d2e3f4a5b"
  }
}
