# DataWedge results (1.4.0)

Android does not hand DataWedge `RESULT_ACTION` replies back to a shell (`am broadcast` from
uid 2000 gets nothing). In 1.4.0 the broker sends the DataWedge GET_* queries itself, catches
the replies, and writes them where a shell, Termux, or the Android MCP bridge can read them.

## Query from adb / rish / Termux

```bash
# live query (status, version, profiles, active_profile); prints JSON in "data="
am broadcast -n com.rjkyker.shizukuai.broker/.datawedge.DataWedgeQueryReceiver \
  -a com.rjkyker.shizukuai.broker.DW_QUERY --es query all
# query values: all | status | version | profiles | active_profile ; optional --es timeout_ms 6000

# persisted files
cat /sdcard/Android/data/com.rjkyker.shizukuai.broker/files/datawedge/last_query.json
ls  /sdcard/Android/data/com.rjkyker.shizukuai.broker/files/datawedge/
#   status.json version.json profiles.json active_profile.json last_result.json last_scan.json results.jsonl
```

## HTTP (127.0.0.1:17999, on the device)

```bash
curl -s "http://127.0.0.1:17999/datawedge/query?q=all"     # live
curl -s  http://127.0.0.1:17999/datawedge                   # last persisted replies
curl -s -X POST -d '{"op":"datawedge_query","query":"profiles"}' http://127.0.0.1:17999/action
curl -s -X POST -d '{"op":"datawedge_results"}' http://127.0.0.1:17999/action
```

## Notes

- GET_* replies do not echo `COMMAND_IDENTIFIER`, so the broker matches replies by result
  kind (`RESULT_GET_DATAWEDGE_STATUS`, `RESULT_GET_VERSION_INFO`, `RESULT_GET_PROFILES_LIST`,
  `RESULT_GET_ACTIVE_PROFILE`).
- The receiver is registered at runtime (BrokerApp / BrokerService) because Android 8+ does not
  deliver implicit broadcasts to manifest receivers; the manifest entry remains for targeted
  deliveries. Duplicates are de-duplicated.
- Only read-only queries are reachable through `DW_QUERY`; config changes stay on the
  allow-listed `/action` ops.

Verified on a Zebra TC22 (Android 13, DataWedge 13.0.107): status `ENABLED`, profiles
`Profile0 (default), Launcher, DWDemo, eSIM`, active `Profile0 (default)`.
