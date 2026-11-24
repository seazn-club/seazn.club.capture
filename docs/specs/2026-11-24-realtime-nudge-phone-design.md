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

The web's wire, confirmed by the web side on 2026-11-24, is what this section and §2.3 follow.

- The pairing carries `realtime: RealtimeGrant?`. The projection never reads it.
- **Token churn.** The server mints a fresh 30-minute token on **every** beat. The core keeps the grant it holds, so a beat's fresh token alone changes nothing. It takes the beat's grant only when:
  - it holds none;
  - the answer names another channel (another `topic`, `url` or `apikey`): leave and rejoin;
  - the held token has **under 10 minutes** left;
  - the socket reported an error or a refusal since the last swap (`realtimeSwapDue`).
    An expired grant heard is no grant. Ten beats with fresh tokens and 25 minutes left give no command (`NudgesTest`).
- It drops the grant when the pairing ends (unpair, Forget, replaced, taken, the code's end, a 401's `codeEndedPending`, Ended) or when the held token expires (judged on every input, a tick included). A broadcast's end keeps it: the court and the stream code still beat.
- Core emits two commands:
  - `Command.Nudges(grant)` when the grant the socket should hold changes;
  - `Command.NudgesOff` when it goes.
- New input `Input.Nudge(reason)`. A `reason` outside `go-live|stop|replaced|over|next-match` is read as a ping, and its word is recorded as `other`.
- A nudge calls `Heartbeats.atOnce`, limited to **one immediate beat per 2 s** (web constraint 2). A nudge inside the 2 s is folded: the beat already due covers it.
  - A nudge while a beat is in flight or a 429 wait holds does nothing more, as `atOnce` already guarantees.
- A nudge never moves the cadence, never changes the phase and never touches the start-pending clock (N48).
- New input `Input.NudgeLink(state, status)`: the socket's report, for the record (§2.4) and the swap rule above.

### 2.3 The socket (glue, `engine/realtime/NudgeLink.kt`)

- **Transport:** OkHttp's WebSocket (`OkHttpNudgeSockets`). The module compiles against `okhttp 4.9.2` (`compileOnly`); the app's release runtime classpath resolves `4.12.0` (`:app:dependencies`), so it adds no dependency.
- **Phoenix protocol v1 JSON** (`vsn=1.0.0`, text frames only).
  - URL: `wss://{host}/realtime/v1/websocket?apikey={apikey}&vsn=1.0.0`, the host taken from the grant's `https` `url`. The **anon key** is public and is how Supabase's socket is addressed. The **token** goes only in the join payload and the `access_token` message, never in the URL.
  - Join: `phx_join` on `realtime:{topic}` (so `realtime:capture:{id}`), payload `{config: {broadcast: {self: false}, private: true}, access_token: token}`.
  - Heartbeat: `phoenix` `heartbeat` every 25 s. One still unanswered when the next is due ends the socket.
  - A nudge arrives as event `broadcast` with payload `{type: "broadcast", event: "nudge", payload: {v, reason, at}}`: `v` is epoch ms (a number), `at` an ISO string, and **the reason is at `payload.payload.reason`**. It reports `Input.Nudge(reason)`. Any other event is ignored.
  - A swapped token on the same topic goes as `access_token` on the joined channel (after the join's ok, if the join is still unanswered). A new topic leaves the old channel and joins the new one on the same socket. A new host or key is a new socket.
- **Lifecycle:** it runs while there is a grant, so only while paired. The join's ok reports `joined`; its error `refused`; the channel's `phx_error` or `phx_close`, a join unanswered after 10 s, a missed heartbeat or the socket's end `closed` (with the close code when there is one). Each but `joined` closes the socket and reconnects with backoff 1, 2, 4, 8, 16, then 30 s, reset by a join, and never without a grant or with an expired one. A new grant while no socket is open (a reconnect waiting included) connects at once: grants come only with beat answers, so those tries are bounded by the beat cadence.
  - It does not hold the wake lock or keep the socket up in Doze. A dozing phone loses the socket, and the beat covers it (N27; web constraint 4).
  - An expired token closes the socket (the core's `NudgesOff`) and waits for the next beat's grant.
- **Threading:** every socket event hops onto the engine's thread and is dropped when it belongs to a socket the link has let go, so a late close is never reported. Reports go to `core.report`; a throw costs a `watch-failed {action: nudge-link}` line, never the process (the NetworkWatch pattern).

### 2.4 Record

- `nudges {state: joined|closed|refused|expired, status, suppressed}`: the state changes only. `status` is the socket's close code (the record's `code` key is on the never list, and new keys would break the vendored record contract). A reconnect loop is held to one line a minute: a change within the minute waits and goes when the minute is up, `suppressed` counting what was held back.
- `nudge {reason, suppressed}`: written only when a nudge makes a beat go (at most one per 2 s), `suppressed` counting the nudges folded since the last line (inside the 2 s, or while a beat was in flight or a 429 held it). A looping server so writes one line per beat, never one per nudge (the controller's ruling, 2026-11-24, from review).
- All three kinds (with `server-delivery`, §3) and their words go on `allow-list.json`, with Kotlin/TS parity (102 kinds). No token, URL, topic or host is ever written: the topic carries the pairing id, so it is not written either. `RealtimeSecretsTest` sweeps a whole pairing's snapshots, record lines, commands and inputs for them.

## 3. T46 delivery facts: native, snapshot, Diagnostics

- **Native read.** `Bodies.kt` reads the optional `delivery` on go-live and live answers: `relayOutput`, `programmePhase`, `fps`, `kbps`, `cameraIngest`, `programmeIngest`.
  - Each member is optional. An unknown word means not known, and an integer out of range means not known.
  - The enum sets are copied from the contract's prose at vendor time.
  - It never refuses the answer.
- **Core** keeps the last facts, with the time they were heard. T46 sends `delivery` on **go-live and live answers only**, and an absent member is not known. They are held only while a broadcast is, cleared off air (no line) and by a go-live or live answer without them.
- **Snapshot.** The snapshot carries `telemetry.server: {relayOutput, programmePhase, fps, kbps, cameraIngest, programmeIngest, ageS}` or null, each member nullable, `ageS` whole seconds to the snapshot's own clock. It sits in telemetry, so it is cleared with it and a build before T46 (no key) still reads. A `snapshotWire.ts` reader reads it member by member (a garbled member is null, a garbled whole is null, never a refused snapshot), with shared vectors.
- **Diagnostics.** The card gains four rows under Server, after Delivery: Relay, Programme, Camera feed and Programme feed. All four languages, house voice. They show only on air (publishing, degraded, reconnecting). A live programme reads as its rate ("30 fps 2800k"); any other phase is its word, since the word and the rate together do not fit the row at 640 in every language (`settingsFit.test.ts`). On air the card is 13 rows, 332 dp, and its half scrolls (owner, 2026-11-24: allow scroll); off air it is Q1's nine.
- **Record.** A `server-delivery {source, state}` line per word that changed (`source`: relay, programme, camera-ingest, programme-ingest; `state` the word, or `unknown`), on go-live and live answers only, so the record can line up the phone's view with the relay's. Existing keys only.

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
