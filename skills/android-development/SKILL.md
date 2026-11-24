# Android Development Skill

## Purpose
Build secure, accessible native Android features in Kotlin and Jetpack Compose.

## Required knowledge
Kotlin, Android lifecycle, Compose, coroutines, Android Keystore, Gradle.

## Supported tools
Android SDK, Gradle, unit/UI test runners, Android Keystore.

## Development rules
Keep UI state lifecycle-aware; minimize permissions; use WorkManager only for deferrable persistent work. Never put API credentials in resources or BuildConfig.

## Implementation procedure
Add a ViewModel/state contract; implement loading/empty/error/offline states; keep network I/O off the main thread; persist secrets only via Keystore-backed encryption.

## Security requirements
Request least-privilege runtime permissions and explain denial states.

## Testing procedure
Run Gradle unit tests, lint, then assemble debug APK.

## Troubleshooting
Check SDK/AGP/JDK compatibility; inspect lint and manifest permission declarations.

## References
https://github.com/android/compose-samples ; https://developer.android.com/topic/libraries/architecture/workmanager
