# Open in App (phone side) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An `https://<host>/app/{stream|score|day}#…` link tapped in the browser opens the dev or store app on that code, handled exactly as a scan of it.

**Architecture:** Android App Links (`autoVerify`) for `/app/` on the build's own Seazn host. A pure `domain/` unwrapper turns a link into the text a scan would have read. An incoming-link port delivers each URL once. A root hook either records and ignores the URL while a broadcast is held, or moves to Home and hands the text to Home's existing scan handling. `recognise()`, `scanOutcome()` and every mode's own handling stay unchanged. Expo Router's `redirectSystemPath` stays as it is (R22), so no link ever picks a screen.

**Tech Stack:** Expo SDK 58 / Expo Router, `expo-linking`, TypeScript strict, vitest (domain), react-native-web + jsdom (hooks/UI).

**Spec:** `docs/specs/2026-11-25-open-in-app-design.md` (approved by the owner, 2026-11-25). The web side is planned in the web repo by the openapp session; its doc path is still to come.

**Status:** parked ("for later"). Do not start until the owner schedules it **and** the web's staging `assetlinks.json` and `/app/*` pages are live (spec §9 order).

## Global Constraints

- Link paths: `/app/stream`, `/app/score`, `/app/day`, on the build's own host only: `seaznHosts(process.env.EXPO_PUBLIC_SEAZN_ENV)`, which is `seazn.club` in production and `stg.seazn.club` otherwise.
- The credential is only in the fragment. The token, the fragment and the URL never go in a record line, a log line, an error or a store. The link is read once and forgotten.
- The stream fragment is base64url (RFC 4648 §5, no padding) of the QR's exact capture-qr v2 JSON text. Scoring: `dl_…`. Match Day: `md_…`.
- Unwrapped text: the stream JSON as-is; `https://<host>/score/<fragment>`; `https://<host>/day/<fragment>`. It goes to `recognise()` unchanged (AGENTS §4: one parser per wire shape).
- An opened link never pairs, goes live or scores by itself. It lands where a scan lands.
- While a broadcast is held (Arming or live), a link is ignored and recorded; it never navigates.
- Record: `link-opened {mode, state}` with `state` ∈ `handled | held | foreign`. Only keys capture-record.v1 lists, proved by an ajv test against `contracts/`.
- The `redirectSystemPath` behaviour (cold → `/`, warm → `null`) is unchanged. `test/nativeIntent.test.ts` gains the `/app/` cases.
- Owner rules: scoped tests only locally (touched files and their dependents; CI runs everything); local builds are arm64 only; the dev twin `com.seazn.capture.dev` only; prebuild rewrites `package.json`, so revert it.

## Review Focus

1. **A link to `/app/stream` must not reach Expo Router as a route**, or the app shows +not-found or navigates off a live broadcast. Pinned in Task 1 (`nativeIntent.test.ts` `/app/` cases).
2. **A link with a percent-encoded or padded fragment** (Chrome may hand `%3D`, or a web encoder may pad with `=`) should still open. Pinned in Task 2 (`unwrapLink` cases).
3. **The same link delivered twice** (initial URL plus the `url` event on some cold starts) should be handled once. Pinned in Task 3 (`incomingLink` dedupe test).
4. **A link arriving while the scanner sheet is up or a scan is saving** should wait for that flight, not race it. Pinned in Task 4 (`useHome` flight test).
5. **A link for another host** (a staging link on a store build) should be `foreign` and never call the network. Pinned in Task 2.

---

### Task 1: App Link declaration per variant

**Files:**

- Modify: `app.config.ts` (the default export: add `android.intentFilters`)
- Modify: `test/nativeIntent.test.ts`
- Create: `test/appLinks.config.test.ts`

**Interfaces:**

- Produces: `appLinkFilters(env: string | undefined): IntentFilter[]`, exported from `app.config.ts`. `IntentFilter` is the type Expo's config uses for `android.intentFilters`.

- [ ] **Step 1: Write the failing tests.** In `test/appLinks.config.test.ts`:
  - `appLinkFilters('production')` deep-equals `[{ action: 'VIEW', autoVerify: true, category: ['BROWSABLE', 'DEFAULT'], data: [{ scheme: 'https', host: 'seazn.club', pathPrefix: '/app/' }] }]`.
  - `appLinkFilters(undefined)` and `appLinkFilters('staging')` have the single host `stg.seazn.club`.
  - The hosts equal `seaznHosts(env)` for each env.
  - The resolved config, under `APP_VARIANT=production` with `EXPO_PUBLIC_SEAZN_ENV=production`, carries the production filter, and with neither it carries the staging one.

  In `test/nativeIntent.test.ts`, add `https://seazn.club/app/stream#e30` and `https://stg.seazn.club/app/score#dl_x` to the cold `it.each` (expect `/`), and a warm `/app/day#md_x` case (expect `null`).

