/**
 * The capture engine's config plugin: the app's Kotlin toolchain for StreamPack 3.2.1 (C7u, owner
 * 2026-10-05), as pure functions [withCaptureEngine] applies at prebuild. One file, since Expo loads a
 * TypeScript plugin by itself and resolves no `.ts` import from it.
 *
 * StreamPack 3.2.1 and its graph carry Kotlin metadata 2.4.0, which a Kotlin compiler reads only from
 * 2.3.0 on (one metadata version ahead of its own). Through Expo 57 the root build.gradle named the Kotlin
 * Gradle plugin with no version, so React Native's Gradle plugin brought its own (2.1.20), and this plugin
 * wrote app.json's `android.kotlinVersion` into the classpath. Expo 58's template versions that classpath
 * from the `android.kotlinVersion` Gradle property itself, which expo-build-properties writes, so app.json's
 * one knob reaches the compiler with no edit. [checkKotlinGradlePlugin] now only checks that the template
 * still does, and that the version reads metadata 2.4: a build that missed either would fail far from here,
 * on StreamPack's metadata.
 */

import {
  type ConfigPlugin,
  withGradleProperties,
  withProjectBuildGradle,
} from 'expo/config-plugins';

export type GradleProperty =
  | { type: 'property'; key: string; value: string }
  | { type: 'comment'; value: string }
  | { type: 'empty' };

/** Expo 58's two template lines that take the Kotlin Gradle plugin's version from the property. */
const FROM_PROPERTY = "def kotlinVersion = findProperty('android.kotlinVersion')";
const VERSIONED_FROM_PROPERTY = '"org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion"';

/** The oldest compiler that reads metadata 2.4.0: one metadata version ahead of its own. */
const READS_METADATA_2_4 = (version: string): boolean => {
  const parts = /^(\d+)\.(\d+)\.(\d+)$/.exec(version);
  if (!parts) return false;
  const [major, minor] = [Number(parts[1]), Number(parts[2])];
  return major > 2 || (major === 2 && minor >= 3);
};

/**
 * The root build.gradle, unchanged, once it is known to version the Kotlin Gradle plugin from
 * `android.kotlinVersion` and [version] is known to read metadata 2.4. A template that does not is refused
 * rather than left at React Native's Kotlin.
 */
export function checkKotlinGradlePlugin(buildGradle: string, version: string): string {
  if (!READS_METADATA_2_4(version)) {
    throw new Error(
      `capture-engine: android.kotlinVersion ${JSON.stringify(version)} must be Kotlin 2.3.0 or newer, which reads StreamPack 3.2.1's metadata 2.4 (C7u)`,
    );
  }
  if (!buildGradle.includes(FROM_PROPERTY) || !buildGradle.includes(VERSIONED_FROM_PROPERTY)) {
    throw new Error(
      'capture-engine: the root build.gradle no longer versions the Kotlin Gradle plugin from android.kotlinVersion: the Expo template changed (C7u)',
    );
  }
  return buildGradle;
}

export function buildPropertiesOf(plugins: readonly unknown[] | undefined): {
  kotlinVersion: string;
  compileSdk: number;
} {
  const entry = (plugins ?? []).find(
    (plugin): plugin is [string, { android?: Record<string, unknown> }] =>
      Array.isArray(plugin) &&
      plugin[0] === 'expo-build-properties' &&
      typeof plugin[1] === 'object',
  );
  if (!entry)
    throw new Error(
      'capture-engine: app.json configures no expo-build-properties android values (C7u)',
    );
  const android = entry[1].android ?? {};
  const kotlinVersion = android.kotlinVersion;
  const compileSdk = android.compileSdkVersion;
  if (typeof kotlinVersion !== 'string')
    throw new Error('capture-engine: expo-build-properties sets no android.kotlinVersion (C7u)');
  if (typeof compileSdk !== 'number')
    throw new Error(
      'capture-engine: expo-build-properties sets no android.compileSdkVersion (C7u)',
    );
  return { kotlinVersion, compileSdk };
}

const STRICT_KEEP_RULES = 'android.r8.strictFullModeForKeepRules';

/**
 * R8's keep rules as AGP 8 read them, the release build reviewed and tested on a phone (2026-11-24). AGP 9.2.1
 * turns `android.r8.strictFullModeForKeepRules` on by default (8.12.0's BooleanOption defaults it to false, 9.2.1's
 * to true, until AGP 10): a `-keep class X` with no member list then no longer keeps X's no-arg constructor, so a
 * class a library builds by reflection keeps its name and loses the constructor (ML Kit's ComponentRegistrars, on
 * the first SDK 58 release). Off, every library's rules mean what they meant. One entry, whatever ran before.
 */
export function withAgp8KeepRules(items: GradleProperty[]): GradleProperty[] {
  const others = items.filter(
    (item) => !(item.type === 'property' && item.key === STRICT_KEEP_RULES),
  );
  return [...others, { type: 'property', key: STRICT_KEEP_RULES, value: 'false' }];
}

/**
 * The capture engine's prebuild (C7u): a check that the Kotlin compiler is the one app.json's
 * expo-build-properties names, and R8's keep rules as AGP 8 read them (expo-build-properties sets no such
 * property). app.json runs this after expo-build-properties. Through Expo 57 it also set
 * `android.suppressUnsupportedCompileSdk=37.0`, since AGP 8.12 was tested up to compile SDK 36; AGP 9.2.1
 * (Expo 58) configures compile SDK 37 with no warning, so the property went with that SDK.
 */
const withCaptureEngine: ConfigPlugin = (config) => {
  const { kotlinVersion } = buildPropertiesOf(config.plugins);
  const checked = withProjectBuildGradle(config, (mod) => {
    mod.modResults.contents = checkKotlinGradlePlugin(mod.modResults.contents, kotlinVersion);
    return mod;
  });
  return withGradleProperties(checked, (mod) => {
    mod.modResults = withAgp8KeepRules(mod.modResults as GradleProperty[]);
    return mod;
  });
};

export default withCaptureEngine;
