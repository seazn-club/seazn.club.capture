import type {
  CameraState,
  EngineSnapshot,
  HeartbeatResult,
  IngestState,
  ProgrammePhase,
  RelayOutput,
  ServerFacts,
  Telemetry,
  ThermalStatus,
} from '@/engine/CaptureEnginePort';
import { UNREPORTED } from '@/engine/unreported';
import { formatAge } from '@/i18n/formatAge';
import { formatNumber } from '@/i18n/formatNumber';
import type { MessageKey } from '@/i18n/messages';
import type { Translator } from '@/i18n/translate';

/** `key` is the message the label was translated from: a row is picked by it, never by its text. */
export type DiagnosticsRow = {
  readonly key: MessageKey;
  readonly label: string;
  readonly value: string;
};
export type DiagnosticsSection = {
  readonly title: string;
  readonly rows: readonly DiagnosticsRow[];
};

/** The copy and the numbers, both in the operator's language. */
type Say = { readonly t: Translator['t']; readonly num: (value: number) => string };
type Pair = readonly [MessageKey, string];

/**
 * Spec §4's Diagnostics, as label and value text. Unknown is "—", never a
 * zero: a value the phone has not measured must not read as a measurement.
 * Numbers carry the language's decimal separator and are never grouped
 * (`formatNumber`).
 */
export function diagnosticsSections(
  snapshot: EngineSnapshot,
  nowMs: number,
  translator: Translator,
): DiagnosticsSection[] {
  const { t, lang } = translator;
  const say: Say = { t, num: (value) => formatNumber(value, lang) };
  const tel = snapshot.telemetry;
  const section = (title: MessageKey, pairs: readonly Pair[]): DiagnosticsSection => ({
    title: t(title),
    rows: pairs.map(([key, value]) => ({ key, label: t(key), value })),
  });
  const server = ON_AIR.has(snapshot.state.kind)
    ? [section('diag.section.server', serverRows(tel.server, say))]
    : [];
  return [
    section('diag.section.link', linkRows(snapshot, say)),
    section('diag.section.delivery', deliveryRows(tel, nowMs, say)),
    ...server,
    section('diag.section.device', deviceRows(snapshot, say)),
    section('diag.section.heartbeat', heartbeatRows(tel, nowMs, say)),
    section('diag.section.engine', engineRows(snapshot, nowMs, say)),
  ];
}

const unit = ({ t, num }: Say, key: MessageKey, value: number | null): string =>
  value === null ? t('diag.none') : t(key, { value: num(value) });
/**
 * `atMs` is epoch ms, as `nowMs` is (the port's `deliveryCheckedAtMs`,
 * `lastSentAtEpochMs`). An age is never below zero: a native time a moment
 * ahead of the phone's clock (an NTP step) reads 0, never Intl's "-0" (n1).
 */
const secondsAgo = ({ t, num }: Say, nowMs: number, atMs: number | null): string =>
  atMs === null ? t('diag.none') : formatAge((nowMs - atMs) / 1000, t, num);

/**
 * The uplink. "Sending audio" is the encoded audio packet rate: audio leaving
 * the phone, not the mic's health (carry 12; the meter is that). Native has
 * no rate before air, so armed reads a dash, which is true.
 */
function linkRows(snapshot: EngineSnapshot, say: Say): Pair[] {
  const { t, num } = say;
  const { state, telemetry: tel } = snapshot;
  const named = 'transport' in state ? state.transport.toUpperCase() : t('diag.none');
  // A18: a broadcast planned RTMPS-only says so in its transport ("RTMPS only"); a fallback does not. Only
  // with a transport to qualify: arming, armed and reconnecting name none, and "— only" says nothing.
  // Short on purpose: it shares a row with its label (settingsFit.test.ts measures every language).
  const transport =
    snapshot.broadcast?.srtOffered === false && 'transport' in state
      ? t('diag.srtNotOffered', { transport: named })
      : named;
  const { srt } = tel;
  return [
    ['diag.transport', transport],
    ['diag.bitrate', unit(say, 'diag.unit.kbps', tel.bitrateKbps)],
    ['diag.target', unit(say, 'diag.unit.kbps', tel.targetBitrateKbps)],
    ['diag.fps', unit(say, 'diag.unit.fps', tel.encodedVideoFps)],
    ['diag.audio', unit(say, 'diag.unit.pps', tel.audioPacketsPerSecond)],
    ['diag.rtt', unit(say, 'diag.unit.ms', srt?.rttMs ?? null)],
    [
      'diag.resent',
      srt === null
        ? t('diag.none')
        : t('diag.unit.ofSent', { value: num(srt.retransmitted), total: num(srt.sent) }),
    ],
    ['diag.dropped', srt === null ? t('diag.none') : num(srt.dropped)],
  ];
}

const DELIVERY: Readonly<Record<Telemetry['delivery'], MessageKey>> = {
  ok: 'diag.delivery.ok',
  stalled: 'diag.delivery.stalled',
  unknown: 'diag.delivery.unknown',
};

function deliveryRows(tel: Telemetry, nowMs: number, say: Say): Pair[] {
  const lag = tel.deliveredLagMs === null ? null : tel.deliveredLagMs / 1000;
  return [
    ['diag.delivery', say.t(DELIVERY[tel.delivery])],
    ['diag.lag', unit(say, 'diag.unit.seconds', lag)],
    ['diag.checked', secondsAgo(say, nowMs, tel.deliveryCheckedAtMs)],
    ['diag.data', unit(say, 'diag.unit.mb', Math.round(tel.dataUsedBytes / 1_000_000))],
  ];
}

