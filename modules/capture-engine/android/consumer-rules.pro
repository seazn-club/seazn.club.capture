# Final review M6. Read by an app that minifies (R8); the release build does not today.
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
