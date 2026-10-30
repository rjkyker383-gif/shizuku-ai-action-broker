# 1.9.0-dev (vc19) — on-device LLM + autonomy foundation + Mod Console

Branch `feature/1.9.0-autonomy` (from 1.8.8 head 630f2d5). Implements the first verified slice of
`docs/ON_DEVICE_LLM_PLAN.md` and `docs/AUTONOMY_UPGRADES_DESIGN.md`.

## Shipped
| Area | What |
|---|---|
| LLM runtime | llama.cpp submodule `third_party/llama.cpp` @ 8642541 (v0.8.0, MIT), own JNI (`app/src/main/cpp`), CPU only, `armv8.2-a+dotprod+fp16`, OpenMP/KleidiAI off, -O3 even in debug, arm64-v8a only (+5.5 MB lib). |
| `:llm` process | `LocalLlmService` (bound AIDL `ILocalLlm`, exported=false, FGS specialUse while busy). Caps n_ctx ≤ 2048, max_new ≤ 256, 45 s; 2 decode / 4 prefill threads; idle unload 120 s; unload on trim-memory; cancel+unload at thermal ≥ SEVERE; refuses work at ≥ MODERATE. |
| Models | `ModelCatalog`: Qwen2.5-0.5B-Instruct Q4_K_M and SmolLM2-360M Q4_0, pinned HF revision + exact size + SHA-256. `ModelStore`: noBackupFilesDir/models, resumable `.part`, size + SHA-256 verify, atomic rename, marker file. |
| Preflight | storage ≥ model + 750 MB, RAM headroom (900 / 450 MB), thermal < MODERATE, not paused (cross-process read), battery ≥ 15 % or charging. Never deletes anything to make room. |
| Router (U5) | Qwen → SmolLM2 → hosted (opt-in, OFF, not wired). Offline-first; falls back only on load/preflight/missing-model errors. Runs recorded in `model_runs`. |
| Permission gate (U7) | `PermissionEngine`: actors USER_DEVICE / MCP_AI / PLANNER / SCHEDULER / WATCHDOG. Autonomous actors get only registry READ/LOW_WRITE tools (never shell, priv, owner policy, Dhizuku, a11y UI control, relays). Device-only: send_approve, memory_accept, send_allowlist_edit, send_kill_switch. Unknown/destructive = FORBIDDEN. |
| Queue (U2) | Room `autonomy.db` (tasks, task_events, audit, memory, outbox, model_runs; schema exported to `app/schemas`). Validated state machine, idempotency keys, leases with crash recovery, TTL expiry, backoff 30 s→30 min, cap 50 active. |
| Scheduler | WorkManager one-shot + 15-min periodic. Gates: autonomy flag, Pause, thermal, battery (20 % / 30 % for LLM), battery saver. No exact alarms. |
| Audit (U9) | Every MCP tool call + every autonomous step: actor, tool, tier, decision, SHA-256 of canonical args (never raw args), capped 5000. Dashboard panel + `autonomy_audit`. |
| Memory (U3) | OFF by default; owner notes active, AI proposals pending until accepted on device; view/accept/delete/wipe/export; secret-looking text refused. |
| Context (U4) | `ContextManager` budgeted prompt build (user request never dropped). |
| Planner/Verifier (U1/U6) | Template planner (status report · draft · draft-and-send → send preview); rule verifier (empty/length/codes/links/secrets/prompt leakage/repetition) → NEEDS_USER instead of continuing. |
| Watchdog (U8) | Probes server/a11y/Shizuku/priv/battery/thermal/storage; heals: restart own FGS, priv re-bootstrap via Shizuku (flag), recover tasks. Audited. |
| Delivery (U10) | Preview-first outbox. Kill switch OFF, allowlists EMPTY by default. Exact `package|recipient` match, content + rate rules (3/h, 10/day per recipient, 20/day, 30 s gap); even then only PENDING_CONFIRM → `SendConfirmActivity` (FLAG_SECURE, hidden overlays, obscured-touch reject, a11y-click-proof button, arm delay) → ACTION_SEND to the target app's compose screen; owner presses send. No shell/SmsManager/a11y send path. MCP cannot approve or enable sending. |
| UI | Dashboard panel “ON-DEVICE AI · AUTONOMY · AUDIT”; `WritingAssistantActivity` (draft-only + model management); **Mod Console** (`ui/mod/`, launched from the owner panel or `MainActivity --ez open_mod_console true`): HUD grid + scanline, privilege chain Shizuku→Dhizuku→priv 2000→broker, radial RAM/storage/temp gauges, tier-coloured animated op cards (READ cyan, MEDIUM green, HIGH amber, LOCKOUT red), terminal-style live log, pulse while running, burst/shake on result, hold-to-confirm ring for HIGH (wall-clock timed so “remove animations” cannot shorten it; a11y clicks refused), type-to-confirm for LOCKOUT. Views only, no new libraries; ambient animation stops in onStop/when paused/when animator scale is 0. Same gates: OwnerPolicyEngine(Caller.DASHBOARD) and manage_dhizuku_policy. |
| MCP | +10 tools: llm_status, llm_draft, llm_model (download = HIGH, confirm), task_submit, task_status, task_cancel, autonomy_audit, autonomy_control (enable = HIGH; send/memory/hosted device-only), memory_control (propose only), send_request (preview only). Allowlist + schema synced (84 actions). |

## Not shipped / gaps
- Notification RemoteInput reply path and device-only standing rules (design §U10) — 1.9.1.
- Streaming tokens to the UI (generation returns at the end).
- Hosted backend wiring (flag exists, OFF).
- LLM-based planner / second-pass self-eval — templates + rules only.
- On-device Room DAO instrumentation tests (JVM tests cover pure logic; DAO verified by on-device smoke).
- Vulkan/OpenCL offload not evaluated.
