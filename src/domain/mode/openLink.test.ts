import { describe, expect, it } from 'vitest';
import { unwrapLink } from '@/domain/mode/openLink';
import { dayLink, FIXTURE_DAY_TOKEN } from '../../../test/fixtures/dayCode';
import { captureRaw } from '../../../test/fixtures/wire';

const HOSTS = ['stg.seazn.club'] as const;
const B = 'https://stg.seazn.club';
const SCORE_TOKEN = 'dl_Fake-Score_0123456789abcdefghijklmnopqrstuvwx';

/** Independent of the code under test: Node's own encoder. */
const b64url = (text: string, pad = false): string => {
  const out = Buffer.from(text, 'utf8').toString('base64url');
  return pad ? out + '='.repeat((4 - (out.length % 4)) % 4) : out;
};

describe('unwrapLink', () => {
  const qr = captureRaw();

  it('unwraps a stream link to the exact QR text', () => {
    expect(unwrapLink(`${B}/app/stream#${b64url(qr)}`, HOSTS)).toEqual({ mode: 'stream', raw: qr });
  });

  it('gives the same text with padding, or a percent-encoded pad', () => {
    const padded = b64url(qr, true);
    expect(padded.endsWith('=')).toBe(true);
    expect(unwrapLink(`${B}/app/stream#${padded}`, HOSTS)).toEqual({ mode: 'stream', raw: qr });
    expect(unwrapLink(`${B}/app/stream#${padded.replace(/=/g, '%3D')}`, HOSTS)).toEqual({
      mode: 'stream',
      raw: qr,
    });
  });

  it('keeps non-ASCII text in the code exactly', () => {
    const text = JSON.stringify({ v: 2, code: 'fx', label: 'Zoë – 日本' });
    expect(unwrapLink(`${B}/app/stream#${b64url(text)}`, HOSTS)).toEqual({
      mode: 'stream',
      raw: text,
    });
  });

  it('unwraps a scoring link to the /score/ link a scan reads', () => {
    expect(unwrapLink(`${B}/app/score#${SCORE_TOKEN}`, HOSTS)).toEqual({
      mode: 'scoring',
      raw: `${B}/score/${SCORE_TOKEN}`,
    });
  });

  it('unwraps a Match Day link to the /day/ link a scan reads', () => {
    expect(unwrapLink(`${B}/app/day#${FIXTURE_DAY_TOKEN}`, HOSTS)).toEqual({
      mode: 'matchday',
      raw: dayLink('stg.seazn.club'),
    });
  });

  it('decodes a percent-encoded fragment once', () => {
    expect(unwrapLink(`${B}/app/score#dl%5FFake-Score_0123`, HOSTS)).toEqual({
      mode: 'scoring',
      raw: `${B}/score/dl_Fake-Score_0123`,
    });
    // A double-encoded `%` is not decoded again: it stays an illegal character.
    expect(unwrapLink(`${B}/app/score#dl%255Fx`, HOSTS)).toEqual({ mode: null });
  });

  it('matches the host case-insensitively', () => {
    expect(unwrapLink(`https://STG.Seazn.Club/app/day#${FIXTURE_DAY_TOKEN}`, HOSTS).mode).toBe(
      'matchday',
    );
  });

  const good = b64url(qr);
  it.each([
    ['no fragment', `${B}/app/stream`],
    ['an empty fragment', `${B}/app/stream#`],
    ['an unknown path', `${B}/app/x#${good}`],
    ['an extra path segment', `${B}/app/stream/extra#${good}`],
    ['a trailing slash', `${B}/app/stream/#${good}`],
    ['a query string', `${B}/app/stream?x=1#${good}`],
    ['the other build host', `https://seazn.club/app/stream#${good}`],
    ['http', `http://stg.seazn.club/app/stream#${good}`],
    ['userinfo', `https://a@stg.seazn.club/app/stream#${good}`],
    ['a port', `https://stg.seazn.club:8443/app/stream#${good}`],
    ['base64 that is not JSON', `${B}/app/stream#${b64url('hello there')}`],
    ['base64 that is not JSON but starts with a brace', `${B}/app/stream#${b64url('{not json}')}`],
    ['a stray fifth base64 character', `${B}/app/stream#${b64url('{"a": 1 }')}A`],
    ['base64 that is a JSON array', `${B}/app/stream#${b64url('[1]')}`],
    ['invalid base64 length', `${B}/app/stream#abcde`],
    ['a fragment over 4096 characters', `${B}/app/score#dl_${'a'.repeat(4097)}`],
    ['a /score/ link, not /app/', `${B}/score/${SCORE_TOKEN}`],
    ['a path-like scoring fragment', `${B}/app/score#dl_a/../b`],
    ['a second fragment', `${B}/app/score#dl_a#b`],
    ['whitespace in the fragment', `${B}/app/day#md_a b`],
    ['not a URL', 'hello'],
    ['empty', ''],
  ])('is foreign for %s', (_name, url) => {
    expect(unwrapLink(url, HOSTS)).toEqual({ mode: null });
  });

  it('is foreign for any host when none is built in', () => {
    expect(unwrapLink(`${B}/app/day#${FIXTURE_DAY_TOKEN}`, [])).toEqual({ mode: null });
  });

  it('never throws, on 200 random strings', () => {
    let seed = 7;
    const next = () => (seed = (seed * 1103515245 + 12345) % 2147483648);
    const alphabet = 'abcXYZ09_-=%#/:@?. {}"é日';
    for (let i = 0; i < 200; i += 1) {
      const len = next() % 80;
      let s = i % 2 === 0 ? `${B}/app/stream#` : '';
      for (let j = 0; j < len; j += 1) s += alphabet[next() % alphabet.length];
      expect(() => unwrapLink(s, HOSTS)).not.toThrow();
    }
  });
});
