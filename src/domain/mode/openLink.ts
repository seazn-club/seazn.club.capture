import { HTTPS_LINK, MAX_RAW_LENGTH } from '@/domain/mode/recognise';
import type { Mode } from '@/domain/mode/Mode';

/**
 * What an Open in app link turned out to be (spec 2026-11-25 §4): the text a scan of the same code would have read,
 * for `recognise()` unchanged, or `mode: null` for anything that is not one of the three links on one of this build's
 * hosts. `raw` carries a credential: never log it, store it or put it in a record line.
 */
export type OpenLink = { readonly mode: Mode; readonly raw: string } | { readonly mode: null };

const FOREIGN: OpenLink = { mode: null };

/** The three paths the web's button builds. */
const APP_PATH = /^\/app\/(stream|score|day)$/;
const TOKEN_CHARS = /^[A-Za-z0-9_-]+$/;
const BASE64URL = /^[A-Za-z0-9_-]+={0,2}$/;
const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';

/**
 * An `https://<host>/app/{stream|score|day}#<fragment>` link on one of the build's hosts, unwrapped. Never throws.
 * A regex and a hand-rolled base64url reader, with no `URL` or `atob` (Hermes; the same reason `recognise` has
 * none). It checks the fragment's characters only: a code's shape is `recognise()`'s (AGENTS §4, one parser per wire
 * shape), so a link to a code that parser refuses lands as the panel a bad scan gets.
 */
export function unwrapLink(url: string, hosts: readonly string[]): OpenLink {
  if (url.length === 0 || url.length > MAX_RAW_LENGTH + 64) return FOREIGN;
  const hash = url.indexOf('#');
  if (hash < 0) return FOREIGN;
  const head = url.slice(0, hash);
  const match = HTTPS_LINK.exec(head);
  if (match === null || head.includes('?')) return FOREIGN;
  const host = (match[1] ?? '').toLowerCase();
  const kind = APP_PATH.exec(match[2] ?? '')?.[1];
  if (kind === undefined || !hosts.includes(host)) return FOREIGN;
  const fragment = decodeOnce(url.slice(hash + 1));
  if (fragment === null || fragment.length === 0 || fragment.length > MAX_RAW_LENGTH) {
    return FOREIGN;
  }
  if (kind === 'stream') return streamLink(fragment);
  if (!TOKEN_CHARS.test(fragment)) return FOREIGN;
  return kind === 'score'
    ? { mode: 'scoring', raw: `https://${host}/score/${fragment}` }
    : { mode: 'matchday', raw: `https://${host}/day/${fragment}` };
}

function decodeOnce(fragment: string): string | null {
  try {
    return decodeURIComponent(fragment);
  } catch {
    return null;
  }
}

function streamLink(fragment: string): OpenLink {
  if (!BASE64URL.test(fragment)) return FOREIGN;
  const text = decodeBase64UrlText(fragment.replace(/=+$/, ''));
  if (text === null || !text.startsWith('{') || !isJson(text)) return FOREIGN;
  return { mode: 'stream', raw: text };
}

function isJson(text: string): boolean {
  try {
    JSON.parse(text);
    return true;
  } catch {
    return false;
  }
}

/** Unpadded base64url to the UTF-8 text it holds, or null when it is not whole base64 or not valid UTF-8. */
function decodeBase64UrlText(body: string): string | null {
  if (body.length % 4 === 1) return null;
  const bytes: number[] = [];
  let bits = 0;
  let acc = 0;
  for (const ch of body) {
    acc = (acc << 6) | ALPHABET.indexOf(ch);
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      bytes.push((acc >> bits) & 0xff);
      acc &= (1 << bits) - 1;
    }
  }
  return utf8(bytes);
}

/** How many continuation bytes a lead byte takes, or -1 when it cannot lead (overlongs C0/C1 and F5+ included). */
function continuation(lead: number): number {
  if (lead < 0x80) return 0;
  if (lead >= 0xc2 && lead < 0xe0) return 1;
  if (lead >= 0xe0 && lead < 0xf0) return 2;
  if (lead >= 0xf0 && lead < 0xf5) return 3;
  return -1;
}

function utf8(bytes: readonly number[]): string | null {
  let out = '';
  let i = 0;
  while (i < bytes.length) {
    const lead = bytes[i] ?? 0;
    const extra = continuation(lead);
    if (extra < 0 || i + extra >= bytes.length) return null;
    let cp = extra === 0 ? lead : lead & (0x3f >> extra);
    for (let k = 1; k <= extra; k += 1) {
      const c = bytes[i + k] ?? 0;
      if ((c & 0xc0) !== 0x80) return null;
      cp = (cp << 6) | (c & 0x3f);
    }
    if (cp > 0x10ffff || (cp >= 0xd800 && cp < 0xe000)) return null;
    out += String.fromCodePoint(cp);
    i += extra + 1;
  }
  return out;
}
