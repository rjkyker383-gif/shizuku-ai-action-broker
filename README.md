# shizuku-ai-action-broker

Permissioned AI-to-Shizuku action broker for Android. Chatbots request structured device actions, the broker validates them against a strict allow-list, and user-visible authorization stays in the loop.

## Current MVP

The Android app now provides:

- package `com.rjkyker.shizukuai.broker`
- Shizuku provider integration
- Binder/permission/backend-UID status
- explicit Shizuku permission request UI
- the existing seven-action allow-list
- regression tests that reject arbitrary shell-style action names
- GitHub Actions debug-APK builds

The AI does **not** receive a raw shell primitive. New capabilities should be added as explicit structured actions with validation and confirmation.

## Build

CI builds with Java 17, Gradle 8.10.2, Android SDK 35, and Shizuku API/provider 13.1.5.

Locally, with Gradle and Android SDK 35 available:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

See `docs/DEVICE_SETUP.md` for ADB, Shizuku, installation, and permission steps.
