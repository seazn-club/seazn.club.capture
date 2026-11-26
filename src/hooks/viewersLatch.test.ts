import { afterEach, describe, expect, it } from 'vitest';
import { createFakeCaptureEngine, type FakeCaptureEngine } from '@/engine/FakeCaptureEngine';
import { viewersLatchOf } from '@/hooks/viewersLatch';

const NOW = 1_700_000_000_000;
let engine: FakeCaptureEngine | null = null;

function fake(): FakeCaptureEngine {
  engine = createFakeCaptureEngine(() => NOW);
  return engine;
}

afterEach(() => {
  engine?.dispose();
  engine = null;
});

/** On air for a second, with nothing yet confirming the viewers' picture: the fake's live scene, its delivery unknown. */
function connectedUnconfirmed(e: FakeCaptureEngine): void {
  e.scene('live');
  e.forceState({ kind: 'publishing', transport: 'srt', sinceEpochMs: NOW - 1000 });
  e.patch({ delivery: 'unknown', server: null });
}

describe('the viewers’ picture latch, one per engine (owner, 2026-11-26)', () => {
  it('is one reader per engine, however often it is asked for', () => {
    const e = fake();
    expect(viewersLatchOf(e)).toBe(viewersLatchOf(e));
    expect(viewersLatchOf(e)).not.toBe(viewersLatchOf(fake()));
  });

  it('folds the snapshot in hand on its first read', () => {
    const e = fake();
    e.scene('live'); // the fake's live telemetry has the playlist moving (delivery ok)
    // The fake's live scene is 12:34 on: first seen past the 60 s mark, as a reload into it would be.
    expect(viewersLatchOf(e)()).toEqual({
      sid: e.getSnapshot().broadcast?.sid,
      confirmed: true,
      inherited: true,
    });
  });

  it('keeps a confirmation it saw with nobody reading, after the playlist goes quiet', () => {
    const e = fake();
    connectedUnconfirmed(e);
    const read = viewersLatchOf(e);
    expect(read().confirmed).toBe(false);
    e.patch({ delivery: 'ok' }); // nobody reads here: only the latch's own listener sees it
    e.patch({ delivery: 'unknown' });
    expect(read().confirmed).toBe(true);
  });

  it('forgets it when the broadcast ends', () => {
    const e = fake();
    const read = viewersLatchOf(e);
    e.scene('live');
    expect(read().confirmed).toBe(true);
    e.scene('paired-ready');
    expect(read()).toEqual({ sid: null, confirmed: false, inherited: false });
  });
});
