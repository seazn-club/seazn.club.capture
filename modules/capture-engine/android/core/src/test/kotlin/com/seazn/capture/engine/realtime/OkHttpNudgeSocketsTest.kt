package com.seazn.capture.engine.realtime

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * The adapter on the OkHttp the app resolves at runtime (4.12.0), against a local WebSocket server: frames both ways,
 * and each socket's end told exactly once, a close with its code and a failure with none.
 */
class OkHttpNudgeSocketsTest {
  private val server = MockWebServer()

  @AfterTest fun stop() = server.shutdown()

  private class Heard : NudgeSocketEvents {
    val said = LinkedBlockingQueue<String>()

    override fun opened() {
      said += "opened"
    }

    override fun text(frame: String) {
      said += "text:$frame"
    }

    override fun closed(status: Int?) {
      said += "closed:$status"
    }

    fun next(): String? = said.poll(5, TimeUnit.SECONDS)
  }

  /** The server's side of one socket: what it heard, and the socket to answer on. */
  private class ServerSide : WebSocketListener() {
    val heard = LinkedBlockingQueue<String>()
    val socket = LinkedBlockingQueue<WebSocket>()

    override fun onOpen(webSocket: WebSocket, response: Response) {
      socket += webSocket
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
      heard += text
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      webSocket.close(code, null)
    }
  }

  private fun url() = server.url("/realtime/v1/websocket").toString().replaceFirst("http", "ws")

  @Test
  fun `frames go both ways, and the server's close is told once with its code`() {
    val side = ServerSide()
    server.enqueue(MockResponse().withWebSocketUpgrade(side))
    val heard = Heard()
    val socket = OkHttpNudgeSockets().open(url(), heard)
    assertEquals("opened", heard.next())
    assertTrue(socket.send("""{"event":"phx_join"}"""))
    assertEquals("""{"event":"phx_join"}""", side.heard.poll(5, TimeUnit.SECONDS))
    val remote = side.socket.poll(5, TimeUnit.SECONDS)!!
    remote.send("""{"event":"broadcast"}""")
    assertEquals("""text:{"event":"broadcast"}""", heard.next())
    remote.close(4001, null)
    assertEquals("closed:4001", heard.next())
    assertNull(heard.said.poll(500, TimeUnit.MILLISECONDS), "told once")
  }

  @Test
  fun `a refused upgrade is a failure, told once with no code`() {
    server.enqueue(MockResponse().setResponseCode(403))
    val heard = Heard()
    OkHttpNudgeSockets().open(url(), heard)
    assertEquals("closed:null", heard.next())
    assertNull(heard.said.poll(500, TimeUnit.MILLISECONDS), "told once")
  }

  @Test
  fun `our own close ends the socket, told once`() {
    server.enqueue(MockResponse().withWebSocketUpgrade(ServerSide()))
    val heard = Heard()
    val socket = OkHttpNudgeSockets().open(url(), heard)
    assertEquals("opened", heard.next())
    socket.close()
    assertEquals("closed:1000", heard.next())
    assertNull(heard.said.poll(500, TimeUnit.MILLISECONDS), "told once")
  }
}
