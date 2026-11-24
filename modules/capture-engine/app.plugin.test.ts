import { readFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { describe, expect, it } from 'vitest';
import appJson from '../../app.json';
import { buildPropertiesOf, checkKotlinGradlePlugin } from './app.plugin';

/**
 * C7u (owner, 2026-10-05): StreamPack 3.2.1 and its graph carry Kotlin metadata 2.4.0, which a Kotlin
 * compiler reads only from 2.3.0 on (a compiler reads one metadata version ahead of its own). Through Expo 57
 * the root build.gradle named the Kotlin Gradle plugin with no version, and this plugin wrote app.json's
 * `android.kotlinVersion` into it. Expo 58's template takes the version from that property itself
 * (`findProperty('android.kotlinVersion')`, which expo-build-properties writes to gradle.properties), so the
 * plugin now only checks that the template still does, and that the version reads metadata 2.4.
 */
const FROM_PROPERTY = "def kotlinVersion = findProperty('android.kotlinVersion')";
const VERSIONED_FROM_PROPERTY = '"org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion"';
/** Expo 57's line: the classpath with no version, which React Native's Gradle plugin then chose. */
const UNVERSIONED_ONLY = "classpath('org.jetbrains.kotlin:kotlin-gradle-plugin')";

/**
 * One file of a ustar archive, by its path: Expo's prebuild template ships as `expo/template.tgz`, and the
 * root `android/build.gradle` in it is what prebuild hands this plugin (review C7 re-review, Minor 1).
 */
function fromTarGz(archive: string, path: string): string {
  const tar = gunzipSync(readFileSync(new URL(archive, import.meta.url)));
  for (let at = 0; at + 512 <= tar.length;) {
    const field = (from: number, length: number) =>
      tar.toString('utf8', at + from, at + from + length).replace(/\0.*$/s, '');
    const name = field(0, 100);
    if (name === '') break;
    const prefix = field(345, 155);
    const size = parseInt(field(124, 12).trim(), 8);
    const start = at + 512;
    if ((prefix ? `${prefix}/${name}` : name) === path)
      return tar.toString('utf8', start, start + size);
    at = start + Math.ceil(size / 512) * 512;
  }
  throw new Error(`${path} is not in ${archive}`);
}

/** The root build.gradle Expo 58's prebuild writes, read from the installed template, never typed here. */
const TEMPLATE = fromTarGz('../../node_modules/expo/template.tgz', 'package/android/build.gradle');

describe('checkKotlinGradlePlugin', () => {
  it("reads Expo's real template, which versions the Kotlin Gradle plugin from android.kotlinVersion", () => {
    expect(TEMPLATE).toContain('buildscript {');
    expect(TEMPLATE.split(FROM_PROPERTY).length - 1).toBe(1);
    expect(TEMPLATE.split(VERSIONED_FROM_PROPERTY).length - 1).toBe(1);
  });

  it('leaves the template as it is: the version reaches the compiler without an edit', () => {
    expect(checkKotlinGradlePlugin(TEMPLATE, '2.3.0')).toBe(TEMPLATE);
  });

  it('leaves its own output as it is: a second prebuild over the same file', () => {
    const once = checkKotlinGradlePlugin(TEMPLATE, '2.3.0');
    expect(checkKotlinGradlePlugin(once, '2.3.0')).toBe(once);
  });

  // The one synthetic input: a template with no plugin at all. The others are the real one, changed.
  it('refuses a template that no longer reads android.kotlinVersion: the Expo template changed', () => {
    expect(() => checkKotlinGradlePlugin('buildscript {}\n', '2.3.0')).toThrow(/template changed/);
    expect(() => checkKotlinGradlePlugin(TEMPLATE.replace(FROM_PROPERTY, ''), '2.3.0')).toThrow(
      /template changed/,
    );
    expect(() =>
      checkKotlinGradlePlugin(TEMPLATE.replace(VERSIONED_FROM_PROPERTY, "''"), '2.3.0'),
    ).toThrow(/template changed/);
  });

  it("refuses Expo 57's template, whose classpath took React Native's Kotlin", () => {
    const expo57 = `buildscript {\n  dependencies {\n    ${UNVERSIONED_ONLY}\n  }\n}\n`;
    expect(() => checkKotlinGradlePlugin(expo57, '2.3.0')).toThrow(/template changed/);
  });

  // From the rule, not the code: metadata 2.4.0 needs a compiler of 2.3.0 or newer.
  it.each(['2.3.0', '2.3.21', '2.4.10', '3.0.0'])(
    'takes %s, which reads metadata 2.4',
    (version) => {
      expect(checkKotlinGradlePlugin(TEMPLATE, version)).toBe(TEMPLATE);
    },
  );

  it.each(['2.1.20', '2.2.21', '1.9.24', '', 'latest', '2.3', '2.3.0; rm'])(
    'refuses %j, which reads no metadata 2.4 or is no version',
    (version) => {
      expect(() => checkKotlinGradlePlugin(TEMPLATE, version)).toThrow(/Kotlin 2\.3\.0 or newer/);
    },
  );
});

describe('buildPropertiesOf', () => {
  const props = { android: { minSdkVersion: 31, compileSdkVersion: 37, kotlinVersion: '2.3.0' } };

  it("reads expo-build-properties' kotlinVersion and compileSdkVersion", () => {
    expect(buildPropertiesOf(['expo-router', ['expo-build-properties', props]])).toEqual({
      kotlinVersion: '2.3.0',
      compileSdk: 37,
    });
  });

  it('refuses a config with no expo-build-properties, or one without either value', () => {
    expect(() => buildPropertiesOf(undefined)).toThrow(/expo-build-properties/);
    expect(() => buildPropertiesOf([])).toThrow(/expo-build-properties/);
    expect(() => buildPropertiesOf(['expo-build-properties'])).toThrow(/expo-build-properties/);
    expect(() =>
      buildPropertiesOf([['expo-build-properties', { android: { compileSdkVersion: 37 } }]]),
    ).toThrow(/kotlinVersion/);
    expect(() =>
      buildPropertiesOf([['expo-build-properties', { android: { kotlinVersion: '2.3.0' } }]]),
    ).toThrow(/compileSdkVersion/);
  });
});

// The inert-seam guard: a plugin app.json does not name never runs at prebuild.
describe('app.json', () => {
  const plugins = appJson.expo.plugins as unknown[];

  it('runs the capture engine plugin, after expo-build-properties, whose values it reads', () => {
    const names = plugins.map((plugin) => (Array.isArray(plugin) ? plugin[0] : plugin));
    const engine = names.indexOf('./modules/capture-engine/app.plugin.ts');
    expect(names.indexOf('expo-build-properties')).toBeGreaterThanOrEqual(0);
    expect(engine).toBeGreaterThan(names.indexOf('expo-build-properties'));
  });

  it('names a plugin that registers its one Android mod, read with what app.json holds', async () => {
    const reference = './modules/capture-engine/app.plugin.ts';
    const plugin = (await import(`../../${reference.slice(2)}`)).default as (config: object) => {
      mods?: { android?: Record<string, unknown> };
    };
    const config = plugin({ name: 'capture', slug: 'capture', plugins });
    // Expo 57's second mod, gradleProperties (AGP 8.12's compile SDK 37 suppression), went with that SDK.
    expect(Object.keys(config.mods?.android ?? {}).sort()).toEqual(['projectBuildGradle']);
  });

  it('asks for the lowest Kotlin that reads metadata 2.4, and compile SDK 37 (ruling C7u-toolchain)', () => {
    expect(buildPropertiesOf(plugins)).toEqual({ kotlinVersion: '2.3.0', compileSdk: 37 });
  });
});
