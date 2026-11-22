# Android setup

Requirements: Android Studio with JDK 17 and Android SDK 35; Gradle 8.9 is used in CI; AGP 8.7.3 and Kotlin 2.0.21 are pinned in Gradle configuration. Open repository root in Android Studio or run `gradle :app:assembleDebug`.

The Android app launches without backend credentials. In Settings, enter a deployed HTTPS backend URL and an already-created scoped NOVA API key. The token/base URL are encrypted with a random AES key stored in Android Keystore. API failures are shown in the assistant banner; there is no offline AI fallback. Clear credentials removes encrypted values. Current UI contains navigation shells for features not yet implemented; those screens explicitly say so.

Debug APK path after successful build: `app/build/outputs/apk/debug/app-debug.apk`. CI uploads it as artifact `nova-debug-apk`. This workspace has no Java/Gradle/Android SDK, so a local build has not been verified in this checkpoint.
