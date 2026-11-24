package com.seazn.capture.engine.core

/** One camera's optical image stabilisation (OIS), as the record says it: on, or not on this camera. */
enum class Stabiliser(val wire: String) {
  ON("on"),
  UNAVAILABLE("unavailable"),
}

/**
 * Spec 2026-11-24-camera-stabilisation §3 (owner, 2026-11-24: "OIS only, drop the EIS setting entirely"): OIS is on
 * wherever the camera has it, with no setting. Electronic stabilisation is not used: it crops the picture.
 */
object Stabilisation {
  const val KIND = "stabilisation"

  /** OIS on where the camera has it; said unavailable where it has not, and never set there. */
  fun optical(available: Boolean): Stabiliser = if (available) Stabiliser.ON else Stabiliser.UNAVAILABLE

  /** The record's line for a camera's OIS, with the record contract's own keys (`source`, `state`). */
  fun entry(optical: Stabiliser): RecordEntry = RecordEntry(KIND, listOf("source" to "optical", "state" to optical.wire))
}
