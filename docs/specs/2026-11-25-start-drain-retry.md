# Start drain retry

Date: 2026-11-25. Status: built on `feat/capture-tuning` (PR #70), core and JS tests green, not yet seen on a phone.

## 1. Ruling

The web (#1301, vendored at `55d5bcc87d`, live on staging) answers a `POST start` that meets the phone's **own**
session still ending with `503` and `code: "unavailable"`, plus a `Retry-After` header: the whole seconds of drain
left, at least 1 and at most 20 (`capture-start.v1`, `$defs.refusal`). Until now the phone read any 5xx as the start
failure, "Couldn't start the stream — try again", so an operator who stopped and held Go live straight away was told
to try again for a reason that clears by itself within 20 s.

The owner, 2026-11-25, asked how the phone should answer it: **"Retry once by itself"**.

## 2. What the phone does

1. **Reading** (`Bodies.start`, Kotlin, the one reader of the start's answer). A `503` whose body's `code` is
   `unavailable` and whose `Retry-After` reads as a whole number of seconds in 1..20 is `StartAnswer.Draining`. Any
   other answer is what it was: no header, `0`, more than 20, a date, a garbled value, another 5xx, another code or no
   JSON body all stay `Failed("503")` (or their own status). The header is read by `Bodies.retryAfter`, the beat's and
   the code's reader.
2. **The wait.** On the first `Draining` for the operator's hold, the phone stays Paired and its status line says
   **"Getting ready — going live shortly"** (`Refusal.DRAINING`, wire word `start-draining`, the status key
   `stream.status.startDraining`). It schedules one more `POST start` at `Retry-After + 500 ms`
   (`StartAsks.RETRY_MARGIN_MS`, so the server's drain has surely ended), on the machine's own 500 ms tick.
3. **The retry.** When it falls due, the phone checks readiness again (A11), exactly as a hold does: the camera, the
   sound, the pose and a validated network.
   - Ready: one `POST start`, a new request id, the hold's url, token and body. While it is in flight the line is the
     hold's, "Opening the link.". Its answer is read as any start's: Started arms, a refusal is said, no answer is
     pending a beat (N48, unchanged).
   - Not ready: no start. The line is the start failure, "Couldn't start the stream — try again", and the beat's
     `startFailed` is `start-error`; the chips and the Go live blocker say what is missing. That is the honest line:
     the one before it promised a go-live that is no longer coming, and falling back to "Waiting for Go live" would
     hide that. Readiness lost and back again before the retry falls due does not stop it. Leaving Live Stream during
     the wait closes the capture, so the retry fails readiness and the line is that start failure: intended.
4. **Once, never a loop.** A second `Draining` on the retry, or any failure, is today's start failure. Nothing asks a
   third time by itself; the operator's next hold is a fresh start with its own one retry.
5. **What ends the wait.** The wait is not a start in flight (`StartAsks.awaited` stays false), so:
   - a **second hold** during the wait is honoured: it posts at once and drops the scheduled retry, so there is never
     a double ask. It spends the drain's one retry (review of d9d1e2d1, the controller's ruling, 2026-11-25): a
     Draining answer to it is the start failure, so a drain earns at most one retry by itself;
   - **Home's Forget, a court's Leave and the notification's Forget** are accepted, not ignored as they are while a
     start is in flight (M1): nothing has been started on the server;
   - a **beat that arms** (the organiser's or the automatic Go live) clears the wait in `Starts.arm`, so it can never
     fire after that broadcast ends;
   - **replaced, code ended** and anything else that leaves the pairing drop it with the pairing.
   - **Stop** is not one of them: in Paired there is no held broadcast, the control on screen is Go live, and native
     ignores a Stop there as it always has. Forget is the way out of the wait.
6. **Process death.** Native keeps no start state across a process death. JS pairs the saved code again, which builds
   a new pairing with no retry, and no start is ever sent without a hold or a beat. A cold start never starts by
   itself (`StartDrainTest`, "a process death mid-wait").

## 3. Status line tier

`stream.status.startDraining` is **routine** in `LINE_TIER`. It is plain progress, like "Opening the link." and the
court's "Can't start yet — retrying" (E2, also routine, also a server retrying by itself): nobody needs to act, and the
wait is at most 21 s. An advisory caption (Not charging, Keep the app open, heat) may take the strip's line for that
time, which is the routine tier's rule. If the retry fails, the urgent start-failure line takes the line back.

The copy fits the strip's 298 dp at 640 × 360 in all four languages (`stripTextFit.test.ts`; not on the cut list), so
it fits 386 dp at 728 × 328 too.

| Language | Copy                                    |
| -------- | --------------------------------------- |
| en       | Getting ready — going live shortly      |
| es       | Preparando: en directo en breve         |
| fr       | Préparation : en direct dans un instant |
| nl       | Even voorbereiden: zo meteen live       |

## 4. Record

- `start-answered {answer: Draining}` as for every answer.
- `start-draining {retryAfterS}`: the wait was kept, with the server's seconds.
- `start-retry`: the retry fell due and posted; `start-retry {notReady}` when it was dropped as not ready.

Both kinds are in the allow-list (`kinds`, now 112, pinned in `ScrubParityTest.kt` and `scrubParity.test.ts`). Their
keys, `retryAfterS` and `notReady`, were already allowed and are in the web's record contract, so the web needs no
change: kinds are pattern-checked there.

## 5. Rejected options

- **No retry** (the phone says "try again", as today). Rejected by the ruling: the operator would be told to act on a
  condition that clears by itself in seconds, and a second hold inside the drain meets the same 503.
- **A loop** (ask again after every `Draining` until it starts). Rejected: the server bounds its drain at 20 s, so a
  second `Draining` means something else is wrong, and a phone that keeps starting by itself is a broadcast nobody held
  for. One retry covers the drain the server describes.
- **A pending state the beats confirm** (as N48's no-answer). Rejected: the server answered, and it did not start;
  there is nothing for a beat to confirm, and holding the start "in flight" would block Forget for up to 21 s.
- **A new snapshot field.** Not needed: the wait is the snapshot's existing `refusal`, with one more word, so the line
  goes through the same `REFUSED` table and no wire shape changes.

## 6. Device-only

The tests run the core on the JVM and the line on react-native-web. What the operator sees on a phone (the line during
the wait, then Opening the link., then live, after a Stop and an immediate hold on staging) is a device check, not yet
done.
