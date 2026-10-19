# Action Allow-list

Allowed actions (see `ActionAllowList` / `schemas/action.schema.json`):

## BrokerServer / ActionRouter ops

- tap
- swipe
- text
- key
- ui_dump
- shell
- admin_scopes
- admin_perm
- admin_suspend
- launch
- datawedge_status
- datawedge_version
- datawedge_profiles
- datawedge_active_profile
- datawedge_soft_scan
- datawedge_switch_profile
- datawedge_create_profile
- datawedge_set_config
- datawedge_last
- datawedge_query
- datawedge_results

Aliases also allow-listed: uidump, snapshot, scopes, perm, suspend.

## MVP structured actions

- GET_DEVICE_INFO
- GET_PACKAGE_LIST
- GET_CURRENT_APP
- OPEN_APP
- OPEN_SETTINGS
- GET_BATTERY_INFO
- GET_DISPLAY_INFO

## Rules

- Requests must map to an explicit allow-listed action
- Risk levels come only from `ActionAllowList.actionRiskLevels`; callers cannot set them,
  and unknown actions fail closed to HIGH
- No arbitrary AI-generated shell strings without validation; `shell` is HIGH risk
- No silent microphone or camera capture
- No credential harvesting
- No hidden surveillance
- No destructive commands without explicit confirmation
- Destructive admin ops (`admin_perm`, `admin_suspend`, `shell`) are HIGH risk
