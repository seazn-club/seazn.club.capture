import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MatchDayGrant, MatchDayTokenAnswer } from '@/domain/matchday/realtimeWire';
import type { SavedDayCode } from '@/domain/mode/dayCode';
import { startMatchDayNudge } from '@/hooks/matchDayNudge';
import { parseRecordLine } from '@/services/sessionRecord';
import { createFakePorts, TEST_NOW, timerClock } from '../../test/fakePorts';
import { FIXTURE_DAY_TOKEN } from '../../test/fixtures/dayCode';

const CODE: SavedDayCode = { origin: 'https://stg.seazn.club', token: FIXTURE_DAY_TOKEN };
const MIN = 60_000;

/** Made up. Each grant's token is told apart by `n`. */
const grant = (n: number, ttlMs = 60 * MIN): MatchDayGrant => ({
  token: `eyJmYWtl.eyJtZC0${n}.c2ln`,
  topic: 'match-day:3c1d2e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f',
  url: 'https://abcdwxyz.supabase.example',
  apikey: 'sb-fake-anon-key',
  expiresAt: new Date(Date.now() + ttlMs),
});
const granted = (g: MatchDayGrant): MatchDayTokenAnswer => ({ ok: true, value: g });

function world() {
  const fake = createFakePorts({ clock: timerClock });
  const read = vi.fn();
  const live = fake.matchDayRealtime;
  const open = () => live.subscriptions.filter((s) => s.open);
  return { ...fake, read, live, open, start: () => startMatchDayNudge(fake.ports, CODE, read) };
}

/** Lets the door's promise settle (the fake answers in a microtask). */
const settle = async (): Promise<void> => {
  await vi.advanceTimersByTimeAsync(0);
};

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(TEST_NOW);
});
afterEach(() => {
  vi.useRealTimers();
});

describe('joining', () => {
  it('asks the door with the code, and subscribes the Match Day port with the topic as answered', async () => {
    const w = world();
    const g = grant(1);
    w.matchDay.answerRealtime(granted(g));
    const stop = w.start();
    await settle();
    expect(w.matchDay.tokenCalls).toEqual([CODE]);
    expect(w.open().map((s) => s.grant)).toEqual([
      { token: g.token, channel: g.topic, url: g.url, apikey: g.apikey },
    ]);
    expect(w.realtime.subscriptions).toHaveLength(0);
    stop();
    expect(w.open()).toHaveLength(0);
  });

  it('a channel coming up reads the day once (pings in the gap were lost)', async () => {
    const w = world();
    w.matchDay.answerRealtime(granted(grant(1)));
    w.start();
    await settle();
    expect(w.read).not.toHaveBeenCalled();
    w.live.restore();
    expect(w.read).toHaveBeenCalledTimes(1);
  });

  it('in the background at the start, nothing is asked until the return', async () => {
    const w = world();
    w.matchDay.answerRealtime(granted(grant(1)));
    w.foreground.leave();
    w.start();
    await settle();
    expect(w.matchDay.tokenCalls).toHaveLength(0);
    w.foreground.fire();
    await settle();
    expect(w.open()).toHaveLength(1);
  });
});

describe('pings (question 1: a second call)', () => {
  it('two pings inside 4 s are one read at once and one trailing read at 4 s', async () => {
    const w = world();
    w.matchDay.answerRealtime(granted(grant(1)));
    w.start();
    await settle();
    w.live.ping();
    w.live.ping();
    expect(w.read).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(3_999);
    expect(w.read).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(w.read).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(w.read).toHaveBeenCalledTimes(2);
  });

  it('a burst of ten is still two reads', async () => {
    const w = world();
    w.matchDay.answerRealtime(granted(grant(1)));
    w.start();
    await settle();
    for (let i = 0; i < 10; i += 1) {
      w.live.ping();
      await vi.advanceTimersByTimeAsync(300);
    }
    await vi.advanceTimersByTimeAsync(10_000);
    expect(w.read).toHaveBeenCalledTimes(2);
  });

  it('two owners at once (a double mount) each hold their own subscription, and closing one leaves the other', async () => {
    const w = world();
    w.matchDay.answerRealtime(granted(grant(1)), granted(grant(2)));
    const first = w.start();
    await settle();
    const second = w.start();
    await settle();
    expect(w.open()).toHaveLength(1);
    first();
    expect(w.open()).toHaveLength(1);
    second();
    expect(w.open()).toHaveLength(0);
  });
});

