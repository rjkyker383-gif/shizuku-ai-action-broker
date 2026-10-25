# Changelog

## 1.8.4-dev (vc14)
- **Talk speaks Muse's real voice.** The relay reply can now carry `audio_url` + `voice`; the broker downloads
  the MP3 with its token (allowed host only, ≤5 MB, MP3 sniffed) and plays it with MediaPlayer, labelled
  "Muse voice — <name>" (default Jordan, avocado_v2:MAI_03). Android TTS (male en-US preferred unless the user
  picked a voice) is only the fallback when no audio arrives within 60 s (was 45 s) or playback fails.
  Media-volume-0 warning on the dashboard. `MuseVoiceClient.parseReply` + unit tests.
- **Accessibility screen/input without ADB.** New MCP tools `a11y_screenshot` (AccessibilityService.takeScreenshot,
  API 30+, PNG/JPEG base64 + MCP image content), `a11y_ui_tree`, `a11y_tap`, `a11y_swipe`, `a11y_text`
  (ACTION_SET_TEXT), `a11y_global` (BACK/HOME/RECENTS/NOTIFICATIONS/QUICK_SETTINGS), `a11y_click_text`.
  Bearer token required like every MCP tool. Service XML adds `canTakeScreenshot` + `canPerformGestures`.
- MainActivity survives rotation (configChanges) so a Talk turn isn't killed mid-playback; debug-only
  `talk_test_text` intent extra runs one mic-free Talk turn for end-to-end verification.

## Unreleased / 1.8.2-dev
- **Roam-proof (reboot + new Wi-Fi).** `BrokerServer` now listens on `127.0.0.1` **and** the device's
  Tailscale 100.64/10 IP(s) when present (never `0.0.0.0`, never Wi-Fi/LAN; `NetBind`). Bearer auth still
  required for `/mcp` `/ws` `/action`; `/health` `/discover` `/.well-known` stay open. New `RoamKeeper`:
  ConnectivityManager callback (incl. VPN) + boot/MY_PACKAGE_REPLACED → debounced rebind when the Tailscale
  IP appears/changes, mDNS re-register, `adb_wifi_enabled=1` (best effort; Android picks a new TLS port —
  plain `adb tcpip` needs root, not possible), Tailscale's own `CONNECT_VPN` broadcast if Tailscale is
  installed but down (no login), Doze allowlist for broker + Tailscale via Shizuku
  (`dumpsys deviceidle whitelist +pkg`). Watchdog also rebinds every 30 s. One-tap **Roam-proof setup**
  sets Tailscale as always-on VPN via Dhizuku device-owner (`setAlwaysOnVpnPackage`, lockdown off).
  Discovery `connections[]` tailscale entries now carry `direct_mcp:true` + `http://<ts-ip>:17999/mcp`
  when bound; `/health` reports `bind`.
- **Lag fix.** Root cause: every 5 s tick ran on the main thread — EncryptedSharedPreferences decrypts
  (discovery knownSecrets, Airtable/token reads), NetworkInterface enumeration, full discovery-manifest
  build, Dhizuku/Shizuku binder calls, parsing/pretty-printing 50 scans incl. hex dumps into a 24-line
  scrolling TextView, `setText` on every Settings EditText, and `notifyDataSetChanged()` on 4 lists inside
  one NestedScrollView (full relayout each tick + nested-scroll fights). Now: state built on a single
  background executor, applied on main ≤1 Hz and coalesced; `ListAdapter`+DiffUtil; TextViews only set when
  changed; scan panel re-rendered only when `ScanHistoryStore.version` changes; settings fields filled only
  on open/save; inner RecyclerViews have nested scrolling off (outer scroll owns flings); hex dumps capped
  in the UI (full JSON via Copy).
- **Scans fixed.** Root cause: DataWedge only applies an app's profile while that app is in the foreground
  (other apps use Profile0 → keystrokes only), and the broker profile was only created if an AI called
  `set_config`. New `DataWedgeRepair` (auto-applied once per install on Zebra + **Repair DataWedge profile**
  buttons): ENABLE_DATAWEDGE; broker profile UPDATE with app binding, free decoders + AIM IDs, INTENT
  broadcast `com.rjkyker.shizukuai.broker.SCAN`/DEFAULT, BDF off (raw bytes/control chars), keystroke off;
  Profile0 UPDATE adds INTENT output only (RESET_CONFIG=false, keystroke typing untouched) so scans in any
  app also reach the broker. Receiver registered in `BrokerService.onCreate`; scan count/time/path shown in
  Systems; live UI update on each scan. No licence anything.