/** T46 (design 2026-11-24 §3): the server's rows show on air only, a broadcast in trouble included. */
const ON_AIR: ReadonlySet<EngineSnapshot['state']['kind']> = new Set([
  'publishing',
  'degraded',
  'reconnecting',
]);

const RELAY: Readonly<Record<RelayOutput, MessageKey>> = {
  connecting: 'diag.relay.connecting',
  connected: 'diag.relay.connected',
  backoff: 'diag.relay.backoff',
};

const PROGRAMME: Readonly<Record<ProgrammePhase, MessageKey>> = {
  waiting: 'diag.programme.waiting',
  live: 'diag.programme.live',
  frozen: 'diag.programme.frozen',
  reconnecting: 'diag.programme.reconnecting',
  final: 'diag.programme.final',
};

const INGEST: Readonly<Record<IngestState, MessageKey>> = {
  connected: 'diag.ingest.connected',
  disconnected: 'diag.ingest.disconnected',
  errored: 'diag.ingest.errored',
};

/**
 * What the relay and Cloudflare last said (T46). Diagnostics only: the phone's own Viewers receiving stays the truth.
 * A programme that is live, or whose phase is not known, reads as its rate when both numbers are known: the rate is
 * what a live programme has to say, and its word with the rate does not fit the row at 640 (settingsFit.test.ts). Any
 * other phase is its word, which is the news.
 */
function serverRows(facts: ServerFacts | null, { t, num }: Say): Pair[] {
  const word = <K extends string>(
    table: Readonly<Record<K, MessageKey>>,
    key: K | null | undefined,
  ) => (key === null || key === undefined ? t('diag.none') : t(table[key]));
  const phase = facts?.programmePhase ?? null;
  const fps = facts?.fps ?? null;
  const kbps = facts?.kbps ?? null;
  const rated = (phase === null || phase === 'live') && fps !== null && kbps !== null;
  const programme = rated
    ? t('diag.programme.rate', { fps: num(fps), kbps: num(kbps) })
    : word(PROGRAMME, phase);
  return [
    ['diag.relay', word(RELAY, facts?.relayOutput)],
    ['diag.programme', programme],
    ['diag.cameraIngest', word(INGEST, facts?.cameraIngest)],
    ['diag.programmeIngest', word(INGEST, facts?.programmeIngest)],
  ];
}

/** Carry 13: whose camera it is, plan B's `Snapshot.camera`; null with no session. */
const CAMERA: Readonly<Record<CameraState, MessageKey>> = {
  own: 'diag.camera.own',
  taken: 'diag.camera.taken',
  reopening: 'diag.camera.reopening',
  resuming: 'diag.camera.resuming',
  switching: 'diag.camera.switching',
};

/** Heat is a device condition here, never a broadcast state (AGENTS §8). */
const THERMAL: Readonly<Record<ThermalStatus, MessageKey>> = {
  none: 'diag.thermal.none',
  light: 'diag.thermal.light',
  moderate: 'diag.thermal.moderate',
  severe: 'diag.thermal.severe',
  critical: 'diag.thermal.critical',
  emergency: 'diag.thermal.emergency',
  shutdown: 'diag.thermal.shutdown',
};

function deviceRows({ camera, telemetry: tel }: EngineSnapshot, say: Say): Pair[] {
  const { t } = say;
  const named = <K extends string>(table: Readonly<Record<K, MessageKey>>, key: K | null) =>
    key === null ? t('diag.none') : t(table[key]);
  const charging =
    tel.charging === null ? t('diag.none') : t(tel.charging ? 'diag.yes' : 'diag.no');
  return [
    ['diag.camera', named(CAMERA, camera)],
    ['diag.battery', unit(say, 'diag.unit.percent', tel.batteryPercent)],
    ['diag.drain', unit(say, 'diag.unit.perHour', tel.drainPctPerHour)],
    ['diag.charging', charging],
    ['diag.thermal', named(THERMAL, tel.thermalStatus)],
  ];
}

const HEARTBEAT: Readonly<Record<HeartbeatResult, MessageKey>> = {
  ok: 'diag.hb.ok',
  failed: 'diag.hb.failed',
  'session-over': 'diag.hb.sessionOver',
};

/** Counted, never blocking (ruling 5): Diagnostics is the only place it shows. */
function heartbeatRows(tel: Telemetry, nowMs: number, say: Say): Pair[] {
  const { t, num } = say;
  const { lastSentAtEpochMs, lastResult, failures } = tel.heartbeat;
  const result = lastResult === null ? t('diag.none') : t(HEARTBEAT[lastResult]);
  return [
    ['diag.hbSent', secondsAgo(say, nowMs, lastSentAtEpochMs)],
    ['diag.hbResult', result],
    ['diag.hbFailures', num(failures)],
  ];
}

/**
 * Carry 12 and CD17: when native last reported, and the record lines its sink
 * refused or its re-entrancy guard dropped. `UNREPORTED`'s 0 is "nothing yet",
 * never a report decades ago. Drift is not silence (review C4c I-4): a refused
 * snapshot keeps this current and leaves `engine.malformed-snapshot` in the
 * record below; silence lets it climb.
 */
function engineRows(snapshot: EngineSnapshot, nowMs: number, say: Say): Pair[] {
  const { reportedAtMs, telemetry: tel } = snapshot;
  return [
    [
      'diag.lastReport',
      secondsAgo(say, nowMs, reportedAtMs === UNREPORTED.reportedAtMs ? null : reportedAtMs),
    ],
    ['diag.recordRefused', say.num(tel.recordSinkFailures)],
    ['diag.recordDropped', say.num(tel.recordReentrantDropped)],
  ];
}