describe('refresh at 80% of the actual lifetime', () => {
  it('a 60 min grant is replaced by a fresh one at 48 min', async () => {
    const w = world();
    w.matchDay.answerRealtime(granted(grant(1)), granted(grant(2)));
    w.start();
    await settle();
    await vi.advanceTimersByTimeAsync(48 * MIN - 1);
    expect(w.matchDay.tokenCalls).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(w.matchDay.tokenCalls).toHaveLength(2);
    expect(w.open().map((s) => s.grant.token)).toEqual([grant(2).token]);
  });

  it('a grant cut short by the code’s end (10 min) is refreshed at 8 min, not 48', async () => {
    const w = world();
    w.matchDay.answerRealtime(granted(grant(1, 10 * MIN)), granted(grant(2)));
    w.start();
    await settle();
    await vi.advanceTimersByTimeAsync(8 * MIN);
    expect(w.matchDay.tokenCalls).toHaveLength(2);
  });

  it('a grant already expired when it lands is never joined, and the door is asked again after the backoff', async () => {
    const w = world();
    w.matchDay.answerRealtime(granted(grant(1, -1)), granted(grant(2)));
    w.start();
    await settle();
    expect(w.live.subscriptions).toHaveLength(0);
    await vi.advanceTimersByTimeAsync(5_000);
    expect(w.open().map((s) => s.grant.token)).toEqual([grant(2).token]);
  });

  it('a channel that drops (an expired token, a channel error) is closed, and a fresh grant is asked for with backoff', async () => {
    const w = world();
    w.matchDay.answerRealtime(granted(grant(1)), granted(grant(2)), granted(grant(3)));
    w.start();
    await settle();
    w.live.drop();
    expect(w.open()).toHaveLength(0);
    await vi.advanceTimersByTimeAsync(5_000);
    expect(w.open().map((s) => s.grant.token)).toEqual([grant(2).token]);
    w.live.drop();
    await vi.advanceTimersByTimeAsync(14_999);
    expect(w.matchDay.tokenCalls).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(w.open().map((s) => s.grant.token)).toEqual([grant(3).token]);
  });

  it('a channel that came up forgets its failures: the next drop waits the first backoff again', async () => {
    const w = world();
    w.matchDay.answerRealtime(granted(grant(1)), granted(grant(2)), granted(grant(3)));
    w.start();
    await settle();
    w.live.drop();
    await vi.advanceTimersByTimeAsync(5_000);
    w.live.restore();
    w.live.drop();
    await vi.advanceTimersByTimeAsync(5_000);
    expect(w.matchDay.tokenCalls).toHaveLength(3);
  });
});

describe('refusals (question 2: an empty input)', () => {
  it.each(['MD_WITHDRAWN', 'MD_ENDED'] as const)(
    '%s stops for good: no subscription, and the door is never asked again, a return included',
    async (refusal) => {
      const w = world();
      w.matchDay.answerRealtime({ ok: false, refusal }, granted(grant(2)));
      w.start();
      await settle();
      await vi.advanceTimersByTimeAsync(60 * MIN);
      w.foreground.leave();
      w.foreground.fire();
      await settle();
      expect(w.matchDay.tokenCalls).toHaveLength(1);
      expect(w.live.subscriptions).toHaveLength(0);
      const lines = w.record.lines().map(parseRecordLine);
      expect(lines.map((l) => [l.event, l.fields])).toContainEqual([
        'matchday.nudge',
        { state: 'stopped' },
      ]);
    },
  );

  it('a code that ends while subscribed (refused at refresh) closes the channel for good', async () => {
    const w = world();
    w.matchDay.answerRealtime(granted(grant(1)), { ok: false, refusal: 'MD_ENDED' });
    w.start();
    await settle();
    await vi.advanceTimersByTimeAsync(48 * MIN);
    expect(w.open()).toHaveLength(0);
    await vi.advanceTimersByTimeAsync(60 * MIN);
    expect(w.matchDay.tokenCalls).toHaveLength(2);
  });

  it('MD_NOT_OPEN polls only and asks the door again later, with backoff', async () => {
    const w = world();
    w.matchDay.answerRealtime(
      { ok: false, refusal: 'MD_NOT_OPEN' },
      { ok: false, refusal: 'MD_NOT_OPEN' },
      granted(grant(3)),
    );
    w.start();
    await settle();
    await vi.advanceTimersByTimeAsync(5_000);
    expect(w.matchDay.tokenCalls).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(14_999);
    expect(w.matchDay.tokenCalls).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(w.open().map((s) => s.grant.token)).toEqual([grant(3).token]);
  });

  it('a 429 waits exactly its Retry-After', async () => {
    const w = world();
    w.matchDay.answerRealtime({ ok: false, retryAfterMs: 90_000 }, granted(grant(2)));
    w.start();
    await settle();
    await vi.advanceTimersByTimeAsync(89_999);
    expect(w.matchDay.tokenCalls).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(w.matchDay.tokenCalls).toHaveLength(2);
  });

  it('offline (a garbled answer, no network) backs off and joins once the door answers', async () => {
    const w = world();
    w.matchDay.answerRealtime({ ok: false, offline: true }, granted(grant(2)));
    w.start();
    await settle();
    expect(w.live.subscriptions).toHaveLength(0);
    await vi.advanceTimersByTimeAsync(5_000);
    expect(w.open()).toHaveLength(1);
  });

  it('a door that throws is offline, said in a closed word, and never escapes', async () => {
    const w = world();
    w.matchDay.throwNextRealtime();
    w.matchDay.answerRealtime(granted(grant(2)));
    w.start();
    await settle();
    expect(
      w.record
        .lines()
        .map(parseRecordLine)
        .map((l) => [l.event, l.fields]),
    ).toEqual([['matchday.nudge', { state: 'door', problem: 'threw' }]]);
    await vi.advanceTimersByTimeAsync(5_000);
    expect(w.open()).toHaveLength(1);
  });
});

