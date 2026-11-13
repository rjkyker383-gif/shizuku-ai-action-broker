# Android SDK setup

The workflow uses `android-actions/setup-android@v3` with `packages: ''` so it installs command-line tools and accepts licenses without requesting the removed `tools` SDK package. The workflow then installs Android 34, build-tools 34.0.0, NDK 27.2.12479018, and CMake 3.22.1 with `sdkmanager`.