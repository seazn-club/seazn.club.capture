package com.seazn.capture.engine.core

import kotlin.math.roundToInt

/**
 * Spec 2026-11-25-zoom-and-metering (owner, 2026-11-24): what the operator's fingers on the preview ask of the camera.
 * The preview is a native view, so the gestures are read there and handed to the capture as these, never to JS.
 */
sealed interface CameraTouch {
  /** Two fingers spread ([scale] over 1) or closed: the zoom, on air as before it (owner, 2026-11-26). */
  data class Pinch(val scale: Float) : CameraTouch

  /** The two fingers lifted: a pinch's gesture is over. On air the zoom it ended on is one record line. */
  data object PinchEnd : CameraTouch

  /**
   * One finger dragged: [dx] and [dy] are shares of the visible picture's width and height the finger moved (right and
   * down positive). Above 1x the crop follows, 1:1; at 1x nothing (owner, 2026-11-26).
   */
  data class Drag(val dx: Float, val dy: Float) : CameraTouch

  /** One tap: focus and exposure metered at [point]; focus stays continuous. */
  data class Tap(val point: MeterPoint) : CameraTouch

  /** A double tap: the camera's own metering again. */
  data object Reset : CameraTouch

  companion object {
    const val KIND = "camera-touch"

    /** The one line a gesture the capture does not carry out writes, at most once an opening for each reason. */
    fun ignored(action: String, reason: String): RecordEntry = RecordEntry(KIND, listOf("action" to action, "reason" to reason))
  }
}

/** A camera's zoom ratios as Camera2 lists them (`CONTROL_ZOOM_RATIO_RANGE`). */
data class ZoomRange(val lower: Float, val upper: Float)

/** Spec §3: the pinch zoom. */
object Zoom {
  const val KIND = "zoom"

  /** Where every camera opens, and where a switch or a new pairing puts it back. */
  const val DEFAULT = 1f

  /**
   * The ratio a pinch of [scale] asks from [current]: never under 1x, though a logical camera may list less (a ratio
   * under 1 hands it to its ultra-wide lens, a camera the operator did not pick), and never past the camera's top. A
   * scale that is no positive number changes nothing.
   */
  fun next(current: Float, scale: Float, range: ZoomRange): Float {
    if (!scale.isFinite() || scale <= 0f) return current
    val floor = maxOf(DEFAULT, range.lower)
    return (current * scale).coerceIn(floor, maxOf(floor, range.upper))
  }

  /** The line written as air starts: the ratio in use, to a tenth, under the contract's `state` key. */
  fun entry(ratio: Float): RecordEntry = RecordEntry(KIND, listOf("state" to (ratio * 10).roundToInt() / 10.0))
}

/** A place on the picture as the operator sees it: 0 to 1 across and down the upright 16:9 frame. */
data class MeterPoint(val x: Float, val y: Float)

/** A metering region in the camera's active array (Camera2's `MeteringRectangle`): where, how big and its weight. */
data class MeterArea(val left: Int, val top: Int, val width: Int, val height: Int, val weight: Int)

/** What the camera says of its sensor: `SENSOR_ORIENTATION`, `LENS_FACING` front, `SENSOR_INFO_ACTIVE_ARRAY_SIZE`. */
data class SensorFacts(val orientation: Int, val front: Boolean, val width: Int, val height: Int)

/**
 * Spec §4: a tap meters focus and exposure at the place tapped. The regions are in the active array's coordinates,
 * which under `CONTROL_ZOOM_RATIO` are the zoomed picture's (Camera2), so the zoom never enters the sum.
 */
object Metering {
  /** Camera2's `MeteringRectangle.METERING_WEIGHT_MAX`. */
  const val WEIGHT = 1_000

  /** The focus region's side, a sixth of the picture's, and the exposure region's, a quarter (StreamPack 3.2.1's own). */
  const val FOCUS_SIZE = 1f / 6
  const val EXPOSURE_SIZE = 1f / 4

  /** One region of no weight: the camera meters as it would with none (Camera2: a zero weight is ignored). */
  val NONE = MeterArea(0, 0, 0, 0, 0)

  /** [x] and [y] as a share of the picture's width and height; null off the picture or no number. */
  fun point(x: Float, y: Float): MeterPoint? = if (x in 0f..1f && y in 0f..1f) MeterPoint(x, y) else null

  /**
   * The region of [size] (a share of the picture's side) centred where [point] is on [sensor], for a frame encoded at
   * [frameDegrees] (90 held left, 270 held right). The turn is StreamPack 3.2.1's `FocusMetering.getRelativeRotationDegrees`
   * and the quarter turns and the front camera's mirror its `normalizePoint` (javap). The centred crop is ours, in place
   * of its `getFovAdjustedPoint`: the 16:9 picture is the centre of the sensor that Camera2 crops it from. A region past
   * the sensor's edge is moved inside it, never cut.
   */
  fun area(point: MeterPoint, frameDegrees: Int, sensor: SensorFacts, size: Float, ratio: Float = Zoom.DEFAULT, centre: PanCentre = Pan.CENTRE): MeterArea {
    val zoom = maxOf(Zoom.DEFAULT, ratio)
    // The picture shows 1 / zoom of the field, centred at [centre]: a tap is that share of it, and the regions are the
    // active array's own (the zoom is a crop with the ratio at 1, so there is no post-zoom frame), inside the crop.
    val inField = MeterPoint((centre.x + (point.x - 0.5f) / zoom).coerceIn(0f, 1f), (centre.y + (point.y - 0.5f) / zoom).coerceIn(0f, 1f))
    val at = onSensor(inField, frameDegrees, sensor)
    val width = (at.wide * size / zoom).roundToInt()
    val height = (at.high * size / zoom).roundToInt()
    return MeterArea(placed(at.centreX, width, sensor.width), placed(at.centreY, height, sensor.height), width, height, WEIGHT)
  }

