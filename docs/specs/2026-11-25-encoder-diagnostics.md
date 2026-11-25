# Encoder diagnostics

Date: 2026-11-25. Status: built on `feat/encoder-diagnostics`, not yet seen on a phone. Follows
[capture tuning](2026-11-24-capture-tuning.md) §3.2 (constant bitrate).

## 1. Evidence and ruling

The owner's A/B, 2026-11-25, record R-NBM8: the same phone (NE2211), the same relay, constant bitrate off and on.

| Encode                                         | Relay fps | Relay CPU | Relay ladder                 | Dropped |
| ---------------------------------------------- | --------- | --------- | ---------------------------- | ------- |
| VBR                                            | 30        | 65 %      | none                         | 0       |
| CBR (`bitrate-mode {cbr}`, `BITRATE_MODE_CBR`) | 27 (avg)  | 80 %      | stepped to overlay_half_rate | 567     |

The phone's input frame rate was fine both times. So the relay's software decode costs about 15 points more on the CBR
stream. The web's hypothesis: in CBR the vendor encoder changes how it codes the stream, by one or more of entropy
coding (CAVLC in place of CABAC), slices per frame, profile or level, or by adding filler NAL units (type 12) or SEI to
hold the rate. Any of these makes the relay's decode dearer.

**Ruling** (owner, 2026-11-25): "Keep it, investigate now". Constant bitrate stays as built, off by default. This adds
what is needed to see which of the above the encoder does: a record line in every build, and a dump of the stream in the
dev twin.

## 2. `encoder-format`, every build

At the first video frame of each attempt, the endpoint (`CountingEndpoint`, through `EncoderTap`) reads the frame's
format, which StreamPack 3.2.1 sets to the codec's `getOutputFormat()` (javap, `MediaCodecEncoder$FrameFactory`), and
parses its `csd-0` and `csd-1` (`H264ParameterSets`, pure, JVM-tested on libx264's real parameter sets: Constrained
Baseline 3.0 CAVLC, Main 3.1 CABAC, High 4.1 CABAC, High 4.0 CAVLC). A line is written when it differs from the last
one said, so a reconnect on the same encoder writes none and a change of mode writes one:

```
{"kind":"encoder-format","mode":"cbr","state":"high-4.1-cabac","source":"c2.qti.avc.encoder"}
```

- `mode`: the bitrate mode the encoder was asked for (vbr, cbr or unsupported).
- `state`: the stream's format from its SPS and PPS, `<profile>-<level>-<entropy>`. The profile is Annex A's name
  (constrained-baseline, baseline, main, extended, high, high10, high422, high444, else `profile<idc>`), the level
  `level_idc` in tenths (1b where Annex A says so), the entropy `cabac` or `cavlc` from the PPS's
  `entropy_coding_mode_flag`. A part is `unread` when its set is missing or too short.
- `source`: the codec's name (`IEncoder.info.name`), or null.

**Why one word under `state`, not `profile`, `level` and `entropy` keys.** The web's record contract
(`contracts/capture-record.v1.json`, vendored) lists a line's keys with `additionalProperties: false`, and the web drops a
whole line with a key it does not list (its fixture `line-invalid-unlisted-scalar.json`). `level` is listed but is the
log level (debug, info, warn, error), so `level: "4.1"` fails too (`line-invalid-level.json`). The kind is open; the keys
are not. So the line uses three keys the contract lists, `mode`, `state` and `source`, each a plain word, with their
reasons in `allow-list.json`. `encoderLines.contract.test.ts` validates the exact lines (`scrub/encoder-lines.json`,
which `EncoderLinesVectorsTest` pins to what `SessionRecord` writes) against the contract with ajv, and shows a
`profile` key is refused. The web may add `profile`, `codecLevel` and `entropy` keys later (its option b); the line
would then move to them.

`KEY_PROFILE` and `KEY_LEVEL` of the output format are not recorded: they are Android's constants for what the SPS says,
often absent from an output format, and there is no contract key for them. The SPS is what the relay decodes.

Not read from the record line: slices per frame (in each slice header, not the parameter sets), filler NAL units and SEI
(in the stream). Those are the dump's (§3).

## 3. The dump, dev twin only

