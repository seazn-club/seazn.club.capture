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
  fun `a drag at 1x, or of no number, moves nothing`() {
    assertEquals(Pan.CENTRE, Pan.drag(Pan.CENTRE, 0.3f, 0.3f, 1f))
    assertEquals(Pan.CENTRE, Pan.drag(Pan.CENTRE, Float.NaN, 0.1f, 2f))
    assertEquals(Pan.CENTRE, Pan.drag(Pan.CENTRE, 0.1f, Float.POSITIVE_INFINITY, 2f))
  }

  @Test
  fun `a drag moves the window the other way, 1 to 1 of the visible picture`() {
    // At 2x the window is half the field: a finger across 0.2 of the picture moves the window 0.1 of the field.
    assertEquals(PanCentre(0.4f, 0.5f), Pan.drag(Pan.CENTRE, 0.2f, 0f, 2f))
    assertEquals(PanCentre(0.5f, 0.6f), Pan.drag(Pan.CENTRE, 0f, -0.2f, 2f))
    // At 4x, the same finger moves it 0.05.
    assertEquals(PanCentre(0.45f, 0.5f), Pan.drag(Pan.CENTRE, 0.2f, 0f, 4f))
  }

  @Test
  fun `a drag is clamped to the picture's field, however far the finger goes`() {
    assertEquals(PanCentre(0.25f, 0.75f), Pan.drag(Pan.CENTRE, 5f, -5f, 2f), "at 2x the centre keeps 0.25 from each edge")
    assertEquals(PanCentre(0.75f, 0.25f), Pan.drag(Pan.CENTRE, -5f, 5f, 2f))
  }

  @Test
  fun `zooming out brings the centre back inside, and to the middle at 1x`() {
    assertEquals(PanCentre(0.125f, 0.5f), Pan.clamp(PanCentre(0.05f, 0.5f), 4f), "at 4x the centre keeps 0.125 from each edge")
    assertEquals(PanCentre(0.25f, 0.5f), Pan.clamp(PanCentre(0.1f, 0.5f), 2f))
    assertEquals(Pan.CENTRE, Pan.clamp(PanCentre(0.25f, 0.75f), 1f))
  }

  @Test
  fun `at 1x the frame is the whole array, and the ratio sent is 1 at every zoom`() {
    assertEquals(PanFrame(1f, null), Pan.frame(PanCentre(0.2f, 0.2f), 1f, left, active), "at 1x nothing pans")
    for (zoom in listOf(1.5f, 2f, 4f, 8f)) assertEquals(1f, Pan.frame(PanCentre(0.3f, 0.6f), zoom, left, active).ratio, "option A: a crop, never a ratio ($zoom)")
  }

  @Test
  fun `a centred zoom is the window of the field about its middle, in the active array`() {
    // 2x on the 3840 x 2160 field (360 above and below): a 1920 x 1080 window about (1920, 1440).
    assertEquals(PanFrame(1f, CropRect(960, 900, 1920, 1080)), Pan.frame(Pan.CENTRE, 2f, left, active))
  }

  @Test
  fun `a window at the field's left edge starts at the field's left`() {
    // Centre x 0.25 of the field = 960, so the window's left edge is 960 - 960 = 0.
    assertEquals(PanFrame(1f, CropRect(0, 900, 1920, 1080)), Pan.frame(PanCentre(0.25f, 0.5f), 2f, left, active))
  }

  @Test
  fun `a window off centre is the same size about the centre that was asked`() {
    // Centre x 0.4 = 1536, y 1440: left 1536 - 960 = 576, top 900.
    assertEquals(PanFrame(1f, CropRect(576, 900, 1920, 1080)), Pan.frame(PanCentre(0.4f, 0.5f), 2f, left, active))
  }

  @Test
  fun `a centre beyond the field is brought inside it, not left to the array's edge`() {
    // y 0 of the field is 360 on the sensor: the 1080 window's top is 360, not the array's 0.
    assertEquals(CropRect(960, 360, 1920, 1080), Pan.frame(PanCentre(0.5f, 0f), 2f, left, active).crop)
  }

  @Test
  fun `the whole rectangle stays inside the array when rounding would push it past`() {
    // 3999 x 2999: 2x is 1999.5 wide; its left 1999.5 and width both round up to 2000, ending at 4000. Moved in by one.
    val odd = active.copy(width = 3999, height = 2999)
    assertEquals(CropRect(1999, 937, 2000, 1125), Pan.frame(PanCentre(0.75f, 0.5f), 2f, left, odd).crop)
  }

  @Test
  fun `held the other way round the same drag lands on the other side of the sensor`() {
    // Held right the turn is 180: the picture's x 0.25 is the sensor's 0.75, 2880. The window's left is 2880 - 960.
    assertEquals(PanFrame(1f, CropRect(1920, 900, 1920, 1080)), Pan.frame(PanCentre(0.25f, 0.5f), 2f, right, active))
  }

  @Test
  fun `the zoom the crop shows reads back as field width over crop width, to a tenth`() {
    // 1.6x: the window is 3840 / 1.6 = 2400 wide, and 3840 / 2400 = 1.6, which is what the record line says.
    val crop = checkNotNull(Pan.frame(Pan.CENTRE, 1.6f, left, active).crop)
    assertEquals(2400, crop.width)
    assertEquals(1.6, (3840.0 / crop.width * 10).let { Math.round(it) / 10.0 })
    assertEquals(RecordEntry("zoom", listOf("state" to 1.6)), Zoom.entry(1.6f))
  }

  @Test
  fun `a tap on a zoomed frame is a place in the crop, and a tap after a pan meters the tapped place`() {
    // 2x about x 0.4 (sensor 1536, y 1440): the picture's middle is that centre. A focus region is 1/6 of the picture,
    // so 1/12 of the field: 320 x 180, left 1536 - 160, top 1440 - 90.
    assertEquals(MeterArea(1376, 1350, 320, 180, 1000), Metering.area(MeterPoint(0.5f, 0.5f), left, active, Metering.FOCUS_SIZE, 2f, PanCentre(0.4f, 0.5f)))
    // A tap left of the middle by 0.25 of the picture is 0.125 of the field: x 0.275 = 1056, so left 896. Inside the
    // window (576 to 2496), never past it.
    assertEquals(MeterArea(896, 1350, 320, 180, 1000), Metering.area(MeterPoint(0.25f, 0.5f), left, active, Metering.FOCUS_SIZE, 2f, PanCentre(0.4f, 0.5f)))
    // The edges of the picture are the edges of the window: x 0 is 576, x 1 is 2496.
    assertEquals(576, Metering.area(MeterPoint(0f, 0.5f), left, active, 0f, 2f, PanCentre(0.4f, 0.5f)).left)
    assertEquals(2496, Metering.area(MeterPoint(1f, 0.5f), left, active, 0f, 2f, PanCentre(0.4f, 0.5f)).left)
  }

  @Test
  fun `the camera's own metering is one region of no weight`() {
    assertEquals(MeterArea(0, 0, 0, 0, 0), Metering.NONE)
  }
}
