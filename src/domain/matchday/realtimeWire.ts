import { httpsOrigin } from '@/domain/credentials/wire';
import { err, ok } from '@/domain/Result';
import type { MatchDayRefusal } from '@/domain/matchday/matchDay';
import { all, field, scalar, successData, word, type Read } from '@/domain/matchday/wire';

/**
 * What the Match Day realtime door hands the list to hear nudges by (phone design 2026-11-24 §4; web spec §3):
 * `GET /api/v1/match-day-code/realtime-token`. The token is a credential: it lives in memory for one subscription, and
 * never goes to the store, the record, a log line, an error or a URL. The topic is joined exactly as answered.
 *
 * Confirmed by the web (2026-11-24): the v1 envelope `{ok: true, data: {token, topic, expiresAt, url, apikey},
 * requestId}`, and `expiresAt` in epoch SECONDS only (an ISO value is garbled: poll only). Vectors stay PROVISIONAL
 * until `docs/contracts/capture-matchday-realtime.v1.json` is vendored from a merged web main.
 */
export type MatchDayGrant = {
  readonly token: string;
  readonly topic: string;
  readonly url: string;
  readonly apikey: string;
  readonly expiresAt: Date;
};

/**
 * What a door call came to. `retry` is a 429, with the wait its Retry-After asked for (null when it named none);
 * `offline` is no answer the phone can act on (no network, a 5xx, a garbled 200, a portal's page).
 */
export type MatchDayTokenAnswer =
  | { readonly ok: true; readonly value: MatchDayGrant }
  | { readonly ok: false; readonly refusal: MatchDayRefusal }
  | { readonly ok: false; readonly retryAfterMs: number | null }
  | { readonly ok: false; readonly offline: true };

const TOPIC = /^match-day:.+$/;
/** Epoch seconds past this are no instant a Date can hold. */
const MAX_EPOCH_S = 8.64e12;
/** A Retry-After longer than this is read as this: a timer past 2^31 ms fires at once. */
export const RETRY_AFTER_CAP_MS = 3_600_000;

const topic = scalar(
  (raw) => (typeof raw === 'string' && TOPIC.test(raw) ? raw : undefined),
  'expected a match-day topic',
);
/** The server's https address; the socket's `wss://` endpoint is the port's to build from it. */
const server = scalar(
  (raw) => (typeof raw === 'string' && httpsOrigin(raw) !== null ? raw : undefined),
  'expected an https url',
);
const expiry = scalar((raw) => {
  if (typeof raw !== 'number' || !Number.isFinite(raw) || raw < 0 || raw > MAX_EPOCH_S)
    return undefined;
  return new Date(raw * 1000);
}, 'expected epoch seconds');

/** The door's 200, or why it is not one the phone can join by. */
export function parseRealtimeToken(body: unknown): Read<MatchDayGrant> {
  const data = successData(body);
  if (!data.ok) return data;
  const read = all({
    token: field(word)(data.value, 'token', ''),
    topic: field(topic)(data.value, 'topic', ''),
    expiresAt: field(expiry)(data.value, 'expiresAt', ''),
    url: field(server)(data.value, 'url', ''),
    apikey: field(word)(data.value, 'apikey', ''),
  });
  return read.ok ? ok(read.value) : err(read.error);
}

const SECONDS = /^\d+$/;

/** A 429's Retry-After as a wait in ms from `now`: delta seconds or an HTTP date, capped; null when unreadable. */
export function parseRetryAfter(value: string | null, now: number): number | null {
  const text = value?.trim() ?? '';
  if (SECONDS.test(text)) return Math.min(Number(text) * 1000, RETRY_AFTER_CAP_MS);
  if (!/^[A-Z][a-z]{2}, \d{2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2}:\d{2} GMT$/.test(text)) return null;
  const at = Date.parse(text);
  if (Number.isNaN(at)) return null;
  return Math.min(Math.max(at - now, 0), RETRY_AFTER_CAP_MS);
}
