package com.seazn.capture.engine.core

import kotlin.math.roundToInt

/**
 * Spec 2026-11-25-zoom-and-metering (owner, 2026-11-24): what the operator's fingers on the preview ask of the camera.
 * The preview is a native view, so the gestures are read there and handed to the capture as these, never to JS.
 */
sealed interface CameraTouch {
  /** Two fingers spread ([scale] over 1) or closed: the zoom, before air only. */
  data class Pinch(val scale: Float) : CameraTouch

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
   * [frameDegrees] (90 held left, 270 held right). The turn and the front camera's mirror are StreamPack 3.2.1's own tap
   * rule (`FocusMetering.normalizePoint`, javap); the 16:9 picture is the centre of the sensor that Camera2 crops it
   * from. A region past the sensor's edge is moved inside it, never cut.
   */
  fun area(point: MeterPoint, frameDegrees: Int, sensor: SensorFacts, size: Float): MeterArea {
    val turn = turn(sensor, frameDegrees)
    val (x, y) = mirrored(turned(point, turn), turn, sensor.front)
    val aspect = ASPECT.let { if (turn % 180 == 0) it else 1 / it }
    val covered = if (sensor.width.toFloat() / sensor.height > aspect) sensor.height * aspect to sensor.height.toFloat() else sensor.width.toFloat() to sensor.width / aspect
    val (wide, high) = covered
    val width = (wide * size).roundToInt()
    val height = (high * size).roundToInt()
    val centreX = (sensor.width - wide) / 2 + x * wide
    val centreY = (sensor.height - high) / 2 + y * high
    return MeterArea(placed(centreX, width, sensor.width), placed(centreY, height, sensor.height), width, height, WEIGHT)
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
