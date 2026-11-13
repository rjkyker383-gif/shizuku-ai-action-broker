# Android CI and TC22 install

The `Android APK Build and Tests` workflow builds the arm64 debug APK on GitHub-hosted Ubuntu runners. It checks out the pinned llama.cpp submodule, runs `:app:testDebugUnitTest`, assembles `:app:assembleDebug`, and uploads `shizuku-ai-action-broker-tc22-debug` for 14 days.

After a successful run, download and extract the workflow artifact. With the TC22 connected over ADB, install using `adb -s <device-serial> install -r app-debug.apk`. This is a development build. Do not install an artifact from a failed or incomplete run.