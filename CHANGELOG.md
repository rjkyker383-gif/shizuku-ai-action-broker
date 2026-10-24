# Changelog

## Unreleased / 1.6.0-dev
- Permanent MCP JSON-RPC on `POST /mcp` + minimal WebSocket at `GET /ws` (same ServerSocket as `/health`/`/action`).
- Bearer auth (`Authorization` / `X-Broker-Token`); token in SecurePrefs; `/health` stays open with `mcp`/`auth_required` flags.
- Tools: `exec_shell` (local|upstream route, optional short sessions), `dump_ui_hierarchy`, `perform_gesture`,
  `manage_dhizuku_policy` (no install/uninstall), `datawedge_query`, `wedge_sync_control`, `upstream_mcp_relay`,
  `get_system_status`.
- Upstream MCP relay: any-wifi URL (Tailscale/tunnel) via SecurePrefs; optional LAN fallback only on matching /24.
- BrokerService sticky + Shizuku/Dhizuku binder watchdog; battery-optimization exemption prompt.
- ShellExecutor multiplexer (max 3 background sessions, not a PTY).

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
