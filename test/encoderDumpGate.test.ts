import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { appVariant } from '../app.config';

/**
 * Spec 2026-11-25-encoder-diagnostics §3: the encoder dump is the dev twin's only. Native builds it only when the app's
 * package is `EncoderDumpRules.DEV_PACKAGE` (GlueRulesTest pins the one construction behind that gate). This pins the
 * constant to the package app.config.ts gives the dev twin, and apart from the one eas.json's production profile
 * builds, so a rename of either side reds here before a production build could ever match the gate.
 */
const read = (path: string) => readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf8');

const rules = read(
  '../modules/capture-engine/android/core/src/main/kotlin/com/seazn/capture/engine/adapter/EncoderDumpRules.kt',
);
const gated = /const val DEV_PACKAGE = "([^"]+)"/.exec(rules)?.[1];
const app = JSON.parse(read('../app.json')) as { expo: Parameters<typeof appVariant>[0] };
const eas = JSON.parse(read('../eas.json')) as {
  build: Record<string, { env?: Record<string, string> }>;
};

describe('the encoder dump gate (dev twin only)', () => {
  it('names the dev twin’s package, as app.config.ts builds it', () => {
    expect(gated).toBe('com.seazn.capture.dev');
    expect(appVariant(app.expo, {}).android?.package).toBe(gated);
  });

  it('never matches the package eas.json’s production profile builds', () => {
    const production = appVariant(app.expo, eas.build.production?.env ?? {}).android?.package;
    expect(production).toBe('com.seazn.capture');
    expect(production).not.toBe(gated);
  });

  it('is an exact comparison, so a package that merely starts with the dev twin’s does not pass', () => {
    expect(rules).toContain(
      'fun allowed(packageName: String?): Boolean = packageName == DEV_PACKAGE',
    );
  });
});
