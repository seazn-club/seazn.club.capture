import { act, render, screen } from '@testing-library/react';
import { Text as RNText } from 'react-native';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ServerFacts } from '@/engine/CaptureEnginePort';
import en from '@/i18n/en.json';
import es from '@/i18n/es.json';
import fr from '@/i18n/fr.json';
import nl from '@/i18n/nl.json';
import { LivePlate } from '@/ui/components/LivePlate';
import { colour } from '@/ui/theme/tokens';
import { TEST_NOW, type FakePorts } from '../../../test/fakePorts';
import { savedCourtCode } from '../../../test/fixtures/savedStream';
import { renderViewfinder } from '../../../test/renderViewfinder';
import { wrapperFor } from '../../../test/renderWithPorts';

/**
 * Owner, 2026-11-26: "Red LIVE must mean viewers can see the picture." What the operator sees at each stage, through
 * the fake engine: the plate's word, the strip's line and the clock. Copy comes from the dictionaries, never the rule.
 */
const plate = () => screen.getByTestId('tally-plate').textContent;
const line = () => screen.getByTestId('strip-message').textContent;
const sweep = () => screen.queryByTestId('plate-sweep');

const PENDING = en['stream.status.viewersPending'];
const UNCONFIRMED = en['stream.status.viewersUnconfirmed'];
const LIVE_LINE = en['stream.status.live'];
const BACKUP_LINE = en['stream.status.fellBack'];
const NOT_CHARGING = en['stream.advisory.notCharging'];
const GOING_LIVE = en['stream.tally.goingLive'];

/** T46's facts with only the programme phase known. */
const programme = (programmePhase: ServerFacts['programmePhase']): ServerFacts => ({
  relayOutput: null,
  programmePhase,
  fps: null,
  kbps: null,
  cameraIngest: null,
  programmeIngest: null,
  ageS: 0,
});

type View = Awaited<ReturnType<typeof renderViewfinder>>;

/** The computed colour of a text painted in `color`, for comparing a token, never a literal. */
function inkOf(color: string): string {
  const reference = render(
    <RNText testID="ink-reference" style={{ color }}>
      ink
    </RNText>,
  );
  const painted = getComputedStyle(reference.getByTestId('ink-reference')).color;
  reference.unmount();
  return painted;
}

/** The phone's clock `ms` after the upload connected, and one 1 Hz report from native at that time. */
function at(view: View, ms: number) {
  act(() => {
    view.setNow(new Date(TEST_NOW.getTime() + ms));
    vi.advanceTimersByTime(1000);
  });
}

/**
 * A broadcast the upload connected `ms` before the screen (and so its latch) first sees it, nothing confirming it:
 * the fake's `going-live` at TEST_NOW, and native's next report `ms` later.
 */
function startedAgo(ms: number) {
  return ({ engine, setNow }: FakePorts) => {
    engine.scene('going-live');
    setNow(new Date(TEST_NOW.getTime() + ms));
    engine.patch({});
  };
}

/** Connected this moment, with nothing yet confirming the viewers' picture (the fake's `going-live`). */
async function goingLive(options: Parameters<typeof renderViewfinder>[1] = {}) {
  const view = await renderViewfinder({}, { quiet: true, ...options });
  act(() => view.engine.scene('going-live'));
  return view;
}

