# Pinch zoom and tap metering

Date: 2026-11-25. Status: built on `feat/capture-tuning` (lane 2), not yet seen on a phone. The glue compiles on CI
only.

## 1. Rulings

The owner, 2026-11-24:

1. **Pinch zoom, before air only.** A pinch zooms. From Arming on a pinch does nothing. The zoom goes back to 1× on a
   camera switch and on a new pairing. One record line when air starts says the zoom in use.
2. **A tap sets the focus and exposure area.** One tap meters there, and focus stays continuous. A double tap gives the
   camera its own metering back. A tap is allowed on air. No JS overlay on the camera box; a native focus ring is
   optional (not built, §7).

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

### 3.2 The lock

The run asks `broadcastHeld` on every pinch, before anything else. It reads the machine's own word, the last published
snapshot's `Phase.holdsBroadcast`, which holds from Arming to the end of the broadcast. So a pinch made while the start
is asked and not yet answered still zooms (nothing is held yet), and one made the moment Arming begins does not. A tick
that ends the broadcast with no input after it frees the lock too (it publishes). Native decides; JS is not asked.

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

A gesture that finds no open camera is ignored and recorded at most once per opening per gesture and reason:
`camera-touch {action: zoom|meter, reason: closed|broadcast}`. The release (`closed`) lets the next opening say its own.
A setter that throws is one `capture-call-failed {action: zoom|metering, error}` line, never the session's failure;
the picture goes on as it was.

## 6. Rejected

- **StreamPack's own gestures** (`enableZoomOnPinch`, `enableTapToFocus`). They act on whatever source is held, with no
  open check and no air lock, so a pinch on air would move the picture and a tap during a busy open would open the
  device. They stay off, and our listener consumes every touch so `PreviewView.onTouchEvent` never runs.
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
- A two-finger pinch is never also a tap, and a double tap is never also a single tap.
- The zoom comes back after Settings, the slate and a side change; it goes to 1× after a switch and a new pairing.
- A pinch from Arming on does nothing visible.
