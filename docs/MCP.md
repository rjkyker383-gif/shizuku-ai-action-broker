# MCP endpoint (broker 1.8.0-dev)

The always-on local server binds **only** to `127.0.0.1:17999` (same process as
`BrokerService` / `BootReceiver`). It speaks MCP JSON-RPC 2.0 over HTTP and a
minimal WebSocket.

## Endpoints

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| GET | `/health` | no | Keepalive + live status booleans (see below). Never prints the token or any key. |
| GET | `/discover` | no | **AI discovery manifest** (1.8.0). Read-only, no credentials. CORS `*`. |
| GET | `/.well-known/mcp.json` | no | Same manifest at the well-known path (`/.well-known/mcp` also works). |
| POST | `/mcp` | **yes** | MCP JSON-RPC (`initialize`, `tools/list`, `tools/call`, `ping`, `notifications/initialized`). |
| GET | `/ws` | **yes** | WebSocket upgrade (`Sec-WebSocket-Protocol: mcp`). Same JSON-RPC as `/mcp`. |
| POST | `/action` | **yes** | Legacy ActionRouter JSON (`{op:…}`). HIGH ops need `"confirm":true`. |
| GET | `/ui`, `/datawedge*` | **yes** | Same as before, now gated. |

### Auth

Token is generated on first run into encrypted/`SecurePrefs` (`mcp_token`).
Reveal / copy / rotate it in **MainActivity**. Send one of:

```
Authorization: Bearer <token>
X-Broker-Token: <token>
```

WebSocket may also pass `?token=` (still localhost-only). CORS `*` was removed from every
authenticated route; only the open, read-only discovery manifest sends `Access-Control-Allow-Origin: *`.

HIGH-risk tools also need `arguments.confirm: true` **or** header `X-Confirm: true`.

### curl (local)

```bash
TOKEN=…   # from the broker app "Reveal / copy MCP token"
BASE=http://127.0.0.1:17999

curl -s $BASE/health | jq .

curl -s $BASE/mcp -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}'

curl -s $BASE/mcp -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

curl -s $BASE/mcp -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H 'X-Confirm: true' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"exec_shell","arguments":{"command":"id","confirm":true,"route":"local"}}}'

curl -s $BASE/mcp -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_system_status","arguments":{}}}'
```

## Tools

| Tool | Risk | Purpose |
|------|------|---------|
| `exec_shell` | HIGH | Local Shizuku shell (`route=local`, default) or upstream (`route=upstream`). Optional short background sessions (`session_start` / `session_poll` / `session_kill`) — **not a PTY**. |
| `dump_ui_hierarchy` | LOW | Accessibility node tree. |
| `perform_gesture` | MEDIUM | tap / swipe / back / home / recents / scroll. |
| `manage_dhizuku_policy` | varies | status/connect/scopes/restriction/hide/suspend/perm/set_scopes. **Install/uninstall/wipe are refused.** |
| `datawedge_query` | LOW | Live DataWedge GET_* or soft_scan / switch_profile / … |
| `wedge_sync_control` | MEDIUM | `snapshot` \| `apply_local` \| `push_upstream` \| `status`. |
| `upstream_mcp_relay` | MEDIUM | Forward JSON-RPC to the configured upstream (`method` default `tools/call`, `payload` = params). |
| `get_system_status` | LOW | Local server + upstream host (no secrets) + Shizuku/Dhizuku/a11y/battery. |
| `bridge_relay` | MEDIUM | Relay JSON-RPC to a named bridge `android`/`spark`/`ether`/`war`/`magic`/`upstream`. URLs prefilled with built-in mesh defaults (editable; no token built in); health-checked first. |
| `airtable_log` | MEDIUM | Log one record to an owner Airtable base (`appQkHjQwQuyzVT99`/`appQDYTaD6gi64g7M`). API key read from SecurePrefs at runtime; queues + no-ops when unset. |
| `wifi_presence_scan` | LOW | Honest RSSI/link-variance presence & motion score. **Near-device only; cannot see through walls or identify people** (Android exposes no Wi-Fi CSI). |
| `zebra_wifi_config` | HIGH | No-license Wi-Fi control (WifiManager/DPM/shell). status is free; mutating ops need `confirm:true`. **No license bypass.** |
| `zebra_datawedge_profile` | MEDIUM | No-license DataWedge profile CRUD (list/active/create/set_config/switch). |
| `zebra_scanner_control` | MEDIUM | No-license scanner enable/disable + soft scan via DataWedge. |
| `discovery_info` | LOW | Returns `{ok, manifest}` — the same manifest as `GET /discover`, for clients already connected. Still requires the bearer token like every tool. |

## Upstream MCP (any wifi)

