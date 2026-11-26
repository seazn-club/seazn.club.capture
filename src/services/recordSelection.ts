import {
  fitLine,
  isRecordTime,
  RECORD_SID,
  RECORD_WINDOW_ENDS,
  RECORD_WINDOW_OPENS,
} from '@/services/recordContract';
import { START_KIND } from '@/services/recordPins';

/**
 * N53: what Send record and Share record take from native's record file (`recordFilePort.ts`) and the JS ring. Pure:
 * lines in, lines out, each exactly as it was written. The upload's own checks (`linesAreSafe`, `fitLine`) still run on
 * what Send picks here.
 */

/** StreamPack's own lines: 95% of the 40-minute run's 10,662 (session 66753ccd). They give way first when over a cap. */
export const CHATTY_KINDS: ReadonlySet<string> = new Set(['vendor-log']);

/**
 * Share record's cap, in UTF-16 code units: React Native's share sheet carries the text as the intent's `EXTRA_TEXT`
 * (`ShareModule.kt`), which Android parcels as UTF-16 through a binder buffer of 1 MB a process, so 250,000 is about
 * 500 KB of it. A 1,000-line share (about 190,000) opened on the owner's phone on 2026-11-26; a 2 MB file would not.
 */
export const SHARE_MAX_CHARS = 250_000;

const AT = /^\{"at":"([^"]*)"/;

/** A line's time, from the `at` both writers put first; NaN for any other line. */
function atOf(line: string): number {
  const match = AT.exec(line);
  return match === null ? Number.NaN : Date.parse(match[1] ?? '');
}

/**
 * The file's lines and the ring's, each once. Every line the ring holds while a pairing is held came back from native
 * and is in the file, byte for byte (CD7); a line the ring has more often than the file (JavaScript's while no pairing
 * was held, or one the file's rotation let go of) is added in time order, after any file line of the same time. Equal
 * lines are counted, never collapsed: two identical lines in the ring and one in the file keep two.
 */
export function mergeRecord(file: readonly string[], ring: readonly string[]): readonly string[] {
  const inFile = new Map<string, number>();
  for (const line of file) inFile.set(line, (inFile.get(line) ?? 0) + 1);
  const seen = new Map<string, number>();
  const ringOnly = ring.filter((line) => {
    const count = (seen.get(line) ?? 0) + 1;
    seen.set(line, count);
    return count > (inFile.get(line) ?? 0);
  });
  if (ringOnly.length === 0) return file;
  const merged: string[] = [];
  let i = 0;
  let j = 0;
  while (i < file.length || j < ringOnly.length) {
    const fromRing =
      i >= file.length || (j < ringOnly.length && atOf(ringOnly[j]!) < atOf(file[i]!));
    merged.push(fromRing ? ringOnly[j++]! : file[i++]!);
  }
  return merged;
}

type Read = { readonly kind: string; readonly sid: unknown; readonly tokenTag: unknown } | null;

/**
 * A record line's `kind`, `sid` and `tokenTag`, or null for a line that is not a JSON object with a text kind and the
 * contract's `at`: a torn write, which the upload's own check would refuse the whole record for.
 */
function readLine(line: string): Read {
  try {
    const data: unknown = JSON.parse(line);
    if (typeof data !== 'object' || data === null || Array.isArray(data)) return null;
    const { at, kind, sid, tokenTag } = data as Record<string, unknown>;
    return typeof kind === 'string' && isRecordTime(at) ? { kind, sid, tokenTag } : null;
  } catch {
    return null;
  }
}

/**
 * The contract's attribution, read from the phone's side: A2's windows, each opened by a `pair` line of the code's tag
 * and run through the next `unpaired`, `ended`, `pair-refused` or `reset`, or up to the next `pair`; and the sids its
 * `broadcast-start` lines name inside them, which are the code's sessions (A1; as N38's pins read it, no other kind
 * binds a sid to a pairing). A line goes when its sid is one of those, or when it has no
 * sid and is inside a window. A masked sid, or any other, names no session of the code: the server drops it. A line
 * that is no record line (a torn write) never goes, so the server never sees it, and it closes nothing.
 */
