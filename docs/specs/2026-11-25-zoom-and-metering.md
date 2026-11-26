# Pinch zoom and tap metering

Date: 2026-11-25. Status: built on `feat/capture-tuning` (lane 2), not yet seen on a phone. The glue compiles on CI
only.

## 1. Rulings

The owner, 2026-11-24:

1. **Pinch zoom, before air only.** _Superseded 2026-11-26, below._ A pinch zooms. From Arming on a pinch does nothing.
   The zoom goes back to 1× on a camera switch and on a new pairing. One record line when air starts says the zoom in use.
2. **A tap sets the focus and exposure area.** One tap meters there, and focus stays continuous. A double tap gives the
   camera its own metering back. A tap is allowed on air. No JS overlay on the camera box; a native focus ring is
   optional (not built, §7).

The owner, 2026-11-26 (option B), **supersedes ruling 1's air lock**: a pinch zooms on air exactly as it does before
air, the same full range, no easing, no cap. The lock was meant to stop a mis-pinch (a pocket, a volunteer steadying the
phone) moving the picture, but reframing during a match is worth more than that risk. The owner chose B over A (keep the
lock) and C (zoom on air, eased or capped). Everything else in ruling 1 stands: 1× on a camera switch and a new pairing,
and the record line at air start. Taps and metering are unchanged. See §3.2 and §3.4.

The owner, 2026-11-26, then added **drag to move the zoomed frame**, before air and on it (§3.5).

The controller's recommendation, built here: **the zoom survives Settings closing and reopening the capture** (§3.3).

## 2. Where it lives

Native owns capture (AGENTS §2), and the preview is a native view, so the gestures are read on it and never cross the
bridge. Nothing changes in JS: no intent, no snapshot field, no prop.

| Part     | File                                 | What it does                                                                                  |
| -------- | ------------------------------------ | --------------------------------------------------------------------------------------------- |
| Rules    | `core/.../core/Framing.kt`           | `CameraTouch`, `Zoom.next`, `Zoom.entry`, `Metering.point`, `Metering.area`: pure, JVM-tested |
| State    | `capture/run/CameraFraming.kt`       | the zoom and the place tapped, per pairing and camera; the gates; the record lines            |
| Queue    | `CaptureRun.touch`                   | every gesture runs on the run's one consumer (`CaptureSerial`), beside opens and connects     |
| Lock     | `BridgeCore.broadcastHeld()`         | the last published phase's `holdsBroadcast` (Arming on), volatile, any thread                 |
| Gestures | `CapturePreviewView`                 | our own `ScaleGestureDetector` and `GestureDetector` on the `PreviewView`                     |
| Camera   | `StreamerAdapter` parts, `Meters.kt` | `RZoom.setZoomRatio`; `CONTROL_AF_REGIONS` and `CONTROL_AE_REGIONS`                           |

## 3. Zoom

### 3.1 The rule

Each pinch multiplies the ratio by the detector's scale factor, kept within the camera's
`CONTROL_ZOOM_RATIO_RANGE`, never under 1× (`Zoom.next`). A scale that is not a positive number changes nothing. Pinch
moves arrive faster than a camera applies a ratio, so the ones the queue has not taken yet are multiplied together and
taken as one (`PinchBuffer`): one queue turn, one ratio set.

A new ratio clears the place tapped (the region would now point at a different part of the scene) and gives the camera
its own metering back.

Quick scale (a double tap and drag with one finger) is turned off on the detector: one finger never zooms, and the
double tap only resets the metering.

### 3.2 The lock (removed, 2026-11-26)

