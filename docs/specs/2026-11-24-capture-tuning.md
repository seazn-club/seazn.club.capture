# Capture tuning

Date: 2026-11-24. Status: built on `feat/capture-tuning`, not yet seen on a phone.

## 1. Rulings

The owner, 2026-11-24, as relayed by the controller's dispatch:

1. **Match sound.** The microphone's default speech processing goes. A Live Stream setting, _Match sound_, on by
   default, keeps the ground's sound; off turns speech processing back on. It can change on air, and it must hold
   across every capture reopen and re-pair, with the audio meter still working.
2. **Constant bitrate.** A Live Stream setting, off by default, disabled while a broadcast is held ("After the
   broadcast"). On asks the video encoder for CBR where that encoder lists it; otherwise the record says so and the
   encode stays VBR.
3. **Anti-flicker.** Antibanding AUTO at every accepted camera open, the same gate as OIS, with one record line of the
   state the camera applied.
4. **A keyframe on resume**, unless StreamPack already starts each resumed stream on one. It does (§6), so no code.

Settings counts four phone settings on Live Stream now (AGENTS §1).

## 2. The settings

Both live in the stream settings store (`streamSettingsStore.ts`, wire v4: `{v, side, automatic, matchSound,
constantBitrate}`; a v3 record reads with the defaults, match sound on and constant bitrate off). They reach native the
way the automatic switch does:

- **At the pair.** `PairIntent` carries `matchSound` and `constantBitrate` (`usePairing`), `PairMapping` reads them
  into the `PairRequest` (absent or mistyped: on and off), and the pairing holds them as its `Tuning`.
- **Each change.** Two intents, `setMatchSound {on}` and `setConstantBitrate {on}`, are reconciled by
  `useEngineSync` against the snapshot's `pairing.matchSound` and `pairing.constantBitrate`, one intent per change.
  Native (`Pairings.matchSound`, `Pairings.constantBitrate`) records `match-sound {on}` or `constant-bitrate {on}`
  and does nothing for the same value again.
- **On air.** Match sound is taken in every paired phase but Ended, on air included. Constant bitrate is ignored while
  a broadcast is held (Arming on, `holdsBroadcast`, `intent-ignored`), and JS does not send it then
  (`selectBroadcastHeld`), so an ignored intent never repeats each second; it goes once the broadcast is over. Both
  Settings screens disable the row from Arming on: Live Stream's with "After the broadcast" in place of its hint, the
  app's with it as the row's helper.

## 3. The capture

The camera-stabilisation spec's trap: a capture opens from the pair's original request
(`BridgeCore.noticeCapture`, `phase.pairing?.request`), so a choice read from the request would be lost at the next
reopen. So the machine sends `Command.Tune(tuning)` at the pair (before `AskPermissions`, so before the capture opens)
and at each accepted change, and `CaptureRun` keeps the last one, held or not, closed or open. Every build reads it.

### 3.1 Match sound

**The microphone is built with no effects.** StreamPack 3.2.1's default `MicrophoneSourceFactory` is `CAMCORDER` with
`{AEC, NS}` (javap). The build now passes `MicrophoneSourceFactory(MediaRecorder.AudioSource.CAMCORDER, emptySet())`.

**Speech processing is ours** (`SpeechEffects`): an `AcousticEchoCanceler` and a `NoiseSuppressor` created on the
broadcast recording's own audio session (the id `AudioSessionIds` already reads for MicWatch), enabled at once, and
released when match sound goes back on, at the capture's release, and when the recording's session changes. Both are
built or neither: one built before the other threw is released, so a half-built set never reads `on` (`SpeechSet`,
review minor 6). So a
change on air is immediate, with no stop, no start and no new recording, and the meter's session never changes.
`CaptureRun.speak()` applies the choice after each build, after a connect when the build could not read the session
yet, and on a held capture at each change. One `speech-processing {state: on|off|unavailable}` line when the state
differs from the last said in an opening, which starts `off` (as built), so the default writes no line. A throw is one
`capture-call-failed {action: speech-processing}` line, never the session's failure.

**Rejected:**

- _Swap the audio source_ (StreamPack's own factory with or without effects). `MicrophoneSourceFactory.isSourceEquals`
  compares the audio source int only, so CAMCORDER to CAMCORDER is skipped ("Audio source is already set,
  skipping"): the swap does nothing. A different source would rebuild the recording, with a gap on air and a new
  session id under the meter.
- _StreamPack's `addEffect` / `removeEffect` on air._ The effects it creates start disabled and are enabled only in
  `startStream`, so on air a change would wait for the next start.
- _`UNPROCESSED`_ in place of `CAMCORDER`. It is optional (`PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED`), and where it
  exists it is flat with no gain: a quiet mic reaches YouTube quiet, since nothing downstream normalises (AGENTS §6).
  `CAMCORDER` with its effects off keeps the camcorder tuning and gain and loses only the speech processing.

### 3.2 Constant bitrate

The encoder's mode is set only at a build, so it never changes under a held streamer. A change with the capture held
and nothing on air rebuilds it (`capture-rebuilt {action: tuning}`); under an attempt it waits for the next build (only
a re-pair could bring one there, since the machine ignores it on air). The held rebuild is near-unreachable in
practice: the capture is held only while the viewfinder shows (`BridgeCore.noticeCapture`), and both Settings screens
cover it, so a change made there reaches the next build, when the viewfinder comes back. It stays for a re-pair's
`Tune` over a held capture.

An encoder that lists CBR and then refuses to configure with it would fail every pair while the choice stays saved.
So a CBR build whose encoder failed is built once more with VBR and says `bitrate-mode {state: unsupported}`; a VBR
build's encoder failure fails as before (review, minor 1).

StreamPack 3.2.1 (`MediaCodecUtils.createCodec`) builds the format, our customize keys included, then takes
`MediaCodecList.findEncoderForFormat(format)`. A `KEY_BITRATE_MODE` of CBR added blind would filter out every encoder
without CBR, and the pick could fall to a software encoder. So customize asks `findEncoderForFormat` itself first,
without the key and with the surface colour format StreamPack sets after customize (`EncoderModes.cbrSupported`), and adds `bitrate-mode` = `BITRATE_MODE_CBR` (2) only when that
encoder's `EncoderCapabilities.isBitrateModeSupported(CBR)`: the same encoder then stays the first match. One
`bitrate-mode {state: cbr|unsupported}` line a build when the operator asked; VBR, the default, writes none.

**Rejected:** `BITRATE_MODE_CBR_FD` (3). It may drop frames to hold the rate, and a dropped frame is a stutter in a
moving ball.

**What CBR does to the regulator and SRT.** N41 named CBR as the experiment for a still picture held at the floor:
F-P5-7's raise gate needs egress at 70% of the expected rate, and VBR on a still scene sends far less, so no raise is
ever tested. CBR fills the rate, so a still picture's raises can be tested. The gate, the cuts and the floor are
unchanged. `SrtBandwidth.OVERSHOOT_FACTOR` (2, `Link.kt`) was sized for VBR's overshoot, about 1.4x the expected
egress on a busy picture (F-P5-7's thin-link run). CBR overshoots less, so the cap keeps more room than CBR needs for
retransmits; it stays 2, one factor for both modes, since the cap must still serve VBR and a smaller factor for CBR
would be a second number with no device run behind it.

### 3.3 Anti-flicker

At each accepted open (the gate OIS uses: never a busy open's false report), `Antibands.set` reads the camera's
`CONTROL_AE_AVAILABLE_ANTIBANDING_MODES`. Without AUTO it sets nothing (`unavailable`). With it, it sets
`CONTROL_AE_ANTIBANDING_MODE` AUTO and applies the repeating request with `applyRepeatingSessionSync`, bounded at 1 s
(`withTimeoutOrNull`) so a stalled session never holds the run's queue, and reads the mode from the capture result:
`auto`, `50hz`, `60hz` or `off`, or `unread` with no result in time. One `antibanding {state}` line when the camera or
the state differs from the last said in an opening. A throw is one `capture-call-failed {action: antibanding}` line;
OIS still goes on.

## 4. Record

Five kinds, in `allow-list.json` (both sides read it; 103 to 108): `match-sound`, `constant-bitrate`,
`speech-processing`, `bitrate-mode`, `antibanding`. Their keys (`on`, `state`) and `capture-call-failed`'s `action`
were already allowed; the reasons now name them.

## 5. Tests

Kotlin: `TuningTest` (the machine), `CaptureRunTuningTest` (the run, over `FakeParts`), `IntentMappingTest`,
`PairMappingTest`, `PairIntentVectorsTest`, `SnapshotWireTest` (a `paired-tuned` vector, both sides),
`GlueRulesTest` (the glue's source). JS: the store, `useEngineSync`, `usePairing`, `intentWire`, `snapshotWire`, the
fake engine, both Settings screens and both fit tests (each label beside its switch, the on-air line on one line, in
four languages). Each new guard was mutated once and a test failed.

The glue (`StreamerAdapter`, `SpeechEffects`, `EncoderModes`, `Antibands`) is compiled by CI only, never locally.

## 6. Keyframe on resume: no code (N51)

StreamPack starts every resumed stream on a fresh codec session, so its first frame is an IDR. Every outage in the
session machine ends in a new `Connect`, and `CaptureRun` stops what it held first: `SingleStreamer.stopStream` stops
the video encoder (`MediaCodec.stop`) and resets it (`resetVideoEncoder`: `reset` then `configure`), and
`startStream` starts only from `CONFIGURED`. So a `requestKeyFrame` on resume would ask for what the encoder already
does. Recorded as N51; seeing it on the wire is the device check's.

## 7. Device-only

None of these is provable in jsdom or on the JVM:

1. Match sound off then on, on air: the ground's sound comes back with no gap in the meter and no blip on air.
2. `speech-processing` reads `on` on the owner's phone (both effects available), and the processing is audible.
3. `bitrate-mode` reads `cbr` on the owner's phone with constant bitrate on, and the encoder is the same hardware one.
4. A still picture with constant bitrate on climbs off the floor (N41).
5. `antibanding` reads `auto` (or `50hz`) at each open, and a scene under mains lighting shows no banding.
6. The first frame after a reconnect is an IDR (N51).

## 8. CBR's cost at the relay (2026-11-25)

The owner's A/B (record R-NBM8, NE2211, the same relay): VBR held the relay at 30 fps and 65 % CPU with nothing dropped;
CBR at 27 fps average and 80 % CPU, the relay's ladder stepping to overlay_half_rate, 567 dropped. The phone's input
frame rate was fine both times. Ruling (owner, 2026-11-25): "Keep it, investigate now". Constant bitrate stays as built;
the investigation (an `encoder-format` line in every build, a dump of the encoded stream in the dev twin) is
[encoder diagnostics](2026-11-25-encoder-diagnostics.md).
