# Realtime nudges and delivery facts: the phone side

**Status:** draft for the owner · 2026-11-24 · branch `fix/gop-3s` (PR #68)
**Owner rulings:**

- "yes, write the design after #68", then "Wait for everything, one test", then "cant start now?" (all 2026-11-24). The phone side is built into #68, and one device test covers #68 and the web's realtime work on staging.

**Web side:** `seazn.club` spec `docs/superpowers/specs/2026-11-24-realtime-push-nudges-design.md` (branch `feat/realtime-nudges`), and T46 on `feat/seazn-hosted` `edfff98171`. The phone session reviewed the web spec with no objections; its six phone constraints are binding there.

**Contracts:**

- They are vendored only from a merged web `main` commit (AGENTS §4). Until then, tests use vectors copied from the web spec and the branch contract, marked PROVISIONAL.
- The last task vendors and pins them. A drift then fails the pin test, not the device.

---

## 1. What this adds, and what it doesn't

| Piece                                      | Owner on the phone                                                 | Replaces                                                                                           | Keeps                                                                                     |
| ------------------------------------------ | ------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| Capture nudge, `capture:{pairingId}`       | **native** (owns the pairing, AGENTS §2)                           | waiting up to 5/10/60 s for the next beat to learn Go live, Stop, replaced, over or the next match | the beat at its answered cadence: still the truth, the liveness and the fallback          |
| Match Day nudge, `match-day:{codeId}`      | **JS** (Match Day is JS's, AGENTS §1)                              | waiting up to 15 s for a score, schedule or call change                                            | the 15 s poll, unchanged                                                                  |
| T46 `delivery` on go-live and live answers | **native** reads it; the snapshot carries it; Diagnostics shows it | nothing; it adds what the relay and Cloudflare last said                                           | the phone's own playlist watch (DeliveryWatch), which stays the truth of what viewers get |

**Not in scope:**

- No status line uses the relay facts. That is a product decision, Open question 1.
- No change to any timeout because a nudge exists (web constraint 4).
- The token never enters a snapshot, the record, a log, an error or a URL query (constraint 1).

## 2. Capture nudge: native

### 2.1 Reading the beat answer (`adapter/Bodies.kt`)

- An optional `realtime: {url, apikey, topic, token, expiresAt}` is read by one parser into a `RealtimeGrant`.
- It is read only from a 2xx answer, never on replaced or taken; the web omits it there anyway.
- A missing or unreadable `realtime` is **no grant**. It never refuses the answer and never makes a waiting field unreadable. The rules:
  - `url` must be `https` (converted to `wss` for the socket). Anything else is no grant.
  - `topic` must start with `capture:`.
  - `token` is a non-empty string.
  - `expiresAt` is epoch seconds and must lie in the future.
  - `apikey` is a non-empty string.
- `toString` on the grant never prints the token (mask it, as the existing credentials do).

### 2.2 Core (pure)

- The pairing carries `realtimeGrant: RealtimeGrant?`.
- It is replaced by each answer that carries one, and dropped on unpair, forget, replaced, over or code end.
- The projection never reads it.
- Core emits two commands:
  - `Command.Nudges(grant)` when the grant's topic or token changes;
  - `Command.NudgesOff` when the grant goes.
- New input `Input.Nudge(reason)`. A `reason` outside `go-live|stop|replaced|over|next-match` is read as a ping, and its word is recorded as `other`.
- A nudge calls `Heartbeats.atOnce`, limited to **one immediate beat per 2 s** (web constraint 2). A nudge inside the 2 s is folded: the beat already due covers it.
  - A nudge while a beat is in flight or a 429 wait holds does nothing more, as `atOnce` already guarantees.
- A nudge never moves the cadence, never changes the phase and never touches the start-pending clock (N48).

### 2.3 The socket (glue, `engine/realtime/NudgeLink.kt`)

- **Transport:** OkHttp's WebSocket. OkHttp is already on the module's compile classpath through React Native (`compileOnly okhttp 4.9.2`), so it adds no new dependency. Check what React Native ships at runtime.
- **Phoenix protocol v1 JSON** (`vsn=1.0.0`, the same choice as `realtimePort.ts`, text frames only).
  - URL: `wss://{host}/realtime/v1/websocket?apikey={apikey}&vsn=1.0.0`. The **anon key** is public and is how Supabase's socket is addressed. The **token** goes only in the join payload and the `access_token` message, never in the URL.
  - Join: `phx_join` on `realtime:{topic}`, payload `{config: {broadcast: {self: false}, private: true}, access_token: token}`.
  - Heartbeat: `phoenix` `heartbeat` every 25 s.
  - A `broadcast` with `event: "nudge"` reports `Input.Nudge(payload.reason)`. Any other event is ignored.
  - A refreshed token on the same topic sends `access_token` on the joined channel. A new topic leaves the old channel and joins the new one.
- **Lifecycle:** it runs while there is a grant, so only while paired. On close or error it reconnects with backoff 1, 2, 4, 8, 16, then 30 s, and never reconnects without a grant.
  - It does not hold the wake lock or keep the socket up in Doze. A dozing phone loses the socket, and the beat covers it (N27; web constraint 4).
  - An expired token (`expiresAt` passed) closes the socket and waits for the next beat's grant.
- **Threading:** OkHttp's callbacks report into the engine as every other watch does (`core.report`), guarded so that a throw costs a line, never the process (the NetworkWatch pattern).

### 2.4 Record

- `nudges {state: joined|closed|refused|expired, code}`: the state changes only; a reconnect loop is rate-limited to one line a minute with a count.
- `nudge {reason}`: one per heard nudge; they are rare, a handful per match.
- Both kinds and their words go on `allow-list.json`, with Kotlin/TS parity. No token, URL, topic or host is ever written: the topic carries the pairing id, so it is not written either.

## 3. T46 delivery facts: native, snapshot, Diagnostics

- **Native read.** `Bodies.kt` reads the optional `delivery` on go-live and live answers: `relayOutput`, `programmePhase`, `fps`, `kbps`, `cameraIngest`, `programmeIngest`.
  - Each member is optional. An unknown word means not known, and an integer out of range means not known.
  - The enum sets are copied from the contract's prose at vendor time.
  - It never refuses the answer.
- **Core** keeps the last facts, with the time they were heard. They are cleared off air and on any answer without them.
- **Snapshot.** The snapshot carries `server: {relayOutput, programmePhase, fps, kbps, cameraIngest, programmeIngest, ageS}`, each nullable. It needs a snapshot wire field and a `snapshotWire.ts` reader (one parser each side, AGENTS §4), with shared vectors.
- **Diagnostics.** The card gains four rows, Relay, Programme, Camera ingest and Programme ingest, as words plus fps/kbps. All four languages, house voice. They are shown only while live; otherwise they are hidden.
- **Record.** A `server-delivery` line on change only (the same words), so the record can line up the phone's view with the relay's.

## 4. Match Day nudge: JS

- **Port.** `MatchDayPort.realtimeToken()` calls `GET /api/v1/match-day-code/realtime-token` with `Authorization: Bearer md_…`, never a query. One parser for its answer, `{token, topic, expiresAt, url, apikey}`, sits in `domain/matchday/`. Refusals are as for the day read (401, 410, 403 and 429 with `Retry-After`), and any refusal means poll only.
- **The client is its own `RealtimeClient` instance**, never Remote Scoring's. `setAuth` is socket-wide, so sharing would make one family's token overwrite the other's (web spec §4.2). Extend `realtimePort.ts` so each caller gets its own client, or add a second port. Keep `vsn` 1.0.0 and the topic exactly as answered.
- **When it subscribes:** only while the day list is mounted and the app is in the foreground, the same gate as the poll. A background, Exit or unmount closes it.
- **Pings:** a `nudge` gives at most one read per 4 s, plus a trailing read. An unknown reason is still a ping. The 15 s poll stays.
- **Refresh:** at 80% of the TTL, or on channel error, with backoff. A fresh grant needs a fresh door call, and that call is metered.
- **The token:** in memory only, never in the store, the record, a log or a URL.

## 5. Tests (AGENTS §10)

- **Unit, native core:**
  - nudge → beat at once;
  - two nudges inside 2 s → one beat;
  - a nudge in flight or in a 429 → nothing more;
  - a grant replaced, refreshed or dropped on each end path;
  - an unknown reason;
  - an empty or garbled grant leaves the answer accepted;
  - the token absent from every record line and from the snapshot (a sweep).
- **Unit, glue:** `NudgeLink` against a fake WebSocket:
  - join, heartbeat, refresh, the reconnect backoff, expiry;
  - the token never in the URL;
  - frames of another event ignored.
- **Unit, JS:**
  - the door parser (valid and refused);
  - coalescing (a burst gives two reads);
  - the gate (background closes it);
  - a separate client from scoring's;
  - the poll unchanged.
- **Snapshot vectors:** `server` facts, shared Kotlin/TS.
- **The four questions:** a second call (two grants, two nudges), an empty input (no grant, no realtime-js), an interruption (process death, then the grant from the next beat; Doze), another mode (Match Day and Remote Scoring at once).
- **Mutate every guard once.**

## 6. Tasks (two lanes with disjoint files, then a vendor task)

1. **Native lane:** §2 and §3, covering `adapter/Bodies.kt`, core (Pairing, SessionMachine/Heartbeats, Projection), the glue (`realtime/NudgeLink.kt`, EngineHost wiring), `SnapshotWire.kt`, `snapshotWire.ts`, the Diagnostics hook and components, i18n, and the allow-list.
2. **JS lane:** §4, covering `services/matchDayPort.ts`, `fetchMatchDayPort.ts`, `fakeMatchDayPort.ts`, `realtimePort.ts`, `domain/matchday/`, `hooks/useMatchDay.ts`, and the native ports wiring in `createNativePorts`.
3. **Vendor (after the web merges):** `pnpm contracts:vendor` at the merged commit, the pin test, the PROVISIONAL vectors swapped for the vendored ones, and a fix for any drift.

## 7. Open questions (each with a recommendation)

1. **Should the status line use the relay facts?** For example, "Viewers receiving via relay", or "Relay reconnecting" while the phone itself is fine. Recommend **not yet**: Diagnostics only until the device test shows the facts are timely (relay beats are 5 s; facts older than 15 s are dropped).
2. **Nudge socket while paired but waiting far from the start** (the 60 s cadence). Recommend **yes**: that is where it saves the most (up to 60 s to about 1 s), and the cost is one idle socket.
3. **Match Day refresh while the list is open for hours.** Recommend refreshing at 80% of the TTL as above. The door is metered at 120 a minute per code, so this is well inside it.
