# Open in app: a code tapped in the browser opens on the same phone

Status: design, approved approach 1 (owner, 2026-11-25). Not scheduled: "for later".

## 1. Intent

A person signed in to seazn.club on the phone they will film or score with has no second screen to show the QR
to. Today they cannot use that phone for the code at all, short of printing it. They want to tap **Open in
app** beside the code in the browser. The app opens on that code exactly as if they had scanned it. If the app is
not installed, they go to the Play Store, install it, and tap **Open in app** again (owner, 2026-11-25:
option A, "A, is ok").

All three code types take part: a stream code (Live Stream), a scoring link (Remote Scoring) and a Match Day
code.

## 2. Goals, non-goals, anti-goals

Goals:

- One tap from the browser to the app on that code, for all three code types, on Android.
- The same checks a scan gets: recognition, the code check, the saved-code rules and the mode's own gates. An
  opened link is a scan by another route, never a shortcut past one.
- No change for anyone who does not tap the new button: `/score/{token}` and `/day/md_…` keep opening the web
  exactly as today, the web scoring pad included.

Non-goals:

- Opening the app on its own after a fresh install (owner chose A over a Play Install Referrer handoff, B).
- iOS (Universal Links). The paths below are chosen so iOS can add an `apple-app-site-association` later with no
  change to them.
- Login in the app. The browser's session decides only who sees the button; the app still trusts the code alone
  (decision record: no login anywhere).
- New QR formats. Printed QRs stay as they are. The new links are for the button only.

Anti-goals (a failure even if it works):

- A code's credential reaching a server log, an analytics event, a referrer header or a third party. The tokens
  are credentials (AGENTS §1: never in a record line, a log line, an error or a URL query).
- A link that opens the app and acts without the operator: an opened link never pairs, goes live or scores by
  itself. It lands where a scan lands and the operator confirms there.
- A link that swaps out a session the operator is in the middle of (a live broadcast, a scoring pad with unsent
  taps) without asking.

## 3. Approaches considered

1. **Separate "open in app" links (chosen, owner 2026-11-25).** New paths used only by the button:
   `https://seazn.club/app/stream`, `/app/score`, `/app/day`. Android App Links open only these in the app.
   Existing links behave as today. The cost is three small fallback pages and the domain verification file on
   the web, plus a link reader in the app.
2. **Existing links open the app.** `/score/` and `/day/` become App Links, and a new `/stream/` is added. Fewer
   paths, but on any phone with the app installed a scoring link would always open the app, never the web pad.
   That takes the web pad away from people who want it. Rejected.
3. **Custom scheme (`seazn-capture://…`).** No domain verification, but Chrome asks "Open with…?" and offers no
   fallback when the app is missing without Chrome-only `intent:` URLs. Rejected.

## 4. The links

The credential travels in the URL **fragment**, never the path or query. A browser never sends a fragment to the
server, so it never reaches seazn.club's access logs, a CDN or a referrer header. Android hands the whole URL,
fragment included, to the app that opens it.

| Mode           | Button link                                          | What the fragment carries                                                              |
| -------------- | ---------------------------------------------------- | -------------------------------------------------------------------------------------- |
| Live Stream    | `https://<host>/app/stream#<capture-qr v2, encoded>` | The stream code exactly as its QR holds it (`{v:2, code, slot, tok, exp?}`), base64url |
| Remote Scoring | `https://<host>/app/score#<dl_ token>`               | The scoring link's token, as `/score/{token}` carries it                               |
| Match Day      | `https://<host>/app/day#<md_ token>`                 | The day code's token                                                                   |

`<host>` is the build's Seazn host (production `seazn.club`, staging `stg.seazn.club`), the same host list the
recogniser already uses (`seaznHosts`). The app turns each link back into the text a scan would have read. That
is the stream code's JSON, `https://<host>/score/{token}` or `https://<host>/day/{token}`, and it goes through
`recognise()` unchanged. One parser per wire shape (AGENTS §4): the link reader only unwraps; recognition stays
where it is.

## 5. What the operator sees

**On the web page (signed in, on a phone):** beside each code, a button.

- Stream code (the session page): **Open in app**.
- Scoring link: **Open in app** and the existing **Score in browser** (the web pad, unchanged).
- Match Day code: **Open in app**.

The button shows only on a phone-sized screen. On a laptop the QR is the way. Whether it also checks for Android
is the builder's call (§9).

**App installed:** Android opens the app directly, with no chooser, because the App Link is verified. The app
lands where a scan of that code lands: the Live Stream pairing panel, the scoring pad or the Match Day list.

```
Browser: [ QR ]  Open in app ─────► Seazn Club opens on the code (same screen a scan gives)
```

