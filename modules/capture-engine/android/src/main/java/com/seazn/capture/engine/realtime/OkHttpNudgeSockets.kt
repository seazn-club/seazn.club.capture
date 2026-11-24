package com.seazn.capture.engine.realtime

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * [NudgeSocketPort] on OkHttp's WebSocket: the OkHttp React Native ships at runtime (`:app:dependencies`, 4.12.0), on a
 * client of its own so the beat's timeouts never cut a long-lived socket. OkHttp sends no pings of its own: the link's
 * Phoenix heartbeat is the liveness check. Each socket's end is told once, whichever of closing, closed and failure
 * comes first; a failure's exception is never read (OkHttp's messages can quote the URL).
 */
class OkHttpNudgeSockets(private val client: OkHttpClient = client()) : NudgeSocketPort {
  override fun open(url: String, events: NudgeSocketEvents): NudgeSocket {
    val listener = Listener(events)
    val socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
    return object : NudgeSocket {
      override fun send(text: String) = socket.send(text)

      override fun close() {
        if (!socket.close(NORMAL, null)) socket.cancel()
      }
    }
  }

  private class Listener(private val events: NudgeSocketEvents) : WebSocketListener() {
    @Volatile private var ended = false

    private fun end(status: Int?) {
      if (ended) return
      ended = true
      events.closed(status)
    }

    override fun onOpen(webSocket: WebSocket, response: Response) = events.opened()

    override fun onMessage(webSocket: WebSocket, text: String) = events.text(text)

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      webSocket.close(NORMAL, null)
      end(code)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = end(code)

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = end(null)
  }

  companion object {
    private const val NORMAL = 1000

    /** Connects within 10 s; no read timeout, since a quiet channel between heartbeats is a healthy one. */
    fun client(): OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
  }
}