The broker can **relay** to another MCP server. That URL must work on **any**
network the phone is on (coffee shop, LTE via Tailscale, work Wi-Fi, …).

### Do **not** use `192.168.1.158:8486` as the primary

`http://192.168.1.158:8486/mcp` only works when the phone is on the home LAN.
Off-LAN it will time out. Put that address in the **optional LAN fallback** if
you still want a fast path at home; keep the **primary** as:

1. **Tailscale** — e.g. `http://100.112.35.46:8486/mcp` (MagicDNS name also fine
   once Tailscale is up on the phone), or
2. **HTTPS tunnel** — ngrok / Cloudflare tunnel / similar, or
3. Any other reachable `http(s)://…/mcp`.

Since 1.8.0 the primary upstream is **prefilled** with the built-in public mesh default
`https://presocial-endocrinopathic-fumiko.ngrok-free.dev/android/mcp` (see *Built-in default
connection URLs* below). Override it in MainActivity (Settings → “Save upstream URL”; saving a blank
field restores the default) or:

```kotlin
SecurePrefs.setUpstreamMcpUrl(ctx, "http://100.x.x.x:8486/mcp")
SecurePrefs.setUpstreamLanFallbackUrl(ctx, "http://192.168.1.158:8486/mcp") // optional
SecurePrefs.setUpstreamMcpToken(ctx, "…") // if upstream requires Bearer
```

LAN fallback is used **only** when the primary call fails **and** the phone
currently has an IPv4 address in the fallback host’s `/24`.

### curl (upstream relay via local /mcp)

```bash
# tools/list on upstream
curl -s $BASE/mcp -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"upstream_mcp_relay","arguments":{"method":"tools/list","payload":{},"timeout_ms":15000}}}'

# tools/call on upstream
curl -s $BASE/mcp -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"upstream_mcp_relay","arguments":{"method":"tools/call","payload":{"name":"some_tool","arguments":{}},"timeout_ms":20000}}}'

# local exec_shell routed upstream (requires upstream to expose exec_shell or shell)
curl -s $BASE/mcp -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H 'X-Confirm: true' \
  -d '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"exec_shell","arguments":{"command":"uname -a","confirm":true,"route":"upstream"}}}'
```

On failure the relay returns `ok:false` with the real error (HTTP / connect /
JSON-RPC). It never invents a successful result.

## Bridge mesh, Airtable, Wi-Fi presence, Zebra (1.7.0-dev)

### Bridge mesh relay

`bridge_relay{bridge, method, params, timeout_ms}` forwards a JSON-RPC call to one of
`android` / `spark` / `ether` / `war` / `magic` / `upstream`. Bridge URLs come from SecurePrefs and
(since 1.8.0) default to `<mesh base>/<bridge>/mcp` on the built-in public mesh host; override with
`SecurePrefs.setBridgeUrl(ctx, "magic", "https://…/mcp")` or the Settings panel. No token is ever
built in. Each bridge is health-checked first (`…/<bridge>/health` for path-mounted bridges, else
`{origin}/health`); an unhealthy bridge is not relayed to and never returns a faked result.

### Airtable telemetry

`airtable_log{base, table, fields}` posts one record. `base` must be an owner base
(`appQkHjQwQuyzVT99` or `appQDYTaD6gi64g7M`, or the aliases `primary`/`secondary`). The Airtable
API key is read from `SecurePrefs` (`airtable_api_key`) **at runtime and is never hardcoded**; set
it in the app ("Save Airtable key"). With no key the record is queued and the call returns
`ok:false` with `error:"Airtable key not set"`. A `AirtableSyncWorker` (WorkManager, every 30 min,
network-constrained) flushes the queue; `airtable_log{…, flush_only:true}` flushes on demand.

### Wi-Fi presence sensor (honest)

`wifi_presence_scan{duration_ms}` samples RSSI, link speed, noise floor and frame delivery, keeps a
sliding window, computes variance + a small DFT, and returns `motion_score`, `presence_detected`
and `confidence`. **It detects movement/occupancy near the device only. It CANNOT see through walls
and CANNOT identify or count people** — Android does not expose Wi-Fi CSI to apps, so true RF
tomography is impossible from an app. The response always carries `cannot_see_through_walls:true`.

### Zebra no-license capability layer

`zebra_wifi_config`, `zebra_datawedge_profile`, `zebra_scanner_control` use only pathways that work
**without a paid Mobility DNA license**: DataWedge intents, standard `WifiManager`/`DevicePolicyManager`
(device-owner via Dhizuku), and Shizuku shell (`cmd wifi`/`svc wifi`). **No license-check bypass,
reflection into licensing daemons, or forged license keys are implemented.** Features that genuinely
require a paid license (Fusion Advanced Wi-Fi tiers, Enterprise Browser, OEMConfig premium, premium
StageNow profiles, …) are intentionally omitted and listed in `ZebraCapabilityLayer.kt`.

