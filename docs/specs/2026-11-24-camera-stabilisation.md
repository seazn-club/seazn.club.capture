# Camera stabilisation

Date: 2026-11-24. Status: built on `feat/stabilisation`, not yet seen on a phone.

## 1. Rulings

The owner, 2026-11-24:

1. "Let's do 1 and 2": optical image stabilisation always on, and electronic stabilisation as a Live Stream setting,
   off by default.
2. Later the same day, on the controller's recommendation after review: **"OIS only, drop the EIS setting
   entirely".** This supersedes the EIS half of ruling 1. §6 records why.

So the app turns **OIS on wherever the camera has it.** There is no setting, no Settings row and no intent.

## 2. Terms

- **OIS** (optical image stabilisation): the lens moves to cancel shake (`LENS_OPTICAL_STABILIZATION_MODE`). It does
  not crop the picture.
- **EIS** (electronic video stabilisation): the camera crops and warps each frame (`CONTROL_VIDEO_STABILIZATION_MODE`
  ON). It is not used (§6).

## 3. The rule

OIS goes on at every camera open where the camera lists it. Where the camera has none, nothing is set, and the record
says `unavailable`. The rule is `Stabilisation.optical` in the native core.

The capture session is native's (AGENTS §2), so this is native's alone. Nothing crosses to TypeScript: no intent, no
snapshot field and no stored setting.

## 4. When native applies it

Camera settings belong to one camera source (`ICameraSource.getSettings()`, StreamPack 3.2.1). A build, a rebuild
after a loss, a switch, a reopen and the slate's fall each give the streamer a **new** source, and a new source
starts with the camera's defaults. So OIS is turned on each time StreamPack reports a camera source open
(`CaptureRun.cameraOpens()`). The work runs on the run's serial queue.

**Never on a false open.** On air, StreamPack can report a source open after an open that failed busy (N45). The run
skips such a report (`CameraAvailability.falseOpen`). The reason is in the bytecode: `CameraSettings.set` and
`applyRepeatingSession` open the device when it is not open. A setting against a false open would therefore open the
camera outside the take and probe machinery, and could hold the queue.

These are the StreamPack 3.2.1 facts this rests on (read with `javap` from the pinned AAR):

- The settings live on `CameraController.captureRequestBuilder`. It is created once each time the device opens and
  cleared in `closeControllers`. So a setting survives a target add (preview to stream), but not a reopen.
- `setIsEnableOptical(true)` only sets the key. Only its `false` branch applies the repeating request. So
  `StreamerAdapter.stabilise` calls `settings.applyRepeatingSession()` itself.
- The adapter reads `isOpticalAvailable` from the held source, which comes from
  `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION`. It touches only the held source of that camera id, never a slate.

**A failed setter never fails the session.** A throw writes `capture-call-failed {action: stabilisation, error:
<class>}`. The picture goes on without OIS. The run forgets the state it last recorded, so the next open records its
state again. A cancellation is passed on, not recorded. This is a convenience, and the broadcast always comes first
(AGENTS §8).

## 5. The record

```
stabilisation {source: optical, state: on|unavailable}
```

One line is written when a camera opens with a camera id or state different from the last line written in this
opening. A repeated report of the same open writes nothing new. A new opening writes again.

The web record contract (`contracts/capture-record.v1.json`, vendored at web `f0feab16c`) closes each line's keys
with `additionalProperties: false`, so the line reuses the existing keys `source` and `state`. The kind is in
`allow-list.json`, which keeps Kotlin and TypeScript level (`ScrubParityTest` and `scrubParity.test.ts`, 103
kinds).

There is no Diagnostics row: the card has 9 rows and 4 dp to spare at 728 × 328 (`settingsFit.test.ts`). The record
carries the state instead.

## 6. Considered and dropped: EIS

EIS was first built as a Settings row, off by default and disabled on air. It was dropped for three reasons:

- **It crops.** EIS narrows the field of view by roughly 10–20 %, and it may change `SCALER_CROP_REGION` (Camera2
  reference). The product is a phone on a tripod, where OIS alone costs nothing and EIS takes picture away.
- **Never both.** Camera2's reference says: "If a camera device supports both this mode and OIS ... turning both modes
  on may produce undesirable interaction, so it is recommended not to enable both at the same time." So EIS on would
  have meant OIS off, a trade that only helps a phone held in the hand.
- **The bug class it brought.** A preference held in JavaScript, carried by the pair and changed by an intent while
  the capture was closed (Settings covers the viewfinder), did not reach the camera when it opened again. The capture
  opened from the pair's original request (review of 802827d4, finding 1). Each such setting needs its own
  reconciliation through every open path. OIS needs none.

For a future handheld mode: StreamPack 3.2.1's `setIsEnableVideo(true)` sets `CONTROL_VIDEO_STABILIZATION_MODE` ON
(1). It does not check `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES` and does not apply the repeating request, so a
caller must do both. Camera2 guarantees stabilisation of a MediaCodec output at 1920 × 1080 or less and 30 fps or less,
which covers our 720p30. `PREVIEW_STABILIZATION` (mode 2) overrides OIS and is not wanted.

## 7. Device-only

These are not proved by the suites:

- that StreamPack reports each new source's open, so OIS reaches a switch, a reopen, the slate's fall and a rebuild;
- that OIS takes effect: compare a held phone's picture with OIS on;
- which cameras on the owner's phone (NE2211), front and back, report OIS;
- that a busy open's false report never opens the camera.
