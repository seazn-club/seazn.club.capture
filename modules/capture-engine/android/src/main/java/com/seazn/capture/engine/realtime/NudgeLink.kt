package com.seazn.capture.engine.realtime

import com.seazn.capture.engine.adapter.JsonReader
import com.seazn.capture.engine.adapter.WireJson
import com.seazn.capture.engine.core.Cancellable
import com.seazn.capture.engine.core.Input
import com.seazn.capture.engine.core.NudgeState
import com.seazn.capture.engine.core.RealtimeGrant
import com.seazn.capture.engine.core.RecordEntry
import com.seazn.capture.engine.core.Scheduler

/** One text WebSocket (OkHttp's in the app, a fake in core's tests). [send] answers false when the frame was not queued. */
interface NudgeSocket {
  fun send(text: String): Boolean

  fun close()
}

/** What the socket says, on whatever thread it says it. [closed] carries the close code, or null for a failure. */
interface NudgeSocketEvents {
  fun opened()

  fun text(frame: String)

  fun closed(status: Int?)
}

fun interface NudgeSocketPort {
  fun open(url: String, events: NudgeSocketEvents): NudgeSocket
}

/**
 * The capture nudge's socket (design 2026-11-24 §2.3; the web's wire, confirmed 2026-11-24): Supabase Realtime's
 * Phoenix protocol, vsn 1.0.0 JSON text frames, on the grant the core hands over ([grant], [off]).
 *
 * - `wss://{the grant's host}/realtime/v1/websocket?apikey={anon key}&vsn=1.0.0`. The anon key is public; the token
 *   goes only in the join's payload and in `access_token`, never in the URL.
 * - Once open: `phx_join` on `realtime:{topic}` with `{config: {broadcast: {self: false}, private: true}, access_token}`,
 *   and a `heartbeat` on `phoenix` every [HEARTBEAT_MS]; one still unanswered when the next is due ends the socket, and so
 *   does a join unanswered after [JOIN_TIMEOUT_MS].
 * - A `broadcast` on our topic whose `payload.event` is `nudge` reports [Input.Nudge] with `payload.payload.reason`;
 *   the core reads the word. Anything else is ignored.
 * - A new token on the same channel goes as `access_token` (after the join's ok, if it is not in yet); a new topic
 *   leaves the old channel and joins the new one; a new host or key is a new socket.
 * - The join's ok reports [NudgeState.JOINED]; its error [NudgeState.REFUSED]; the channel's error or close, a missed
 *   heartbeat or the socket's end [NudgeState.CLOSED] (with the close code when there is one). Each but the join's ok
 *   closes the socket and reconnects on [BACKOFF_MS], reset by a join, never without a grant or with an expired one.
 *
 * Every socket event hops onto [scheduler] (the engine's thread) and is dropped when it belongs to a socket this link
 * has already let go ([generation]), so a late close of an old socket is never reported. A throw costs a
 * `watch-failed` line, never the process. Imports nothing Android, so core's tests run it on the JVM.
 */
