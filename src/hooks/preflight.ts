import { firstNotReady, type NotReady } from '@/domain/session/Pairing';
import type { SessionState } from '@/domain/session/SessionState';
import type { AirStage } from '@/domain/session/viewersConfirmed';
import type { EngineSnapshot } from '@/engine/CaptureEnginePort';
import type { MessageKey } from '@/i18n/messages';

/** The pre-flight chips (amendment §2): camera, sound and network. Held has no chip; it blocks. */
export type Chip = 'camera' | 'sound' | 'network';
export type Preflight = Readonly<Record<Chip, boolean>>;

/** Spec §1's order on screen: camera, sound, network. */
export const CHIP_ORDER: readonly Chip[] = ['camera', 'sound', 'network'];

/** What keeps Go live off: one of native's readiness checks (A11). */
export type Blocker = NotReady;

/**
 * M2: ready only when native says the camera is ready AND it is ours. Another
 * app's take, our own reopen, resume or switch, and no pairing all read not
 * ready, so the chip, the plate and Go live never sit READY beside a camera line.
 */
export const selectCameraReady = (snapshot: EngineSnapshot) =>
  snapshot.ready.camera && snapshot.camera === 'own';
export const selectNetworkReachable = (snapshot: EngineSnapshot) => snapshot.ready.network;
/** Native's verdict (A11): the mic is available. The level never decides it (design follow-up §4). */
export const selectSoundReady = (snapshot: EngineSnapshot) => snapshot.ready.sound;

export type BlockerKey = Extract<MessageKey, `stream.blocker.${string}`>;

const BLOCKER_REASON: Readonly<Record<Blocker, BlockerKey>> = {
  camera: 'stream.blocker.camera',
  sound: 'stream.blocker.sound',
  network: 'stream.blocker.network',
  held: 'stream.blocker.held',
};

/**
 * Native's verdict (A11), in native's order (`firstNotReady`), so the blocker
 * and native's `notReady` never disagree. A camera that is not ours blocks as
 * the camera even when native's check has not caught up (M2).
 */
export const selectBlocker = (snapshot: EngineSnapshot): Blocker | null => {
  const blocker = firstNotReady(snapshot.ready);
  return blocker === null && snapshot.camera !== 'own' ? 'camera' : blocker;
};

/** Why Go live is off, at the control (ruling I3). */
export function blockerReason(blocker: Blocker | null): BlockerKey | null {
  return blocker === null ? null : BLOCKER_REASON[blocker];
}

export type TallyPlate =
  'starting' | 'notReady' | 'ready' | 'connecting' | 'goingLive' | 'live' | 'trouble' | 'ended';

/** `going` is Going live's own: a dark plate with a red outline, never the red fill (owner, 2026-11-26). */
export type TallyTone = 'live' | 'going' | 'healthy' | 'degraded' | 'inert';

/**
 * Spec §4's state plate (D14). The LIVE gate: LIVE only while publishing —
 * connecting is native saying frames are not yet advancing (F-P5-6). Arming
 * and Armed read as connecting: the amendment names no plate of their own, and
 * their line says who started it or what it waits for (Task 28). `backupOnly` is a degraded session whose one reason
 * is the fall to RTMPS (`selectBackupOnly`): the broadcast is publishing, so it reads LIVE.
 *
 * Red LIVE means viewers can see the picture (owner, 2026-11-26): a plate that would read LIVE reads GOING LIVE while
 * `stage` says the viewers' picture is not yet confirmed (`airStage`), on the main link and the backup alike. Trouble
 * stays trouble whatever the stage.
 */
export function tallyPlateFor(
  kind: SessionState['kind'],
  ready: boolean,
  backupOnly = false,
  stage: AirStage = 'live',
): TallyPlate {
  const plate = plateOf(kind, ready, backupOnly);
  return plate === 'live' && stage === 'goingLive' ? 'goingLive' : plate;
}

function plateOf(kind: SessionState['kind'], ready: boolean, backupOnly: boolean): TallyPlate {
  switch (kind) {
    case 'idle':
    case 'pairing':
      return 'starting';
    case 'paired':
      return ready ? 'ready' : 'notReady';
    case 'arming':
    case 'armed':
    case 'connecting':
      return 'connecting';
    case 'publishing':
      return 'live';
    case 'degraded':
      // Only the backup link in use: frames advance cleanly, the line says which link (owner, 2026-11-26).
      return backupOnly ? 'live' : 'trouble';
    case 'reconnecting':
      return 'trouble';
    case 'ended':
      return 'ended';
  }
}

/** The only place a plate becomes a colour. Red is LIVE and nothing else (AGENTS §5); Going live is outlined in it. */
export function tallyTone(plate: TallyPlate): TallyTone {
  switch (plate) {
    case 'live':
      return 'live';
    case 'goingLive':
      return 'going';
    case 'ready':
      return 'healthy';
    case 'trouble':
      return 'degraded';
    default:
      return 'inert';
  }
}