**App not installed:** the browser loads the `/app/…` page itself. It reads no fragment and shows:

```
  Seazn Club isn't on this phone yet
  [ Get it on Google Play ]
  Then come back and tap Open in app again.
  (scoring only)  [ Score in browser instead ]
```

**Score in browser** on that page goes back to the page the person came from (`history.back()`). The page never
reads the fragment and never builds a `/score/` URL from it. Stream and Match Day have no browser path: a stream
needs this phone's camera, and Match Day's list is an app screen.

## 6. Arriving in the app

An opened link is treated as a scan that arrives on its own:

- **Cold start or warm**, the link is read once and then forgotten, never stored as a link. Expo Router's
  incoming URL, or `Linking`, behind a new port so screens stay testable (AGENTS §3).
- It goes through `recognise()` and then the same handling the scanner result gets for that mode (the saved-code
  rules, the code check, the mode's own screen). A foreign, garbled or expired link gives the same outcome a bad
  scan gives.
- **Busy:** if a broadcast is held (Arming or live), the link is refused with the line a scan would get in that
  state. Other modes follow the existing rules for scanning a second code: a Match Day code with matches still
  sending, or a scoring pad with unsent taps, asks before switching, as a scan does.
- The record gets one line, `link-opened {mode}` or the scan's own outcome lines. It never contains the URL, the
  fragment or the token.

## 7. The web's part

- `/.well-known/assetlinks.json` on each host, listing the app's package and signing certificate fingerprints:
  `com.seazn.capture` (Play signing key) on `seazn.club`, and the dev twin `com.seazn.capture.dev` (its debug
  key) on `stg.seazn.club`.
- The three `/app/…` fallback pages from §5. They are static, read no fragment and send no analytics with the
  URL.
- The **Open in app** buttons from §5, which build the fragment from the code the page already has.
- A response header on the `/app/…` pages, `Referrer-Policy: no-referrer`, as a second guard.

## 8. The app's part

- `app.json`: an Android intent filter with `autoVerify` for `https://<host>/app/*`, per variant host, through a
  config plugin. This needs a fresh prebuild (AGENTS §13).
- A link port (incoming URL, one read per arrival) and a pure `domain/` unwrapper: link → the text a scan reads,
  or foreign. It carries the four questions' tests: a second link while the first settles, an empty or garbled
  fragment, a link arriving during a cold start or mid-broadcast, and each mode.
- No change to `recognise()` or to any mode's own handling.

## 9. Settled after the builder check (controller's calls, 2026-11-25)

- **Scoring token.** The web's scoring link is `https://<host>/score/dl_<43 base64url>` (capture-scoring-link.v1,
  the shape native already checks). The button sits wherever the web already shows that link or its QR, so the page
  holds the token. No new server call.
- **Stream code freshness.** Stream codes are stable per match (A1), and `exp` is optional. The button reuses the
  code the page shows, byte for byte. An expired one lands as `expired` in the app, as a scan would. No minting at
  tap time and no extra fallback state.
- **Scope and order.** This document covers both sides. The web part is its own plan in the web repo, built with
  the web session. Order: `assetlinks.json` and the fallback pages go to staging first, then a dev build verifies
  against `stg.seazn.club`, then production `assetlinks.json` ships before the release that declares the filter.
- **Fingerprints.** The Play app-signing SHA-256 comes from Play Console (App integrity), supplied by the owner. The
  upload key is not it. The dev twin's comes from the local debug keystore (`keytool`). `assetlinks.json` is a
  static file per host, built from those values.
- **Where a link goes.** The link handler calls the same code path Home's scanner result uses after `recognise()`
  (today's scan-outcome handling). The builder lifts that path out of the scanner UI if needed, as a verbatim move.
  On a cold start the link waits for the reopen gate to settle, then is handled once. The "ask before switching"
  prompts are the ones Home already shows for a second scan, so the link lands on Home first when a prompt is due.
- **Hosts per variant.** Each variant's intent filter declares the hosts its `seaznHosts` lists, and no others.
- **Record.** `link-opened {mode}` is a new kind, and kinds are open on the wire. `mode` is a listed key, so the
  contract needs no change. Then the scan's own lines follow.

## 10. Left to the builder

- The exact base64url form of the stream code in the fragment, provided it round-trips the QR's JSON exactly.
- Whether the button checks for Android or only for screen size.
- Copy for the fallback page and the button, in en/es/fr/nl on the phone; the web's own languages on the web.
- How the dev twin's debug-key fingerprint is supplied to the staging `assetlinks.json`.
- Whether the `link-opened` line is a new kind or the existing scan lines alone are enough. If it is new, it uses
  only keys the record contract lists (capture-record.v1 is `additionalProperties: false`).