class NudgeLink(
  private val sockets: NudgeSocketPort,
  private val scheduler: Scheduler,
  private val wallMs: () -> Long,
  private val report: (Input) -> Unit,
  private val line: (RecordEntry) -> Unit,
) {
  private var grant: RealtimeGrant? = null
  private var socket: NudgeSocket? = null
  private var generation = 0
  private var open = false

  /** The topic joined (or being joined), its join's ref, and the token it was last given. */
  private var topic: String? = null
  private var joinRef: String? = null
  private var joined = false
  private var tokenIn: String? = null

  private var ref = 0
  private var heartbeatRef: String? = null
  private var heartbeat: Cancellable? = null
  private var retry: Cancellable? = null
  private var joinWait: Cancellable? = null
  private var attempts = 0

  /**
   * The grant the socket should hold: connect, refresh the token, or move to another channel or host. With no socket
   * (a reconnect waiting on the backoff included) it connects at once: a new grant comes only with a beat's answer
   * (the core swaps it after a refusal), so these tries are bounded by the beat cadence, not the backoff.
   */
  @Synchronized
  fun grant(next: RealtimeGrant) = guarded {
    val held = grant
    grant = next
    when {
      next.expired(wallMs()) -> drop()
      socket == null -> connect()
      held == null || held.url != next.url || held.apikey != next.apikey -> {
        drop()
        connect()
      }
      !open -> Unit // the join goes, with the newest grant, once the socket opens
      held.topic != next.topic -> {
        send(frame("realtime:${held.topic}", "phx_leave", emptyMap<String, Any?>(), nextRef()))
        join()
      }
      joined && next.token != tokenIn -> refresh()
    }
  }

  /** No grant: the socket closes and nothing reconnects. */
  @Synchronized
  fun off() = guarded {
    grant = null
    drop()
  }

  private fun connect() {
    retry?.cancel()
    retry = null
    val held = grant ?: return
    if (held.expired(wallMs())) return
    val mine = ++generation
    try {
      socket = sockets.open(url(held), Events(mine))
    } catch (failure: Exception) {
      failed(failure)
      retryLater()
    }
  }

  /** Lets the socket go: no event of it is heard again, and no timer of it runs. */
  private fun drop() {
    generation++
    retry?.cancel()
    retry = null
    heartbeat?.cancel()
    heartbeat = null
    heartbeatRef = null
    joinWait?.cancel()
    joinWait = null
    val gone = socket
    socket = null
    open = false
    topic = null
    joinRef = null
    joined = false
    tokenIn = null
    try {
      gone?.close()
    } catch (failure: Exception) {
      failed(failure)
    }
  }

  /** The socket ended as [state] says: reported, dropped, and tried again on the backoff. */
  private fun lost(state: NudgeState, status: Int? = null) {
    report(Input.NudgeLink(state, status))
    drop()
    retryLater()
  }

  /** The next try on the backoff. Whether there is still a grant in date is [connect]'s to judge, when it runs. */
  private fun retryLater() {
    val delay = BACKOFF_MS[attempts.coerceAtMost(BACKOFF_MS.lastIndex)]
    attempts++
    retry = scheduler.schedule(delay) { synchronized(this) { guarded { connect() } } }
  }

  private fun opened() {
    open = true
    join()
    beatLater()
  }

  private fun join() {
    val held = grant ?: return
    topic = held.topic
    joined = false
    val joining = nextRef()
    joinRef = joining
    tokenIn = held.token
    val config = linkedMapOf("broadcast" to linkedMapOf("self" to false), "private" to true)
    send(frame("realtime:${held.topic}", "phx_join", linkedMapOf("config" to config, "access_token" to held.token), joining, joinRef = joining))
    joinWait?.cancel()
    joinWait = scheduler.schedule(JOIN_TIMEOUT_MS) { synchronized(this) { guarded { if (!joined && joinRef == joining) lost(NudgeState.CLOSED) } } }
  }

  private fun refresh() {
    val held = grant ?: return
    tokenIn = held.token
    send(frame("realtime:${held.topic}", "access_token", linkedMapOf("access_token" to held.token), nextRef()))
  }

  private fun beatLater() {
    heartbeat = scheduler.schedule(HEARTBEAT_MS) { synchronized(this) { guarded { beat() } } }
  }

  private fun beat() {
    if (!open) return
    if (heartbeatRef != null) return lost(NudgeState.CLOSED)
    val beating = nextRef()
    heartbeatRef = beating
    send(frame("phoenix", "heartbeat", emptyMap<String, Any?>(), beating))
    beatLater()
  }

  private fun heard(text: String) {
    val message = (read(text) as? Map<*, *>) ?: return
    val event = message["event"] as? String ?: return
    val from = message["topic"] as? String ?: return
    val payload = message["payload"] as? Map<*, *>
    if (from == "phoenix") {
      if (event == "phx_reply" && message["ref"] == heartbeatRef) heartbeatRef = null
      return
    }
    if (from != "realtime:$topic") return
    when (event) {
      "phx_reply" -> if (message["ref"] == joinRef) joinReplied(payload?.get("status"))
      "phx_error", "phx_close" -> lost(NudgeState.CLOSED)
      "broadcast" -> if (payload?.get("event") == "nudge") report(Input.Nudge((payload["payload"] as? Map<*, *>)?.get("reason") as? String))
    }
  }

  private fun joinReplied(status: Any?) {
    joinWait?.cancel()
    joinWait = null
    if (status != "ok") return lost(NudgeState.REFUSED)
    joined = true
    attempts = 0
    report(Input.NudgeLink(NudgeState.JOINED))
    if (grant?.token != tokenIn) refresh()
  }

  private fun read(text: String): Any? =
    try {
      JsonReader.parse(text)
    } catch (malformed: JsonReader.Malformed) {
      null
    }

  private fun send(text: String) {
    socket?.send(text)
  }

  private fun nextRef(): String = (++ref).toString()

  private fun frame(topic: String, event: String, payload: Map<String, Any?>, ref: String, joinRef: String? = null): String {
    val message = linkedMapOf<String, Any?>("topic" to topic, "event" to event, "payload" to payload, "ref" to ref)
    if (joinRef != null) message["join_ref"] = joinRef
    return WireJson.encode(message)
  }

  private fun failed(failure: Exception) = line(RecordEntry("watch-failed", listOf("action" to "nudge-link", "error" to failure.javaClass.simpleName)))

  private inline fun guarded(block: () -> Unit) {
    try {
      block()
    } catch (failure: Exception) {
      failed(failure)
    }
  }

  /** One socket's events, heard on the engine's thread only while that socket is still this link's. */
  private inner class Events(private val mine: Int) : NudgeSocketEvents {
    override fun opened() = hop { opened() }

    override fun text(frame: String) = hop { heard(frame) }

    override fun closed(status: Int?) = hop { lost(NudgeState.CLOSED, status) }

    private fun hop(block: NudgeLink.() -> Unit) {
      try {
        scheduler.schedule(0) { synchronized(this@NudgeLink) { if (mine == generation) guarded { this@NudgeLink.block() } } }
      } catch (failure: Exception) {
        failed(failure)
      }
    }
  }

  companion object {
    /** Phoenix's own heartbeat interval, and realtime-js's. */
    const val HEARTBEAT_MS = 25_000L

    /** A join unanswered this long ends the socket, as a missed heartbeat does (realtime-js's push timeout). */
    const val JOIN_TIMEOUT_MS = 10_000L

    /** 1, 2, 4, 8, 16, then 30 s for every later try. */
    val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)

    /**
     * The grant's https origin as the socket's wss URL. Bodies holds the origin with no trailing slash, and the key's
     * characters need no encoding (both Bodies' rules).
     */
    fun url(grant: RealtimeGrant): String = "wss://${grant.url.substringAfter("://")}/realtime/v1/websocket?apikey=${grant.apikey}&vsn=1.0.0"
  }
}
