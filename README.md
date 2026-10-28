# Shizuku AI Action Broker

Android package: `com.rjkyker.shizukuai.broker` (version **1.5.0**)

Localhost action broker (127.0.0.1:17999) with:

- **AccessibilityService** UI automation (tap / swipe / text / ui dump / back / home)
- **ShizukuGateway** binder status + permission request (Shizuku API)
- **ShizukuBridge** prefers Shizuku `newProcess` when binder+permission OK; else `rish -c` → `sh -c`
- **Dual AI shell routes**: `exec_shell` can select `auto`, `shizuku`, `termux`, `local`, or `upstream`; results report the actual backend, exit code, stdout, stderr, and timeout state.
- **ActionAllowList** / **ActionRequest** structured allow-list (router ops + MVP GET_*)
- **Delegated Device Admin** (null-admin DPM) via `DelegatedAdminActionHandler`
- **AdminCliService** / **AdminCliReceiver** for Termux/`rish` helpers

- **DataWedge results (1.4.0)**: real RESULT_ACTION answers (status, version, profile list,
  active profile) captured by a runtime + manifest receiver, persisted as JSON, and served over HTTP
- **BrokerService** foreground service keeps the server alive; **BootReceiver** restarts it after reboot/update
- **Authoritative risk policy**: `ActionRequest.riskLevel` is derived from `ActionAllowList`
  (unknown actions fail closed to HIGH); `tests/verify_contract.py` + CI keep schema and policy in sync

- **Dhizuku device-owner sharing (1.5.0)**: Dhizuku-API client; the broker requests
  `com.rosan.dhizuku.permission.API` on start / **Connect Dhizuku** so it appears in Dhizuku's app list.
  Once approved, a small set of reversible device-owner ops runs through Dhizuku's binder-wrapped
  `DevicePolicyManager` (see `docs/DHIZUKU.md`)

See also: `docs/DHIZUKU.md`, `docs/DATAWEDGE_RESULTS.md`, `docs/ALLOWLIST.md`, `docs/DEVICE_SETUP.md`, `docs/ARCHITECTURE.md`,
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

## Dhizuku (device owner without making the broker the DPC)

`dpm set-delegated-scopes` is **not** a shell command; only a device owner can delegate. With
[Dhizuku](https://github.com/iamr0s/Dhizuku) as Device Owner, open the broker and tap
**Connect Dhizuku**, then tap **Grant** in Dhizuku's prompt (it auto-denies after ~15 s) or enable
*Shizuku AI Broker* in Dhizuku's app list.

| op | risk | params |
|----|------|--------|
| `dhizuku_status` | LOW | — (installed, granted, owner component, active restrictions) |
| `dhizuku_connect` | LOW | — (re-sends the permission request) |
| `dhizuku_scopes` | LOW | — |
| `dhizuku_add_restriction` / `dhizuku_clear_restriction` | MEDIUM | `restriction` (allow-listed keys only) |
| `dhizuku_hide_app` | MEDIUM | `package`, `hidden` (default true) |
| `dhizuku_suspend_app` | MEDIUM | `package`, `suspended` (default true) |
| `dhizuku_perm` | HIGH, needs `"confirm":true` | `package`, `permission`, `state` granted/denied/default |
| `dhizuku_set_scopes` | HIGH, needs `"confirm":true` | `scopes[]` (broker only, allow-listed delegations) |

```bash
curl -s -X POST http://127.0.0.1:17999/action -d '{"op":"dhizuku_status"}'
curl -s -X POST http://127.0.0.1:17999/action -d '{"op":"dhizuku_add_restriction","restriction":"no_config_wifi"}'
curl -s -X POST http://127.0.0.1:17999/action -d '{"op":"dhizuku_clear_restriction","restriction":"no_config_wifi"}'
```

Deliberately **not** exposed: wipe / factory reset, reset password, remove admin / clear owner,
`no_debugging_features`, `no_modify_accounts`, `no_config_vpn`. System, Dhizuku, Shizuku, Tailscale,
Termux, DataWedge and the broker itself cannot be hidden or suspended.

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


## AI shell access: Shizuku + Termux

The MCP `exec_shell` tool requires `confirm=true` for command execution. Use `route=auto` to prefer the granted Shizuku binder, then Termux when its external-command permission is available, then the existing local fallback. Use `route=shizuku` or `route=termux` to request a specific backend. Every result reports `via`, `route`, `exit_code`, `stdout`, `stderr`, and `timed_out`; a route is not reported as successful unless the command result says so.

### Termux setup (one-time)

1. Install Termux from a trusted distribution source and open it once.
2. In Termux, create or edit `~/.termux/termux.properties` and set `allow-external-apps=true`.
3. Restart Termux.
4. In Android Settings → Apps → Shizuku AI Action Broker → Permissions, grant **Run commands in Termux environment** if Android exposes it.
5. Use `exec_shell` with `route=termux` and `confirm=true` to test, for example, `{"command":"id; pwd; echo TERMUX_BRIDGE_OK","route":"termux","confirm":true}`.

If the Termux permission or external-app setting is missing, the bridge returns a setup error rather than silently claiming Termux ran the command. Termux commands run as Termux's app user, not as root. Shizuku commands run with the identity Shizuku provides on that device. Neither route bypasses Android sandbox boundaries automatically.

Only connect a trusted AI client to the broker, keep its bearer token private, and require explicit confirmation for shell execution. Avoid sending secrets in shell command arguments because commands may appear in logs or diagnostic output.

### Background shell sessions and diagnostics

- `session_start` accepts the same `route` choice (`auto`, `shizuku`, `termux`, or `local`) as one-shot shell calls. Termux sessions run on the bounded background session worker instead of blocking the request while starting.
- Shell commands are limited to 64 KiB. Session status reports command length rather than echoing the full command, reducing accidental exposure of embedded secrets in routine status output.
- Sessions remain capped at three concurrent jobs and collect at most 64 KiB of output per session. They are command jobs, not an interactive terminal/PTY. Killing a session cancels the worker future, but an underlying remote process may continue until its own timeout; verify status rather than assuming immediate process termination.
