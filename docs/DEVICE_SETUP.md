# Device setup

This broker is designed for a user-controlled Android device with Shizuku installed and explicitly authorized.

## 1. Verify ADB

```bash
adb devices -l
```

The target must appear with state `device`.

## 2. Start Shizuku

On Android 11 and later, use the Shizuku app's Wireless debugging flow to start the Shizuku service. The broker does not depend on a hard-coded `/sdcard/Android/data/.../start.sh` path.

Verify the manager package is installed:

```bash
adb shell pm path moe.shizuku.privileged.api
```

## 3. Install the broker APK

After CI builds the debug APK:

```bash
adb install -r app-debug.apk
```

Verify the package:

```bash
adb shell pm path com.rjkyker.shizukuai.broker
```

Launch it:

```bash
adb shell monkey -p com.rjkyker.shizukuai.broker -c android.intent.category.LAUNCHER 1
```

## 4. Grant Shizuku permission

Open the broker and press **Request Shizuku permission**. Approve the request in the Shizuku UI. The broker status screen should then show a connected Binder and the backend UID.

A backend UID of `2000` indicates the ADB shell identity. UID `0` indicates a root-backed Shizuku/Sui session.

## Security boundary

The broker intentionally does not expose arbitrary shell execution. AI-originated requests must be mapped to an explicit action in `ActionAllowList` and remain subject to user-visible authorization.
