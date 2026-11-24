package com.seazn.capture.engine.core

/**
 * A beat answer's `realtime` (design 2026-11-24 §2.1; web spec 2026-11-24 §2.1, PROVISIONAL until vendored): where the
 * pairing's nudge channel is, and the short-lived token that joins it. [url] is the server's https origin, [apikey] its
 * public anon key, [topic] `capture:{pairing}`, [expiresAtS] epoch seconds.
 *
 * The token is a credential: it never reaches the snapshot, the record, a log, an error or a URL, and `toString` prints
 * only lengths. The topic carries the pairing's id, so it is never written either.
 */
class RealtimeGrant(val url: String, val apikey: String, val topic: String, val token: String, val expiresAtS: Long) {
  /** The same socket and channel: only the token (and its expiry) may differ. */
  fun sameChannel(other: RealtimeGrant): Boolean = url == other.url && apikey == other.apikey && topic == other.topic

  /** How long the token has left at [wallMs]; zero or less once it has expired. */
  fun leftMs(wallMs: Long): Long = expiresAtS * 1_000 - wallMs

  fun expired(wallMs: Long): Boolean = leftMs(wallMs) <= 0

  override fun equals(other: Any?): Boolean = other is RealtimeGrant && sameChannel(other) && token == other.token && expiresAtS == other.expiresAtS

  override fun hashCode(): Int = listOf(url, apikey, topic, token, expiresAtS).hashCode()

  override fun toString(): String = "RealtimeGrant(url=<${url.length} chars>, topic=<${topic.length} chars>, token=<${token.length} chars>)"

  companion object {
    /**
     * The coordinator's ruling (2026-11-24, web side confirmed): the server mints a fresh 30-minute token on every
     * beat, so the phone keeps the token it joined with and takes the beat's only when the held one has less than this
     * left, after a channel error or refusal, or for another channel.
     */
    const val SWAP_UNDER_MS = 10 * 60_000L
  }
}

/**
 * Why the server nudged (design §2.2): a word the phone records, never acts on. Every nudge is one beat at once; a
 * reason outside [WORDS], or none, is still a ping, recorded as [OTHER].
 */
object NudgeReason {
  val WORDS = setOf("go-live", "stop", "replaced", "over", "next-match")
  const val OTHER = "other"

  fun of(reason: String?): String = reason?.takeIf { it in WORDS } ?: OTHER
}

/** What the nudge socket reports of itself (design §2.4), and the core's own `expired`. */
enum class NudgeState(override val wire: String) : Wire {
  JOINED("joined"),
  CLOSED("closed"),
  REFUSED("refused"),
  EXPIRED("expired"),
}

/**
 * T46's `delivery` on a go-live or live answer (capture-beat.v1 at web edfff98171, PROVISIONAL until vendored): what
 * the relay and Cloudflare last said about the session's video. Each member null when not known: absent, an unknown
 * word, or an integer out of range. Plain values only.
 */
data class ServerDelivery(
  val relayOutput: String? = null,
  val programmePhase: String? = null,
  val fps: Long? = null,
  val kbps: Long? = null,
  val cameraIngest: String? = null,
  val programmeIngest: String? = null,
) {
  /** The four words by the record's `source` names (`server-delivery {source, state}`), unknown as null. */
  val words: Map<String, String?>
    get() = linkedMapOf("relay" to relayOutput, "programme" to programmePhase, "camera-ingest" to cameraIngest, "programme-ingest" to programmeIngest)

  val empty: Boolean
    get() = this == ServerDelivery()

  companion object {
    /** The contract's enum sets, copied from capture-beat.v1 at web edfff98171 (PROVISIONAL). */
    val RELAY_OUTPUT = setOf("connecting", "connected", "backoff")
    val PROGRAMME_PHASE = setOf("waiting", "live", "frozen", "reconnecting", "final")
    val INGEST = setOf("connected", "disconnected", "errored")
  }
}

/** The last [ServerDelivery] heard, and when (epoch ms), for the snapshot's age. Held only while a broadcast is. */
data class ServerFacts(val delivery: ServerDelivery, val heardAtEpochMs: Long)
