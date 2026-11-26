/**
 * N53: native's record file (`RecordFile.kt`: filesDir/record/session-record.ndjson and its one rotation, 5 MB each),
 * read whole, oldest line first. Every line native wrote is in it, JavaScript's while a pairing was held included
 * (CD7), across an app restart, as the JS ring (1,000 lines, in memory) is not.
 *
 * AGENTS §2's third call that answers (recorded there): one read on the record's own thread, no session state, never
 * read by the machine, the snapshot or the heartbeat, and never on the machine's failure path. Native answers the
 * file's text, or null when the disk refused it; the app gives up at [RECORD_READ_TIMEOUT_MS] and drops a late answer.
 */
export interface RecordFilePort {
  /** The file's lines, oldest first, with no empty line; null when there is no file to read. Never rejects. */
  read(): Promise<readonly string[] | null>;
}

/** A local read of at most about 10 MB: past this the disk is not answering, and the JS ring is sent instead. */
export const RECORD_READ_TIMEOUT_MS = 5_000;

/** No native record (iOS, the web, a test): nothing to read. */
export const NO_RECORD_FILE: RecordFilePort = { read: () => Promise.resolve(null) };

/**
 * `read` is native's `readRecord`, or null where there is none. A stale build with no `readRecord` throws inside the
 * read, a refused read rejects or answers null, and an answer that is not text is no file: each is null, so the caller
 * sends what it sent before N53, the ring's lines.
 */
export function createRecordFilePort(
  read: (() => Promise<unknown>) | null,
  timeoutMs: number = RECORD_READ_TIMEOUT_MS,
): RecordFilePort {
  if (read === null) return NO_RECORD_FILE;
  return {
    read: async () => {
      let timer: ReturnType<typeof setTimeout> | undefined;
      const gaveUp = new Promise<null>((resolve) => {
        timer = setTimeout(() => resolve(null), timeoutMs);
      });
      try {
        const text = await Promise.race([Promise.resolve().then(read), gaveUp]);
        return typeof text === 'string' ? linesOf(text) : null;
      } catch {
        return null;
      } finally {
        clearTimeout(timer);
      }
    },
  };
}

/** NDJSON's lines: a `\n` ends each, and a blank one (the file's last, a torn write's) is no line. */
export function linesOf(text: string): readonly string[] {
  return text.split('\n').filter((line) => line.trim() !== '');
}
