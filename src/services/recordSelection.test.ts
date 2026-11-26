import { describe, expect, it } from 'vitest';
import { tokenTag } from '@/domain/credentials/tokenTag';
import { fitLine, RECORD_MAX_BYTES, RECORD_MAX_LINES } from '@/services/recordContract';
import {
  mergeRecord,
  selectForCode,
  selectForShare,
  SHARE_MAX_CHARS,
} from '@/services/recordSelection';

/** Made-up tokens: only their tags reach a line. */
const OURS = tokenTag('test-token-ours-0001');
const THEIRS = tokenTag('test-token-theirs-0002');
const SID = '66753ccd-a784-4799-b0bf-5a80fb7f5e37';
const OTHER_SID = 'b6ec9f69-8592-44f4-954a-c110b7ff6b5c';

/** A record line as both writers write it: `at` first, then `kind`, then the fields. */
const line = (second: number, kind: string, fields: Record<string, unknown> = {}): string =>
  JSON.stringify({
    at: new Date(Date.UTC(2026, 10, 26, 20, 0, 0) + second * 1_000).toISOString(),
    kind,
    ...fields,
  });

const pair = (second: number, tag: string) => line(second, 'pair', { claim: 'new', tokenTag: tag });

const send = (lines: readonly string[], tag = OURS) =>
  selectForCode(lines, tag, RECORD_MAX_LINES, RECORD_MAX_BYTES);

describe('Send record picks what the server files under the code (N53, the contract’s A1 and A2)', () => {
  it('keeps the code’s window and its broadcast’s lines, and drops another code’s and a line after the end', () => {
    const ours = [
      pair(1, OURS),
      line(2, 'paired'),
      line(3, 'broadcast-start', { sid: SID, cause: 'operator' }),
      line(4, 'bitrate', { from: 1_500_000, to: 1_800_000, reason: 'raise' }),
      line(5, 'ended', { reason: 'operator-stopped', wasLive: true }),
    ];
    const after = [
      line(6, 'bitrate-floor', { reason: 'drops', count: 2 }),
      line(7, 'stopped-delivered', { sid: SID }),
    ];
    const theirs = [
      pair(8, THEIRS),
      line(9, 'broadcast-start', { sid: OTHER_SID }),
      line(10, 'live'),
    ];
    const lateOurs = line(11, 'record.send', { sid: SID, result: 'sent' });
    const picked = send([...ours, ...after, ...theirs, lateOurs]);
    expect(picked).toEqual([...ours, after[1], lateOurs]);
  });

  it('keeps every window of the code, across an app restart, and nothing between them', () => {
    const first = [pair(1, OURS), line(2, 'paired'), line(3, 'reset')];
    const idle = [line(4, 'scoring.open'), line(5, 'app.start')];
    const second = [pair(6, OURS), line(7, 'paired'), line(8, 'live')];
    expect(send([...first, ...idle, ...second])).toEqual([...first, ...second]);
  });

  it('another code’s pair closes the window, and a pair of the code reopens it', () => {
    const lines = [
      pair(1, OURS),
      line(2, 'a'),
      pair(3, THEIRS),
      line(4, 'b'),
      pair(5, OURS),
      line(6, 'c'),
    ];
    expect(send(lines)).toEqual([lines[0], lines[1], lines[4], lines[5]]);
  });

  it('a masked sid and a sid of no window are dropped, a torn line goes nowhere and closes nothing', () => {
    const torn = '{"at":"2026-11-26T20:00:03.0';
    const lines = [
      pair(1, OURS),
      line(2, 'broadcast-start', { sid: '***' }),
      torn,
      line(4, 'live'),
      line(5, 'resumed', { sid: OTHER_SID }),
      '{"kind":"no-time"}',
    ];
    expect(send(lines)).toEqual([lines[0], lines[3]]);
  });

  it('nothing of the code (an empty input) picks nothing, whatever else is there', () => {
    expect(send([])).toEqual([]);
    expect(send([pair(1, THEIRS), line(2, 'live'), line(3, 'idle-line')])).toEqual([]);
  });

  /** The 40-minute run's shape: StreamPack's lines are most of it (10,112 of 10,662). */
  const busy = (vendor: number, other: number): string[] => {
    const lines = [pair(0, OURS), line(1, 'broadcast-start', { sid: SID })];
    for (let i = 0; i < vendor + other; i += 1) {
      const second = 2 + i;
      lines.push(
        i % ((vendor + other) / other) === 0
          ? line(second, 'bitrate', { from: 1, to: 2, reason: 'raise' })
          : line(second, 'vendor-log', { level: 'warn', tag: 'VideoEncoder', message: `m${i}` }),
      );
    }
    return lines;
  };

  it('over 1,000 lines, the pins and the broadcast’s own lines go before StreamPack’s, which keep their newest', () => {
    const lines = busy(1_800, 200);
    const picked = send(lines);
    expect(picked).toHaveLength(RECORD_MAX_LINES);
    expect(picked.slice(0, 2)).toEqual(lines.slice(0, 2));
    const others = lines.filter((each) => each.includes('"kind":"bitrate"'));
    expect(others).toHaveLength(200);
    expect(picked.filter((each) => each.includes('"kind":"bitrate"'))).toEqual(others);
    const vendor = picked.filter((each) => each.includes('vendor-log'));
    expect(vendor).toHaveLength(RECORD_MAX_LINES - 2 - 200);
    // The newest: the picked vendor lines are the last ones written, in their order.
    const allVendor = lines.filter((each) => each.includes('vendor-log'));
    expect(vendor).toEqual(allVendor.slice(allVendor.length - vendor.length));
    // In the order written.
    expect([...picked].sort((a, b) => lines.indexOf(a) - lines.indexOf(b))).toEqual(picked);
  });

  it('with more of the broadcast’s own lines than fit, the pins stay and the newest of the rest go', () => {
    const lines = [pair(0, OURS), line(1, 'broadcast-start', { sid: SID })];
    for (let i = 0; i < 1_500; i += 1) lines.push(line(2 + i, 'bitrate', { count: i }));
    const picked = send(lines);
    expect(picked).toHaveLength(RECORD_MAX_LINES);
    expect(picked.slice(0, 2)).toEqual(lines.slice(0, 2));
    expect(picked.slice(2)).toEqual(lines.slice(lines.length - (RECORD_MAX_LINES - 2)));
  });

  it('keeps the body within 1 MB as the upload writes it, a fitted line by its fitted size', () => {
    const message = 'x'.repeat(4_000);
    const lines = [pair(0, OURS)];
    // An unlisted key is dropped by fitLine, so it costs nothing here; the message is a listed key and costs its bytes.
    for (let i = 0; i < 400; i += 1)
      lines.push(line(1 + i, 'vendor-log', { message, unlistedKeyThatIsDropped: message }));
    const picked = send(lines);
    const bytes = picked.reduce(
      (sum, each) => sum + new TextEncoder().encode(fitLine(each)).length + 1,
      0,
    );
    expect(bytes).toBeLessThanOrEqual(RECORD_MAX_BYTES);
    // Each fitted line is about 4,060 bytes, so about 258 go; unfitted (about 8,100) only 129 would.
    expect(picked.length).toBeGreaterThan(200);
    expect(picked[0]).toBe(lines[0]);
  });

  it('a line too big alone is skipped, and a smaller one after it still goes', () => {
    // Newest first in one tier: the huge line is met first, and the older small one must still be weighed.
    const huge = line(2, 'code', { message: 'y'.repeat(RECORD_MAX_BYTES) });
    const lines = [pair(0, OURS), line(1, 'live'), huge];
    expect(send(lines)).toEqual([lines[0], lines[1]]);
  });
});