describe('interruption (question 3)', () => {
  it('the background closes the channel and stops every timer; the return asks the door again', async () => {
    const w = world();
    w.matchDay.answerRealtime(granted(grant(1)));
    w.start();
    await settle();
    w.live.ping();
    w.live.ping();
    w.foreground.leave();
    expect(w.open()).toHaveLength(0);
    await vi.advanceTimersByTimeAsync(120 * MIN);
    expect(w.read).toHaveBeenCalledTimes(1);
    expect(w.matchDay.tokenCalls).toHaveLength(1);
    // The first grant expired while the app was away: the return asks for a fresh one, never rejoins the old.
    w.matchDay.answerRealtime(granted(grant(2)));
    w.foreground.fire();
    await settle();
    expect(w.open().map((s) => s.grant.token)).toEqual([grant(2).token]);
  });

  it('a door answer that lands after the background is dropped, never joined', async () => {
    const w = world();
    w.matchDay.answerRealtime(granted(grant(1)));
    w.start();
    w.foreground.leave();
    await settle();
    expect(w.live.subscriptions).toHaveLength(0);
  });

  it('a door answer that lands after the stop is dropped, and nothing is timed after it', async () => {
    const w = world();
    w.matchDay.answerRealtime(granted(grant(1)));
    const before = vi.getTimerCount();
    const stop = w.start();
    stop();
    await settle();
    expect(w.live.subscriptions).toHaveLength(0);
    expect(w.foreground.returnListeners()).toBe(0);
    expect(w.foreground.leaveListeners()).toBe(0);
    expect(vi.getTimerCount()).toBe(before);
  });

  it('the stop while joined cancels the refresh: nothing is asked again', async () => {
    const w = world();
    w.matchDay.answerRealtime(granted(grant(1)));
    const stop = w.start();
    await settle();
    stop();
    await vi.advanceTimersByTimeAsync(120 * MIN);
    expect(w.matchDay.tokenCalls).toHaveLength(1);
  });

  it('a foreground event while a channel is held and its refresh timed asks nothing more', async () => {
    const w = world();
    w.matchDay.answerRealtime(granted(grant(1)), granted(grant(2)));
    w.start();
    await settle();
    w.foreground.fire();
    await settle();
    expect(w.matchDay.tokenCalls).toHaveLength(1);
    expect(w.open().map((s) => s.grant.token)).toEqual([grant(1).token]);
  });

  it('a foreground event with no channel held (waiting out a backoff) asks the door at once', async () => {
    const w = world();
    w.matchDay.answerRealtime({ ok: false, offline: true }, granted(grant(2)));
    w.start();
    await settle();
    w.foreground.fire();
    await settle();
    expect(w.matchDay.tokenCalls).toHaveLength(2);
    expect(w.open()).toHaveLength(1);
  });

  it('a code that ends while a trailing read waits cancels that read too', async () => {
    const w = world();
    w.matchDay.answerRealtime(granted(grant(1, 5_000)), { ok: false, refusal: 'MD_ENDED' });
    w.start();
    await settle();
    await vi.advanceTimersByTimeAsync(1_000);
    w.live.ping();
    await vi.advanceTimersByTimeAsync(500);
    w.live.ping();
    expect(w.read).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(2_500);
    expect(w.matchDay.tokenCalls).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(w.read).toHaveBeenCalledTimes(1);
  });

  it('the token is in no record line', async () => {
    const w = world();
    w.matchDay.answerRealtime(granted(grant(1)), { ok: false, refusal: 'MD_ENDED' });
    w.start();
    await settle();
    w.live.drop();
    await vi.advanceTimersByTimeAsync(5_000);
    const text = w.record.lines().join('\n');
    expect(text).not.toContain(grant(1).token);
    expect(text).not.toContain(FIXTURE_DAY_TOKEN);
  });
});

describe('another mode (question 4)', () => {
  it('never touches Remote Scoring’s Realtime port, whose channel stays open', async () => {
    const w = world();
    const scoringOff = w.ports.realtime.subscribe(
      {
        token: 'eyJmYWtl.c2NvcmluZw.c2ln',
        channel: 'fixture:x',
        url: 'https://a.example',
        apikey: 'k',
      },
      { onPing: vi.fn(), onUp: vi.fn(), onDown: vi.fn() },
    );
    w.matchDay.answerRealtime(granted(grant(1)));
    const stop = w.start();
    await settle();
    expect(w.realtime.subscriptions.map((s) => s.open)).toEqual([true]);
    stop();
    expect(w.realtime.subscriptions.map((s) => s.open)).toEqual([true]);
    scoringOff();
  });
});
