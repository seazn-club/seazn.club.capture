import { readFileSync } from 'node:fs';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import { describe, expect, it } from 'vitest';
import shared from './wire/realtime-delivery-vectors.json';

/**
 * The shared vectors Kotlin's RealtimeBodiesTest reads (a beat answer's `realtime`, T46's `delivery`) against the
 * vendored capture-beat.v1 (web 55d5bcc87d, #1301), so the native readers' made-up values and enum sets cannot drift
 * from the web's. The Kotlin readers are looser than the contract by design (a trailing slash or a port on the origin),
 * so only the valid grant and the closed word sets are pinned here.
 */
const beat = JSON.parse(
  readFileSync(new URL('../../../contracts/capture-beat.v1.json', import.meta.url), 'utf8'),
) as { $id: string };

/** Every `properties.<key>` object under the schema, in document order. */
function propertiesNamed(node: unknown, key: string, found: Record<string, unknown>[] = []) {
  if (typeof node !== 'object' || node === null) return found;
  const record = node as Record<string, unknown>;
  const props = record.properties as Record<string, unknown> | undefined;
  if (props !== undefined && typeof props[key] === 'object') found.push(props[key] as never);
  for (const value of Object.values(record)) propertiesNamed(value, key, found);
  return found;
}

describe('the realtime and delivery vectors against the vendored capture-beat.v1', () => {
  const realtimes = propertiesNamed(beat, 'realtime');
  const deliveries = propertiesNamed(beat, 'delivery');

  it('the contract carries them on the answers that may (realtime on three, delivery on two)', () => {
    expect(realtimes.length).toBeGreaterThanOrEqual(3);
    expect(deliveries.length).toBeGreaterThanOrEqual(2);
  });

  it('the valid grant is admitted by every realtime shape the contract names', () => {
    for (const schema of realtimes) {
      const ajv = new Ajv2020({ allErrors: true, strict: false });
      addFormats(ajv);
      expect(ajv.compile(schema)(shared.realtime.valid)).toBe(true);
    }
  });

  it("the delivery word sets are the contract's own", () => {
    const delivery = deliveries.find((d) => 'properties' in d) as {
      properties: Record<string, { enum?: string[] }>;
    };
    const words = (name: string) => [...(delivery.properties[name]?.enum ?? [])].sort();
    expect(words('relayOutput')).toEqual(['backoff', 'connected', 'connecting']);
    expect(words('programmePhase')).toEqual(['final', 'frozen', 'live', 'reconnecting', 'waiting']);
    expect(words('cameraIngest')).toEqual(['connected', 'disconnected', 'errored']);
    expect(words('programmeIngest')).toEqual(['connected', 'disconnected', 'errored']);
  });
});
