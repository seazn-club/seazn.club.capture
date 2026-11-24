import { describe, expect, it } from 'vitest';
import {
  IDLE_GATE,
  doorStep,
  onPing,
  onTrail,
  refreshInMs,
  retryWaitMs,
  type PingGate,
} from '@/domain/matchday/nudge';
import type { MatchDayGrant, MatchDayTokenAnswer } from '@/domain/matchday/realtimeWire';

const NOW = Date.parse('2026-10-03T13:00:00Z');
const MIN = 60_000;

/** Made up. */
const grantFor = (ttlMs: number): MatchDayGrant => ({
  token: 'eyJmYWtl.eyJtZC1mYWtl.ZmFrZQ',
  topic: 'match-day:3c1d2e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f',
  url: 'https://abcdwxyz.supabase.example',
  apikey: 'sb-fake-anon-key',
  expiresAt: new Date(NOW + ttlMs),
});

/** Plays a run of pings at the given offsets (ms from NOW), firing any trailing read when its time comes. */
function play(offsets: readonly number[], until: number): number[] {
  let gate: PingGate = IDLE_GATE;
  let trailAt: number | null = null;
  const reads: number[] = [];
  const events = [...offsets.map((at) => ({ at, kind: 'ping' as const }))];
  for (let t = 0; t <= until; t += 1) {
    if (trailAt === t) {
      const step = onTrail(gate, NOW + t);
      gate = step.gate;
      if (step.read) reads.push(t);
      trailAt = null;
    }
    for (const event of events.filter((e) => e.at === t)) {
      void event;
      const step = onPing(gate, NOW + t);
      gate = step.gate;
      if (step.read) reads.push(t);
      if (step.trailInMs !== null) trailAt = t + step.trailInMs;
    }
  }
  return reads;
}

describe('the ping gate: at most one read per 4 s, plus a trailing read', () => {
  it('a lone ping reads at once', () => {
    expect(play([0], 10_000)).toEqual([0]);
  });

  it('a burst inside 4 s gives two reads: one at once and one trailing at 4 s', () => {
    expect(play([0, 100, 900, 2_000, 3_999], 20_000)).toEqual([0, 4_000]);
  });

  it('a second ping after the gap reads at once again', () => {
    expect(play([0, 4_000], 20_000)).toEqual([0, 4_000]);
    expect(play([0, 5_000], 20_000)).toEqual([0, 5_000]);
  });

  it('a ping just inside the gap waits for the gap, never reads twice inside it', () => {
    expect(play([0, 3_999], 20_000)).toEqual([0, 4_000]);
  });

  it('a steady stream of pings reads once per 4 s, never more', () => {
    const pings = Array.from({ length: 100 }, (_, i) => i * 200);
    const reads = play(pings, 30_000);
    for (let i = 1; i < reads.length; i += 1) {
      expect(reads[i] - reads[i - 1]).toBeGreaterThanOrEqual(4_000);
    }
    expect(reads).toEqual([0, 4_000, 8_000, 12_000, 16_000, 20_000]);
  });

  it('a ping while a trailing read waits starts no second timer', () => {
    const first = onPing(IDLE_GATE, NOW);
    const second = onPing(first.gate, NOW + 1_000);
    expect(second.trailInMs).toBe(3_000);
    const third = onPing(second.gate, NOW + 2_000);
    expect(third).toEqual({ gate: second.gate, read: false, trailInMs: null });
  });

  it('a trailing timer with nothing waiting reads nothing', () => {
    expect(onTrail(IDLE_GATE, NOW)).toEqual({ gate: IDLE_GATE, read: false });
  });

  it('a clock that steps back still waits at most 4 s', () => {
    const first = onPing(IDLE_GATE, NOW);
    const second = onPing(first.gate, NOW - 60_000);
    expect(second.read).toBe(false);
    expect(second.trailInMs).toBe(4_000);
  });
});