describe('the file and the ring, merged (N53)', () => {
  const a = line(1, 'paired');
  const b = line(3, 'live');
  const c = line(5, 'ended');

  it('a ring line the file holds goes once; one the file lacks goes in its time', () => {
    const idle = line(2, 'scoring.open');
    expect(mergeRecord([a, b, c], [b, idle, c])).toEqual([a, idle, b, c]);
  });

  it('equal lines are counted, never collapsed', () => {
    expect(mergeRecord([a, b], [b, b])).toEqual([a, b, b]);
    expect(mergeRecord([b, b], [b])).toEqual([b, b]);
  });

  it('a ring line at the same time as a file line goes after it, and one with no time goes last', () => {
    const same = line(3, 'intent.stop');
    expect(mergeRecord([a, b, c], [same])).toEqual([a, b, same, c]);
    expect(mergeRecord([a, b], ['not json'])).toEqual([a, b, 'not json']);
  });

  it('an empty file is the ring, and an empty ring is the file', () => {
    expect(mergeRecord([], [a, b])).toEqual([a, b]);
    expect(mergeRecord([a, b], [])).toEqual([a, b]);
  });
});

describe('Share record shares every code’s lines, within the share sheet’s size (N53)', () => {
  it('everything, in order, when it fits', () => {
    const lines = [pair(0, THEIRS), line(1, 'live'), pair(2, OURS), line(3, 'vendor-log')];
    expect(selectForShare(lines)).toEqual(lines);
  });

  it('over the cap, the other lines before StreamPack’s, newest first, back in order', () => {
    const lines = [
      line(0, 'pair'),
      line(1, 'vendor-log', { message: 'a'.repeat(100) }),
      line(2, 'live'),
      line(3, 'vendor-log', { message: 'b'.repeat(100) }),
    ];
    const cap = lines[0]!.length + lines[2]!.length + lines[3]!.length + 3;
    expect(selectForShare(lines, cap)).toEqual([lines[0], lines[2], lines[3]]);
  });

  it('the cap is about 500 KB of the intent’s UTF-16, over the 1,000 lines shared on 2026-11-26', () => {
    expect(SHARE_MAX_CHARS).toBe(250_000);
  });
});
