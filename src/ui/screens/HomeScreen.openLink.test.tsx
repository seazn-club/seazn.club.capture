import { act, cleanup, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { encodeSavedCode } from '@/domain/mode/savedCode';
import { pendingLink } from '@/hooks/pendingLink';
import { useIncomingLink } from '@/hooks/useIncomingLink';
import en from '@/i18n/en.json';
import es from '@/i18n/es.json';
import fr from '@/i18n/fr.json';
import nl from '@/i18n/nl.json';
import type { Lang } from '@/i18n/language';
import { STORE_KEYS } from '@/services/modeStore';
import { HomeScreen } from '@/ui/screens/HomeScreen';
import { createFakePorts, readRecord, TEST_NOW } from '../../../test/fakePorts';
import { dayLink, FIXTURE_DAY_TOKEN } from '../../../test/fixtures/dayCode';
import { savedStreamCode } from '../../../test/fixtures/savedStream';
import { captureRaw, epochSeconds, FIXTURE_TOKEN } from '../../../test/fixtures/wire';
import { pairCode } from '../../../test/pairCode';
import { renderWithPorts } from '../../../test/renderWithPorts';
import { FAKE_MATCH, TOKEN } from '../../../test/scoringHomeFixtures';

/** Home with the root's link hook, as the layout mounts them once the gate has settled. */
function HomeWithLinks() {
  useIncomingLink();
  return <HomeScreen />;
}

const B = 'https://stg.seazn.club';
const IN_TWO_HOURS = new Date(TEST_NOW.getTime() + 2 * 3600_000);
const b64url = (text: string): string => Buffer.from(text, 'utf8').toString('base64url');
const streamLink = (): string =>
  `${B}/app/stream#${b64url(captureRaw({ exp: epochSeconds(IN_TWO_HOURS), slot: 1 }))}`;
const scoreLink = (): string => `${B}/app/score#${TOKEN}`;
const dayAppLink = (): string => `${B}/app/day#${FIXTURE_DAY_TOKEN}`;

async function renderHome(options?: Parameters<typeof renderWithPorts>[1], load = true) {
  const home = renderWithPorts(<HomeWithLinks />, options);
  if (load) await act(() => home.ports.modeStore.load());
  return home;
}
type Home = Awaited<ReturnType<typeof renderHome>>;
const arrive = (home: Home, url: string) => act(() => home.incomingLink.deliver(url));
const kinds = (home: Home) => home.engine.intents.map((intent) => intent.kind);
const linkLines = (home: Home) =>
  readRecord(home.record).filter((entry) => entry.event === 'link-opened');

const DICTIONARY: Readonly<Record<Lang, Record<string, string>>> = { en, es, fr, nl };

beforeEach(() => pendingLink.clear());
afterEach(() => cleanup());

// Open in app (plan 2026-11-25-open-in-app-phone, Task 4; spec §6, §9): a tapped link is a scan by another route, so
// each of these lands where the scan of the same code lands (HomeScreen.test, .scoringScan.test, .matchday.test).
describe('Home with an Open in app link', () => {
  it('opens Live Stream on a stream link, as a scan of its QR does', async () => {
    const home = await renderHome();
    await arrive(home, streamLink());
    await waitFor(() => expect(home.navigation.current()).toBe('stream'));
    expect(home.kv.entries.get(STORE_KEYS.active)).toBe('stream');
    expect(kinds(home)).toEqual(['pair']);
  });

  it('opens the scoring pad on a scoring link, and never pairs', async () => {
    const home = await renderHome();
    await arrive(home, scoreLink());
    await waitFor(() => expect(home.navigation.current()).toBe('scoring'));
    expect(home.kv.entries.get(STORE_KEYS.active)).toBe('scoring');
    expect(home.engine.intents).toEqual([]);
    expect(home.kv.entries.has(`scoring.link.${FAKE_MATCH}`)).toBe(true);
  });

  it('opens the day list on a Match Day link', async () => {
    const home = await renderHome();
    await arrive(home, dayAppLink());
    await waitFor(() => expect(home.navigation.current()).toBe('matchday'));
    expect(home.kv.entries.get(STORE_KEYS.active)).toBe('matchday');
    expect(home.engine.intents).toEqual([]);
  });

  it('asks first while Live Stream is paired, the same sheet a second scan shows', async () => {
    const stream = savedStreamCode();
    const home = await renderHome({
      kvSeed: {
        [STORE_KEYS.active]: 'stream',
        [STORE_KEYS.code('stream')]: encodeSavedCode(stream),
      },
    });
    pairCode(home.engine, stream);
    await arrive(home, dayAppLink());
    await screen.findByRole('button', { name: 'Unpair & open' });
    expect(kinds(home)).toEqual(['pair']);
    expect(home.kv.entries.has(STORE_KEYS.code('matchday'))).toBe(false);
  });

  it('takes the link home first when the app was on another screen', async () => {
    const home = await renderHome();
    home.navigation.go('settings');
    await arrive(home, dayAppLink());
    expect(home.navigation.history[0]).toBe('settings');
    expect(home.navigation.history[1]).toBe('home');
  });
});

describe('Home with an Open in app link while a broadcast is held', () => {
  it.each([
    ['a stream link', streamLink],
    ['a scoring link', scoreLink],
    ['a Match Day link', dayAppLink],
    ['a link it cannot read', () => `${B}/app/stream#!!`],
  ])('ignores %s and records that it did', async (_name, link) => {
    const home = await renderHome();
    home.navigation.go('stream');
    act(() => home.engine.forceState({ kind: 'publishing', transport: 'srt', sinceEpochMs: 1 }));
    await arrive(home, link());
    await act(async () => undefined);
    expect(home.navigation.current()).toBe('stream');
    expect(home.navigation.history).toEqual(['stream']);
    expect(home.engine.intents).toEqual([]);
    const lines = linkLines(home);
    expect(lines).toHaveLength(1);
    expect(lines[0]?.fields.state).toBe('held');
    expect(pendingLink.take()).toBeNull();
  });

  it('ignores a link while Arming, from the first moment the broadcast is held', async () => {
    const home = await renderHome();
    home.navigation.go('stream');
    act(() => home.engine.forceState({ kind: 'arming', cause: 'operator', retrying: false }));
    await arrive(home, dayAppLink());
    expect(home.navigation.current()).toBe('stream');
    expect(linkLines(home).map((l) => l.fields)).toEqual([{ mode: 'matchday', state: 'held' }]);
  });
});

describe('Home with an Open in app link it cannot read', () => {
  it('shows the foreign panel a bad scan shows, and records the mode the path named', async () => {
    const home = await renderHome();
    await arrive(home, `${B}/app/stream#not-a-code`);
    await screen.findByText(en['panel.foreign.title']);
    expect(home.navigation.current()).toBe('home');
    expect(linkLines(home).map((l) => l.fields)).toEqual([{ mode: 'stream', state: 'foreign' }]);
  });

  it('records no mode for a link that names none', async () => {
    const home = await renderHome();
    await arrive(home, `${B}/app/elsewhere#x`);
    await screen.findByText(en['panel.foreign.title']);
    expect(linkLines(home).map((l) => l.fields)).toEqual([{ state: 'foreign' }]);
  });

  it('shows a link for the other build’s host as foreign, and calls no server', async () => {
    const home = await renderHome();
    await arrive(home, `https://seazn.club/app/day#${FIXTURE_DAY_TOKEN}`);
    await screen.findByText(en['panel.foreign.title']);
    expect(home.matchDay.calls).toEqual([]);
  });

  it.each<Lang>(['en', 'es', 'fr', 'nl'])('says so in %s', async (lang) => {
    const home = await renderHome({ deviceLanguages: [lang] });
    await arrive(home, `${B}/app/day#`);
    expect(await screen.findByText(DICTIONARY[lang]['panel.foreign.title'] ?? '')).toBeTruthy();
    expect(home.navigation.current()).toBe('home');
  });
});

describe('Home with an Open in app link at awkward moments', () => {
  it('waits for the mode store on a cold start, then handles the link once', async () => {
    const home = await renderHome(undefined, false);
    await arrive(home, streamLink());
    await act(async () => undefined);
    expect(home.engine.intents).toEqual([]);
    expect(home.navigation.current()).toBe('home');
    await act(() => home.ports.modeStore.load());
    await waitFor(() => expect(home.navigation.current()).toBe('stream'));
    expect(kinds(home)).toEqual(['pair']);
  });

  it('waits for a scan that is up, then handles the link once', async () => {
    const home = await renderHome();
    expect(home.ports.scanFlight.begin()).toBe(true);
    await arrive(home, streamLink());
    await act(async () => undefined);
    expect(home.engine.intents).toEqual([]);
    expect(home.navigation.current()).toBe('home');
    act(() => home.ports.scanFlight.end());
    await waitFor(() => expect(home.navigation.current()).toBe('stream'));
    expect(kinds(home)).toEqual(['pair']);
  });

  it('lets a second link replace one that has not been taken', async () => {
    const home = await renderHome();
    home.ports.scanFlight.begin();
    await arrive(home, streamLink());
    await arrive(home, dayAppLink());
    act(() => home.ports.scanFlight.end());
    await waitFor(() => expect(home.navigation.current()).toBe('matchday'));
    expect(home.engine.intents).toEqual([]);
    expect(home.kv.entries.get(STORE_KEYS.active)).toBe('matchday');
  });

  it('holds the scan flight while it handles the link, so a scan cannot race it', async () => {
    const home = await renderHome();
    home.matchDay.hold();
    await arrive(home, dayAppLink());
    await screen.findByText('Checking code…');
    expect(home.ports.scanFlight.active()).toBe(true);
    act(() => home.matchDay.release());
    await waitFor(() => expect(home.navigation.current()).toBe('matchday'));
    expect(home.ports.scanFlight.active()).toBe(false);
  });

  it('puts no token, no fragment and no /app/ path in the record', async () => {
    const home = await renderHome();
    await arrive(home, dayAppLink());
    await waitFor(() => expect(home.navigation.current()).toBe('matchday'));
    await arrive(home, streamLink());
    await arrive(home, scoreLink());
    const text = home.record.lines().join('\n');
    expect(text).toContain('link-opened');
    for (const secret of [FIXTURE_DAY_TOKEN, FIXTURE_TOKEN, TOKEN, '/app/', '#']) {
      expect(text).not.toContain(secret);
    }
  });

  it('stops listening when Home goes', async () => {
    const home = await renderHome();
    expect(home.incomingLink.listeners).toBe(1);
    home.unmount();
    expect(home.incomingLink.listeners).toBe(0);
  });
});
