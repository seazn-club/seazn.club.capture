# Regulator reset and the record file (N53)

Owner ruling, 2026-11-26: "yes both". Finding: `_FINDINGS.device.md` N53. Evidence: session 66753ccd (staging,
2026-11-26, about 40 minutes on air over SRT), the record shared from the phone (10,662 lines, 10,112 of them
`vendor-log`).

## 1. Why the picture sat at the floor for 4.5 to 5 minutes

Not the raise gate. F-P5-7's gate (a raise needs the interval's mean egress at 70% of the target) writes a
`bitrate-held` line when it holds a due raise, and no such line appears in either window. The hold was the
**failed-rate cap**: after a cut, `BitrateRegulator` remembers the rate that failed and caps every raise at 80% of it
for `FAILED_RATE_MEMORY_MS`, 5 minutes. A raise that the cap leaves at or under the target does nothing and writes
nothing.

- **Episode 1 (20:03).** Drops cut 1.4M to 703,821, then to the 500k floor; the second cut recorded 703,821 as the
  failed rate, so the cap was 563,040. The raise at 20:03:44 reached that cap, and every raise after it was a no-op
  until the memory lapsed at 20:03:14.8 + 300 s = 20:08:14.8. The record's next raise is at 20:08:15.1.
- **Episode 2 (20:17).** A `drop-restored` had set 608k; at 20:17:07 drops cut to the floor with 608k as the failed
  rate, so the cap was 486,400, **under the floor**: no raise was possible at all. The next raise is at 20:22:07.3,
  300 s later to the tenth. This cut came from the 20:17 outage (phone side 5.1 s; Cloudflare kept the dead SRT
  connection until it errored at 20:17:11 and disconnected at 20:17:25). The phone came back on a reconnect after a
  validation gap, and the cap from the old link still held it at the floor. The owner and seaznstream confirm the
  network changed at 20:17, but the record carries no network handle (`network` and `connected` lines name none), so
  that cannot be proven from the record itself; rule 1 below resets only when it can tell.

The floor itself holds as designed (10.8 minutes at or under 0.7M in the run), and a quiet picture still holds its rate
(F-P5-7, N41, capture tuning §3.2): neither rule below touches the gate.

## 2. Rule 1: a new network restarts the regulator

`Transports.newNetwork` puts the target back to `Encode.START_BPS` (1.5M) or keeps a higher one, and clears the cut
time, the failed rate, the failing time and the clean egress, so the first raise waits only the usual interval. It
writes one `bitrate {reason: new-network}` line when the target moves; on air over SRT it also sets the encoder and the
socket's maxBW. It fires:

- on a network switch that replaces the attempt (`switchedOver`, N49), and on a switch between attempts;
- on validation of the network when the link was remade while it was not validated (a provisional `OnAir`, N52, or a
  `Connecting` attempt) **and** validation names another network than the one the attempt was asked on
  (`Session.attemptNetwork`), or either handle is unknown, as N52's rebind treats an unknown one.

It does not fire on the same handle (review of #80): a data stall loses validation, the link drops, it reconnects and
validation returns on the same network, so it is the link that just failed. Its failed rate and repeats stand, the
target stays where the cut left it, and rule 2's hold applies and escalates cycle after cycle (60, 120, 240, 300 s;
`RegulatorNetworkResetTest`). Nor does it fire on a validation flap the connection outlived, on a second validated
report, or with no session. The
old cut's evidence is about a link that no longer exists; the start rate is the regulator's own guess for a link it
knows nothing about, and the floor would make a good new link wait.

## 3. Rule 2: a failed rate under two floors is held for a minute, doubling on repeats

A failed rate whose cap is under `LOW_CAP_BPS` (1M, two floors) is held for `LOW_CAP_HOLD_MS` (60 s) instead of
5 minutes. If the same link fails again under such a cap within 5 minutes of the hold ending, the next hold doubles
(60, 120, 240, then 300 s), at most `LOW_CAP_MAX_REPEATS` (3) times; a cut inside the hold (a cascade) is not a repeat.
Caps at or over 1M, a failed rate of 1.25M or more, keep the 5-minute memory. A link that fails at 1.2M has a cap of
960k, under `LOW_CAP_BPS`, so it takes the short hold, and its repeats double it as any other low cap's.

