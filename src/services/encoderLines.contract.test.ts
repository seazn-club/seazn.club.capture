import { readFileSync } from 'node:fs';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import { describe, expect, it } from 'vitest';
import list from '../../modules/capture-engine/src/scrub/allow-list.json';
import vectors from '../../modules/capture-engine/src/scrub/encoder-lines.json';
import { fitLine } from '@/services/recordContract';

/**
 * Spec 2026-11-25-encoder-diagnostics §4: the encoder-format and encoder-dump lines, exactly as native writes them
 * (EncoderLinesVectorsTest pins the file to SessionRecord's output), are admitted by the web's vendored record contract.
 * A line object there is closed (additionalProperties false): a key it does not list makes the web drop the whole line,
 * so a new key on either line reds here first.
 */
const contract = JSON.parse(
  readFileSync(new URL('../../contracts/capture-record.v1.json', import.meta.url), 'utf8'),
) as { readonly $id: string };
const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);
const admitsLine = ajv.compile(contract);

describe('the encoder diagnostics lines against the record contract', () => {
  it('holds one case for each line native writes', () => {
    expect(vectors.lines).toHaveLength(4);
    expect(
      new Set(vectors.lines.map(({ line }) => (JSON.parse(line) as { kind: string }).kind)),
    ).toEqual(new Set(['encoder-format', 'encoder-dump']));
  });

  it.each(vectors.lines)('admits $why', ({ line }) => {
    const parsed: unknown = JSON.parse(line);
    expect(admitsLine(parsed), JSON.stringify(admitsLine.errors)).toBe(true);
    expect(fitLine(line)).toBe(line);
  });

  it('lists both kinds in the allow-list', () => {
    expect(Object.keys(list.kinds)).toEqual(
      expect.arrayContaining(['encoder-format', 'encoder-dump']),
    );
  });

  // The web's own fixture (line-invalid-unlisted-scalar): a key the contract does not list fails the line. A profile
  // key, the obvious one to add, is such a key today, which is why the format rides `state` as one word.
  it('refuses the same line with a profile key the contract does not list', () => {
    const line = JSON.parse(vectors.lines[0]?.line ?? '{}') as Record<string, unknown>;
    expect(admitsLine({ ...line, profile: 'high' })).toBe(false);
  });
});
