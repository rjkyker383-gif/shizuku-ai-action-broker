# Changelog

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
