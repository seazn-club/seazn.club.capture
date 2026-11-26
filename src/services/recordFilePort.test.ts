import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  createRecordFilePort,
  linesOf,
  NO_RECORD_FILE,
  RECORD_READ_TIMEOUT_MS,
} from '@/services/recordFilePort';

const A = '{"at":"2026-11-26T20:17:07.006Z","kind":"bitrate"}';
const B = '{"at":"2026-11-26T20:17:09.969Z","kind":"network","validated":true}';

describe('the record file port (N53)', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("reads native's text as its lines, oldest first, and clears its timer", async () => {
    const port = createRecordFilePort(() => Promise.resolve(`${A}\n${B}\n`));
    await expect(port.read()).resolves.toEqual([A, B]);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('asks native again on every read (a second call)', async () => {
    const read = vi.fn(() => Promise.resolve(`${A}\n`));
    const port = createRecordFilePort(read);
    await port.read();
    await port.read();
    expect(read).toHaveBeenCalledTimes(2);
  });

  it('an empty file is no line, and a missing native read is no file (an empty input)', async () => {
    await expect(createRecordFilePort(() => Promise.resolve('')).read()).resolves.toEqual([]);
    await expect(createRecordFilePort(null).read()).resolves.toBeNull();
    await expect(NO_RECORD_FILE.read()).resolves.toBeNull();
  });

  it('a refused read, a stale build with no readRecord, and an answer that is not text are each no file', async () => {
    await expect(
      createRecordFilePort(() => Promise.reject(new Error('io'))).read(),
    ).resolves.toBeNull();
    const stale = (): Promise<unknown> => {
      throw new TypeError('readRecord is not a function');
    };
    await expect(createRecordFilePort(stale).read()).resolves.toBeNull();
    for (const answer of [null, undefined, 42, ['a'], { text: A }]) {
      await expect(createRecordFilePort(() => Promise.resolve(answer)).read()).resolves.toBeNull();
    }
  });

  it('gives up at its bound, and a late answer changes nothing (after an interruption)', async () => {
    let answer: (text: string) => void = () => undefined;
    const port = createRecordFilePort(() => new Promise<string>((resolve) => (answer = resolve)));
    const read = port.read();
    await vi.advanceTimersByTimeAsync(RECORD_READ_TIMEOUT_MS - 1);
    let settled = false;
    void read.then(() => (settled = true));
    await Promise.resolve();
    expect(settled).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    await expect(read).resolves.toBeNull();
    answer(`${A}\n`);
    await expect(read).resolves.toBeNull();
  });

  it('a torn last line or blank lines are not lines, and CRLF-free text keeps every other', () => {
    expect(linesOf(`${A}\n\n${B}\n   \n`)).toEqual([A, B]);
    expect(linesOf(`${A}\n{"at":"2026-11-26T20:1`)).toEqual([A, '{"at":"2026-11-26T20:1']);
  });
});