Ruling 1 of 2026-11-24 locked the pinch from Arming on: the run asked `BridgeCore.broadcastHeld()` (the last published
snapshot's `Phase.holdsBroadcast`) on every pinch and refused with a `camera-touch` line, reason `broadcast`. The owner
removed that lock on 2026-11-26 (option B, above): `CameraFraming.pinch` no longer asks, and the `broadcast` refusal no
longer exists. `broadcastHeld` stays wired, now only to decide whether a gesture's end writes a record line (§3.4).

### 3.3 What keeps it and what resets it

The zoom and the place tapped are held for one pairing (by object identity: a re-scan of the same code is a new pairing)
and one camera. Every accepted open of that camera in that pairing puts both back, because a new StreamPack source
opens at the camera's defaults:

- a rebuild for the other side, or for a new encode;
- the slate falling;
- **Settings closing the capture and the viewfinder reopening it.** `BridgeCore.noticeCapture` hands the platform the
  same `PairRequest` object again, so it is the same pairing. Recommended because Settings is where the operator goes
  mid-setup to check Diagnostics: coming back to a different framing would mean pinching again for nothing, and the
  framing the operator chose is part of the shot, not a passing state;
- a reopen after a loss, once that open is accepted.

A camera switch, a switch back, and a new pairing start again at 1× with the camera's own metering. A new pairing over a
capture that is still open sets 1× and clears the region at once.

### 3.4 The record line

`zoom {state: <ratio to a tenth>}`, once per broadcast, when its first attempt connects (`CaptureRun.connect`, after
`signals.connected`). A failed start writes none; a rebuild within the broadcast writes none; the next broadcast writes
its own. `state` is the allow-list's plain key (a word or a number), so no new key was needed; a tenth is enough to
tell 1× from a zoomed shot without writing noise.

On air a pinch also writes the same `zoom` line, once per gesture: the preview's `onScaleEnd` sends `CameraTouch.PinchEnd`,
and if the ratio moved since the last gesture ended and the machine holds a broadcast (`broadcastHeld`), the ratio at that
end is one line. Never per frame; a gesture that moves nothing (already at the top, or at 1×) writes none. Before air the
line at air start says the zoom in use, so a pinch's end writes none.

### 3.5 Drag to move the zoomed frame (owner, 2026-11-26, "yes")

Above 1× a one-finger drag on the preview moves the crop: left, right, up and down, 1:1 with the finger, no easing, before
air and on it, clamped to the picture's own field (the 16:9 centre of the sensor that the 1× picture is). At 1× a drag
does nothing. A camera switch and a new pairing put the crop back in the middle with the zoom at 1×, and a reopen of the
same camera puts back the crop it had, as it does the zoom (§3.3).

**Feasibility.** StreamPack 3.2.1's `RZoom.setZoomRatio` is `settings.set(CONTROL_ZOOM_RATIO, …)` then
`applyRepeatingSession()` (javap), and `Meters` sets the metering regions the same way. A crop region is one more key in
that repeating request (`Pans.kt`, `settings.set(CaptureRequest.SCALER_CROP_REGION, …)`): no capture session restart and
no encoder restart. The ratio and the crop go in one request, so no frame has one and not the other.

**The maths** (`Pan`, pure, JVM-tested). Camera2 zooms about the centre of `SCALER_CROP_REGION`. For a window of
field / Z centred at C, the crop is the largest window centred at C that stays inside the field (S), and the ratio is
S / (field / Z): so S is as far from the field's edge as C is, and a window at the edge is the crop itself at ratio 1. A
centred window is today's frame (whole array, ratio Z), set back with the whole array when the crop returns to the middle.
A drag of d (a share of the visible picture) moves C by d / Z the other way (the content follows the finger), kept at
least 0.5 / Z from each edge. A pinch re-clamps C to the new Z. The picture's axes become the sensor's through
`Metering.onSensor`, the same turn and front-camera mirror as a tap, so a drag follows the finger held either way round.

**Gestures** (`CapturePreviewView`). The `GestureDetector`'s scroll starts only past the system's touch slop, so a press
that moves less is a tap and one that moves more is a drag, never both. Scrolls are ignored while a pinch is in progress
and for the rest of any press that has had two fingers down (the detector reports a pinch's focal move as a scroll), and
for a press that began as a double tap's second (`noPan`): quick scale stays off, and that press resets metering and
pans nothing. A drag moves the place tapped to the camera's own metering, as a pinch does.

**Record.** None. The contract's keys are fixed and the `zoom` line's `state` is the ratio, which a drag does not change;
no listed key carries two numbers for a centre, and a line with an unchanged ratio would misreport. A pan on air is
therefore not in the post-match record; a key is a contract change for the web repo.

**Unverified.** That the camera honours `SCALER_CROP_REGION` together with `CONTROL_ZOOM_RATIO` as Camera2 documents
(§8), and that a tap's region lands right on a panned frame (§4.2 says the regions are in the zoomed picture's own
coordinates; a panned crop is a different zoom about a different centre).

## 4. Metering

### 4.1 The rule

A tap at a place on the picture sets `CONTROL_AF_REGIONS` (a sixth of the picture's side) and `CONTROL_AE_REGIONS` (a
quarter), weight 1000, centred there and moved inside the sensor at an edge. Only the regions: the AF mode StreamPack
opens with (`CONTINUOUS_VIDEO`) and the AE mode stay as they are, so a tap never locks focus or sweeps the lens on air.
A double tap sets one region of zero weight in each (Camera2 ignores it, the CameraX reset pattern). A camera that lists
no AF or AE regions (`CONTROL_MAX_REGIONS_*` zero) is left alone for that half.

### 4.2 Held either way round (P4)

The place tapped is a share of the upright 16:9 picture the operator sees. The preview is laid out at the encode's
1280 × 720 and scaled into the box, and Android maps a touch through that scale, so `e.x / width` and `e.y / height`
are already shares of the picture. The turn to the sensor is StreamPack 3.2.1's own tap rule (`FocusMetering`, javap),
with the frame's degrees taken from the encode's rotation (90 held left, 270 held right, as `CaptureRun.rotation`
holds it), never the display's:

- relative turn: back camera `(sensor − frame + 360) % 360`, front camera `(sensor + frame) % 360`;
- turn 90 → `(y, 1 − x)`, 180 → `(1 − x, 1 − y)`, 270 → `(1 − y, x)`;
- the front camera is mirrored across the picture: `(x, 1 − y)` at a turn of 90 or 270, `(1 − x, y)` otherwise.

The picture is the centre of the sensor Camera2 crops it from (16:9, or 9:16 when the sensor lies across). Under
`CONTROL_ZOOM_RATIO` the regions are in the active array's coordinates of the zoomed picture (Camera2;
`RZoom.getCropSensorRegion` returns the whole active array), so the zoom never enters the sum.

