# Provisional reconnect

Date: 2026-11-26. Status: built on `feat/provisional-reconnect`, core JVM tests green, not yet seen on a phone.
Finding: N52 (`_FINDINGS.device.md`).

## 1. Evidence

Record R-SAV6 (staging, 2026-11-26): airplane mode on, then off after about 5 s, on air over SRT. Times Z.

| Time        | Line                                                                   |
| ----------- | ---------------------------------------------------------------------- |
| 17:55:18.97 | `network validated:false`                                              |
| 17:55:23.56 | SRT dropped, `endpoint-closed`                                         |
| 17:55:25.9  | connect failed: the host did not resolve                               |
| 17:55:28.8  | SRT connected, attempt 9; `resumed` at 29.78, outage 6.2 s             |
| 17:55:34.46 | SRT broke again after 5.7 s published; the network still not validated |
| 17:55:37.3  | connected, attempt 10; `resumed` at 38.3, outage 3.8 s                 |
| 17:55:40.2  | `network validated:true`                                               |

The reconnect over a network Android had not validated yet broke, and cost about 10 s more before viewers had the
picture back.

## 2. Ruling

The owner, 2026-11-26, chose **option B**: reconnect at once, as today, but a connection made while the default
network is not validated is **provisional**.

1. A provisional connection that breaks is retried at once: no backoff grows, and nothing counts toward a give-up or
   fallback budget.
2. When the network validates while the connection is provisional, it is re-established on the validated network if
   its socket is on another network, or on none known; it is left alone if it is already on the validated network.
3. Once validated, behaviour is as before.
4. A validated ↔ not-validated flap never causes a reconnect storm.

Rejected:

- **A: hold the reconnect until the network validates.** Slower whenever validation lags (21 s in R-SAV6, from the
  loss of validation to its return), and a network that never validates (a captive portal that still passes the
  ingest) would never be tried.
- **C: leave it as it is.** R-SAV6's extra break waited today's 2 s, a break before the first frame stepped the
  never-live ladder toward its 30 s waits, and a connection made with no network known was adopted by whichever
  network validated next, unchecked.

## 3. What the phone does

The rule is the core's (`SessionMachine`, `ProvisionalRetries`), on the JVM with a fake clock. The Android glue already
existed: `NetworkWatch` reports the default network's `NET_CAPABILITY_VALIDATED` and its `Network.getNetworkHandle()`
on every change (N49), and `SrtBind` binds each SRT socket to the handle its attempt was asked on. Nothing in the
adapter changed.

1. **Provisional.** An attempt that connects while the machine holds the network as not validated is provisional
   (`Phase.OnAir.provisional`). It is decided at the connect, not at the ask: an attempt asked before validation that
   connects after it is not provisional. A connection made on a validated network that later loses validation is not
   provisional either; its drop is today's.
2. **Its break.** Retried in the same step (`Command.Connect` with the drop's own commands), or after the flap bound's
   spacing. What it skips, exactly:
   - the retry wait: 2 s on air (`SessionMachine.RETRY_MS`), and the never-live ladder before the first frame
     (`NeverLiveRetries.dropped`: neither its first-failure time nor its step moves);
   - the fallback count (`FallbackPolicy`, C1's three failures): already skipped, since the policy counts nothing on a
     network that is not validated (F-P5-2), and a provisional connection is on one by definition. No second guard was
     added: two guards that cover each other are each untested.

   What it does not skip, and why:
   - **the hold** (C2's outage countdown, "Uplink lost — holding"): it is the platform's clock, how long Cloudflare
     keeps the input, counted from the phone's drop (H-P5-1). It is not a budget of the phone's, and restarting it would
     overstate the time left. An outage opened by an earlier drop and not yet resumed keeps its hold, as every drop does.
   - the regulator's reaction to a drop (`BitrateRegulator.afterDrop`): a rate, not a budget.
   - the descriptor ask on a drop: bounded by the flap bound.

3. **Validation.** Decided from the network handle, the one the attempt was asked on (`Session.attemptNetwork`), which
   is the one its SRT socket is bound to:
   - the same handle: confirmed. No reconnect, `provisional` is cleared, and its breaks are today's from then on;
   - another handle: replaced at once on the validated network, as an N49 switch is (`Command.StartNewSession`, the
     outage kept or opened, the switch grace);
   - no handle (asked while no network was known): replaced. Its route is unknown, so it cannot be shown to be on the
     validated network. N49's adopt for such an attempt stays for a connection made on a validated network.

   A second validated report on the same network changes nothing. A new default network that validates is N49's switch,
   as before.

4. **RTMPS.** The fallback link shares the loop, so it is treated the same. The fallback stays final for the broadcast.

## 4. The flap bound

At most **3** immediate retries in **any 30 s**, each asked at least **1 s** after the one before (`ProvisionalRetries`:
`MAX_IMMEDIATE`, `WINDOW_MS`, `MIN_SPACING_MS`). The window slides: the bound keeps the last three retries' times, and a
slot frees only when the oldest is 30 s old. A fixed window would let six fall across its edge (review of #79): retries
at 0, 28, 29 and 30 s go, and 31 and 32 s do not. Past the cap, a provisional break is today's: the 2 s wait on air, the
never-live ladder before.

The hold is never starved by it: connects that publish no frame keep the first drop's outage and its hold, which ends
the broadcast on time however many quick retries come between.

Why: R-SAV6 broke twice in 6 s on a network that took about 21 s to validate, so three in 30 s covers that outage with
one to spare. A second's spacing is more than SRT's connect took there (about 0.9 s), so two retries never overlap.
The worst case on a network that never validates and breaks every socket at once is 3 quick retries in any 30 s, the
rest at today's pace. Kept per session and never stored: a new process starts a new session with an empty bound.

Validation that flaps on one network causes no reconnect at all: a not-validated report never touches a connection,
and a validated one on the same handle only confirms it.

## 5. The record

Only keys `capture-record.v1` already lists, and existing line kinds:

- `connected {transport, attempt, validated}`: `validated: false` marks the connection provisional.
- `dropped {…, retryMs}`: a provisional break inside the bound carries its wait, 0 for at once. Past the cap it has no
  `retryMs`, as before.
- `network-switched {result: replaced, attempt, phase, reason: provisional}`: the rebind on validation.
- A confirmation has no line of its own: `network validated:true` after `connected validated:false`, with no
  `network-switched`, says it.

`validated` and `retryMs` are scalars, `reason` a plain word; their reasons in
`modules/capture-engine/src/scrub/allow-list.json` say the new uses.

## 6. What R-SAV6 would have looked like

Attempt 9's break at 34.46 would have asked attempt 10 at once instead of at 36.5, about 2 s sooner. Validation at
40.2 would then have confirmed it if it was on the same network, or replaced it at once if not. The socket breaking a
second time is the network's, and no rule on the phone short of option A avoids it.

## 7. Device check (not yet done)

The owner's hands: airplane mode on and off for about 5 s while live, as in R-SAV6, then Wi-Fi off with 4G on.
Read the record for `connected validated:false`, `dropped … retryMs:0` and a `connecting` in the same second, and
either no `network-switched` at `network validated:true` or one with `reason: provisional`.