- [ ] **Step 2: Run** `pnpm vitest run test/appLinks.config.test.ts test/nativeIntent.test.ts`. Expected: `appLinks` FAILs on the missing export, and `nativeIntent` PASSes, which proves R22 already covers the new links.
- [ ] **Step 3: Implement `appLinkFilters`** in `app.config.ts` and set `android.intentFilters` from `process.env.EXPO_PUBLIC_SEAZN_ENV` in the default export. Import `seaznHosts` from `src/services/seaznHosts.ts` (relative path). It is pure, so it is safe in config.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Prebuild check.** Run `pnpm expo prebuild -p android --no-install`, then `grep -A6 'autoVerify' android/app/src/main/AndroidManifest.xml`. Expected: one filter for `https`, `stg.seazn.club`, `/app/`. Then revert `package.json` (`git checkout package.json`) and do not commit `android/`.
- [ ] **Step 6: Commit** `feat(config): App Links for /app/ on the build's own host`.

### Task 2: The link unwrapper (domain)

**Files:**

- Create: `src/domain/mode/openLink.ts`
- Test: `src/domain/mode/openLink.test.ts`

**Interfaces:**

- Produces: `type OpenLink = { readonly mode: Mode; readonly raw: string } | { readonly mode: null }` (`mode: null` means foreign), and `unwrapLink(url: string, hosts: readonly string[]): OpenLink`.

- [ ] **Step 1: Write the failing tests.** Test codes are built from the fixtures in `test/fixtures/` (dayCode, the scoring link, the stream QR); no expected value is computed by `unwrapLink`.
  - **Stream:** base64url of the fixture QR JSON (no padding) at `https://stg.seazn.club/app/stream#<b64>` gives `{mode:'stream', raw:<the exact JSON text>}`. The same string with `=` padding gives the same result.
  - **Scoring:** `…/app/score#dl_<fixture>` gives `{mode:'scoring', raw:'https://stg.seazn.club/score/dl_<fixture>'}`.
  - **Match Day:** `…/app/day#md_<fixture>` gives `{mode:'matchday', raw:'https://stg.seazn.club/day/md_<fixture>'}`.
  - **Percent-encoded fragment:** `%5F` in place of `_` decodes once and gives the same result.
  - **Foreign:** each gives `{mode:null}`: no fragment; an empty fragment; an unknown path (`/app/x#…`); `/app/stream/extra#…`; a query string; host `seazn.club` when `hosts=['stg.seazn.club']`; `http:`; userinfo (`https://a@stg.seazn.club/…`); a port; base64 that is not JSON; a fragment over 4096 chars; `/score/` (not `/app/`).
  - **No token in errors:** `unwrapLink` never throws (it is fuzzed with 200 random strings).
- [ ] **Step 2: Run** `pnpm vitest run src/domain/mode/openLink.test.ts`. Expected: FAIL (module missing).
- [ ] **Step 3: Implement `unwrapLink`.** Use no `URL` global (Hermes). Use one anchored regex in the style of `HTTPS_LINK` and a hand-rolled base64url decode to UTF-8. Do not validate code shapes here; `recognise()` does that.
- [ ] **Step 4: Run** it again. Expected: PASS. Mutate each foreign guard once and watch a test fail.
- [ ] **Step 5: Commit** `feat(domain): unwrap an Open in app link into the text a scan reads`.

### Task 3: Incoming-link port

**Files:**

- Modify: `src/services/devicePorts.ts` (add the interface)
- Create: `src/services/incomingLink.ts` (the native implementation over `expo-linking`) and `src/services/fakeIncomingLink.ts`
- Modify: `src/hooks/nativePorts.ts` (wire it into `Ports`) and the test ports factory
- Test: `src/services/incomingLink.test.ts`

**Interfaces:**

- Produces: `interface IncomingLinkPort { subscribe(listener: (url: string) => void): () => void }`, plus `Ports.incomingLink`. The port delivers the initial URL once, after subscribe, and then each `url` event. The same URL string delivered within 2 s is delivered once. The fake exposes `deliver(url)`.

- [ ] **Step 1: Write the failing tests** over a stub of `expo-linking`'s `getInitialURL` and `addEventListener`:
  - The initial URL is delivered once.
  - A later event is delivered.
  - The initial URL plus the same URL as an event within 2 s is delivered once.
  - The same URL again after 2 s is delivered again.
  - Unsubscribe removes the listener.
  - A `getInitialURL` that rejects delivers nothing and never throws.
- [ ] **Step 2: Run** `pnpm vitest run src/services/incomingLink.test.ts`. Expected: FAIL.
- [ ] **Step 3: Implement** it, and wire it in `createNativePorts()`. Check that the wiring is real (recurring failure class 1, the inert seam): a test asserts `createNativePorts().incomingLink` is the native implementation.
- [ ] **Step 4: Run** it again. Expected: PASS.
- [ ] **Step 5: Commit** `feat(services): incoming link port`.

### Task 4: Hand a link to Home's scan handling

**Files:**

