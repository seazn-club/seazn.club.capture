import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

/**
 * Google's code scanner is ML Kit, and ML Kit starts through firebase-components: its MlKitInitProvider builds each
 * ComponentRegistrar the merged manifest names (CommonComponentRegistrar, VisionCommonRegistrar) with its no-arg
 * constructor, by reflection. firebase-components' own rule keeps the registrars with no member list, which R8's
 * strict keep rules (AGP 9.2.1, Expo 58) read as "not the constructor": the first SDK 58 release (build 1365) logged
 * `InvalidRegistrarException ... NoSuchMethodException <init> []` at launch. This module's rule names it.
 */
describe('the code scanner hands R8 the rule ML Kit needs', () => {
  it('names one consumer rule file', () => {
    const gradle = read('./android/build.gradle');
    expect([...gradle.matchAll(/consumerProguardFiles\s+'([^']+)'/g)].map((m) => m[1])).toEqual([
      'consumer-rules.pro',
    ]);
  });

  it("keeps every ComponentRegistrar's no-arg constructor", () => {
    expect(read('./android/consumer-rules.pro')).toContain(
      '-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }',
    );
  });
});
