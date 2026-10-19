# Shizuku AI Action Broker

Android package: `com.rjkyker.shizukuai.broker` (version **1.4.0**)

Localhost action broker (127.0.0.1:17999) with:

- **AccessibilityService** UI automation (tap / swipe / text / ui dump / back / home)
- **ShizukuGateway** binder status + permission request (Shizuku API)
- **ShizukuBridge** prefers Shizuku `newProcess` when binder+permission OK; else `rish -c` → `sh -c`
- **ActionAllowList** / **ActionRequest** structured allow-list (router ops + MVP GET_*)
- **Delegated Device Admin** (null-admin DPM) via `DelegatedAdminActionHandler`
- **AdminCliService** / **AdminCliReceiver** for Termux/`rish` helpers

- **DataWedge results (1.4.0)**: real RESULT_ACTION answers (status, version, profile list,
  active profile) captured by a runtime + manifest receiver, persisted as JSON, and served over HTTP
- **BrokerService** foreground service keeps the server alive; **BootReceiver** restarts it after reboot/update
- **Authoritative risk policy**: `ActionRequest.riskLevel` is derived from `ActionAllowList`
  (unknown actions fail closed to HIGH); `tests/verify_contract.py` + CI keep schema and policy in sync

See also: `docs/DATAWEDGE_RESULTS.md`, `docs/ALLOWLIST.md`, `docs/DEVICE_SETUP.md`, `docs/ARCHITECTURE.md`,
`schemas/action.schema.json`, `examples/action.example.json`.

## Build (host Linux / this box)

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # or JDK 17+
export ANDROID_HOME=/workspace/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
cd /workspace/shizuku-ai-action-broker
./gradlew :app:assembleDebug
```

Debug APK:

`app/build/outputs/apk/debug/app-debug.apk`

Copy for PC companion:

```bash
cp app/build/outputs/apk/debug/app-debug.apk \
  /workspace/shizukuai-broker-pc/com.rjkyker.shizukuai.broker.apk
```

### Shizuku Gradle deps

`dev.rikka.shizuku:api:13.1.5` and `provider:13.1.5` are declared. The app registers
`rikka.shizuku.ShizukuProvider`. MainActivity shows binder/permission/UID status and
can request Shizuku permission. `ShizukuBridge.exec` uses the binder API when ready,
otherwise falls back to `rish`/`sh`.

### proot-distro fallback

```bash
./scripts/build-with-proot-distro.sh
```

## Install

```bash
adb install -r /workspace/shizukuai-broker-pc/com.rjkyker.shizukuai.broker.apk
```

## Enable Accessibility

1. Open the app (**Shizuku AI Broker**).
2. Tap **Open accessibility settings**.
3. Find **Shizuku AI Broker** / **AgentAccessibilityService** and enable it.
4. Confirm the MainActivity status line shows `accessibility: enabled`.

Without accessibility, tap/swipe/text/ui_dump fall back to `ShizukuBridge` shell
(`input tap` / `uiautomator dump`) when `rish` or elevated `sh` is available.

## BrokerServer (localhost HTTP)

Started from `MainActivity.onCreate` (guarded). Binds **127.0.0.1:17999**.

### Health

```bash
adb shell curl -s http://127.0.0.1:17999/health
# or from device Termux:
curl -s http://127.0.0.1:17999/health
```

### UI snapshot

```bash
curl -s http://127.0.0.1:17999/ui
```

### Actions (`POST /action`)

```bash
# Tap by coordinates
curl -s -X POST http://127.0.0.1:17999/action \
  -H 'Content-Type: application/json' \
  -d '{"op":"tap","x":540,"y":960}'

# Tap by text (accessibility)
curl -s -X POST http://127.0.0.1:17999/action \
  -H 'Content-Type: application/json' \
  -d '{"op":"tap","text":"OK"}'

# Swipe
curl -s -X POST http://127.0.0.1:17999/action \
  -H 'Content-Type: application/json' \
  -d '{"op":"swipe","x1":500,"y1":1400,"x2":500,"y2":400,"durationMs":300}'

# Input text
curl -s -X POST http://127.0.0.1:17999/action \
  -H 'Content-Type: application/json' \
  -d '{"op":"text","text":"hello"}'

# Keys
curl -s -X POST http://127.0.0.1:17999/action \
  -H 'Content-Type: application/json' \
  -d '{"op":"key","key":"back"}'

# UI dump
curl -s -X POST http://127.0.0.1:17999/action \
  -H 'Content-Type: application/json' \
  -d '{"op":"ui_dump"}'

# Shell via ShizukuBridge
curl -s -X POST http://127.0.0.1:17999/action \
  -H 'Content-Type: application/json' \
  -d '{"op":"shell","cmd":"id"}'

# Delegated admin
curl -s -X POST http://127.0.0.1:17999/action \
  -H 'Content-Type: application/json' \
  -d '{"op":"admin_scopes"}'

curl -s -X POST http://127.0.0.1:17999/action \
  -H 'Content-Type: application/json' \
  -d '{"op":"admin_perm","package":"com.example.app","permission":"android.permission.CAMERA","state":"grant"}'

