package com.seazn.capture.engine.endpoints

import android.media.MediaFormat
import com.seazn.capture.engine.adapter.FrameTally
import io.github.thibaultbee.streampack.core.elements.data.Frame
import io.github.thibaultbee.streampack.core.elements.endpoints.IEndpointInternal
import io.github.thibaultbee.streampack.core.utils.InternalStreamPackApi

/**
 * F-P5-4: counts the encoded frames that reached the endpoint, video apart from audio, then writes them. Spec
 * 2026-11-25-encoder-diagnostics: each video frame is shown to the [encoder] tap first, which reads it through duplicates
 * and never throws, so what is written on is the frame as it came.
 * StreamPack 3.2.1 marks `IEndpointInternal` `@SubclassOptInRequired(InternalStreamPackApi)` (javap): this
 * programme implements it on purpose (CD14, CD15).
 */
@OptIn(InternalStreamPackApi::class)
class CountingEndpoint(
  private val inner: IEndpointInternal,
  private val tally: FrameTally,
  private val clock: CaptureClockLine,
  private val encoder: EncoderTap,
) : IEndpointInternal by inner {
  override suspend fun write(frame: Frame, streamPid: Int) {
    val mime = frame.format.getString(MediaFormat.KEY_MIME)
    tally.counted(mime, frame.rawBuffer.remaining())
    if (mime?.startsWith("video/") == true) {
      clock.videoFrame(frame.ptsInUs)
      encoder.videoFrame(frame)
    }
    inner.write(frame, streamPid)
  }
}