## Built-in default connection URLs (1.8.0)

These ship **prefilled** so nothing needs manual entry on first launch. They are public connection
URLs, **not secrets**; every one is editable/overridable in the app (Settings panel), and saving a
blank field restores the default. **No token, bearer value or Airtable key is ever built in** —
those stay blank in SecurePrefs until the owner sets them.

| Setting | Built-in default |
|---------|------------------|
| Local MCP | `http://127.0.0.1:17999/mcp` (ws `/ws`) — the on-device server |
| Mesh base | `https://presocial-endocrinopathic-fumiko.ngrok-free.dev` |
| Upstream MCP | `<mesh base>/android/mcp` |
| Bridge `android` | `<mesh base>/android/mcp` |
| Bridge `spark` | `<mesh base>/spark/mcp` |
| Bridge `ether` | `<mesh base>/ether/mcp` |
| Bridge `war` | `<mesh base>/war/mcp` |
| Bridge `magic` | `<mesh base>/magic/mcp` |

Upstream and bridge defaults are derived from the mesh base at read time, so changing the mesh base
moves every bridge you have not overridden.

## AI discovery (1.8.0)

Goal: any AI / MCP client can find the broker, learn what it does, and learn how to connect —
without ever being handed a credential.

### 1. Open manifest: `GET /discover` and `GET /.well-known/mcp.json`

Unauthenticated, read-only, `Cache-Control: no-store`. Shape (`schema: shizuku-broker-discovery/1`):

| Field | Contents |
|-------|----------|
| `app` | `id`, `name`, `version`, `version_code` |
| `device` | `manufacturer`, `model`, `android_release`, `sdk_int` |
| `mcp` | `server_info`, `running`, `protocol`, `protocol_versions`, `default_protocol_version`, `methods`, `transports` (`POST /mcp`, WebSocket `/ws` subprotocol `mcp`) |
| `auth` | `required: true`, `scheme: bearer`, accepted header templates, `websocket_query_param`, `high_risk_confirmation`, `credential_source` (a sentence — never the value) |
| `open_endpoints` | `/health`, `/discover`, `/.well-known/mcp.json` |
| `connections[]` | `loopback` (`mcp_http`, `mcp_ws`, `discover`, `health`), `tailscale_<100.x>` (node address + how: adb over Tailscale + `adb forward`), `mesh_<bridge>` for android/spark/ether/war/magic (`mcp_http`, `health`), `upstream` |
| `mesh_base` | The mesh base URL |
| `mdns` | `service_types`, `service_name`, `registered`, `port`, `txt` |
| `tools[]` / `tool_count` | `name`, `risk` (LOW/MEDIUM/HIGH), one-line `description` — no schemas, no args |
| `limits` | Honest limits: Wi-Fi presence is near-device RSSI only (no through-wall, no CSI, no people identification); Zebra uses no-license APIs only |
| `credentials_included` | always `false` |

Credential safety (enforced in `DiscoveryManifest` and unit-tested):
- URLs are stripped of user-info, query and fragment (a `?token=` can never ride along).
- A final pass redacts any string containing a real SecurePrefs secret (MCP token, Airtable key,
  upstream/bridge tokens) and removes any key containing `token`/`secret`/`password`/`psk`/`api_key`/…
- Connection URLs come from SecurePrefs (with the built-in non-secret defaults above); no LAN IP is hardcoded.

> The HTTP server still binds **127.0.0.1 only**. The manifest tells remote AIs to come in via a mesh
> bridge, or adb over Tailscale + `adb forward tcp:17999 tcp:17999`.

### 2. mDNS / DNS-SD (Bonjour / zeroconf)

`BrokerService` registers two services with Android `NsdManager` on start and unregisters on stop:
`_mcp._tcp` and `_broker._tcp`, name `ShizukuBroker-<model>`, port `17999`, TXT:

```
path=/discover  wk=/.well-known/mcp.json  mcp=/mcp  ws=/ws  auth=bearer
bind=127.0.0.1  ver=1.8.0-dev  id=com.rjkyker.shizukuai.broker
mesh=https://presocial-endocrinopathic-fumiko.ngrok-free.dev/android/mcp
```

A LAN peer browsing `_mcp._tcp` (e.g. `avahi-browse -rt _mcp._tcp`, `dns-sd -B _mcp._tcp`) finds the
broker and reads the TXT. Because the port is loopback-bound, the useful pointer for a LAN peer is
`mesh=` (or adb). No secret is ever placed in TXT.

