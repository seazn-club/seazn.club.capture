import {
  IDLE_GATE,
  doorStep,
  onPing,
  onTrail,
  retryWaitMs,
  type PingGate,
} from '@/domain/matchday/nudge';
import type { MatchDayTokenAnswer } from '@/domain/matchday/realtimeWire';
import type { SavedDayCode } from '@/domain/mode/dayCode';
import type { Ports } from '@/hooks/usePorts';

export type NudgePorts = Pick<
  Ports,
  'matchDay' | 'matchDayRealtime' | 'foreground' | 'logger' | 'clock' | 'steadyClock'
>;

const OFFLINE: MatchDayTokenAnswer = { ok: false, offline: true };

function createTimer(fire: () => void) {
  let timer: ReturnType<typeof setTimeout> | null = null;
  const cancel = (): void => {
    if (timer !== null) clearTimeout(timer);
    timer = null;
  };
  return {
    cancel,
    start: (ms: number): void => {
      cancel();
      timer = setTimeout(() => {
        timer = null;
        fire();
      }, ms);
    },
  };
}

/**
 * The one owner of the day list's nudge channel (phone design 2026-11-24 §4). A nudge is a ping, never data: it makes a
 * `read` of the day, at most one per 4 s plus a trailing one (`onPing`), and so does the channel coming up (pings in the
 * gap were lost). The 15 s poll is the list's own and is never touched here.
 *
 * - Each join takes a fresh grant from the door, refreshed at 80% of its actual lifetime (`doorStep`).
 * - A channel that drops, a door that fails or a grant too short to join by: poll only, and ask the door again after the
 *   backoff. A 429 waits its Retry-After. A code that is over (410) stops for good: the door is not asked again for
 *   this code, a return to the foreground included.
 * - In the background the channel is closed and nothing is timed; the return asks the door again.
 * - The grant's token is held by the port's subscription alone: it is never stored, said or put in a URL here.
 */
export function startMatchDayNudge(
  ports: NudgePorts,
  code: SavedDayCode,
  read: () => void,
): () => void {
  let round = 0;
  let failures = 0;
  let stopped = false;
  let gate: PingGate = IDLE_GATE;
  let leave: (() => void) | null = null;
  const trail = createTimer(() => {
    const step = onTrail(gate, ports.steadyClock.nowMs());
    gate = step.gate;
    if (step.read) read();
  });
  const next = createTimer(() => void door());

  const ping = (): void => {
    const step = onPing(gate, ports.steadyClock.nowMs());
    gate = step.gate;
    if (step.read) read();
    if (step.trailInMs !== null) trail.start(step.trailInMs);
  };
  const close = (): void => {
    leave?.();
    leave = null;
  };
  const up = (): void => {
    failures = 0;
    ping();
  };
  const down = (): void => {
    close();
    next.start(retryWaitMs(failures));
    failures += 1;
  };
  const door = async (): Promise<void> => {
    if (stopped || !ports.foreground.isForeground()) return;
    const mine = (round += 1);
    const answer = await askDoor(ports, code);
    if (mine !== round) return;
    const step = doorStep(answer, failures, ports.clock().getTime());
    if (step.kind === 'stop') {
      stopped = true;
      close();
      trail.cancel();
      ports.logger.info('matchday.nudge', { state: 'stopped' });
      return;
    }
    if (step.kind === 'retry') {
      failures += 1;
      next.start(step.inMs);
      return;
    }
    const { token, topic, url, apikey } = step.grant;
    leave = ports.matchDayRealtime.subscribe(
      { token, channel: topic, url, apikey },
      { onPing: ping, onUp: up, onDown: down },
    );
    next.start(step.refreshInMs);
  };
  const away = (): void => {
    round += 1;
    close();
    trail.cancel();
    next.cancel();
  };

  const listening = [
    // A return while a channel is held (a repeated foreground event) asks nothing more: a held channel always has its
    // refresh timed or in flight, so the door is already accounted for.
    ports.foreground.subscribe(() => {
      if (leave !== null) return;
      void door();
    }),
    ports.foreground.subscribeBackground(away),
  ];
  void door();
  return () => {
    away();
    listening.forEach((unsubscribe) => unsubscribe());
  };
}

/** The door's answer; a port that throws (none should) is offline, said in a closed word. */
async function askDoor(ports: NudgePorts, code: SavedDayCode): Promise<MatchDayTokenAnswer> {
  try {
    return await ports.matchDay.realtimeToken(code);
  } catch {
    ports.logger.warn('matchday.nudge', { state: 'door', problem: 'threw' });
    return OFFLINE;
  }
}
