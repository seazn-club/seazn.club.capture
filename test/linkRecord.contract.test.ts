import { readFileSync } from 'node:fs';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import { describe, expect, it } from 'vitest';
import { createLogger } from '@/services/logger';
import { createRingRecord } from '@/services/sessionRecord';

/**
 * The `link-opened` lines Open in app writes (plan 2026-11-25, Task 4), judged by ajv against the vendored
 * capture-record.v1, which is `additionalProperties: false`: a key the contract does not list is a line the server
 * drops. They are written through the real logger and ring, so the scrub's own output is what is judged.
 */
const contract = JSON.parse(
  readFileSync(new URL('../contracts/capture-record.v1.json', import.meta.url), 'utf8'),
) as { readonly $id: string };
const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);
ajv.addSchema(contract);
const validate = ajv.getSchema(contract.$id);

function linesFor(fields: Record<string, string>): string[] {
  const record = createRingRecord();
  const logger = createLogger({ record, now: () => Date.parse('2026-11-25T10:00:00.000Z') });
  logger.info('link-opened', fields);
  return [...record.lines()];
}

describe('the link-opened record line', () => {
  it.each([
    ['handled', { mode: 'stream', state: 'handled' }],
    ['held', { mode: 'matchday', state: 'held' }],
    ['foreign', { mode: 'scoring', state: 'foreign' }],
    ['foreign with no mode', { state: 'foreign' }],
  ])('is a line the contract admits (%s)', (_name, fields) => {
    if (validate === undefined) throw new Error('no record schema');
    const [line] = linesFor(fields);
    expect(line).toBeDefined();
    const parsed = JSON.parse(line ?? '{}') as Record<string, unknown>;
    expect(parsed).toEqual({ at: '2026-11-25T10:00:00.000Z', kind: 'link-opened', ...fields });
    expect(validate(parsed), JSON.stringify(validate.errors)).toBe(true);
  });

  it('is refused by the contract once a key it does not list is added (the check can fail)', () => {
    if (validate === undefined) throw new Error('no record schema');
    const bad = { at: '2026-11-25T10:00:00.000Z', kind: 'link-opened', url: 'x', state: 'held' };
    expect(validate(bad)).toBe(false);
  });
});