describe('refreshInMs: at 80% of the grant’s ACTUAL lifetime', () => {
  it('a 60 min grant is refreshed at 48 min', () => {
    expect(refreshInMs(grantFor(60 * MIN).expiresAt, NOW)).toBe(48 * MIN);
  });

  it('a grant cut short by the code’s own end (10 min left) is refreshed at 8 min, not 48', () => {
    expect(refreshInMs(grantFor(10 * MIN).expiresAt, NOW)).toBe(8 * MIN);
  });

  it('a grant with under 5 s left, or already gone, is no grant to join by', () => {
    expect(refreshInMs(grantFor(4_999).expiresAt, NOW)).toBeNull();
    expect(refreshInMs(grantFor(0).expiresAt, NOW)).toBeNull();
    expect(refreshInMs(grantFor(-MIN).expiresAt, NOW)).toBeNull();
    expect(refreshInMs(grantFor(5_000).expiresAt, NOW)).toBe(4_000);
  });
});

describe('retryWaitMs: the door and a dropped channel back off to five minutes', () => {
  it('grows, then holds', () => {
    expect([0, 1, 2, 3, 4, 5, 6, 50].map(retryWaitMs)).toEqual([
      5_000, 15_000, 30_000, 60_000, 120_000, 300_000, 300_000, 300_000,
    ]);
  });

  it('reads nonsense as the first wait', () => {
    expect(retryWaitMs(-3)).toBe(5_000);
    expect(retryWaitMs(Number.NaN)).toBe(5_000);
  });
});

describe('doorStep: what a door answer means for the subscription', () => {
  const refused = (refusal: 'MD_UNKNOWN' | 'MD_WITHDRAWN' | 'MD_ENDED' | 'MD_NOT_OPEN') =>
    ({ ok: false, refusal }) as MatchDayTokenAnswer;

  it('a grant joins, and says when to refresh it', () => {
    const grant = grantFor(60 * MIN);
    expect(doorStep({ ok: true, value: grant }, 0, NOW)).toEqual({
      kind: 'join',
      grant,
      refreshInMs: 48 * MIN,
    });
  });

  it('a grant already expired is retried after the backoff, never joined', () => {
    expect(doorStep({ ok: true, value: grantFor(-1) }, 2, NOW)).toEqual({
      kind: 'retry',
      inMs: 30_000,
    });
  });

  it.each(['MD_WITHDRAWN', 'MD_ENDED'] as const)(
    '%s stops for good: the code is over, so the door is never asked again',
    (word) => {
      expect(doorStep(refused(word), 0, NOW)).toEqual({ kind: 'stop' });
    },
  );

  it.each(['MD_NOT_OPEN', 'MD_UNKNOWN'] as const)(
    '%s polls only and asks the door again later, with backoff (web: only a 410 stops)',
    (word) => {
      expect(doorStep(refused(word), 0, NOW)).toEqual({ kind: 'retry', inMs: 5_000 });
      expect(doorStep(refused(word), 3, NOW)).toEqual({ kind: 'retry', inMs: 60_000 });
    },
  );

  it('a 429 waits what its Retry-After asked, whatever the backoff says', () => {
    expect(doorStep({ ok: false, retryAfterMs: 90_000 }, 0, NOW)).toEqual({
      kind: 'retry',
      inMs: 90_000,
    });
    expect(doorStep({ ok: false, retryAfterMs: 90_000 }, 5, NOW)).toEqual({
      kind: 'retry',
      inMs: 90_000,
    });
  });

  it('a 429 asking for less than 5 s (0, or a date gone by) still waits 5 s: never a hot loop', () => {
    for (const asked of [0, 1_000, 4_999]) {
      expect(doorStep({ ok: false, retryAfterMs: asked }, 5, NOW)).toEqual({
        kind: 'retry',
        inMs: 5_000,
      });
    }
    expect(doorStep({ ok: false, retryAfterMs: 5_001 }, 5, NOW)).toEqual({
      kind: 'retry',
      inMs: 5_001,
    });
  });

  it('a 429 with no Retry-After backs off', () => {
    expect(doorStep({ ok: false, retryAfterMs: null }, 1, NOW)).toEqual({
      kind: 'retry',
      inMs: 15_000,
    });
  });

  it('offline (no network, a 5xx, a garbled answer) backs off', () => {
    expect(doorStep({ ok: false, offline: true }, 0, NOW)).toEqual({ kind: 'retry', inMs: 5_000 });
  });
});
