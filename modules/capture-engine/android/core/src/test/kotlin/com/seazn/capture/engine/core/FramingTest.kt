package com.seazn.capture.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Spec 2026-11-25-zoom-and-metering: the pure rules of the preview's pinch and tap. Every expected value is worked by
 * hand from the spec's rule (§3, §4), never computed by the code under test. The sensor is 3840 x 2880 (4:3), so the
 * 16:9 picture covers 3840 x 2160 of it with 360 above and below when the sensor lies the picture's way.
 */
class FramingTest {
  private val active = SensorFacts(orientation = 90, front = false, width = 3840, height = 2880)
  private val left = 90 // Side.LEFT's encode, Surface.ROTATION_90
  private val right = 270 // Side.RIGHT's, ROTATION_270

  @Test
  fun `a pinch multiplies the ratio, within the camera's range`() {
    assertEquals(2f, Zoom.next(1f, 2f, ZoomRange(0.6f, 8f)))
    assertEquals(3f, Zoom.next(2f, 1.5f, ZoomRange(1f, 8f)))
    assertEquals(8f, Zoom.next(4f, 4f, ZoomRange(1f, 8f)), "never past the camera's top")
  }

  @Test
  fun `a pinch never goes under 1x, even where the camera lists less`() {
    assertEquals(1f, Zoom.next(1f, 0.5f, ZoomRange(0.6f, 8f)), "a ratio under 1x hands the logical camera to another lens")
    assertEquals(1f, Zoom.next(2f, 0.25f, ZoomRange(1f, 8f)))
  }

  @Test
  fun `a camera with no zoom stays at 1x`() {
    assertEquals(1f, Zoom.next(1f, 3f, ZoomRange(1f, 1f)))
  }

  @Test
  fun `a scale that is no number, or none, leaves the ratio as it is`() {
    assertEquals(2f, Zoom.next(2f, Float.NaN, ZoomRange(1f, 8f)))
    assertEquals(2f, Zoom.next(2f, Float.POSITIVE_INFINITY, ZoomRange(1f, 8f)))
    assertEquals(2f, Zoom.next(2f, 0f, ZoomRange(1f, 8f)))
    assertEquals(2f, Zoom.next(2f, -1f, ZoomRange(1f, 8f)))
  }

  @Test
  fun `the zoom line says the ratio to a tenth, with the contract's state key`() {
    assertEquals(RecordEntry("zoom", listOf("state" to 2.5)), Zoom.entry(2.46f))
    assertEquals(RecordEntry("zoom", listOf("state" to 1.0)), Zoom.entry(1f))
    assertEquals(RecordEntry("zoom", listOf("state" to 3.0)), Zoom.entry(2.96f))
  }

  @Test
  fun `the ignored line names the gesture and why, in plain words`() {
    assertEquals(RecordEntry("camera-touch", listOf("action" to "zoom", "reason" to "broadcast")), CameraTouch.ignored("zoom", "broadcast"))
  }

  @Test
  fun `a point is a place on the picture, or nothing`() {
    assertEquals(MeterPoint(0.25f, 0.75f), Metering.point(0.25f, 0.75f))
    assertEquals(MeterPoint(0f, 1f), Metering.point(0f, 1f), "the edges are on the picture")
    assertNull(Metering.point(-0.01f, 0.5f))
    assertNull(Metering.point(0.5f, 1.01f))
    assertNull(Metering.point(Float.NaN, 0.5f))
  }

  @Test
  fun `the back camera held left lies the picture's way - a tap left of centre meters left of centre`() {
    // Sensor 90, frame 90: relative 0. (0.25, 0.5) -> x 0.25 * 3840 = 960, y 360 + 0.5 * 2160 = 1440.
    // Focus: a sixth of the picture, 640 x 360; exposure: a quarter, 960 x 540; each centred on the point.
    val point = MeterPoint(0.25f, 0.5f)
    assertEquals(MeterArea(640, 1260, 640, 360, 1000), Metering.area(point, left, active, Metering.FOCUS_SIZE))
    assertEquals(MeterArea(480, 1170, 960, 540, 1000), Metering.area(point, left, active, Metering.EXPOSURE_SIZE))
  }

  @Test
  fun `held right, the same tap is the sensor's other side`() {
    // Sensor 90, frame 270: relative 180, so (0.25, 0.5) is the sensor's (0.75, 0.5): x 2880.
    assertEquals(MeterArea(2560, 1260, 640, 360, 1000), Metering.area(MeterPoint(0.25f, 0.5f), right, active, Metering.FOCUS_SIZE))
    // And down is up: (0.5, 0.25) is the sensor's (0.5, 0.75): y 360 + 0.75 * 2160 = 1980.
    assertEquals(MeterArea(1600, 1800, 640, 360, 1000), Metering.area(MeterPoint(0.5f, 0.25f), right, active, Metering.FOCUS_SIZE))
  }

  @Test
  fun `the front camera is mirrored, as StreamPack's own tap rule mirrors it`() {
    val front = active.copy(orientation = 270, front = true)
    // Left: relative (270 + 90) % 360 = 0, mirrored across: (0.75, 0.5), x 2880.
    assertEquals(MeterArea(2560, 1260, 640, 360, 1000), Metering.area(MeterPoint(0.25f, 0.5f), left, front, Metering.FOCUS_SIZE))
    // Right: relative 180, (0.75, 0.5), mirrored across: (0.25, 0.5), x 960.
    assertEquals(MeterArea(640, 1260, 640, 360, 1000), Metering.area(MeterPoint(0.25f, 0.5f), right, front, Metering.FOCUS_SIZE))
  }

  @Test
  fun `a sensor that lies across the picture turns the point a quarter and fits the picture upright in it`() {
    // Sensor 0, frame 90: relative 270, StreamPack's rotate gives (1 - y, x): (0.25, 0.5) -> (0.5, 0.25). Upright on
    // this sensor the picture is 9:16, so it covers 1620 x 2880 with 1110 either side: x 1110 + 810 = 1920, y 720.
    // A sixth of 1620 x 2880 is 270 x 480.
    val across = active.copy(orientation = 0)
    assertEquals(MeterArea(1785, 480, 270, 480, 1000), Metering.area(MeterPoint(0.25f, 0.5f), left, across, Metering.FOCUS_SIZE))
  }

  @Test
  fun `an area at the picture's edge is moved inside the sensor, never cut`() {
    // (0, 0): x 0, y 360; the 640 x 360 box would start at -320, 180: it starts at 0.
    assertEquals(MeterArea(0, 180, 640, 360, 1000), Metering.area(MeterPoint(0f, 0f), left, active, Metering.FOCUS_SIZE))
    // (1, 1): x 3840, y 2520; the 960 x 540 box would end at 4320, 2790: it ends at 3840, 2790 inside 2880.
    assertEquals(MeterArea(2880, 2250, 960, 540, 1000), Metering.area(MeterPoint(1f, 1f), left, active, Metering.EXPOSURE_SIZE))
  }

  @Test
  fun `the camera's own metering is one region of no weight`() {
    assertEquals(MeterArea(0, 0, 0, 0, 0), Metering.NONE)
  }
}
