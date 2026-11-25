import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * The inert seam (AGENTS §13, failure class 1): `createNativePorts` imports every native module, so it cannot be built
 * on a laptop. `Ports.incomingLink` is required, so tsc fails if it is left out; this pins that what fills it is the
 * `expo-linking` implementation, never the fake.
 */
describe('createNativePorts wiring', () => {
  const source = readFileSync(join(__dirname, 'nativePorts.ts'), 'utf8');

  it('fills incomingLink with the expo-linking implementation', () => {
    expect(source).toMatch(/incomingLink:\s*createIncomingLink\(\)/);
    expect(source).toContain("from '@/services/incomingLink'");
  });

  it('never wires the fake', () => {
    expect(source).not.toContain('fakeIncomingLink');
  });
});
