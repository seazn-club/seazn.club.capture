import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MatchDayGrant } from '@/domain/matchday/realtimeWire';
import { encodeSavedCode, type SavedCode } from '@/domain/mode/savedCode';
import { MATCHDAY_POLL_MS, useMatchDay } from '@/hooks/useMatchDay';
import { STORE_KEYS } from '@/services/modeStore';
import { createFakePorts, TEST_NOW, timerClock, type FakePorts } from '../../test/fakePorts';
import { dayLink, FIXTURE_DAY_TOKEN } from '../../test/fixtures/dayCode';
import { wrapperFor } from '../../test/renderWithPorts';

// The day list's nudge (phone design 2026-11-24 §4): subscribed under the poll's own gate, a nudge reads the day, at most
// one read per 4 s plus a trailing one, and the 15 s poll is unchanged.

const DAY_CODE = { origin: 'https://stg.seazn.club', token: FIXTURE_DAY_TOKEN };
const SAVED_DAY: SavedCode = {
  mode: 'matchday',
  raw: dayLink(),
  slot: null,
  savedAt: TEST_NOW,
  expiresAt: null,
  stream: null,
  scoring: null,
};
/** Made up. */
const GRANT: MatchDayGrant = {
  token: 'eyJmYWtl.eyJtZC1ob29r.c2ln',
  topic: 'match-day:3c1d2e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f',
  url: 'https://abcdwxyz.supabase.example',
  apikey: 'sb-fake-anon-key',
  expiresAt: new Date(TEST_NOW.getTime() + 3_600_000),
};

const settle = (ms = 0) => act(() => vi.advanceTimersByTimeAsync(ms));
const dayCalls = (fakes: FakePorts) => fakes.matchDay.calls.filter((call) => call.op === 'day');
const openSubs = (fakes: FakePorts) => fakes.matchDayRealtime.subscriptions.filter((s) => s.open);

async function mount(prepare?: (fakes: FakePorts) => void) {
  const fakes = createFakePorts({
    kvSeed: {
      [STORE_KEYS.code('matchday')]: encodeSavedCode(SAVED_DAY),
      [STORE_KEYS.active]: 'matchday',
    },
    clock: timerClock,
  });
  await fakes.ports.modeStore.load();
  fakes.navigation.go('matchday');
  fakes.matchDay.answerRealtime({ ok: true, value: GRANT });
  prepare?.(fakes);
  const view = renderHook(() => useMatchDay(), { wrapper: wrapperFor(fakes) });
  await settle();
  return { ...fakes, ...view };
}

beforeEach(() => vi.useFakeTimers({ now: TEST_NOW }));
afterEach(() => vi.useRealTimers());

describe('useMatchDay: the nudge', () => {
  it('subscribes Match Day’s own port with the code’s grant once the list is up, never Remote Scoring’s', async () => {
    const view = await mount();
    expect(view.matchDay.tokenCalls).toEqual([DAY_CODE]);
    expect(openSubs(view).map((s) => s.grant.channel)).toEqual([GRANT.topic]);
    expect(view.realtime.subscriptions).toHaveLength(0);
  });

  it('a nudge reads the day at once; a burst is one read now and one trailing', async () => {
    const view = await mount();
    const before = dayCalls(view).length;
    act(() => {
      view.matchDayRealtime.ping();
    });
    await settle();
    expect(dayCalls(view).length).toBe(before + 1);
    act(() => {
      view.matchDayRealtime.ping();
      view.matchDayRealtime.ping();
      view.matchDayRealtime.ping();
    });
    await settle(3_999);
    expect(dayCalls(view).length).toBe(before + 1);
    await settle(1);
    expect(dayCalls(view).length).toBe(before + 2);
  });

  it('a nudge while a read is out is never lost: a trailing read follows it', async () => {
    const view = await mount();
    const before = dayCalls(view).length;
    view.matchDay.hold();
    act(() => view.result.current.refresh());
    act(() => {
      view.matchDayRealtime.ping();
    });
    expect(dayCalls(view).length).toBe(before + 1);
    view.matchDay.release();
    await settle();
    expect(dayCalls(view).length).toBe(before + 2);
  });

  it('the 15 s poll is unchanged with the channel up and silent', async () => {
    const view = await mount();
    const before = dayCalls(view).length;
    await settle(MATCHDAY_POLL_MS * 4);
    expect(dayCalls(view).length).toBe(before + 4);
  });

  it('a refused door means the poll alone, with nothing shown: the list is ready', async () => {
    const view = await mount((fakes) =>
      fakes.matchDay.answerRealtime({ ok: false, retryAfterMs: null }),
    );
    expect(view.matchDayRealtime.subscriptions).toHaveLength(0);
    expect(view.result.current.state).toBe('ready');
    const before = dayCalls(view).length;
    await settle(MATCHDAY_POLL_MS);
    expect(dayCalls(view).length).toBe(before + 1);
  });

  it('the background closes it and the return opens it again (the poll’s gate)', async () => {
    const view = await mount();
    act(() => view.foreground.leave());
    expect(openSubs(view)).toHaveLength(0);
    act(() => view.foreground.fire());
    await settle();
    expect(openSubs(view)).toHaveLength(1);
    expect(view.matchDay.tokenCalls).toHaveLength(2);
  });

  it('unmounting closes it and asks nothing more', async () => {
    const view = await mount();
    view.unmount();
    expect(openSubs(view)).toHaveLength(0);
    await settle(3_600_000);
    expect(view.matchDay.tokenCalls).toHaveLength(1);
  });

  it('Exit (the code forgotten) closes it', async () => {
    const view = await mount();
    const snapshot = view.ports.modeStore.getSnapshot();
    const saved = snapshot.status === 'ready' ? snapshot.saved.codes.matchday : undefined;
    if (saved === undefined) throw new Error('no day code saved');
    await act(async () => {
      await view.ports.modeStore.forget(saved);
    });
    expect(openSubs(view)).toHaveLength(0);
  });

  it('asks the door only once the last copy has been read, the poll’s own gate', async () => {
    let copyRead: () => void = () => undefined;
    const view = await mount((fakes) => {
      const read = fakes.ports.matchDayStore.readCache;
      vi.spyOn(fakes.ports.matchDayStore, 'readCache').mockImplementation(
        (code) =>
          new Promise((resolve) => {
            copyRead = () => resolve(read(code));
          }),
      );
    });
    expect(view.matchDay.tokenCalls).toHaveLength(0);
    await act(async () => copyRead());
    await settle();
    expect(view.matchDay.tokenCalls).toHaveLength(1);
  });

  it('with no day code saved, nothing is asked or subscribed', async () => {
    const fakes = createFakePorts({ kvSeed: { [STORE_KEYS.active]: 'matchday' } });
    await fakes.ports.modeStore.load();
    renderHook(() => useMatchDay(), { wrapper: wrapperFor(fakes) });
    await settle();
    expect(fakes.matchDay.tokenCalls).toHaveLength(0);
    expect(fakes.matchDayRealtime.subscriptions).toHaveLength(0);
  });
});
