import { describe, expect, it } from 'vitest';
import { parseRealtimeToken, parseRetryAfter } from '@/domain/matchday/realtimeWire';

/**
 * PROVISIONAL (phone design 2026-11-24 §4; web spec §3): vectors copied from the web spec until
 * `capture-matchday-realtime.v1.json` is vendored from a merged web main. The answer is in the v1 envelope every md_
 * route answers in, and `expiresAt` is epoch seconds, as the beat's grant is. All values are made up.
 */
const TOKEN = 'eyJmYWtl.eyJtZC1mYWtlLXBheWxvYWQ.ZmFrZS1zaWduYXR1cmU';
const DATA = {
  token: TOKEN,
  topic: 'match-day:3c1d2e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f',
  expiresAt: 1_791_000_000,
  url: 'https://abcdwxyz.supabase.example',
  apikey: 'sb-fake-anon-key-0123456789',
};
const answer = (data: Record<string, unknown>) => ({ ok: true, data, requestId: 'req-1' });

describe('parseRealtimeToken (PROVISIONAL)', () => {
  it('reads the door’s answer: the token, the topic as answered, the server and key, and when it expires', () => {
    expect(parseRealtimeToken(answer(DATA))).toEqual({
      ok: true,
      value: {
        token: TOKEN,
        topic: DATA.topic,
        url: DATA.url,
        apikey: DATA.apikey,
        expiresAt: new Date(1_791_000_000_000),
      },
    });
  });

  it('reads expiresAt as a UTC instant too', () => {
    const read = parseRealtimeToken(answer({ ...DATA, expiresAt: '2026-10-03T14:00:00Z' }));
    expect(read.ok && read.value.expiresAt.toISOString()).toBe('2026-10-03T14:00:00.000Z');
  });

  it('ignores a field the contract does not name', () => {
    expect(parseRealtimeToken(answer({ ...DATA, extra: 1 })).ok).toBe(true);
  });

  it.each([
    ['no envelope', DATA],
    ['a refusal envelope', { ok: false, error: { code: 'MD_ENDED' } }],
    ['null', null],
    ['a page', '<html>'],
  ])('refuses %s', (_name, body) => {
    expect(parseRealtimeToken(body).ok).toBe(false);
  });

  it.each([
    ['token', { token: '' }],
    ['token', { token: 7 }],
    ['topic', { topic: 'capture:3c1d2e4f' }],
    ['topic', { topic: 'match-day:' }],
    ['topic', { topic: 'fixture:3c1d2e4f' }],
    ['url', { url: 'http://abcdwxyz.supabase.example' }],
    ['url', { url: 'wss://abcdwxyz.supabase.example' }],
    ['url', { url: 'not a url' }],
    ['apikey', { apikey: '' }],
    ['expiresAt', { expiresAt: -1 }],
    ['expiresAt', { expiresAt: 1.5e20 }],
    ['expiresAt', { expiresAt: Number.NaN }],
    ['expiresAt', { expiresAt: '2026-10-03 14:00' }],
    ['expiresAt', { expiresAt: null }],
  ])('refuses a bad %s (%j), and the error never carries the value', (name, patch) => {
    const read = parseRealtimeToken(answer({ ...DATA, ...patch }));
    expect(read.ok).toBe(false);
    if (read.ok) return;
    expect(JSON.stringify(read.error)).toContain(name);
    expect(JSON.stringify(read.error)).not.toContain(TOKEN);
  });

  it.each(['token', 'topic', 'expiresAt', 'url', 'apikey'])(
    'refuses an answer with no %s',
    (key) => {
      const data: Record<string, unknown> = { ...DATA };
      delete data[key];
      const read = parseRealtimeToken(answer(data));
      expect(read).toEqual({ ok: false, error: { kind: 'missing-field', field: key } });
    },
  );
});

describe('parseRetryAfter', () => {
  const NOW = Date.parse('2026-10-03T13:00:00Z');

  it('reads delta seconds', () => {
    expect(parseRetryAfter('30', NOW)).toBe(30_000);
    expect(parseRetryAfter(' 0 ', NOW)).toBe(0);
  });

  it('reads an HTTP date, never less than nothing', () => {
    expect(parseRetryAfter('Sat, 03 Oct 2026 13:01:00 GMT', NOW)).toBe(60_000);
    expect(parseRetryAfter('Sat, 03 Oct 2026 12:00:00 GMT', NOW)).toBe(0);
  });

  it('caps a wait at an hour, so a timer never overflows', () => {
    expect(parseRetryAfter('999999999', NOW)).toBe(3_600_000);
  });

  it.each([null, '', 'soon', '-5', '1.5', 'Sat, 99 Foo 2026', 'Sat, 99 Oct 2026 13:00:00 GMT'])(
    'is null for %j',
    (value) => {
      expect(parseRetryAfter(value, NOW)).toBeNull();
    },
  );
});