- Modify: `src/hooks/useHome.ts`. Extract the body of `useHandleScan` after `recognise` into `handleRecognised(tapped, raw)` as a verbatim move, and accept a pending link.
- Create: `src/hooks/useIncomingLink.ts`. This is the root hook: it subscribes, unwraps, applies the held rule, records, and parks the link for Home.
- Create: `src/hooks/pendingLink.ts`. One slot, `{ mode, raw } | null`, read and cleared by Home.
- Modify: `app/_layout.tsx`. Mount `useIncomingLink` after the reopen gate has settled.
- Test: `src/hooks/useIncomingLink.test.tsx` and additions to `HomeScreen` tests in a new sibling `HomeScreen.openLink.test.tsx` (the main file is past 1500 lines).
- Test: `test/linkRecord.contract.test.ts`, an ajv test of the three `link-opened` lines against the vendored capture-record.v1.

**Interfaces:**

- Consumes: `unwrapLink` (Task 2), `Ports.incomingLink` (Task 3), `Ports.navigation`, and the snapshot selector for a held broadcast that the reopen gate uses (Arming or live).
- Produces: `useIncomingLink(): void`, `pendingLink.put(link)` / `pendingLink.take(): {mode, raw} | null`, and record lines `link-opened {mode, state}`.

- [ ] **Step 1: Write the failing tests.** All of these use the fake engine and the fake link port.
  - **Paired, not held:** a stream link moves to Home and shows the same pairing panel a scan of that QR shows. Assert the panel's dictionary copy.
  - **Scoring:** a scoring link opens the scoring pad, as a scan does.
  - **Match Day:** a Match Day link opens the day list.
  - **A second code:** with a saved Match Day code still sending, a Match Day link for another day shows the same prompt a second scan shows.
  - **Held broadcast (live):** the link does not navigate. `navigation.current()` stays `stream`, and the record has `link-opened {mode:'stream', state:'held'}`.
  - **Foreign link:** Home shows the scan's foreign panel copy, and the record has `link-opened {mode:'stream', state:'foreign'}` when the path names a mode, or `{state:'foreign'}` with no mode.
  - **Cold start:** with the link delivered before the reopen gate settles, it is handled once, after the gate settles.
  - **During a scan flight:** a link arriving while the scanner is up is handled after the flight ends, and once.
  - **Two links in a row:** the second one replaces the first if the first is not yet taken.
  - **No token anywhere:** the record text and logger output contain no fixture token, no `#`, and no `/app/`.
  - **Language:** in each of en, es, fr and nl, the foreign panel shows that dictionary's copy.
  - **Contract:** the ajv test validates each of the three `link-opened` shapes against `contracts/capture-record.v1.json`.
- [ ] **Step 2: Run** `pnpm vitest run src/hooks/useIncomingLink.test.tsx src/ui/screens/HomeScreen.openLink.test.tsx test/linkRecord.contract.test.ts`. Expected: FAIL.
- [ ] **Step 3: Implement it.** The verbatim extraction in `useHome` comes first, and the existing `HomeScreen` tests must still pass by name before any new code. `tapped` for a link is the link's own mode, so `scanOutcome` gives `open` for a good code. Add the kind to the record allow-list with Kotlin/TS parity if `ScrubParity` requires it.
- [ ] **Step 4: Run** the command from Step 2, then `pnpm vitest run src/ui/screens/HomeScreen.test.tsx`. Expected: PASS. Mutate the held guard, the dedupe and the flight wait once each, and watch a test fail.
- [ ] **Step 5: Update AGENTS.md.** In §1, add one sentence: a tapped Open in app link is a scan by another route (spec of 2026-11-25). Add `useIncomingLink` to §3's list of hooks that may import native modules only if it does; it should go through the port.
- [ ] **Step 6: Commit** `feat(home): open a tapped Open in app link as a scan`.

### Task 5: Device check (owner's hands)

**Files:**

- Create: `docs/specs/2026-11-25-open-in-app-results.md`

- [ ] **Step 1: Build and install.** Build the dev twin: arm64, `assembleRelease`, `EXPO_PUBLIC_SEAZN_ENV` unset (so staging). Check it isn't live, then install it with `adb`.
- [ ] **Step 2: Check verification.** Run `adb shell pm get-app-links com.seazn.capture.dev`. Expected: `stg.seazn.club: verified`. A value other than `verified` means the web's `assetlinks.json` or the debug fingerprint is wrong; stop and tell the web session.
- [ ] **Step 3: The owner's steps, listed, not simulated.** On the same phone, signed in on stg in Chrome: 0. Do each of 1–3 in Chrome and again in Samsung Internet (same-host App Link hand-off, spec §10).
  1. Stream code → Open in app → the pairing panel appears.
  2. Scoring link → Open in app → the pad; Score in browser → the web pad.
  3. Match Day → Open in app → the list.
  4. While live, Open in app on another code → nothing moves.
  5. Uninstall, tap Open in app → the fallback page → Play → install → tap again → the app opens.
- [ ] **Step 4: Pull the record.** Confirm `link-opened` lines and no token. Write the results doc, then commit `docs(specs): open in app device check`.
