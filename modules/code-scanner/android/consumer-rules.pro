# Read by an app that minifies (R8). Google's code scanner is ML Kit, which starts through firebase-components: each
# ComponentRegistrar the merged manifest names (ML Kit's CommonComponentRegistrar, VisionCommonRegistrar) is built by
# reflection with its no-arg constructor. firebase-components keeps the registrars with no member list, which R8's
# strict keep rules (AGP 9.2.1, Expo 58) no longer read as keeping that constructor: the first SDK 58 release (build
# 1365) logged `InvalidRegistrarException ... NoSuchMethodException <init> []` at launch. Named here, whatever the
# app's `android.r8.strictFullModeForKeepRules` says. consumerRules.test.ts pins it.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
