import type { MatchDayGrant, MatchDayTokenAnswer } from '@/domain/matchday/realtimeWire';

/**
 * The Match Day nudge's rules (phone design 2026-11-24 §4; web spec §3), pure so the hook's timers only carry them out.
 * A nudge is a ping, never data: it is only ever a reason to read the day again, and the 15 s poll stays the liveness.
 */

/** Ping-driven reads come at most this often (web spec §3, "Read storms"), plus one trailing read. */
export const PING_GAP_MS = 4_000;
/** A grant is refreshed at this share of its actual lifetime (`expiresAt` less now; the server's TTL is min(60 min, the code's end)). */
export const REFRESH_SHARE = 0.8;
/** A grant with less than this to live is not joined: it would expire before the join settled. */
export const MIN_GRANT_MS = 5_000;

/** `lastReadAt`: when a ping last made a read; `trailing`: a read is waiting for the gap to end. */
export type PingGate = { readonly lastReadAt: number | null; readonly trailing: boolean };
export const IDLE_GATE: PingGate = { lastReadAt: null, trailing: false };

/** `read`: read now. `trailInMs`: start the trailing timer, firing after this long (null: none to start). */
export type PingStep = {
  readonly gate: PingGate;
  readonly read: boolean;
  readonly trailInMs: number | null;
};

/**
 * A ping at `now`: a read at once when the last ping-driven read is 4 s old or more (or there was none); otherwise one
 * trailing read at the end of the gap, and any ping before it fires is folded into it. A clock that steps back waits at
 * most the gap.
 */
export function onPing(gate: PingGate, now: number): PingStep {
  const since = gate.lastReadAt === null ? Infinity : now - gate.lastReadAt;
  if (since >= PING_GAP_MS) {
    return { gate: { lastReadAt: now, trailing: false }, read: true, trailInMs: null };
  }
  if (gate.trailing) return { gate, read: false, trailInMs: null };
  const wait = Math.min(Math.max(PING_GAP_MS - since, 0), PING_GAP_MS);
  return { gate: { ...gate, trailing: true }, read: false, trailInMs: wait };
}

/** The trailing timer fired at `now`: the read that waited, if one did. */
export function onTrail(gate: PingGate, now: number): { gate: PingGate; read: boolean } {
  if (!gate.trailing) return { gate, read: false };
  return { gate: { lastReadAt: now, trailing: false }, read: true };
}

/** When to ask the door for a fresh grant: 80% of what is left of this one, or null when too little is left to join by. */
export function refreshInMs(expiresAt: Date, now: number): number | null {
  const left = expiresAt.getTime() - now;
  if (!(left >= MIN_GRANT_MS)) return null;
  return Math.floor(left * REFRESH_SHARE);
}

const WAITS_MS: readonly number[] = [5_000, 15_000, 30_000, 60_000, 120_000, 300_000];

/** The wait before the door is asked again after `failures` failed rounds in a row (0 for the first). */
export function retryWaitMs(failures: number): number {
  const whole = Number.isFinite(failures) ? Math.trunc(failures) : 0;
  return WAITS_MS[Math.min(Math.max(whole, 0), WAITS_MS.length - 1)];
}

export type DoorStep =
  | { readonly kind: 'join'; readonly grant: MatchDayGrant; readonly refreshInMs: number }
  | { readonly kind: 'stop' }
  | { readonly kind: 'retry'; readonly inMs: number };

/** A code that is over: the door is never asked again for it (web, 2026-11-24: 410 for good, 401 stop). */
const OVER: ReadonlySet<string> = new Set(['MD_WITHDRAWN', 'MD_ENDED', 'MD_UNKNOWN']);

/**
 * What a door answer means. A grant joins; a code that is over stops for good; a 429 waits its Retry-After; MD_NOT_OPEN,
 * offline, a garbled answer and a grant too short to join by all poll only and ask again after the backoff.
 */
export function doorStep(answer: MatchDayTokenAnswer, failures: number, now: number): DoorStep {
  const backoff: DoorStep = { kind: 'retry', inMs: retryWaitMs(failures) };
  if (answer.ok) {
    const refresh = refreshInMs(answer.value.expiresAt, now);
    return refresh === null ? backoff : { kind: 'join', grant: answer.value, refreshInMs: refresh };
  }
  if ('refusal' in answer) return OVER.has(answer.refusal) ? { kind: 'stop' } : backoff;
  if ('retryAfterMs' in answer && answer.retryAfterMs !== null) {
    return { kind: 'retry', inMs: answer.retryAfterMs };
  }
  return backoff;
}
