# TC22 device tools and settings guardians

This feature slice adds owner-launched utilities to the 1.9.0 development line.

## Storage manager

The storage manager uses Android's Storage Access Framework (SAF). The owner selects a folder and grants access to that tree. Within the granted tree it can list folders/files, create folders, rename, copy, move, delete with confirmation, and create or extract ZIP/TAR archives. Extraction rejects absolute and `..` paths and skips same-name existing files. It does not request `MANAGE_EXTERNAL_STORAGE` or silently bypass Android's scoped-storage rules. Android's protected `Android/data` and other private app directories may remain unavailable through the system picker; Shizuku does not make every protected path universally accessible.

## Cache explorer

The cache explorer measures and clears only this broker's `cacheDir` and `externalCacheDir`, with confirmation. Android intentionally hides other apps' private cache files from ordinary apps. The screen links to Android's app-details storage page for owner-managed inspection of other apps.

## Wi-Fi saved-network helper

The Wi-Fi button opens Android Wi-Fi settings. On Android 10+ the owner can select a saved network and use the system Share/QR flow where the OEM exposes it. The broker does not decrypt Wi-Fi configuration files, dump credentials, or claim to bypass the unrooted TC22's platform restrictions.

## Settings guardians

Guardians are OFF until the owner starts them. Each pin is an explicit namespace/key/value tuple. Supported namespaces are `global`, `system`, `secure`, and the limited app-owned property prefix `persist.rjkyker.*`. Security-sensitive settings and secret-like keys are blocked. The service has a persistent notification with a Stop action, resumes on normal boot/app replacement only if the owner had enabled it, checks every 30 seconds, and also reacts to Settings content changes. Failed reads/writes use bounded exponential backoff and suspend after three failed attempts. Every pin, restoration, failure, and stop is recorded in a small local audit ring. Property writes remain best-effort because Android property-service policy may reject them even under Shizuku shell.

## Build and install

The `android-ci.yml` workflow checks out the llama.cpp submodule, installs the pinned Android 34 / NDK 27.2.12479018 / CMake 3.22.1 toolchain, runs JVM unit tests, assembles the arm64 debug APK, and uploads it as `shizuku-ai-action-broker-tc22-debug`. Install only after the workflow succeeds and the artifact is downloaded, using `adb install -r app-debug.apk` against the intended device. This build is a development APK, not a signed Play release.


## Watchdog boundary

The existing broker watchdog keeps the BrokerServer healthy and reasserts this app's own accessibility service. It does not force-start an unrelated keyboard or third-party app. The TC22 package inventory did not show a separate app named Type Anywhere, so no external package restart target has been configured.