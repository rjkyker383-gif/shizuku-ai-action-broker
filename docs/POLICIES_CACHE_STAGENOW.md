# 1.10.0 — Policies, cache hygiene, StageNow / MX

## Policies (in-app + MCP `broker_policies`)
Dashboard → Autonomy → **Policies / cache / StageNow**.

Soft toggles (owner-tunable):
- `type_anywhere_enabled` — master switch for finish-my-sentences
- `skip_sensitive_fields` — **locked ON** (password/OTP/PIN/card); cannot be disabled
- `preview_first_messaging` — **locked ON**; AI never auto-sends
- `foreground_recheck_before_touch` — a11y tap/swipe/text/click_text abort when `expect_package` ≠ foreground
- `mcp_host_allowlist_enforced` — when on, upstream / bridge relay URLs must match the host allowlist (localhost always ok)

**Locked denylist** (UI read-only, always enforced in `ShellDenylist` / `OwnerPolicyCatalog.FORBIDDEN_OPS`):
pm uninstall/clear, rm -rf on data, wipe/factory reset, dialer/SMS intents, bare dumpsys / AMS dumpsys, bugreport, locksettings password changes, dpm owner removal, adb_enabled 0, zebra/mx license bypass.

MCP: `broker_policies` op=status|set (confirm)|set_hosts|exclude_app.

## Cache (`broker_cache`)
- `clear_own` — deletes contents of `cacheDir` / `externalCacheDir` / `codeCacheDir` only. databases, shared_prefs, filesDir untouched. Never `pm clear`.
- `trim_system` — `pm trim-caches <size>` via Shizuku (confirm). Other apps' caches only.

## StageNow / MX (`stagenow`)
Legitimate XML export + launch only. **No Mobility DNA license bypass.**

Works without a DNA license:
- Export AccessMgr SystemSettings=Full, SettingsMgr enables, combined unlock, AppMgr disable (e.g. OSX Provision Service), basic open Wi-Fi Add.
- Share/open the XML into an installed StageNow client (`com.symbol.stagenow` / MDNA variants).
- DataWedge profile/scanner control (existing `zebra_*` tools) — free on every Zebra with DataWedge.

Needs EMDK SDK on the classpath + typically a licensed MX feature set:
- Live `ProfileManager.processProfile` submit. The broker is built **without** the EMDK AAR; `submit_emdk` reports that honestly and points at StageNow export.

Intentionally omitted (paid / bypass): OEMConfig premium, Fusion Advanced, Enterprise Browser/Keyboard premium, forged license keys, licensing-daemon reflection.

## Install (when adb is up)
```
adb -s 127.0.0.1:5557 install -r /workspace/apk-host/broker-1.10.0-dev.apk
```
Never `-d`, never uninstall. Restart priv server after install.