describe('Going live (owner, 2026-11-26)', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('reads GOING LIVE, with its sweep and its line, when the upload connects', async () => {
    await goingLive();
    expect(plate()).toBe(en['stream.tally.goingLive']);
    expect(sweep()).not.toBeNull();
    expect(line()).toBe(PENDING);
    // On air all the same: the clock counts and Stop is offered.
    expect(screen.getByTestId('elapsed').textContent).toBe('0:00:00');
    expect(screen.getByRole('button', { name: 'Hold to stop and unpair' })).toBeTruthy();
  });

  it('reads LIVE once the phone’s playlist check sees new frames', async () => {
    const view = await goingLive();
    act(() => view.engine.patch({ delivery: 'ok' }));
    expect(plate()).toBe(en['stream.tally.live']);
    expect(sweep()).toBeNull();
    expect(line()).toBe(LIVE_LINE);
  });

  it('reads LIVE once the server says the programme is live', async () => {
    const view = await goingLive();
    act(() => view.engine.patch({ server: programme('waiting') }));
    expect(plate()).toBe(GOING_LIVE);
    act(() => view.engine.patch({ server: programme('live') }));
    expect(plate()).toBe(en['stream.tally.live']);
    expect(line()).toBe(LIVE_LINE);
  });

  it('stays LIVE once confirmed: a frozen programme and a quiet playlist are not this rule’s', async () => {
    const view = await goingLive();
    act(() => view.engine.patch({ delivery: 'ok', server: programme('live') }));
    act(() => view.engine.patch({ delivery: 'unknown', server: programme('frozen') }));
    at(view, 90_000);
    expect(plate()).toBe(en['stream.tally.live']);
    expect(line()).toBe(LIVE_LINE);
  });

  it('is the same after a second report with nothing new', async () => {
    const view = await goingLive();
    at(view, 1000);
    at(view, 2000);
    expect(plate()).toBe(GOING_LIVE);
    expect(line()).toBe(PENDING);
  });

  it('is still Going live at 59 s, and red LIVE with the warning at 60 s', async () => {
    const view = await goingLive();
    at(view, 59_000);
    expect(plate()).toBe(GOING_LIVE);
    expect(line()).toBe(PENDING);
    at(view, 60_000);
    expect(plate()).toBe(en['stream.tally.live']);
    expect(sweep()).toBeNull();
    expect(line()).toBe(UNCONFIRMED);
    // A confirmation after the warning takes it down.
    act(() => view.engine.patch({ delivery: 'ok' }));
    expect(line()).toBe(LIVE_LINE);
  });

  // Which token was painted, through jsdom's cascade of react-native-web's classes: the choice of token, not what a
  // phone draws (AGENTS §10; colour on screen is a device check).
  it('counts the clock in ink3 while Going live, then in the lime LED; the 60 s warning in caution', async () => {
    const view = await goingLive();
    const clock = () => getComputedStyle(screen.getByTestId('elapsed')).color;
    const said = () => getComputedStyle(screen.getByTestId('status-line')).color;
    expect(clock()).toBe(inkOf(colour.ink3));
    expect(said()).not.toBe(inkOf(colour.caution));
    at(view, 60_000);
    expect(clock()).toBe(inkOf(colour.lime));
    expect(said()).toBe(inkOf(colour.caution));
    act(() => view.engine.patch({ delivery: 'ok' }));
    expect(clock()).toBe(inkOf(colour.lime));
    expect(said()).not.toBe(inkOf(colour.caution));
  });

  it('gives its routine line to a caption, and never the 60 s warning, which is urgent', async () => {
    const view = await goingLive({ quiet: false });
    expect(line()).toBe(NOT_CHARGING);
    at(view, 60_000);
    expect(line()).toBe(UNCONFIRMED);
  });

  it('reads the backup link Going live before confirmation, then LIVE with its own line', async () => {
    const view = await goingLive();
    act(() =>
      view.engine.forceState({
        kind: 'degraded',
        transport: 'rtmps',
        reason: 'fell-back-to-rtmps',
        sinceEpochMs: TEST_NOW.getTime(),
      }),
    );
    // forceState plays the fake's on-air telemetry, whose playlist moves: the fall itself confirms. A fresh mount
    // over a backup link not yet confirmed is the case below.
    expect(plate()).toBe(en['stream.tally.live']);
    expect(line()).toBe(BACKUP_LINE);
  });

  it('reads a backup link not yet confirmed Going live, with the Going live line', async () => {
    const view = await renderViewfinder(
      {},
      {
        quiet: true,
        prepare: ({ engine }) => {
          engine.scene('fell-back');
          engine.forceState({
            kind: 'degraded',
            transport: 'rtmps',
            reason: 'fell-back-to-rtmps',
            sinceEpochMs: TEST_NOW.getTime(),
          });
          engine.patch({ delivery: 'unknown', server: null });
        },
      },
    );
    expect(plate()).toBe(GOING_LIVE);
    expect(line()).toBe(PENDING);
    act(() => view.engine.patch({ delivery: 'ok' }));
    expect(plate()).toBe(en['stream.tally.live']);
    expect(line()).toBe(BACKUP_LINE);
  });

  it('keeps TROUBLE for trouble: a reconnect before confirmation is not Going live', async () => {
    const view = await goingLive();
    act(() => view.engine.scene('holding'));
    expect(plate()).toBe(en['stream.tally.trouble']);
  });

  // A process restart mid-broadcast: native rejoins, and the latch, like native's own session, starts again.
  it('after a restart, reads Going live on the rejoin until the facts arrive, then LIVE', async () => {
    const view = await renderViewfinder(
      {},
      {
        quiet: true,
        prepare: ({ engine }) => {
          engine.scene('going-live');
        },
      },
    );
    expect(plate()).toBe(GOING_LIVE);
    act(() => view.engine.patch({ server: programme('live') }));
    expect(plate()).toBe(en['stream.tally.live']);
  });

  it('proves a new broadcast again: a second match does not inherit the first one’s confirmation', async () => {
    const view = await goingLive();
    act(() => view.engine.patch({ delivery: 'ok' }));
    expect(plate()).toBe(en['stream.tally.live']);
    act(() => view.engine.send({ kind: 'stop' }));
    act(() => view.engine.scene('paired-ready'));
    act(() => view.engine.scene('going-live'));
    expect(plate()).toBe(GOING_LIVE);
  });

  it('reads the same on a court code, where the line after confirmation is the court’s Next line', async () => {
    const view = await goingLive({ saved: savedCourtCode() });
    expect(plate()).toBe(GOING_LIVE);
    expect(line()).toBe(PENDING);
    act(() => view.engine.patch({ delivery: 'ok' }));
    expect(plate()).toBe(en['stream.tally.live']);
    expect(line()).not.toBe(PENDING);
  });

  // Settings over the viewfinder unmounts the plate, and the viewfinder's comes back after: the latch outlives both.
  it('keeps LIVE across an unmount and a remount of the plate (Settings ↔ viewfinder)', async () => {
    const view = await goingLive();
    act(() => view.engine.patch({ delivery: 'ok' }));
    expect(plate()).toBe(en['stream.tally.live']);
    view.unmount();
    act(() => view.engine.patch({ delivery: 'unknown' }));
    render(<LivePlate />, { wrapper: wrapperFor(view) });
    expect(plate()).toBe(en['stream.tally.live']);
    expect(screen.queryByText(GOING_LIVE)).toBeNull();
  });

  // The controller's ruling (PR #78 review): a latch that first sees a broadcast past the 60 s mark cannot know
  // whether it was confirmed, so it shows LIVE with no warning, as a cold-start rejoin, a JS reload or a late start.
  it('reads red LIVE and no warning for a broadcast first seen 90 s in, and confirms normally', async () => {
    const view = await renderViewfinder({}, { quiet: true, prepare: startedAgo(90_000) });
    expect(plate()).toBe(en['stream.tally.live']);
    expect(line()).toBe(LIVE_LINE);
    expect(line()).not.toBe(UNCONFIRMED);
    act(() => view.engine.patch({ delivery: 'ok' }));
    expect(plate()).toBe(en['stream.tally.live']);
    expect(line()).toBe(LIVE_LINE);
  });

  it('reads Going live for a broadcast first seen 10 s in, then the warning at 60 s', async () => {
    const view = await renderViewfinder({}, { quiet: true, prepare: startedAgo(10_000) });
    expect(plate()).toBe(GOING_LIVE);
    expect(line()).toBe(PENDING);
    at(view, 60_000);
    expect(plate()).toBe(en['stream.tally.live']);
    expect(line()).toBe(UNCONFIRMED);
  });

  it.each([
    ['es', es],
    ['fr', fr],
    ['nl', nl],
  ] as const)('says it in %s, from the dictionary', async (lang, dictionary) => {
    const view = await renderViewfinder({ deviceLanguages: [lang] }, { quiet: true });
    act(() => view.engine.scene('going-live'));
    expect(plate()).toBe(dictionary['stream.tally.goingLive']);
    expect(line()).toBe(dictionary['stream.status.viewersPending']);
    at(view, 60_000);
    expect(plate()).toBe(dictionary['stream.tally.live']);
    expect(line()).toBe(dictionary['stream.status.viewersUnconfirmed']);
  });
});
