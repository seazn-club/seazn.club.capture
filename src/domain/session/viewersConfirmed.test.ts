import { describe, expect, it } from 'vitest';
import {
  airStage,
  latchViewers,
  NO_LATCH,
  pastConfirmWindow,
  UNCONFIRMED_AFTER_MS,
  type ViewersFacts,
  type ViewersLatch,
} from '@/domain/session/viewersConfirmed';

/** A broadcast whose upload is connected and nothing yet confirms the viewers' picture. */
const CONNECTED: ViewersFacts = {
  sid: 'sid-1',
  onAir: true,
  programmeLive: false,
  playlistFresh: false,
  late: false,
};

const fold = (facts: readonly ViewersFacts[], from: ViewersLatch = NO_LATCH) =>
  facts.reduce(latchViewers, from);

describe('latchViewers: the viewers’ picture, confirmed once per broadcast (owner, 2026-11-26)', () => {
  it('starts unconfirmed with nothing held', () => {
    expect(NO_LATCH).toEqual({ sid: null, confirmed: false, inherited: false });
  });

  it('stays unconfirmed while the upload is connected and nothing confirms', () => {
    expect(fold([CONNECTED, CONNECTED])).toEqual({
      sid: 'sid-1',
      confirmed: false,
      inherited: false,
    });
  });

  it("confirms on the server's programme phase live", () => {
    expect(fold([CONNECTED, { ...CONNECTED, programmeLive: true }]).confirmed).toBe(true);
  });

  it("confirms on the phone's own playlist check seeing new frames", () => {
    expect(fold([CONNECTED, { ...CONNECTED, playlistFresh: true }]).confirmed).toBe(true);
  });

  it('confirms on either alone: the OR, both ways round', () => {
    expect(latchViewers(NO_LATCH, { ...CONNECTED, playlistFresh: true }).confirmed).toBe(true);
    expect(latchViewers(NO_LATCH, { ...CONNECTED, programmeLive: true }).confirmed).toBe(true);
    expect(latchViewers(NO_LATCH, CONNECTED).confirmed).toBe(false);
  });

  it('stays confirmed once confirmed: a later frozen programme or a stale playlist is not this rule’s', () => {
    const confirmed = fold([{ ...CONNECTED, programmeLive: true }]);
    expect(fold([CONNECTED, { ...CONNECTED, onAir: false }, CONNECTED], confirmed)).toEqual({
      sid: 'sid-1',
      confirmed: true,
      inherited: false,
    });
  });

  it('is the same latch after the same facts again (a second snapshot changes nothing)', () => {
    const once = fold([{ ...CONNECTED, playlistFresh: true }]);
    expect(latchViewers(once, { ...CONNECTED, playlistFresh: true })).toEqual(once);
    expect(latchViewers(once, { ...CONNECTED, playlistFresh: true })).toBe(once);
  });

  it('never confirms off air: facts before the upload connects are not the viewers’ picture', () => {
    expect(
      fold([{ ...CONNECTED, onAir: false, programmeLive: true, playlistFresh: true }]),
    ).toEqual({ sid: 'sid-1', confirmed: false, inherited: false });
  });

  it('forgets the confirmation with the broadcast: a court’s next match proves itself again', () => {
    const first = fold([{ ...CONNECTED, programmeLive: true }]);
    expect(fold([{ ...CONNECTED, sid: 'sid-2' }], first)).toEqual({
      sid: 'sid-2',
      confirmed: false,
      inherited: false,
    });
    expect(fold([{ ...CONNECTED, sid: null }], first)).toEqual(NO_LATCH);
  });

  it('confirms a new broadcast on its first fact', () => {
    const first = fold([CONNECTED]);
    expect(latchViewers(first, { ...CONNECTED, sid: 'sid-2', playlistFresh: true })).toEqual({
      sid: 'sid-2',
      confirmed: true,
      inherited: false,
    });
  });
});

describe('airStage: Going live, LIVE, or LIVE with the warning (owner, 2026-11-26)', () => {
  it('waits 60 s for a confirmation', () => {
    expect(UNCONFIRMED_AFTER_MS).toBe(60_000);
  });

  it.each<[string, number | null, boolean]>([
    ['at the connect', 0, false],
    ['59.999 s on', 59_999, false],
    ['60 s on', 60_000, true],
    ['an hour on', 3_600_000, true],
    ['off air', null, false],
  ])('the wait has run out: %s', (_name, onAirMs, late) => {
    expect(pastConfirmWindow(onAirMs)).toBe(late);
  });

  it.each<[string, Parameters<typeof airStage>[0], ReturnType<typeof airStage>]>([
    [
      'connected, no facts',
      { onAir: true, inherited: false, confirmed: false, hasSource: true, late: false },
      'goingLive',
    ],
    [
      '60 s, unconfirmed',
      { onAir: true, inherited: false, confirmed: false, hasSource: true, late: true },
      'unconfirmed',
    ],
    [
      'confirmed in time',
      { onAir: true, inherited: false, confirmed: true, hasSource: true, late: false },
      'live',
    ],
    [
      'confirmed after 60 s',
      { onAir: true, inherited: false, confirmed: true, hasSource: true, late: true },
      'live',
    ],
    // No real broadcast reaches the no-source branch today: native refuses a session whose playbackUrl is not https
    // (SessionConfig.problems, "playbackUrl is not https"), so every held broadcast has a URL to check. The guard is
    // kept for a destination without one (owner, 2026-11-26: "behave as today").
    [
      'no source of confirmation',
      { onAir: true, inherited: false, confirmed: false, hasSource: false, late: false },
      'live',
    ],
    [
      'no source, after 60 s',
      { onAir: true, inherited: false, confirmed: false, hasSource: false, late: true },
      'live',
    ],
    [
      'off air',
      { onAir: false, inherited: false, confirmed: false, hasSource: true, late: false },
      'live',
    ],
    [
      'off air, late',
      { onAir: false, inherited: false, confirmed: false, hasSource: true, late: true },
      'live',
    ],
    // The controller's ruling (PR #78 review): first seen past the mark, the latch cannot know; LIVE, no warning.
    [
      'first seen past the mark, unconfirmed',
      { onAir: true, inherited: true, confirmed: false, hasSource: true, late: true },
      'live',
    ],
  ])('%s', (_name, input, stage) => {
    expect(airStage(input)).toBe(stage);
  });
});

describe('a latch born late: a rejoin, a JS reload or a late JS start (PR #78 review, 2026-11-26)', () => {
  it('marks a broadcast first seen already past the 60 s mark', () => {
    expect(fold([{ ...CONNECTED, late: true }])).toEqual({
      sid: 'sid-1',
      confirmed: false,
      inherited: true,
    });
  });

  it('does not mark one first seen inside the 60 s, however late it later runs', () => {
    expect(fold([CONNECTED, { ...CONNECTED, late: true }])).toEqual({
      sid: 'sid-1',
      confirmed: false,
      inherited: false,
    });
  });

  it('confirms normally if a fact arrives', () => {
    expect(
      fold([
        { ...CONNECTED, late: true },
        { ...CONNECTED, late: true, playlistFresh: true },
      ]),
    ).toEqual({
      sid: 'sid-1',
      confirmed: true,
      inherited: true,
    });
  });

  it('starts again with the next broadcast', () => {
    const born = fold([{ ...CONNECTED, late: true }]);
    expect(latchViewers(born, { ...CONNECTED, sid: 'sid-2' }).inherited).toBe(false);
    expect(latchViewers(born, { ...CONNECTED, sid: null, late: true }).inherited).toBe(false);
  });
});
