# Android setup

Requirements: Android Studio with JDK 17 and Android SDK 35; Gradle 8.9 is used in CI; AGP 8.7.3 and Kotlin 2.0.21 are pinned in Gradle configuration. Open repository root in Android Studio or run `gradle :app:assembleDebug`.

The app uses **zero-login onboarding**: INSTALL → OPEN → USE NOVA. On first launch it creates a random persistent device ID and calls the NOVA Central Server installation registration endpoint. Installation ID, workspace ID, installation token, device ID and expiry are encrypted at rest with the Android Keystore-backed `SecureSettings` store. Near expiry or after a 401, the app refreshes the installation automatically. Regular users never need an account, a login, or an AI provider/gateway API key. Provider credentials and admin secrets are never compiled into the APK or sent by the Android client.

The service URL and endpoint contract are documented in [NOVA Gateway Android integration](NOVA_GATEWAY_ANDROID.md). If the service is unavailable, the UI shows a retryable connection state; it does not fabricate AI responses. The debug APK path after a successful build is `app/build/outputs/apk/debug/app-debug.apk`; CI uploads artifact `nova-debug-apk`.
