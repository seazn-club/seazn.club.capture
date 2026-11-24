# Final review M6. Read by an app that minifies (R8): the release build does, since 2026-11-24 (app.json's
# expo-build-properties `enableMinifyInReleaseBuilds`; docs/release.md).
#
# AudioSessionIds reads StreamPack's private `audioRecord` field by its name, by reflection, walking up
# from MicrophoneSource to the class that declares it (3.2.1, javap). Renamed or removed by R8, MicWatch
# would hear nothing and each opening would write `watch-failed {action: "mic-session"}`. GlueRulesTest
# holds the field's name here to the name AudioSessionIds reads.
-keepclassmembers class io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.AudioRecordSource {
    private android.media.AudioRecord audioRecord;
}

# N49: SeaznSrtSink reads srtdroid 1.10.1's SRT socket id by reflection (CoroutineSrtSocket's private `socket`, then
# SrtSocket's private `srtsocket`; srtdroid's own rules keep its core package, not ktx), and our JNI shim's native
# method is found by its name.
-keepclassmembers class io.github.thibaultbee.srtdroid.ktx.CoroutineSrtSocket {
    private final io.github.thibaultbee.srtdroid.core.models.SrtSocket socket;
}
-keepclasseswithmembernames class com.seazn.capture.engine.srt.SrtNetworkBinder {
    native <methods>;
}

# R8 (2026-11-24): two core readers decide behaviour by a package's name. CameraFailures (core adapter) tells a
# camera's own failure by StreamPack's camera source package, on a class name or a throw's first frame, and the
# capture then rebuilds the camera. TlsCloserGuard swallows Ktor's TLS closer throw only with an `io.ktor.network.`
# frame in its chain (F-P5-12: otherwise Android kills the process). Renamed, or inlined into a caller elsewhere,
# neither would match, so each package keeps its names and stays where it is. GlueRulesTest pins both prefixes.
-keep,allowshrinking class io.github.thibaultbee.streampack.core.elements.sources.video.camera.** { *; }
-keep,allowshrinking class io.ktor.network.** { *; }

# The session record names a failure by its class (`error`, `rootError`, `site`; CD30, never a message) and the
# engine's own words by theirs (`start-answered {answer}`, `beats {word}`, `command-failed`). Kept legible, never
# `a.b`; InnerClasses gives a nested class its simple name. `capture-start-failed {site}` (F-S4-1, F-S7-1) is a throw's
# first frame outside the runtimes, as `Type.method`: CaptureRun skips Kotlin's frames by the `kotlin.` prefix, so
# Kotlin keeps its names, and the frames it lands on (the engine, StreamPack, srtdroid, coroutines) keep theirs,
# members included. GlueRulesTest pins each package to CaptureRun's RUNTIME list.
-keepnames class * extends java.lang.Throwable
-keepnames class kotlin.** { *; }
-keepnames class kotlinx.coroutines.** { *; }
-keepnames class io.github.thibaultbee.srtdroid.** { *; }
-keepnames class io.github.thibaultbee.streampack.** { *; }
-keepnames class com.seazn.capture.engine.** { *; }
-keepattributes InnerClasses,EnclosingMethod

# Expo 58 (AGP 9.2.1, 2026-11-24): with R8's strict keep rules, a member-less `-keep class` no longer keeps the no-arg
# constructor. Ktor's own rule (ktor-utils-jvm) keeps its engine container that way, and ServiceLoader builds the
# container with that constructor, so it is named here too, whatever `android.r8.strictFullModeForKeepRules` says
# (the app turns it off: modules/capture-engine/app.plugin.ts). GlueRulesTest pins the rule.
-keep class io.ktor.client.engine.** implements io.ktor.client.HttpClientEngineContainer { <init>(); }
