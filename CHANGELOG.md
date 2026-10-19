# Changelog

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