- **Plain-English "What this means"** (`ScanExplainer`): every enriched scan now carries `english[]`
  ({field,value,meaning}) — symbology, GS1 AIs (GTIN, lot, expiry/best-before/production dates as real
  dates, serial, SSCC, weights with decimals, ship-to…), UPC/EAN/ISBN with GS1 prefix issuer + check digit,
  AAMVA licence fields (names, dates, sex, eye colour…), Wi-Fi QR (password length only), vCard/MECARD,
  URL/geo/mailto/tel, EMVCo payment QR (currency, static/dynamic), ISO 15434, HIBC, JSON/kv, hidden
  `<GS>/<RS>/<EOT>` explained. Explains only what is encoded. Bare 8/12/13-digit retail codes are no longer
  mis-parsed as GS1 element strings. Also returned by `scan_history`.
- **Talk (live voice agent).** New TALK panel: tap → RECORD_AUDIO (runtime prompt) → SpeechRecognizer with
  partial transcript → instant on-device answers (`LocalAgent`: status, last-scan meaning, time) or Muse via
  `https://presocial-endocrinopathic-fumiko.ngrok-free.dev/muse/voice` (only host allowed; broker token as
  X-Broker-Token over HTTPS) → reply spoken → listens again until STOP/"stop". Muse exposes no TTS API to
  third-party apps, so replies use Android TextToSpeech, labelled **Device voice** (voice picker); a real
  Muse `audio_url` from the relay would be played and labelled "Muse voice". Mic only during a session and
  stopped on pause. Never sends SMS/email/calls/spends.
- Version 1.8.2-dev (versionCode 12). Tests: +ScanExplainerTest, DataWedgeRepairTest, NetBindTest,
  LocalAgentTest, discovery tailscale-direct (49 total).

## Unreleased / 1.8.0-dev
- **AI discovery layer.** New open, read-only endpoints `GET /discover` and `GET /.well-known/mcp.json`
  serve a capability manifest (`schema: shizuku-broker-discovery/1`): app/device/version, MCP
  protocol + transports, auth requirements, every connection method (loopback, Tailscale node
  address, public mesh bridges android/spark/ether/war/magic, upstream, mDNS) and tool names + risk +
  one-line descriptions. Never contains a credential: URLs are stripped of user-info/query/fragment,
  credential-like keys are removed and any real SecurePrefs secret is redacted (unit-tested).
- **mDNS / NSD** (`NsdAdvertiser`): registers `_mcp._tcp` + `_broker._tcp` (port 17999, TXT
  `path=/discover`, `wk`, `mcp`, `ws`, `auth=bearer`, `bind`, `ver`, `id`, `mesh`) on service start,
  unregisters on stop. Added `CHANGE_WIFI_MULTICAST_STATE`.
- **MCP tool `discovery_info`** (LOW, bearer auth) returns the same manifest. Allow-list + schema updated.
- **Expanded `/health`** (still open, booleans/ids only): server, shizuku, dhizuku, accessibility,
  upstream_configured, airtable_key_set, wifi_presence state, mdns, pid, uptime_ms, discover endpoints.
- **Built-in default connection URLs** (non-secret, editable): mesh base
  `https://presocial-endocrinopathic-fumiko.ngrok-free.dev`; upstream + bridges default to
  `<mesh base>/<bridge>/mcp` for android/spark/ether/war/magic. Tokens and the Airtable key are still
  never built in. Bridge set expanded from magic/war/ether to android/spark/ether/war/magic.
  Bridge health for path-mounted bridges now probes `…/<bridge>/health`. Relay requests send
  `ngrok-skip-browser-warning`.
- **Command-center dashboard**: MainActivity redesigned as a dark, panelled MaterialCardView +
  RecyclerView dashboard with status LEDs bound to real state, live bridge probes, a connections
  panel with COPY buttons, one-tap Rotate MCP token (shown once), show/hide panels + accent color
  (persisted). Existing settings (upstream URL, Airtable key, bridge URLs) remain; new mesh base field.
- `WifiPresenceEngine` now exposes honest engine state (idle/scanning/last result) for `/health` + UI.
- Version 1.8.0-dev (versionCode 10). MCP serverInfo 1.8.0-dev. Tests: `DiscoveryManifestTest` (8).

## Unreleased / 1.7.0-dev
- **BridgeMeshRelay** + MCP tool `bridge_relay{bridge,method,params}`: relay JSON-RPC to
  `magic`/`war`/`ether`/`upstream`. Per-bridge URLs in SecurePrefs (nothing hardcoded; no 192.168
  default); each bridge is health-checked before relaying and unreachable bridges return `ok:false`.
