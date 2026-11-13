# TC22 storage tools and settings guardians (1.10.1-dev)

This feature builds on the 1.10.0 development line (version code 24) and increments the APK to 1.10.1-dev (version code 25). It preserves the existing Type Anywhere, policy controls, cache hygiene, StageNow/MX export, LLM, and autonomy features.

## Storage manager

The storage manager uses Android's Storage Access Framework (SAF). The owner selects a folder and grants access to that tree. Within the granted tree it can list folders/files, create folders, rename, copy, move, delete with confirmation, and create or extract ZIP/TAR archives. Extraction rejects absolute and `..` paths, skips same-name existing files, and caps extracted data at 512 MiB / 5,000 entries. It does not request `MANAGE_EXTERNAL_STORAGE` or silently bypass Android's scoped-storage rules. Android's protected `Android/data` and other private app directories may remain unavailable through the system picker; Shizuku does not make every protected path universally accessible.

## Cache explorer / hygiene

The existing 1.10.0 Policies screen already reports cache sizes and provides two operations: clear only this broker's cache/code-cache folders, or (with an explicit confirmation) ask Package Manager through Shizuku to trim other apps' caches. It never uses `pm clear` and never deletes photos, Downloads, app databases, shared preferences, or the broker's files directory. The new storage manager does not duplicate this cache UI.

## Wi-Fi saved-network helper

The Policies screen and storage tools open Android Wi-Fi settings. On Android 10+ the owner can select a saved network and use the system Share/QR flow where the OEM exposes it. The broker does not decrypt Wi-Fi configuration files, dump credentials, or claim to bypass the unrooted TC22's platform restrictions.

## Settings guardians

Guardians are OFF until the owner starts them. Each pin is an explicit namespace/key/value tuple. Supported namespaces are `global`, `system`, `secure`, and the limited app-owned property prefix `persist.rjkyker.*`. Security-sensitive keys (ADB, accessibility, input method, device-owner, airplane mode, SMS, lock-screen, secret-like keys, etc.) are blocked. The service has a persistent notification with a Stop action, resumes on normal boot/app replacement only if the owner had enabled it, checks every 30 seconds, and also reacts to Settings content changes. The broker watchdog re-starts the guardian service if it dies while enabled. Failed reads/writes use bounded exponential backoff and suspend after three failed attempts. Every pin, restoration, failure, and stop is recorded in a local ring and the existing Room audit log. Property writes remain best-effort because Android property-service policy may reject them even under Shizuku shell.

## Existing Type Anywhere / watchdog

Type Anywhere already exists in this 1.10.0 branch. It runs inside the broker's accessibility service; the existing BrokerService watchdog re-asserts that service, which reattaches the Type Anywhere controller and overlay. The watchdog also maintains the BrokerServer. No unrelated keyboard app is force-started.

## Build and install

The `android-ci.yml` workflow checks out the llama.cpp submodule, installs the pinned Android 34 / NDK 27.1.12297006 / CMake 3.22.1 toolchain, runs JVM unit tests, assembles the arm64 debug APK, verifies package/version identity, and uploads it as `shizuku-ai-action-broker-tc22-debug`. Install only after the workflow succeeds and the artifact is downloaded, using `adb install -r app-debug.apk` against the intended device. This is a development APK, not a signed Play release.