### 4.3 On air

A tap is allowed on air (ruling 2). It moves no frame: it changes where focus and exposure look, not the crop. A
continuous-AF camera refocuses on the new region as it would on a scene change.

## 5. The gates

StreamPack 3.2.1's `CameraSettings` setters open a closed device (javap; the stabilisation spec's gate). So:

1. **The run's gate.** `CameraFraming` sets nothing until `watchOpens` accepts an open (`!cameras.falseOpen(id)`, the
   same test stabilisation and antibanding pass). A new open asked (switch, slate, rebuild), a busy word for the open
   camera, and a loss clear it at once, before StreamPack can report anything.
2. **The adapter's gate.** Each part reads the held source of that id while StreamPack reports it open now
   (`isPreviewingFlow` or `isStreamingFlow`), and returns false otherwise.

**An accepted narrow window.** The adapter's check and the set are two steps. A device that closes between them (a
loss, or another app taking it, in the milliseconds a set takes) can be reopened by the setter. StreamPack 3.2.1 guards
its own open and close with `CameraSource.streamMutex`, which is private (javap), so the check cannot run under it. The
run's gate closes on the loss or busy word as soon as StreamPack reports it, so the window is one set, not a state.
Whether it is ever hit is device-only (§8).

A gesture that finds no open camera is ignored and recorded at most once per opening per gesture and reason:
`camera-touch {action: zoom|meter, reason: closed|broadcast}`. The release (`closed`) lets the next opening say its own.
A setter that throws is one `capture-call-failed {action: zoom|metering, error}` line, never the session's failure;
the picture goes on as it was.

## 6. Rejected

- **StreamPack's own gestures** (`enableZoomOnPinch`, `enableTapToFocus`). They act on whatever source is held, with no
  open check, so a pinch or a tap during a busy open would open the device (and, until 2026-11-26, a pinch ignored the
  air lock). They stay off, and our listener consumes every touch so `PreviewView.onTouchEvent` never runs.
- **`FocusMetering.tapToFocus`.** It sets `CONTROL_AF_MODE_AUTO` and triggers an AF scan and AE precapture: focus
  locks, and on air the lens visibly hunts. Its `cancelFocusAndMetering` cancels the triggers but never puts continuous
  AF back. Ruling 2 keeps continuous AF.
- **Zoom under 1×.** A logical camera that lists a lower ratio hands the picture to its ultra-wide lens: another
  camera, another field of view and distortion, not chosen by the operator.
- **A JS overlay on the camera box** (a focus ring, a zoom label). AGENTS §6 draws nothing over the camera box but the
  scoring panel and What viewers see; the owner repeated it.
- **Zoom as an intent or a setting.** It would put a framing value through the bridge and the snapshot, and give JS a
  second say in a decision native owns.
- **Resetting the zoom when Settings closes the capture.** §3.3.
- **A focus ring drawn natively.** Optional in the ruling; not built, so the first device check judges the bare tap.

## 7. Tests

- `FramingTest` (core, 13): the ratio rule, the line, the point, and the regions held left and right, front and back,
  a sensor lying across, and the clamp, every value worked by hand.
- `CaptureRunFramingTest` (24): the queue, the lock from Arming, the gates (before an open, a busy false open, a loss,
  the slate), what keeps and resets, the line once a broadcast, the ignored lines once, a setter's throw.
- `BridgeCoreCaptureTest` (+2): the lock reads Arming on and frees at the stop and at a tick that ends it.
- `GlueRulesTest` (+1 and one narrowed): the preview's own listener and detectors, StreamPack's gestures off, no
  `tapToFocus` or AF-mode call anywhere, the open check before every setter, the lock wired in `EngineHost`.
- `ScrubParityTest` and `scrubParity.test.ts`: 110 kinds.

## 8. Device-only

- A pinch zooms smoothly and the tap's region lands where tapped, held left and held right, on the back and the front
  camera (the mirror is StreamPack's rule, unverified on our phones).
- Continuous AF and AE follow the region without a hunt, on air too.
- A two-finger pinch is never also a tap, a double tap is never also a single tap, and a double tap and drag never
  zooms.
- The adapter's check-then-set window (§5) never reopens a camera another app took.
- The zoom comes back after Settings, the slate and a side change; it goes to 1× after a switch and a new pairing.
- A drag above 1× moves the frame 1:1 under the finger, both ways round and on the front camera, with no jump or blip on
  air; at the field's edge it stops; a tap after a pan meters where it was tapped; a double tap's second press dragged
  pans nothing; a pinch never also pans.
- A pinch from Arming on zooms the picture as it does before air, and each gesture on air writes one `zoom` line.
