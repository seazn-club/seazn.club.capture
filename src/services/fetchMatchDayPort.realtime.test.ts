import { describe, expect, it } from 'vitest';
import type { SavedDayCode } from '@/domain/mode/dayCode';
import { createFetchMatchDayPort } from '@/services/fetchMatchDayPort';
import type { ScoringFetch } from '@/services/fetchScoringPort';
import type { FetchResponse } from '@/services/httpAsk';
import { createLogger } from '@/services/logger';
import { createRingRecord, parseRecordLine } from '@/services/sessionRecord';
import { FIXTURE_DAY_TOKEN } from '../../test/fixtures/dayCode';
import { answered, contractAdmits, fixtureOf, refusedWith } from '../../test/matchDayContracts';

/** Made up throughout. The `md_` bearer and the grant's JWT are both credentials the port must never say. */
const ORIGIN = 'https://stg.seazn.club';
const CODE: SavedDayCode = { origin: ORIGIN, token: FIXTURE_DAY_TOKEN };
const DOOR = `${ORIGIN}/api/v1/match-day-code/realtime-token`;
const NOW = Date.parse('2026-10-03T13:00:00Z');
const JWT = 'eyJmYWtl.eyJtZC1mYWtlLXBheWxvYWQ.ZmFrZS1zaWduYXR1cmU';

/** The vendored contract's own fixture (capture-matchday-realtime.v1, web #1301). */
const GRANT = fixtureOf('capture-matchday-realtime.v1', 'valid') as {
  token: string;
  topic: string;
  expiresAt: number;
  url: string;
  apikey: string;
};

const respond = (
  status: number,
  body: unknown,
  headers: Record<string, string> = {},
): FetchResponse => ({
  status,
  headers: { get: (name) => headers[name.toLowerCase()] ?? null },
  text: async () => JSON.stringify(body),
});

function portOver(fetch: ScoringFetch) {
  const calls: Parameters<ScoringFetch>[] = [];
  const record = createRingRecord();
  const logger = createLogger({ record, now: () => 0 });
  const port = createFetchMatchDayPort({
    fetch: (url, init) => {
      calls.push([url, init]);
      return fetch(url, init);
    },
    logger,
    host: 'stg.seazn.club',
    now: () => NOW,
  });
  const entries = () => record.lines().map(parseRecordLine);
  return { port, calls, record, entries };
}

const answering = (response: FetchResponse) => portOver(async () => response);

describe('realtimeToken: the Match Day realtime door ', () => {
  it('GETs the door with the md_ token in the Authorization header only, never in the URL', async () => {
    const { port, calls } = answering(respond(200, answered(GRANT)));
    await port.realtimeToken(CODE);
    expect(calls).toHaveLength(1);
    const [url, init] = calls[0];
    expect(url).toBe(DOOR);
    expect(url).not.toContain(FIXTURE_DAY_TOKEN);
    expect(url).not.toContain('?');
    expect(init.method).toBe('GET');
    expect(init.headers.Authorization).toBe(`Bearer ${FIXTURE_DAY_TOKEN}`);
    expect(init.headers['Cache-Control']).toBe('no-store');
  });

  it('answers the grant read by its one parser', async () => {
    const { port } = answering(respond(200, answered(GRANT)));
    expect(await port.realtimeToken(CODE)).toEqual({
      ok: true,
      value: {
        token: GRANT.token,
        topic: GRANT.topic,
        url: GRANT.url,
        apikey: GRANT.apikey,
        expiresAt: new Date(GRANT.expiresAt * 1000),
      },
    });
  });

  it.each([
    [401, 'MD_UNKNOWN', 'unknown'],
    [410, 'MD_WITHDRAWN', 'withdrawn'],
    [410, 'MD_ENDED', 'ended'],
    [403, 'MD_NOT_OPEN', 'not-open'],
  ])(
    'a %i %s is the refusal, in its word (the contract’s own fixture)',
    async (status, code, name) => {
      const error = fixtureOf('capture-matchday-realtime.v1', `refusal-valid-${name}`);
      expect(contractAdmits('capture-matchday-realtime.v1', error, '/$defs/refusal')).toBe(true);
      const { port } = answering(respond(status, refusedWith(error)));
      expect(await port.realtimeToken(CODE)).toEqual({ ok: false, refusal: code });
    },
  );

  it('a 429 carries the wait its Retry-After asked for', async () => {
    const { port } = answering(respond(429, null, { 'retry-after': '42' }));
    expect(await port.realtimeToken(CODE)).toEqual({ ok: false, retryAfterMs: 42_000 });
  });

  it('a 429 whose headers cannot be read carries no wait, and never rejects', async () => {
    const { port } = answering({
      status: 429,
      headers: {
        get: () => {
          throw new Error('no headers');
        },
      },
      text: async () => 'null',
    });
    expect(await port.realtimeToken(CODE)).toEqual({ ok: false, retryAfterMs: null });
  });

  it('a 429 with no Retry-After carries none', async () => {
    const { port } = answering(respond(429, null));
    expect(await port.realtimeToken(CODE)).toEqual({ ok: false, retryAfterMs: null });
  });

  it.each([
    ['a garbled 200', respond(200, answered({ ...GRANT, url: 'http://plain.example' }))],
    ['a 200 with no envelope', respond(200, GRANT)],
    ['a 500', respond(500, null)],
    [
      'a 503 REALTIME_UNAVAILABLE',
      respond(503, { ok: false, error: { code: 'REALTIME_UNAVAILABLE' } }),
    ],
    ['a 404 page', respond(404, '<html>')],
    ['the envelope’s own 404', respond(404, { ok: false, error: { code: 'NOT_FOUND' } })],
  ])('%s is offline', async (_name, response) => {
    const { port } = answering(response);
    expect(await port.realtimeToken(CODE)).toEqual({ ok: false, offline: true });
  });

  it('a network failure is offline, and never rejects', async () => {
    const { port } = portOver(async () => {
      throw new Error(`boom ${FIXTURE_DAY_TOKEN}`);
    });
    await expect(port.realtimeToken(CODE)).resolves.toEqual({ ok: false, offline: true });
  });

  it('a code on a foreign host never sends its token', async () => {
    const { port, calls } = answering(respond(200, answered(GRANT)));
    const answer = await port.realtimeToken({
      origin: 'https://evil.example',
      token: FIXTURE_DAY_TOKEN,
    });
    expect(answer).toEqual({ ok: false, offline: true });
    expect(calls).toHaveLength(0);
  });

  it('says each failure in closed words, and no line carries the md_ token or the JWT', async () => {
    const responses = [
      respond(200, answered(GRANT)),
      respond(410, refusedWith({ code: 'MD_ENDED', message: `ended ${JWT}` })),
      respond(429, null, { 'retry-after': '5' }),
      respond(200, answered({ ...GRANT, topic: `capture:${JWT}` })),
    ];
    const { port, entries, record } = portOver(async () => responses.shift() as FetchResponse);
    for (let i = 0; i < 4; i += 1) await port.realtimeToken(CODE);
    expect(entries().map((line) => [line.event, line.fields])).toEqual([
      ['matchday.answer', { op: 'realtime', kind: 'refused', status: 410, word: 'MD_ENDED' }],
      [
        'matchday.answer',
        { op: 'realtime', kind: 'offline', status: 429, problem: 'rate-limited' },
      ],
      ['matchday.answer', { op: 'realtime', kind: 'offline', status: 200, problem: 'unparseable' }],
    ]);
    const text = record.lines().join('\n');
    expect(text).not.toContain(JWT);
    expect(text).not.toContain(FIXTURE_DAY_TOKEN);
  });
});