**Gate.** `EncoderDumpRules.allowed(context.packageName)` is true for `com.seazn.capture.dev` alone, an exact match.
`StreamerAdapter` builds the one `EncoderDump` behind it and passes null otherwise, so the production app
(`com.seazn.capture`, `eas.json`'s production profile, AGENTS §11) holds none. Pinned three ways:
`EncoderDumpRulesTest` (the gate: production, a longer id, empty, null), `GlueRulesTest` (one construction, behind the
gate, and no other glue names a dump file) and `test/encoderDumpGate.test.ts` (the constant is `app.config.ts`'s dev
package and never the production profile's).

**What.** One file a broadcast: the first attempt that takes a frame dumps, and a reconnect in the same broadcast does
not (`DumpArming`); the broadcast ending or the capture's release arms the next. The dump starts at the first key frame
the endpoint writes (the stream is on air from the connect) and takes every video frame for 10 s of presentation time,
or 8 MB, as Annex-B H.264: each key frame is preceded by `csd-0` and `csd-1` after start codes (StreamPack strips them
from the key frame's own buffer, `removePrefixes`, javap), so the file decodes from its first byte. Its name is
`encoder-dump-<mode>-<n>.h264` in the app's files dir, numbered past every dump there; four are kept and older ones
deleted. No token, URL or code is in the name or the file, which holds the encoder's bytes only.

**Never on the encode path.** The tap reads the frame through duplicates, so the network endpoint sees the frame as it
came, and the endpoint writes it on unconditionally, after the tap (`GlueRulesTest`). The endpoint's thread decides
(`DumpBudget`, pure) and copies the frame's bytes; the file is opened, written and closed on one writer thread of its
own. Copies not yet written are bounded at 2 MB: past that the dump ends as `overflow` and the rest is dropped, since a
file with a hole would decode wrongly from the hole on. A throw in the tap is one
`capture-call-failed {action: encoder-tap}` line and the tap goes quiet; the frame is still written.

**Record.** One `encoder-dump {state, mode, count, bytes, ms}` line when a dump ends: `written` (10 s), `full` (8 MB),
`overflow`, `cut` (the attempt stopped first; nothing when it took no frame) or `failed` (with `error`, a class name).

### Pulling and reading a dump

With the dev twin installed and a broadcast run (CBR on for one, off for another):

```sh
adb shell run-as com.seazn.capture.dev ls files
adb exec-out run-as com.seazn.capture.dev cat files/encoder-dump-cbr-1.h264 > cbr.h264
adb exec-out run-as com.seazn.capture.dev cat files/encoder-dump-vbr-2.h264 > vbr.h264
```

Frame types and sizes (a filler-padded CBR stream shows near-equal P-frame sizes):

```sh
ffprobe -show_frames -show_entries frame=pict_type,pkt_size cbr.h264
```

Every header, NAL by NAL: the SPS's `profile_idc` and `level_idc`, the PPS's `entropy_coding_mode_flag`, each slice
header (slices per frame), and any filler data (NAL type 12) or SEI (type 6):

```sh
ffmpeg -i cbr.h264 -c copy -bsf:v trace_headers -f null -
```

Compare the two files: the same profile, level and entropy in both clears the parameter sets; more slices per frame,
filler NAL units or SEI in the CBR file is the cost the relay pays.

## 4. Record

Two kinds, in `allow-list.json` (both sides read it; 112 to 114): `encoder-format` and `encoder-dump`. Their keys were
already allowed; the reasons of `mode`, `state`, `source`, `count`, `ms`, `error` and `bytes` now name them, and
`capture-call-failed`'s names `encoder-tap`.

## 5. Tests

Kotlin: `H264ParameterSetsTest` (the parser on real vectors, start codes, emulation prevention, Exp-Golomb, truncation,
the words, the line's first-frame and change rules), `EncoderDumpRulesTest` (gate, names, keep, chunk and its size,
arming, budget: key frame, span, cap, queue bound, said once), `EncoderLinesVectorsTest` (the lines as written),
`ScrubParityTest` (114 kinds), `GlueRulesTest` (the gate's one site, the tap's order, catch and duplicates). JS:
`encoderLines.contract.test.ts` (the contract admits the lines, refuses a `profile` key), `scrubParity.test.ts` (114),
`test/encoderDumpGate.test.ts`. Each guard was mutated once and a test failed. The glue (`EncoderTap`, `EncoderDump`,
`CountingEndpoint`, `CaptureEndpointFactory`, `StreamerAdapter`) compiles on CI only.

## 6. Device-only

1. `encoder-format` reads the owner's phone's real format in both modes, and `source` names the hardware encoder.
2. The dev twin writes `encoder-dump-cbr-<n>.h264` and `encoder-dump-vbr-<n>.h264`, each decodes in ffprobe from its
   first byte, and `trace_headers` answers the hypothesis.
3. With the dump running, the broadcast shows no stutter and the relay's input is unchanged (the tap never moves what
   goes to the network).
4. A production build writes no dump file and no `encoder-dump` line.