Bounded: on a link that carries 800k and no more, a 20-minute replay makes 2 to 6 cuts, with gaps of at least 60, 120,
240, 300 and 300 s (`BitrateRegulatorRecoveryTest`). Replayed, episode 1 raises at 32 s to 563,040, then 675,648 at
62 s, 810,777 at 72 s and 972,932 at 82 s; episode 2's first raise comes at 60 s, not 300 s.

## 4. Send and Share read native's record file

Send posted the JS ring's last 1,000 lines since the app started; in the run the ring held 1,000 lines out of 10,662,
mostly `vendor-log`, so the regulator's lines from 20:03 were gone. Native's record file (`RecordFile`, 5 MB and one
rotation) holds every line of every pairing across an app restart, already scrubbed: `SessionRecord.append` scrubs a
line before its sink, `Platform.recordLine`, writes it to the file, JavaScript's forwarded lines included.

**The read (AGENTS §2).** `readRecord` is recorded as a third call that answers, beside `takePhoto` and `scoringLink`.
Folding it into an intent was rejected: an intent returns void, and the text must reach the one caller that asked.
Streaming the file over `onRecord` was rejected: every listener would hear it, and the ring would grow by megabytes.
Its bounds: it runs on the record's own thread, which writes every line, so it sees every line appended before it,
never half a line and never a rotation midway; it reads at most 10 MB (both files at their size), the newest, from a
line's start; it answers null when the disk refuses; the JS port gives up at 5 s and drops a late answer. It carries no
session state, the core and its machine never hear it, and it is never on the failure path.

**Send** merges the file with the ring (`mergeRecord`: a line both hold goes once; one only the ring has, such as
JavaScript's while no pairing was held, goes in time order) and posts what the web files under the credential's code
(capture-record.v1's A1 and A2): lines inside the windows opened by a `pair` with the code's `tokenTag` and run through
`unpaired`, `ended`, `pair-refused`, `reset` or the next `pair`, and lines carrying a sid that a `broadcast-start` in
those windows named. Over 1,000 lines or 1 MB (each line as `fitLine` writes it), the pins go first (each window's
`pair` and each `broadcast-start`), then the other lines newest first, then `vendor-log` newest first; the result is in
written order. With no file (iOS, a stale build, a refused or late read) or none of the code's lines, the ring goes as
before N53. The upload's fail-closed token check (`linesAreSafe`) still runs on what is picked.

**Known gap.** A sid is the code's only through its `broadcast-start` line. A broadcast whose start line fell past the
file's 10 MB read bound and out of the ring loses its sid-bearing lines; its lines with no sid still go inside their
window. The server could not file those lines under the code without the start line either.

**Share** sends the merged record, every code's lines, up to 250,000 characters. React Native's share sheet puts the
text in the intent's `EXTRA_TEXT`, which Android parcels as UTF-16 through a 1 MB binder buffer, so the whole 5 to 10 MB
file cannot go as text. Over the cap the same tiers apply. Sharing the file itself (expo-sharing, a new native
dependency) is the owner's decision and is not built.

## 5. Rejected options

- **Shorten the 5-minute memory for every cap.** A link that fails at 1.25M or more (a cap of 1M or more) would be
  probed every minute at rates it has just failed at, the oscillation F-P5-7 stopped. The short hold is kept to caps
  under 1M, where the picture sits within two raises of the floor, and repeats double it there.
- **Clear the cap on every validated report.** A validation flap the socket outlived, or a reconnect on the same
  handle, is the same link; resetting there probes a bad link every flap and wipes the repeats that escalate its hold.
- **Restart from the floor on a new network.** The floor makes a good network wait minutes; the start rate is what a
  first connect already uses.
- **Send the whole file.** The web keeps only the code's lines; the 1,000-line cap would be spent on another code's.
