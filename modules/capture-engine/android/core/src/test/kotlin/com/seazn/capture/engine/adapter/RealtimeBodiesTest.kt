package com.seazn.capture.engine.adapter

import com.seazn.capture.engine.core.Environment
import com.seazn.capture.engine.core.HeartbeatResponse
import com.seazn.capture.engine.core.RealtimeGrant
import com.seazn.capture.engine.core.ServerDelivery
import com.seazn.capture.engine.core.Shared
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Design 2026-11-24 §2.1 and §3: a beat answer's `realtime` and T46's `delivery`, read from the rows of
 * `wire/realtime-delivery-vectors.json`. Neither ever refuses the answer or makes a waiting field unreadable.
 */
class RealtimeBodiesTest {
  private val vectors = Shared.read("wire/realtime-delivery-vectors.json")

  private fun plain(value: Any?): Any? = if (value == JSONObject.NULL) null else JsonReader.parse(JSONArray(listOf(value)).toString()).let { (it as List<*>)[0] }

  @Test
  fun `realtime, every shared case`() {
    val realtime = vectors.getJSONObject("realtime")
    val cases = realtime.getJSONArray("cases")
    assertEquals(27, cases.length())
    val wrong = mutableListOf<String>()
    for (i in 0 until cases.length()) {
      val case = cases.getJSONObject(i)
      val value: Any? =
        when {
          case.has("whole") -> plain(case.get("whole"))
          else -> {
            val grant = JSONObject(realtime.getJSONObject("valid").toString())
            if (case.has("drop")) grant.remove(case.getString("drop"))
            case.optJSONObject("patch")?.let { patch -> patch.keys().forEach { grant.put(it, patch.get(it)) } }
            plain(grant)
          }
        }
      val read = Bodies.realtime(value)
      if ((read != null) != case.getBoolean("grant")) wrong += case.getString("why")
    }
    assertEquals(emptyList(), wrong)
  }

  @Test
  fun `the valid grant reads field for field, its origin without the slash`() {
    val valid = plain(vectors.getJSONObject("realtime").getJSONObject("valid"))
    val grant = assertNotNull(Bodies.realtime(valid))
    assertEquals("https://example-ref.supabase.co", grant.url)
    assertEquals("anon.example-key_0123456789", grant.apikey)
    assertEquals("capture:6f1c2b7a-3d4e-4f50-8a9b-0c1d2e3f4a5b", grant.topic)
    assertEquals("made-up.realtime-token.0001", grant.token)
    assertEquals(1_790_001_800L, grant.expiresAtS)
    val slashed = (valid as Map<*, *>).toMutableMap().apply { put("url", "https://example-ref.supabase.co/") }
    assertEquals("https://example-ref.supabase.co", Bodies.realtime(slashed)?.url)
  }

  @Test
  fun `the grant prints no token, topic or url`() {
    val grant = assertNotNull(Bodies.realtime(plain(vectors.getJSONObject("realtime").getJSONObject("valid"))))
    val printed = grant.toString()
    for (secret in listOf(grant.token, grant.topic, grant.url, grant.apikey)) assertFalse(secret in printed, printed)
  }

  @Test
  fun `delivery, every shared case`() {
    val cases = vectors.getJSONObject("delivery").getJSONArray("cases")
    assertEquals(10, cases.length())
    val wrong = mutableListOf<String>()
    for (i in 0 until cases.length()) {
      val case = cases.getJSONObject(i)
      val expected = case.optJSONObject("expected")?.let { e ->
        fun word(key: String) = if (e.isNull(key)) null else e.getString(key)
        fun number(key: String) = if (e.isNull(key)) null else e.getLong(key)
        ServerDelivery(word("relayOutput"), word("programmePhase"), number("fps"), number("kbps"), word("cameraIngest"), word("programmeIngest"))
      }
      val read = Bodies.delivery(plain(case.get("value")))
      if (read != expected) wrong += "${case.getString("why")}: $read"
    }
    assertEquals(emptyList(), wrong)
  }

  @Test
  fun `a beat answer, every shared case - the grant and the facts land only where they may, and the answer always reads`() {
    val answers = vectors.getJSONArray("answers")
    assertEquals(8, answers.length())
    val wrong = mutableListOf<String>()
    for (i in 0 until answers.length()) {
      val case = answers.getJSONObject(i)
      val body = case.getJSONObject("body")
      val read = assertIs<HeartbeatResponse.Answered>(Bodies.beat(case.getInt("status"), body.toString(), null, Environment.STAGING), case.getString("why"))
      val reply = assertNotNull(read.reply, case.getString("why"))
      val why = case.getString("why")
      if ((reply.realtime != null) != case.getBoolean("grant")) wrong += "$why: grant ${reply.realtime}"
      if ((reply.delivery != null) != case.getBoolean("delivery")) wrong += "$why: delivery ${reply.delivery}"
      // Neither member ever refuses the answer or its waiting fields.
      assertEquals(body.optString("state").ifEmpty { null }, reply.state, why)
      if (case.getInt("status") == 200 && body.has("label")) {
        assertNotNull(reply.waiting, why)
        assertNull(reply.waitingUnreadable, why)
      }
    }
    assertEquals(emptyList(), wrong)
  }

  @Test
  fun `the same answer without either member reads the same, but for them`() {
    val answers = vectors.getJSONArray("answers")
    for (i in 0 until answers.length()) {
      val case = answers.getJSONObject(i)
      val body = case.getJSONObject("body")
      val bare = JSONObject(body.toString()).apply {
        remove("realtime")
        remove("delivery")
      }
      val status = case.getInt("status")
      val withThem = assertIs<HeartbeatResponse.Answered>(Bodies.beat(status, body.toString(), null, Environment.STAGING))
      val without = assertIs<HeartbeatResponse.Answered>(Bodies.beat(status, bare.toString(), null, Environment.STAGING))
      assertEquals(without, withThem.copy(reply = withThem.reply?.copy(realtime = null, delivery = null)), case.getString("why"))
    }
  }

  @Test
  fun `a grant is a value - equal by its fields, its expiry included`() {
    val a = RealtimeGrant("https://x.example", "k", "capture:1", "t", 10)
    assertEquals(a, RealtimeGrant("https://x.example", "k", "capture:1", "t", 10))
    assertFalse(a == RealtimeGrant("https://x.example", "k", "capture:1", "t", 11))
    assertFalse(a == RealtimeGrant("https://x.example", "k", "capture:1", "u", 10))
  }
}
