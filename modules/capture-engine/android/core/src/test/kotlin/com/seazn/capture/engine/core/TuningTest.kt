package com.seazn.capture.engine.core

import com.seazn.capture.engine.adapter.EncoderKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Spec 2026-11-24-capture-tuning: the operator's two capture choices (owner, 2026-11-24), held by the pairing as the
 * automatic switch is (AGENTS §2: native owns the session, JS reconciles), and handed to the capture as
 * [Command.Tune] at the pair and at every change, so the capture holds the latest for every open (§3).
 */
class TuningTest {
  private fun request(matchSound: Boolean = true, constantBitrate: Boolean = false): PairRequest {
    val r = Configs.pairRequest()
    return PairRequest(
      r.code, r.slot, r.token, r.codeUrl, r.heartbeatUrl, r.startUrl, r.language, r.automatic, r.claim, r.environment,
      matchSound = matchSound,
      constantBitrate = constantBitrate,
    )
  }

  private val MachineRig.tuning: Tuning
    get() = phase.pairing!!.tuning

  @Test
  fun `the defaults are match sound on and constant bitrate off`() {
    assertEquals(Tuning(matchSound = true, constantBitrate = false), Tuning.DEFAULT)
    val r = Configs.pairRequest()
    assertEquals(true, r.matchSound)
    assertEquals(false, r.constantBitrate)
  }

  @Test
  fun `the pair seeds the pairing's tuning and hands it to the capture`() {
    val rig = MachineRig(request = request(matchSound = false, constantBitrate = true))
    rig.permissions = null
    rig.send(Input.Pair(rig.request, Configs.PHONE, stopped = null))
    assertEquals(Tuning(matchSound = false, constantBitrate = true), rig.tuning)
    assertEquals(listOf(Command.Tune(Tuning(matchSound = false, constantBitrate = true))), rig.sent<Command.Tune>())
    val view = rig.snapshot.pairing!!
    assertEquals(Tuning(matchSound = false, constantBitrate = true), view.tuning)
  }

  @Test
  fun `match sound changes while paired - its line, and the capture told`() {
    val rig = MachineRig().paired()
    val before = rig.sent<Command.Tune>().size
    rig.send(Input.SetMatchSound(on = false))
    assertEquals(Tuning(matchSound = false), rig.tuning)
    assertEquals(listOf(RecordEntry("match-sound", listOf("on" to false))), rig.records("match-sound"))
    assertEquals(Command.Tune(Tuning(matchSound = false)), rig.sent<Command.Tune>().drop(before).single())
  }

  @Test
  fun `match sound changes on air too (owner, 2026-11-24)`() {
    val rig = MachineRig().live()
    rig.send(Input.SetMatchSound(on = false))
    assertIs<Phase.OnAir>(rig.phase)
    assertEquals(false, rig.tuning.matchSound)
    assertEquals(Command.Tune(Tuning(matchSound = false)), rig.sent<Command.Tune>().last())
  }

  @Test
  fun `a second call with the same choice writes nothing and tells the capture nothing`() {
    val rig = MachineRig().paired()
    val before = rig.sent<Command.Tune>().size
    rig.send(Input.SetMatchSound(on = true))
    rig.send(Input.SetConstantBitrate(on = false))
    assertEquals(before, rig.sent<Command.Tune>().size)
    assertEquals(emptyList(), rig.records("match-sound") + rig.records("constant-bitrate") + rig.records("intent-ignored"))
  }

  @Test
  fun `constant bitrate changes while paired - its line, and the capture told`() {
    val rig = MachineRig().paired()
    rig.send(Input.SetConstantBitrate(on = true))
    assertEquals(Tuning(constantBitrate = true), rig.tuning)
    assertEquals(listOf(RecordEntry("constant-bitrate", listOf("on" to true))), rig.records("constant-bitrate"))
    assertEquals(Command.Tune(Tuning(constantBitrate = true)), rig.sent<Command.Tune>().last())
  }

  @Test
  fun `constant bitrate is ignored while a broadcast is held, armed or live, and the encoder is never told`() {
    for (rig in listOf(MachineRig().armed(), MachineRig().live())) {
      val phase = rig.phase.name
      val before = rig.sent<Command.Tune>().size
      rig.send(Input.SetConstantBitrate(on = true))
      assertEquals(false, rig.tuning.constantBitrate, phase)
      assertEquals(before, rig.sent<Command.Tune>().size, phase)
      assertEquals("set-constant-bitrate", rig.records("intent-ignored").single().field("intent"), phase)
    }
  }

  @Test
  fun `both are ignored with no pairing`() {
    val idle = MachineRig()
    idle.send(Input.SetMatchSound(on = false))
    idle.send(Input.SetConstantBitrate(on = true))
    assertEquals(listOf("set-match-sound", "set-constant-bitrate"), idle.records("intent-ignored").map { it.field("intent") })
    assertEquals(emptyList(), idle.sent<Command.Tune>())
  }

  @Test
  fun `the bitrate mode - constant only when asked and the encoder lists it`() {
    assertEquals(BitrateMode.VBR, BitrateMode.of(constant = false, supported = true))
    assertEquals(BitrateMode.VBR, BitrateMode.of(constant = false, supported = null))
    assertEquals(BitrateMode.CBR, BitrateMode.of(constant = true, supported = true))
    assertEquals(BitrateMode.UNSUPPORTED, BitrateMode.of(constant = true, supported = false))
    assertEquals(BitrateMode.UNSUPPORTED, BitrateMode.of(constant = true, supported = null), "no encoder found: never asked")
  }

  @Test
  fun `the encoder's keys - B-frames off always, and MediaFormat's CBR (2) only for CBR`() {
    assertEquals(mapOf("max-bframes" to 0), EncoderKeys.video(BitrateMode.VBR))
    assertEquals(mapOf("max-bframes" to 0), EncoderKeys.video(BitrateMode.UNSUPPORTED))
    assertEquals(mapOf("max-bframes" to 0, "bitrate-mode" to 2), EncoderKeys.video(BitrateMode.CBR))
  }

  @Test
  fun `antibanding - AUTO only where the camera lists it, and the result's mode said in words`() {
    assertEquals(true, Antibanding.autoListed(intArrayOf(0, 1, 2, 3)))
    assertEquals(false, Antibanding.autoListed(intArrayOf(0, 1, 2)))
    assertEquals(false, Antibanding.autoListed(null))
    assertEquals(
      listOf(Antibanding.OFF, Antibanding.HZ50, Antibanding.HZ60, Antibanding.AUTO, Antibanding.UNREAD, Antibanding.UNREAD),
      listOf(0, 1, 2, 3, 7, null).map(Antibanding::of),
    )
    assertEquals(listOf("off", "50hz", "60hz", "auto", "unavailable", "unread"), Antibanding.entries.map { it.wire })
  }

  @Test
  fun `the record's lines use the contract's existing keys`() {
    assertEquals(RecordEntry("speech-processing", listOf("state" to "off")), SpeechProcessing.entry(SpeechState.OFF))
    assertEquals(RecordEntry("bitrate-mode", listOf("state" to "unsupported")), BitrateMode.UNSUPPORTED.entry())
    assertEquals(RecordEntry("antibanding", listOf("state" to "50hz")), Antibanding.HZ50.entry())
  }

  @Test
  fun `speech processing is wanted only with match sound off`() {
    assertEquals(false, SpeechProcessing.wanted(Tuning(matchSound = true)))
    assertEquals(true, SpeechProcessing.wanted(Tuning(matchSound = false)))
  }
}