curl -s -X POST http://127.0.0.1:17999/action \
  -H 'Content-Type: application/json' \
  -d '{"op":"admin_suspend","package":"com.example.app","suspended":true}'

# Launch app
curl -s -X POST http://127.0.0.1:17999/action \
  -H 'Content-Type: application/json' \
  -d '{"op":"launch","package":"com.android.settings"}'
```

From a host PC via adb port-forward:

```bash
adb forward tcp:17999 tcp:17999
curl -s http://127.0.0.1:17999/health
```

## Grant delegated scopes (device owner / DPC / elevated shell)

```bash
dpm set-delegated-scopes com.rjkyker.shizukuai.broker \
  delegation-permission-grant \
  delegation-package-access \
  delegation-block-uninstall \
  delegation-app-restrictions \
  delegation-enable-system-app \
  delegation-network-logging \
  delegation-security-logging
```

## Verify with rish / Termux

```bash
source scripts/worker-admin-cli.sh
worker-admin-scopes
worker-admin-perm com.example.app android.permission.CAMERA grant
worker-admin-suspend com.example.app true
```

Logcat:

```bash
adb logcat -s BrokerServer:I ActionRouter:I AgentA11y:I ShizukuBridge:I \
  AdminCliService:I AdminCliReceiver:I DelegatedAdminHandler:I
```


## DataWedge on Zebra TC22 (and other Zebra / Symbol devices)

Requires **Zebra DataWedge** (preinstalled on TC22 / TC52 / etc.). Soft-fails on non-Zebra:
broadcasts still fire, but no RESULT_ACTION replies arrive.

### Enable a profile with broadcast Intent output

1. Open **DataWedge** on the device.
2. Create or edit profile (e.g. `ShizukuAiBroker`), enable it, associate with
   `com.rjkyker.shizukuai.broker` (or all activities `*`).
3. Under **Intent output**:
   - Enabled: **On**
   - Intent action: `com.rjkyker.shizukuai.broker.SCAN`
   - Intent delivery: **Broadcast**
   - Category: `android.intent.category.DEFAULT`
4. Ensure barcode input is enabled for the scanner.

Or from the broker (ActionRouter / localhost):

```bash
curl -s -X POST http://127.0.0.1:17999/action \
  -H 'Content-Type: application/json' \
  -d '{"op":"datawedge_set_config","profile":"ShizukuAiBroker"}'
```

### Query / soft-scan via ActionRouter

```bash
curl -s -X POST http://127.0.0.1:17999/action -H 'Content-Type: application/json' \
  -d '{"op":"datawedge_status"}'
curl -s -X POST http://127.0.0.1:17999/action -H 'Content-Type: application/json' \
  -d '{"op":"datawedge_version"}'
curl -s -X POST http://127.0.0.1:17999/action -H 'Content-Type: application/json' \
  -d '{"op":"datawedge_profiles"}'
curl -s -X POST http://127.0.0.1:17999/action -H 'Content-Type: application/json' \
  -d '{"op":"datawedge_active_profile"}'
curl -s -X POST http://127.0.0.1:17999/action -H 'Content-Type: application/json' \
  -d '{"op":"datawedge_soft_scan","mode":"start"}'
curl -s -X POST http://127.0.0.1:17999/action -H 'Content-Type: application/json' \
  -d '{"op":"datawedge_switch_profile","profile":"ShizukuAiBroker"}'
curl -s -X POST http://127.0.0.1:17999/action -H 'Content-Type: application/json' \
  -d '{"op":"datawedge_last"}'
```

MainActivity **Query DataWedge** sends `GET_DATAWEDGE_STATUS` and shows the last
status string from `DataWedgeResultReceiver`.

Logcat: `adb logcat -s DataWedgeApi:I DataWedgeResultRx:I`

See also `/workspace/android-bridge/docs/DATAWEDGE.md`.

## Architecture

| Component | Role |
|-----------|------|
| `BrokerServer` | `ServerSocket` HTTP on 127.0.0.1:17999 |
| `ActionRouter` | Routes JSON `op` to a11y / shell / admin / launch |
| `AgentAccessibilityService` | Snapshot + gestures + text + global actions |
| `ShizukuGateway` | Binder alive / permission / backend UID + requestPermission |
| `ShizukuBridge` | Shizuku API `newProcess` → `rish -c` → `sh -c` |
| `ActionAllowList` / `ActionRequest` | Structured allow-list + risk levels |
| `SecurePrefs` | EncryptedSharedPreferences or plain prefs |
| `DelegatedAdminProfileReceiver` | `DelegatedAdminReceiver` for logs / key alias |
| `DelegatedAdminActionHandler` | Null-admin `DevicePolicyManager` + scope checks |
| `AdminCliService` | `am startservice` CLI |
| `AdminCliReceiver` | `ADMIN_SCOPES` broadcast |
| `MainActivity` | Package / server / a11y / Shizuku / scopes status |

Null-admin contract: always call DPM with `admin = null` when scopes are delegated to this package.