- **AirtableSyncWorker** (WorkManager, 30-min periodic flush) + MCP tool `airtable_log{base,table,fields}`:
  owner bases `appQkHjQwQuyzVT99` / `appQDYTaD6gi64g7M` only. API key read from SecurePrefs at runtime,
  never hardcoded; no key => worker no-ops, logs "Airtable key not set", records stay queued on disk.
- **WifiPresenceEngine** + MCP tool `wifi_presence_scan{duration_ms}`: honest RSSI/link-variance
  presence & motion score (sliding window + small FFT) using public APIs + `dumpsys wifi`.
  Explicitly near-device only — CANNOT see through walls or identify people (Android exposes no CSI).
- **ZebraCapabilityLayer** + MCP tools `zebra_wifi_config`, `zebra_datawedge_profile`,
  `zebra_scanner_control`: NO-LICENSE Zebra control via DataWedge / WifiManager / DPM / Shizuku shell.
  No license-check bypass, no licensing-daemon reflection, no forged keys; paid-license features are
  listed and intentionally omitted at the top of the file.
- Settings: added Airtable API key field + per-bridge URL list to MainActivity (encrypted SecurePrefs).
- Manifest: ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE,
  ACCESS_FINE_LOCATION. Foreground service confirmed START_STICKY + specialUse FGS type +
  BOOT_COMPLETED/LOCKED_BOOT_COMPLETED/MY_PACKAGE_REPLACED receiver + binder/server watchdog.
- `get_system_status` now reports bridge configuration (host only) and Airtable key_set.
- Version 1.7.0-dev (versionCode 9). MCP serverInfo version bumped. Contract verifier + unit tests
  updated for the 6 new tools (kept in sync with schemas/action.schema.json).

## Unreleased / 1.6.0-dev
- Permanent MCP JSON-RPC on `POST /mcp` + minimal WebSocket at `GET /ws` (same ServerSocket as `/health`/`/action`).
- Bearer auth (`Authorization` / `X-Broker-Token`); token in SecurePrefs; `/health` stays open with `mcp`/`auth_required` flags.
- Tools: `exec_shell` (local|upstream route, optional short sessions), `dump_ui_hierarchy`, `perform_gesture`,
  `manage_dhizuku_policy` (no install/uninstall), `datawedge_query`, `wedge_sync_control`, `upstream_mcp_relay`,
  `get_system_status`.
- Upstream MCP relay: any-wifi URL (Tailscale/tunnel) via SecurePrefs; optional LAN fallback only on matching /24.
- BrokerService sticky + Shizuku/Dhizuku binder watchdog; battery-optimization exemption prompt.
- ShellExecutor multiplexer (max 3 background sessions, not a PTY).
- UI: "No delegated scopes" no longer hides Dhizuku state; button renamed "Request Dhizuku permission" and re-inits the Dhizuku binder on tap.

## 1.5.0
- Dhizuku support: `io.github.iamr0s:Dhizuku-API:2.5.4` (2.6.0 requires compileSdk 37),
  `com.rosan.dhizuku.permission.API`, `<queries>` for `com.rosan.dhizuku`.
- `Dhizuku.init()` + `requestPermission()` on app start and via a new **Connect Dhizuku** button, so the
  broker shows up in Dhizuku's app list for approval.
- `DhizukuGateway`: binder-wrapped DevicePolicyManager (documented Dhizuku pattern, HiddenApiBypass 6.1).
- New ops: `dhizuku_status`, `dhizuku_connect`, `dhizuku_scopes` (LOW); `dhizuku_add_restriction`,
  `dhizuku_clear_restriction`, `dhizuku_hide_app`, `dhizuku_suspend_app` (MEDIUM);
  `dhizuku_perm`, `dhizuku_set_scopes` (HIGH, require `confirm: true`). Restriction keys, scopes and
  target packages are allow-listed / protected; no destructive owner calls.
- Contract verifier rejects destructive owner actions (wipe, reset_password, remove_admin, factory_reset,
  clear_device_owner); new `DhizukuPolicyTest` unit tests.

## 1.4.0
- DataWedge result capture: runtime RESULT_ACTION receiver, JSON persistence
  (internal + `/sdcard/Android/data/<pkg>/files/datawedge`), `DW_QUERY` shell trigger,
  `GET /datawedge`, `GET /datawedge/query`, `datawedge_query` / `datawedge_results` ops.
- BrokerService foreground service (specialUse) + BootReceiver; BrokerApp registers receivers at process start.
- MainActivity DataWedge button now shows real status/version/profiles.
- Restored authoritative risk policy from main: riskLevel derived from ActionAllowList, fail-closed HIGH;
  schema enum synced to policy; contract verifier + CI updated for the `action/` package.

## 1.3.0
- DataWedge Intent API helpers + receiver, accessibility service, local server, router, delegated-admin CLI.
