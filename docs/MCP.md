# MCP endpoint (broker 1.7.0-dev)

The always-on local server binds **only** to `127.0.0.1:17999` (same process as
`BrokerService` / `BootReceiver`). It speaks MCP JSON-RPC 2.0 over HTTP and a
minimal WebSocket.

## Endpoints

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| GET | `/health` | no | Keepalive for Termux menu. Returns `mcp:true`, `auth_required:true`. Never prints the token. |
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

WebSocket may also pass `?token=` (still localhost-only). CORS `*` was removed.

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
| `bridge_relay` | MEDIUM | Relay JSON-RPC to a named bridge `magic`/`war`/`ether`/`upstream`. Per-bridge URLs from settings (nothing hardcoded); health-checked first. |
| `airtable_log` | MEDIUM | Log one record to an owner Airtable base (`appQkHjQwQuyzVT99`/`appQDYTaD6gi64g7M`). API key read from SecurePrefs at runtime; queues + no-ops when unset. |
| `wifi_presence_scan` | LOW | Honest RSSI/link-variance presence & motion score. **Near-device only; cannot see through walls or identify people** (Android exposes no Wi-Fi CSI). |
| `zebra_wifi_config` | HIGH | No-license Wi-Fi control (WifiManager/DPM/shell). status is free; mutating ops need `confirm:true`. **No license bypass.** |
| `zebra_datawedge_profile` | MEDIUM | No-license DataWedge profile CRUD (list/active/create/set_config/switch). |
| `zebra_scanner_control` | MEDIUM | No-license scanner enable/disable + soft scan via DataWedge. |

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

There is **no** documented public/ngrok URL for the 8486 node checked into this
repo, so the built-in default is **empty**. Paste the URL in MainActivity
(“Save upstream URL”) or:

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
`magic` / `war` / `ether` / `upstream`. Every bridge URL is read from settings
(`SecurePrefs.setBridgeUrl(ctx, "magic", "https://…/mcp")`; `upstream` reuses the Upstream MCP
URL). **No bridge address is hardcoded** — a bridge with no URL set returns `ok:false`. Each
bridge is health-checked (`GET {origin}/health`) before the call; an unhealthy bridge is not
relayed to and never returns a faked result.

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

## Persistence

- `BrokerService` is `START_STICKY` with a low-importance ongoing notification
  that shows local MCP + configured upstream **host** (never the token).
- `BootReceiver` handles `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`.
- Binder watchdog inside `BrokerService` re-inits Dhizuku when Shizuku returns
  and restarts `BrokerServer` if its thread died.
- Request “Ignore battery optimizations” from MainActivity so Doze does not kill the server.