  /** A place on the picture, on the sensor: its pixel, and the picture's own field there (`wide` x `high`, centred). */
  data class SensorPlace(val centreX: Float, val centreY: Float, val wide: Float, val high: Float)

  /** The turn and the mirror of [area], for any place on the picture, so a pan's centre goes where a tap does. */
  fun onSensor(point: MeterPoint, frameDegrees: Int, sensor: SensorFacts): SensorPlace {
    val turn = turn(sensor, frameDegrees)
    val (x, y) = mirrored(turned(point, turn), turn, sensor.front)
    val aspect = ASPECT.let { if (turn % 180 == 0) it else 1 / it }
    val covered = if (sensor.width.toFloat() / sensor.height > aspect) sensor.height * aspect to sensor.height.toFloat() else sensor.width.toFloat() to sensor.width / aspect
    val (wide, high) = covered
    return SensorPlace((sensor.width - wide) / 2 + x * wide, (sensor.height - high) / 2 + y * high, wide, high)
  }

  /** StreamPack's relative turn: the back camera's sensor less the frame's, the front's the two together. */
  private fun turn(sensor: SensorFacts, frameDegrees: Int): Int =
    if (sensor.front) (sensor.orientation + frameDegrees) % 360 else (sensor.orientation - frameDegrees + 360) % 360

  /** StreamPack's `PointF.rotate` (javap): the frame's point on the sensor, a quarter turn at a time. */
  private fun turned(p: MeterPoint, turn: Int): Pair<Float, Float> =
    when (turn) {
      90 -> p.y to 1 - p.x
      180 -> 1 - p.x to 1 - p.y
      270 -> 1 - p.y to p.x
      else -> p.x to p.y
    }

  private fun mirrored(p: Pair<Float, Float>, turn: Int, front: Boolean): Pair<Float, Float> =
    when {
      !front -> p
      turn % 180 == 0 -> 1 - p.first to p.second
      else -> p.first to 1 - p.second
    }

  private fun placed(centre: Float, side: Int, limit: Int): Int = (centre - side / 2f).roundToInt().coerceIn(0, maxOf(0, limit - side))

  private const val ASPECT = Encode.WIDTH.toFloat() / Encode.HEIGHT
}

/** The crop's centre, as shares of the upright picture's field: 0.5, 0.5 is the middle, where every zoom starts. */
data class PanCentre(val x: Float, val y: Float)

/** A crop region in the active array (Camera2's `SCALER_CROP_REGION`). */
data class CropRect(val left: Int, val top: Int, val width: Int, val height: Int)

/** What to set on the camera for a zoom and a pan: `CONTROL_ZOOM_RATIO` (always 1.0) and the crop window (null: the whole array). */
data class PanFrame(val ratio: Float, val crop: CropRect?)

/**
 * Spec §3.5 (owner, 2026-11-26, option A): all zoom is a digital crop of the current camera, and a one-finger drag moves
 * the crop, 1:1, within the picture's own field. `CONTROL_ZOOM_RATIO` stays 1.0: with another ratio the crop region is
 * overridden and its coordinates are post-zoom (CaptureRequest docs), and a ratio is what hands a logical camera to
 * another lens. So the window of field / Z centred at C is sent as `SCALER_CROP_REGION` alone, centred or not.
 */
object Pan {
  val CENTRE = PanCentre(0.5f, 0.5f)

  /** The centre kept so the window of 1 / [ratio] of the field stays inside it. At 1x that is the middle only. */
  fun clamp(centre: PanCentre, ratio: Float): PanCentre {
    val half = 0.5f / maxOf(Zoom.DEFAULT, ratio)
    val range = half..(1f - half)
    return PanCentre(centre.x.coerceIn(range), centre.y.coerceIn(range))
  }

  /** A finger that moved [dx], [dy] of the visible picture drags the content with it, so the window moves the other way. */
  fun drag(centre: PanCentre, dx: Float, dy: Float, ratio: Float): PanCentre {
    if (ratio <= Zoom.DEFAULT || !dx.isFinite() || !dy.isFinite()) return centre
    return clamp(PanCentre(centre.x - dx / ratio, centre.y - dy / ratio), ratio)
  }

  /**
   * What to set for zoom [ratio] centred at [centre]: ratio 1.0 always, and the crop window of the field / [ratio] in the
   * active array, the whole of it (null) at 1x. The whole rectangle is kept inside the array, right and bottom edges
   * included, whatever the rounding.
   */
  fun frame(centre: PanCentre, ratio: Float, frameDegrees: Int, sensor: SensorFacts): PanFrame {
    if (ratio <= Zoom.DEFAULT) return PanFrame(1f, null)
    val held = clamp(centre, ratio)
    val at = Metering.onSensor(MeterPoint(held.x, held.y), frameDegrees, sensor)
    val wide = at.wide / ratio
    val high = at.high / ratio
    val width = wide.roundToInt().coerceIn(1, sensor.width)
    val height = high.roundToInt().coerceIn(1, sensor.height)
    val left = (at.centreX - wide / 2).roundToInt().coerceIn(0, sensor.width - width)
    val top = (at.centreY - high / 2).roundToInt().coerceIn(0, sensor.height - height)
    return PanFrame(1f, CropRect(left, top, width, height))
  }
}