### 3. `discovery_info` MCP tool

LOW risk, bearer-auth like every other tool:

```sh
curl -s -X POST $BASE/mcp -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"discovery_info","arguments":{}}}'
```

### 4. Flow for an AI client

1. Find it: mDNS `_mcp._tcp`, or `GET http://127.0.0.1:17999/.well-known/mcp.json` on-device /
   through `adb forward`.
2. Read `connections[]` and pick a transport; read `tools[]` to see what is possible.
3. Get the bearer token from the owner (Security panel → Reveal / Rotate). Discovery never serves it.
4. `initialize` → `tools/list` → `tools/call` on `/mcp` or `/ws`; HIGH-risk tools need `X-Confirm: true`.

### Expanded `/health` (open, booleans/ids only)

`ok, port, accessibility, package, version, mcp, auth_required, endpoints{mcp,ws,action,health,discover,well_known}`,
plus `server{running,host,port,bearer_required}`, `shizuku{binder_alive,permission_granted}`,
`dhizuku{installed,granted}`, `upstream_configured`, `airtable_key_set`,
`wifi_presence{state,last_scan_at_ms,last_ok,last_presence_detected,last_motion_score,last_confidence,last_error,kind,cannot_see_through_walls}`,
`mdns{registered,types}`, `pid`, `uptime_ms`.

## Command-center dashboard (1.8.0)

MainActivity is a dark, panelled dashboard (MaterialCardView panels + RecyclerView status rows with
LEDs). Every value is bound to real state; nothing is mocked.

- **Systems**: MCP server (port + bearer required), Shizuku binder/permission/backend, Dhizuku
  device-owner, Accessibility, Upstream reachable (live probe), Airtable key set (bool), Wi-Fi presence
  engine state, mDNS registration, battery-optimization exemption, app version/pid/uptime.
- **Bridge mesh**: android/spark/ether/war/magic/upstream, probed via `GET …/health` every 60 s while
  the app is open (or on tap), with COPY per URL.
- **Connections & discovery**: the same entries as the `/discover` manifest, COPY on each.
- **Security**: reveal/copy token, one-tap **Rotate MCP token** (confirm → new token shown once,
  hidden again when the app is backgrounded).
- **Controls**: Shizuku / Dhizuku / Accessibility / battery / DataWedge.
- **Settings**: prefilled mesh base, upstream URL and bridge URLs (editable), Airtable key (write-only,
  encrypted).
- **Customize**: show/hide each panel and pick an accent (cyan/amber/magenta/green/red); persisted in
  non-secret `broker_ui_prefs` SharedPreferences.

## Persistence

- `BrokerService` is `START_STICKY` with a low-importance ongoing notification
  that shows local MCP + configured upstream **host** (never the token).
- `BootReceiver` handles `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`.
- Binder watchdog inside `BrokerService` re-inits Dhizuku when Shizuku returns
  and restarts `BrokerServer` if its thread died.
- Request “Ignore battery optimizations” from MainActivity so Doze does not kill the server.


## Roam-proof networking (1.8.2)
- Listens on `127.0.0.1:17999` + every Tailscale IPv4 (100.64.0.0/10) on the device. Never `0.0.0.0`/LAN.
- From any tailnet peer: `curl http://100.95.242.123:17999/health` (open) and
  `curl -X POST http://100.95.242.123:17999/mcp -H "Authorization: Bearer $TOKEN" -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'`.
- Rebinds automatically on network change / Tailscale up / boot (`RoamKeeper`). `/health.bind` lists live addresses.
- Phone side: Tailscale → Always-on VPN (or the **Roam-proof setup** button via Dhizuku); battery optimization off
  for Tailscale + broker.

## Scans (1.8.2)
- DataWedge output: broadcast `com.rjkyker.shizukuai.broker.SCAN`, category DEFAULT (broker profile + Profile0).
  **Repair DataWedge profile** re-applies it. Test: `adb shell am broadcast -a com.rjkyker.shizukuai.broker.SCAN
  -c android.intent.category.DEFAULT --es com.symbol.datawedge.data_string "]C101095011010209171727063010ABC123"
  --es com.symbol.datawedge.label_type LABEL-TYPE-GS1-128`.
- Each scan record (and `scan_history`) includes `english[]`: `{field, value, meaning}` plain-English explanations.

## Talk voice relay (1.8.2)
- Phone → `POST https://presocial-endocrinopathic-fumiko.ngrok-free.dev/muse/voice` `{text, session}` (X-Broker-Token),
  polls `GET …/muse/voice/reply?id=`. Muse answers via the Muse bridge MCP tools `voice_inbox` / `voice_reply`.
- Replies are spoken with Android TTS ("Device voice"); Muse offers no TTS API.
