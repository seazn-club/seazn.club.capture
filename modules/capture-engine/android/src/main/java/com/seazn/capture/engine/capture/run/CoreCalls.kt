package com.seazn.capture.engine.capture.run

import com.seazn.capture.engine.core.Input
import com.seazn.capture.engine.core.PairRequest
import com.seazn.capture.engine.core.RecordEntry

/** What the capture reports to, every one of them BridgeCore's (Task 20). Each is safe from any thread. */
class CoreCalls(
  val report: (Input) -> Unit,
  val failure: (Throwable) -> Unit,
  val pairFailed: (PairRequest, Throwable) -> Unit,
  val cameraBusy: (PairRequest) -> Unit,
  val readiness: (cameraFrames: Boolean, audioPeak: Double?) -> Unit,
  val line: (RecordEntry) -> Unit,
  /**
   * Spec 2026-11-25-zoom-and-metering §3: the machine holds a broadcast, from Arming on (`Phase.holdsBroadcast`, as
   * BridgeCore last published it). The pinch zoom is locked while it does.
   */
  val broadcastHeld: () -> Boolean,
)
