# GitHub Actions

`.github/workflows/android-debug.yml` runs on main pushes, pull requests and manual dispatch. It installs Temurin JDK 17 and Gradle 8.9, validates project files, runs Android unit tests and lint, builds the debug APK, verifies a non-empty APK, uploads it as `nova-debug-apk` for 14 days and publishes a job summary. It needs no private API key or signing credential. `.github/workflows/backend.yml` installs Python 3.11 dependencies and runs repository tests.

These workflows are committed but have not been run by GitHub during this workspace checkpoint. The Android build is not locally verified. Future release signing must use protected GitHub environment secrets and a separate reviewed workflow; do not commit keystores, passwords, or production signing material.