function attributed(lines: readonly string[], tag: string): { kept: number[]; pins: Set<number> } {
  const reads = lines.map(readLine);
  const inWindow: boolean[] = [];
  const sids = new Set<string>();
  const pins = new Set<number>();
  let open = false;
  reads.forEach((read, index) => {
    if (read?.kind === RECORD_WINDOW_OPENS) open = read.tokenTag === tag;
    inWindow.push(open);
    if (!open || read === null) return;
    if (read.kind === RECORD_WINDOW_OPENS) pins.add(index);
    if (read.kind === START_KIND && typeof read.sid === 'string' && RECORD_SID.test(read.sid))
      sids.add(read.sid);
    if (RECORD_WINDOW_ENDS.has(read.kind)) open = false;
  });
  const kept: number[] = [];
  reads.forEach((read, index) => {
    if (read === null) return;
    const sid = read.sid;
    const goes =
      sid === null || sid === undefined
        ? inWindow[index]
        : typeof sid === 'string' && sids.has(sid);
    if (!goes) return;
    kept.push(index);
    if (read.kind === START_KIND) pins.add(index);
  });
  return { kept, pins };
}

/**
 * Under a cap, which lines go: the pins first (each window's `pair` line and each of its broadcasts' `broadcast-start`,
 * without which the server files nothing of the window or names no session), then every other line but the chatty
 * ones, newest first, then the chatty ones, newest first; back in their own order. A line that alone is past what is
 * left is skipped, and a smaller one after it may still fit.
 */
function budgeted(
  lines: readonly string[],
  candidates: readonly number[],
  pins: ReadonlySet<number>,
  cap: {
    readonly maxLines: number;
    readonly maxSize: number;
    readonly size: (line: string) => number;
  },
): readonly string[] {
  const kinds = new Map(candidates.map((index) => [index, readLine(lines[index]!)?.kind ?? '']));
  const newest = [...candidates].reverse();
  const order = [
    ...newest.filter((index) => pins.has(index)),
    ...newest.filter((index) => !pins.has(index) && !CHATTY_KINDS.has(kinds.get(index)!)),
    ...newest.filter((index) => !pins.has(index) && CHATTY_KINDS.has(kinds.get(index)!)),
  ];
  const chosen = new Set<number>();
  let size = 0;
  for (const index of order) {
    if (chosen.size >= cap.maxLines) break;
    const add = cap.size(lines[index]!);
    if (size + add > cap.maxSize) continue;
    size += add;
    chosen.add(index);
  }
  return candidates.filter((index) => chosen.has(index)).map((index) => lines[index]!);
}

const utf8 = new TextEncoder();

/**
 * Send record's lines for the code whose token's tag is `tag` (`tokenTag`), from the merged record: only the lines the
 * server would file under that code (`attributed`), so none of the 1,000 is spent on a line it drops, and within the
 * contract's caps, `maxLines` lines and `maxBytes` of body as the upload writes it (each line fitted, then `\n`).
 * Over either cap, the pins and the broadcast's own lines go before the chatty ones (`budgeted`).
 */
export function selectForCode(
  lines: readonly string[],
  tag: string,
  maxLines: number,
  maxBytes: number,
): readonly string[] {
  const { kept, pins } = attributed(lines, tag);
  const size = (line: string) => utf8.encode(fitLine(line)).length + 1;
  return budgeted(lines, kept, pins, { maxLines, maxSize: maxBytes, size });
}

/**
 * Share record's lines: every line of the merged record, every code's, when they fit the share sheet's
 * [SHARE_MAX_CHARS] (`\n` between them); over it, the record's other lines before its chatty ones, newest first.
 */
export function selectForShare(
  lines: readonly string[],
  maxChars: number = SHARE_MAX_CHARS,
): readonly string[] {
  const all = lines.map((_, index) => index);
  return budgeted(lines, all, new Set(), {
    maxLines: Number.POSITIVE_INFINITY,
    maxSize: maxChars,
    size: (line) => line.length + 1,
  });
}
