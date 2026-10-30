# On-device LLM plan for the broker (Zebra TC22)

Status: plan only. Nothing is built, installed, or downloaded yet.
Written 2026-10-30 against branch `feature/1.8.8-owndroid-parity` (broker 1.8.8-dev, vc18).
Hardware facts in §1 were read over ADB, read-only, on 2026-10-30 at about 7:15 PM EDT.
Runtime facts in §2 were checked on the web on the same day.
**Every tokens/s and RAM figure in this document is an estimate. None of them were measured on the TC22.**
§9 lists the benchmark that has to replace these estimates before anything ships.

> WritingAssistantActivity was not found. It is not in any local or remote branch of
> `shizuku-ai-action-broker`, not in a GitHub code search of `rjkyker383-gif`, and not in the installed APK.
> This plan treats it as a new component (§5.6). If it lives in another repo, point us at it and §5.6 will be aligned to it.

---

## 1. Verified hardware (TC22, read-only over ADB 127.0.0.1:5557)

| Item | Value read | Source |
|---|---|---|
| SoC | Qualcomm **QCM5430** (`ro.soc.model`), platform `lahaina` | getprop |
| CPU | 6 cores: **4× Cortex-A55** (part 0xd05) @ 1.80 GHz + **2× Cortex-A78** (part 0xd41) @ 2.13 GHz | /proc/cpuinfo, cpufreq `cpuinfo_max_freq` |
| ISA features | `asimd asimddp fphp asimdhp atomics lrcpc dcpop` → **dotprod yes, fp16 yes, i8mm no, SVE no** | /proc/cpuinfo |
| ABI | `arm64-v8a` (also armeabi-v7a) | `ro.product.cpu.abilist` |
| RAM | **MemTotal 5,560,848 kB (~5.3 GiB, 6 GB SKU)**. MemAvailable **1,392,124 kB (~1.33 GiB)** at read time | /proc/meminfo |
| Swap (zram) | 3.0 GiB total, ~1.83 GiB free, so ~1.2 GiB already swapped | /proc/meminfo |
| GPU | **Adreno 643** (`gpu_model` = Adreno643v1), kgsl max_gpuclk read 315 MHz (governor cap at read time) | /sys/class/kgsl |
| Vulkan | `android.hardware.vulkan.version=4198400` → **Vulkan 1.1.0**, level 1, compute | pm list features |
| GL/EGL | `ro.hardware.egl=adreno`, GLES 3.2 (`ro.opengles.version=196610`) + AEP | getprop |
| OpenCL | `/vendor/lib64/libOpenCL.so` present and listed in `/vendor/etc/public.libraries.txt`, so an app can load it with `<uses-native-library>` | ls |
| DSP | `libcdsprpc.so` public (Hexagon). No QNN/SNPE libs in /vendor | ls |
| Storage | `/data` 36 GB, **98% used, ~918 MB free**. Termux's earlier "3.5 GB" is no longer true | df -h |
| Thermal | CPU zones 54–59 °C, GPU zones 49–51 °C, at idle-ish load (load avg ~9). thermal-hal + thermal-engine running | /sys/class/thermal |
| OS | Android 13 (SDK 33). Zebra build `13-12-08.00-TG-U00-STD-ATH-04` | getprop |
| Root | **none** (Shizuku uid 2000 and Dhizuku DO only) | — |

`dumpsys` was not used, per the freeze rule.

### What this means
* **CPU inference is the only path we can count on.** There are only two big cores. Use 2 threads pinned to the
  A78s for decode, and possibly 4 threads for prefill. A55s add little to decode.
* **Memory is the binding constraint, not compute.** ~1.3 GiB was free, with the broker, Termux, Tailscale and
  Zebra services all running and swap already in use. A model whose resident footprint goes above ~700–800 MB
  will cause lmkd kills: either of the LLM process, or worse, of Tailscale or the broker.
* **Storage blocks the install today.** 918 MB free means no model passes the preflight in §6. RJ must
  free about **1.3 GB** before the primary model can be downloaded.

---

## 2. Runtime options (checked 2026-10-30)

| Runtime | Android status today | TC22 GPU (Adreno 643, Vulkan 1.1, OpenCL) | Model format | License | Verdict |
|---|---|---|---|---|---|
| **llama.cpp** (ggml) via NDK/JNI | Actively maintained. Official `examples/llama.android`. CPU path has Arm dotprod kernels and Q4_0 runtime repacking (KleidiAI CPU kernels are optional) | **Vulkan:** ggml-vulkan targets Vulkan 1.2 (`VK_API_VERSION_1_2`) and needs 16-bit storage. Older Adreno drivers expose 1.1 without it, and reports show Android Vulkan running *slower than CPU* → **not usable**. **OpenCL:** the Adreno backend's verified list is A750/A810/A830/A840/X1/X2. Docs: "A6xx GPUs in phones are likely not supported due to the outdated driver and compiler". OpenCL 2.0 builds were fixed in July 2026 (PR #25673, cites Adreno 642L). One Adreno 650 report: OpenCL decode 18 t/s vs **CPU 49 t/s** → GPU **experimental only** | GGUF (Q4_0, Q4_K_M, Q8_0, …) | MIT | **Primary runtime (CPU)** |
| **LiteRT-LM** (successor to MediaPipe LLM Inference; MediaPipe `tasks-genai` is now *maintenance-only*) | Kotlin API `com.google.ai.edge.litertlm:litertlm-android`. CPU/GPU/NPU. Officially tuned for flagship devices | GPU through its own delegate. Unverified on A643 | `.litertlm` / `.task` (the LiteRT Community Qwen builds are **q8 only**: Qwen2.5-0.5B 547 MB, 1.5B 1.6 GB) | Apache-2.0 runtime. Model terms vary (Gemma Terms for Gemma 3) | **Fallback runtime** |
| **MLC LLM** (TVM) | Last official Android demo APK is 2024-09-26 (`Android-09262024`). Needs Rust/TVM build chain. Adreno `_1` weight layouts cause 20–50 s UI freezes at prefill | OpenCL on Adreno. Same old-driver concerns | MLC compiled libs + weights | Apache-2.0 | Not recommended: heavy toolchain and stale Android releases |
| **ONNX Runtime GenAI** | Official Android AAR (`onnxruntime-genai-android-*.aar`, ~35 MB) + `onnxruntime-android` | CPU EP. QNN EP needs QNN libs, which the TC22 lacks | ONNX + genai_config | MIT | Viable but larger APK and fewer small chat models. Second fallback |

Sources: llama.cpp `docs/backend/OPENCL.md`, issue #5198 (Vulkan 1.2 / 16-bit storage), PRs #9672, #12886 and #25673;
Google AI Edge LLM Inference page ("maintenance-only… migrate to LiteRT-LM") and LiteRT-LM overview;
mlc-llm `docs/deploy/android.rst` and the `Android-09262024` release; onnxruntime-genai `AndroidBuild.md` and release v0.13.1.

---

## 3. Candidate models (GGUF sizes and sha256 read from Hugging Face on 2026-10-30)

| Model | Quant | File size | License / terms | Fits TC22 RAM? (est. RSS @ 2k ctx) | Notes |
|---|---|---|---|---|---|
| **Qwen2.5-0.5B-Instruct** (official `Qwen/…-GGUF`) | Q4_K_M | **491.4 MB** | **Apache-2.0** | ~0.6–0.7 GB ✅ | Primary. KV ≈ 12 KB/token f16 → ~25 MB @ 2048 |
| Qwen2.5-0.5B-Instruct | Q4_0 | 428.7 MB | Apache-2.0 | ~0.55–0.65 GB ✅ | Fastest on dotprod (repack). Use if Q4_K_M is slow |
| Qwen3-0.6B (unsloth GGUF; official repo has Q8 only, 639 MB) | Q4_K_M | 396.7 MB | Apache-2.0 | ~0.55–0.65 GB ✅ | Evaluate. Needs `/no_think`. Third-party quant |
| **SmolLM2-360M-Instruct** (bartowski GGUF) | Q4_0 | **229.7 MB** | **Apache-2.0** | ~0.3–0.35 GB ✅ | Fallback for low RAM or storage. Weaker writing |
| Qwen2.5-1.5B-Instruct (official) | Q4_0 | 1,066 MB | Apache-2.0 | ~1.2–1.35 GB ⚠️ | "Upgrade tier". Only when MemAvailable ≥ 2.2 GB **and** storage allows |
| Llama 3.2 1B Instruct | Q4_K_M | 808 MB | **Llama 3.2 Community License** (AUP, "Built with Llama", 700M MAU clause) | ~0.95 GB ⚠️ | Skip: license friction, no quality win over Qwen at this size |
| Gemma 3 1B IT (QAT) | Q4_0 | 1,004 MB (gated) | **Gemma Terms of Use** + Prohibited Use Policy (gated download) | ~1.1 GB ⚠️ | Skip for primary: gated, so offline redistribution needs acceptance, and too large here |
| Gemma 4 E2B (`.litertlm`) | int4 | ~2.58 GB | Apache-2.0 | ❌ | Too big for this device's free RAM and storage |
| SmolLM2-1.7B-Instruct | Q4_K_M | 1,056 MB | Apache-2.0 | ~1.2 GB ⚠️ | Similar footprint to Qwen 1.5B, weaker |

Pinned artifacts (the download must verify these sha256 values; revision = HF commit):

| id | repo @ revision | file | bytes | sha256 |
|---|---|---|---|---|
| `qwen2.5-0.5b-q4km` | Qwen/Qwen2.5-0.5B-Instruct-GGUF @ `9217f5db79a29953eb74d5343926648285ec7e67` | qwen2.5-0.5b-instruct-q4_k_m.gguf | 491400032 | `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db` |
| `qwen2.5-0.5b-q40` | same @ `9217f5db…` | qwen2.5-0.5b-instruct-q4_0.gguf | 428730208 | `7671c0c304e6ce5a7fc577bcb12aba01e2c155cc2efd29b2213c95b18edaf6ed` |
| `smollm2-360m-q40` | bartowski/SmolLM2-360M-Instruct-GGUF @ `7be6f65f1db715fe5dc5a4634c0d459b4eed42ec` | SmolLM2-360M-Instruct-Q4_0.gguf | 229733280 | `c3608933eb6e5763b87f769bda40c204dc158333668c7af214644fe39da58627` |
| `qwen2.5-1.5b-q40` (upgrade) | Qwen/Qwen2.5-1.5B-Instruct-GGUF @ `91cad51170dc346986eccefdc2dd33a9da36ead9` | qwen2.5-1.5b-instruct-q4_0.gguf | 1066227232 | `dcd819ff094852c38faba6873d8ff0c9d51eadb2844539e52042ae5d647bbfdb` |
| `qwen3-0.6b-q4km` (eval) | unsloth/Qwen3-0.6B-GGUF @ `50968a4468ef4233ed78cd7c3de230dd1d61a56b` | Qwen3-0.6B-Q4_K_M.gguf | 396705472 | `ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a` |

### Recommendation
* **Primary:** llama.cpp (CPU, NDK, arm64-v8a, dotprod) + **Qwen2.5-0.5B-Instruct Q4_K_M** (Apache-2.0, official
  first-party quant, 491 MB).
* **Fallback:** chosen automatically on preflight failure or OOM. Stay on llama.cpp and switch to
  **SmolLM2-360M-Instruct Q4_0** (230 MB, Apache-2.0). If llama.cpp itself fails to build or load, use
  **LiteRT-LM CPU + Qwen2.5-0.5B `.task` q8 (547 MB)**.
* **Upgrade tier (opt-in, later):** Qwen2.5-1.5B-Instruct Q4_0, only after storage is freed and a benchmark
  shows MemAvailable ≥ 2.2 GB during normal use.
* GPU (llama.cpp OpenCL on A643) is a **benchmark-only experiment**. Ship CPU.

---

## 4. Performance estimates (NOT measured on TC22)

Basis: decode is memory-bandwidth bound. One published reference is a Snapdragon 865 phone
(4× A77 big cores) running Qwen2.5-0.5B Q4_0 on CPU with 4 threads: tg ≈ 49 t/s, pp ≈ 83 t/s (llama.cpp PR #12886).
The TC22 has only 2 big A78 cores at 2.13 GHz plus A55s, so I scale that down by ~2–3×.

| Model | Prefill (est.) | Decode (est.) | First token for a 300-token prompt (est.) | 120-token reply (est.) |
|---|---|---|---|---|
| Qwen2.5-0.5B Q4_K_M / Q4_0 | 40–80 t/s | **15–25 t/s** | 4–8 s | 5–8 s |
| SmolLM2-360M Q4_0 | 60–110 t/s | 22–35 t/s | 3–5 s | 4–6 s |
| Qwen2.5-1.5B Q4_0 | 15–30 t/s | 6–10 t/s | 10–20 s | 12–20 s |

RAM (est.) = mmap'd weights (shared file pages, reclaimable) + KV cache + compute buffers (~60–150 MB) + JNI overhead.
For the primary this comes to ~0.6–0.7 GB at ctx 2048. Thermal: expect throttling to cut decode 20–40% after
1–2 minutes of continuous generation. The design keeps replies short, so this is usually avoided.

---

## 5. Android integration architecture

```
 MainActivity / WritingAssistantActivity           McpToolExecutor (AI over MCP)
            │  bindService (same app, exported=false)           │
            ▼                                                    ▼
   ┌──────────────── process ":llm" ───────────────────────────────────────┐
   │ LocalLlmService (foreground, specialUse)  ── AIDL ILocalLlm           │
   │   ├─ Preflight (RAM / storage / thermal / pause / battery)            │
   │   ├─ ModelStore  (download → .part → sha256 → atomic rename)          │
   │   ├─ LlamaEngine (JNI → libllama.so, mmap, ctx cap, cancel flag)      │
   │   └─ Governor   (onTrimMemory, thermal listener, idle unload)         │
   └───────────────────────────────────────────────────────────────────────┘
            │ draft text only
            ▼
   SendGate (main process) ── §7, never reachable from the LLM process
```

### 5.1 Process, service and binder
* `<service android:name=".llm.LocalLlmService" android:process=":llm" android:exported="false"
  android:foregroundServiceType="specialUse">` with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
  ("On-device text drafting for the owner"). If the llama runtime crashes or is OOM-killed, only `:llm` dies.
  The MCP server, accessibility service and Tailscale stay up.
* Foreground **only while a model is loaded or generating**. After 120 s idle: unload the model, `stopForeground`,
  `stopSelf`.
* AIDL `ILocalLlm`:
  `status(): String(JSON)`, `preflight(modelId): String`, `ensureModel(modelId, IProgress)`,
  `generate(GenRequest, ILlmCallback): long requestId`, `cancel(requestId)`, `unload()`.
  `ILlmCallback.onToken(String)`, `onDone(String json)`, `onError(String code)`. Use oneway callbacks with
  coalescing (≤ 20 Hz) so the binder stays small.
* `GenRequest` is capped: prompt ≤ 2,000 tokens, `n_ctx` ≤ 2048 (default 1024), `max_new_tokens` ≤ 256
  (default 160), temperature ≤ 1.0. One request at a time; a second request gets `BUSY`.
* Binder calls are checked: `Binder.getCallingUid() == Process.myUid()`.

### 5.2 Model storage and download
* Location: `context.noBackupFilesDir/models/<id>.gguf`. This is app-specific internal storage: excluded from
  backup, not world-readable, removed on uninstall. Never `/sdcard`.
* The download runs only on explicit user action in the dashboard. The AI cannot trigger it; MCP gets `llm_status`
  only. Use HTTPS to the pinned HF `resolve/<revision>/<file>` URL, on unmetered Wi-Fi by default.
  Resume with `Range`.
* Write `<id>.gguf.part`, stream it through SHA-256, compare with the pinned hash (table above), then `rename()`.
  On mismatch, delete the file and report `HASH_MISMATCH`. Re-verify the hash on first load after each app update,
  and cache the result keyed by (size, mtime).
* Model license text is shown once and its acceptance stored. Apache-2.0 NOTICE ships in the about screen.

### 5.3 Runtime (llama.cpp via JNI)
* Build `libllama.so` + `libggml*.so` with NDK r27+ for `arm64-v8a`, `-march=armv8.2-a+dotprod+fp16`,
  `GGML_OPENMP=OFF`, CPU backend only (OpenCL in a separate experimental flavor). Pin a llama.cpp tag.
  The MIT license goes into NOTICE.
* `use_mmap=true`, `use_mlock=false`: weights stay page-cache backed, so the kernel can reclaim them under pressure
  instead of killing the process. `n_threads=2` for decode on CPUs 4–5 and `n_threads_batch=4` for prefill. Set
  affinity with `sched_setaffinity` from the JNI thread. KV cache f16. Flash attention off (no gain on CPU at this size).
* Cancellation: a `std::atomic<bool>` checked between decode steps and in the llama abort callback. `cancel()`
  returns within one token.
* Timeouts: 45 s wall clock per request. Stop at the first of max tokens, EOS, or a stop string.
* Chat template comes from the GGUF metadata (`tokenizer.chat_template`). The system prompt is fixed, short, and
  states it is "drafting only".

### 5.4 Governor (backoff)
* `onTrimMemory(TRIM_MEMORY_RUNNING_LOW | RUNNING_CRITICAL | BACKGROUND+)` → cancel and unload immediately.
* `PowerManager.addThermalStatusListener`: at `THERMAL_STATUS_MODERATE`, halve `max_new_tokens` and drop to
  1 thread. At `SEVERE` or above, refuse new requests and cancel the current one. Also poll
  `getThermalHeadroom(10)` (API 30+); if it is ≥ 0.85, defer.
* **Pause:** `BrokerPause.isPaused(ctx)` is checked in preflight and before every generate. A pause during
  generation cancels it. The `broker_pause` tool unloads the model.
* Battery: refuse below 15% unless charging (`BatteryManager.EXTRA_LEVEL` sticky intent).
* Never start while the DataWedge scan pipeline is busy or a Talk session is active, so scanning latency stays first.

### 5.5 MCP surface (stays narrow)
* Add `llm_status` (LOW, read-only) and `llm_draft` (LOW: returns text only and never sends). Add both to
  `ActionAllowList` and the schema. **No** `llm_download`, `llm_send` or `approve_send` tools.
* The Shizuku/ShellDenylist and the existing 74-action allowlist are unchanged. The LLM process never gets Shizuku,
  Dhizuku, or the priv-server binder: it has no reference to `ShizukuBridge`, `PrivServiceClient` or
  `OwnerPolicyEngine`. This is enforced by a unit test that scans the `llm/` package for those imports.

### 5.6 WritingAssistantActivity (new)
* Today: it builds prompts only. Plan: it binds `ILocalLlm`, streams tokens into the editor, and has buttons
  Cancel, Copy, and **"Send…"**. "Send…" hands the draft to SendGate (§7) and never sends directly.

---

## 6. Preflight checks (before download, load and each generate)

| Check | API | Threshold (primary 0.5B) | Fail result |
|---|---|---|---|
| Free storage before download | `StatFs(noBackupFilesDir).availableBytes` | ≥ model bytes + **750 MB** floor (≈ 1.24 GB for the primary) | `STORAGE_LOW` (**fails today: 918 MB free**) |
| Free storage at load | same | ≥ 300 MB | `STORAGE_LOW` |
| Available RAM | `ActivityManager.getMemoryInfo()` `availMem`, `lowMemory`, `threshold` | `availMem − threshold ≥ 900 MB` and `!lowMemory` (1.5B tier: ≥ 2.2 GB) | `RAM_LOW` → try fallback model |
| Process headroom | `Debug.getPss()` of `:llm` after load | ≤ 900 MB | unload and fall back |
| Thermal | `PowerManager.currentThermalStatus`, `getThermalHeadroom` | < MODERATE, headroom < 0.85 | `THERMAL` |
| Pause | `BrokerPause.isPaused` | false | `PAUSED` |
| Battery | sticky `ACTION_BATTERY_CHANGED` | ≥ 15% or charging | `BATTERY_LOW` |
| Integrity | SHA-256 vs pinned | equal | `HASH_MISMATCH` |
| ABI/CPU | `Build.SUPPORTED_64_BIT_ABIS`, `/proc/cpuinfo` has `asimddp` | arm64-v8a + dotprod | `UNSUPPORTED` |

Results go into `llm_status` so the AI can tell RJ *why* it cannot draft right now.

---

## 7. Safe message auto-send design (draft-only AI)

**Rule zero: the AI drafts, and a human-set gate decides.** No MCP tool, Muse call or LLM output can approve
or perform a send. Only SendGate in the main process can send. It acts only on an on-device user confirmation,
or on a standing rule RJ created on the device itself.

### 7.1 Gates (all must pass, in order)
1. **Kill switch:** `send_enabled` (default **off**). It is toggled only in the dashboard; a quick-settings tile
   and the `broker_pause` tool can turn it *off* (never on). If off, the request is rejected and logged.
2. **App allowlist:** a set of package names plus a pinned signing-cert SHA-256 per package. Example:
   `org.thoughtcrime.securesms` (Signal), `com.google.android.apps.messaging`. The default is empty. Edited only on
   the device, with a confirm dialog.
3. **Recipient allowlist, per app:** a normalized E.164 number, or the exact conversation title or shortcut ID for
   notification-reply paths. Matching is exact; no wildcards or regex. The default is empty.
4. **Content checks:** ≤ 500 chars; no URLs unless the rule allows them; no OTP-like or 6-digit-code patterns; no
   phone numbers or emails other than the recipient's; must not contain the MCP token or any SecurePrefs secret
   (scrubbed with the DiscoveryManifest scrubber).
5. **Rate limits:** per recipient ≤ 3/hour and ≤ 10/day; global ≤ 20/day; ≥ 30 s between sends. Exceeding
   any limit forces a per-message confirm or a rejection.
6. **Approval**, one of:
   * **Per-message confirm (default):** a full-screen, non-exported `SendConfirmActivity` shows app, recipient and
     the exact text, with Send / Edit / Cancel. It requires a real touch on the device: `FLAG_SECURE`, ignore
     taps when `MotionEvent.FLAG_WINDOW_IS_OBSCURED`, and reject while our own AccessibilityService is
     dispatching a gesture (the a11y tools get a send-confirm-window guard that refuses to tap it). The
     confirmation expires after 2 minutes.
   * **Standing rule (opt-in):** created only in the dashboard on the device. Scope is one app, one recipient, a
     max length, a daily cap, a time window, and an expiry of ≤ 7 days. It shows a "will auto-send" notification
     with a 10 s Undo before sending.
7. **Audit log:** an append-only file in `noBackupFilesDir`, one JSON line per decision: time, app, recipient hash,
   text hash and length, gate results, approver (`user_touch` or `rule:<id>`), outcome. It is rotated at 1 MB. MCP
   gets `send_audit` (LOW, read-only, no message bodies).

### 7.2 Allowed send paths (official APIs only)
* **Notification reply (preferred for auto-send):** a `NotificationListenerService` that the user enables in
  Settings. For an allowlisted app's conversation notification, find the `Notification.Action` with a
  `RemoteInput`, fill it with `RemoteInput.addResultsToIntent`, and fire its `PendingIntent`. This is the reply
  path the app itself provides. It works only for apps that post reply actions, and only while that
  notification exists.
* **Share/compose intent (per-message confirm only):** `Intent.ACTION_SEND` / `ACTION_SENDTO` with
  `setPackage(allowlistedPkg)`, launched from a foreground activity after the user taps Send in
  SendConfirmActivity. The target app shows its compose screen and the user presses send there. This is never
  automated with accessibility taps.
* **Forbidden, and kept forbidden:**
  * Shizuku or priv-server shell `am start` with CALL/SEND/SENDTO/DIAL, `sms:`, `smsto:`, `tel:`,
    `mailto:`; `service call isms`; `cmd telecom`; and `settings put secure sms_*`. These are already in
    `ShellDenylist` and stay there.
  * `SmsManager` and `SEND_SMS` / `CALL_PHONE` permissions; the broker will not request them.
  * Accessibility taps on another app's send button.
  * Sending from the `:llm` process.

### 7.3 Who can do what
| Actor | Draft | Request send | Approve | Edit allowlists or rules | Kill switch |
|---|---|---|---|---|---|
| RJ on device (touch) | ✅ | ✅ | ✅ | ✅ | on/off |
| MCP / Muse / any AI | ✅ (`llm_draft`) | ✅ (`send_request`, MEDIUM: queues only) | ❌ never | ❌ | off only |
| Delegated client apps (1.8.8) | ❌ | ❌ | ❌ | ❌ | ❌ |
| Standing rule | — | — | ✅ within scope | ❌ | — |

`send_request` returns `PENDING_DEVICE_CONFIRM` or `REJECTED:<gate>`. It never returns `SENT` synchronously,
and an approval can never be inferred from an MCP argument such as `confirm=true`.

---

## 8. Offline and licensing summary

| Component | License | Network needed? | Redistribution / obligations |
|---|---|---|---|
| llama.cpp / ggml | MIT | No | Keep the copyright notice in NOTICE |
| LiteRT-LM runtime (fallback) | Apache-2.0 | No | NOTICE. Google's Generative AI Prohibited Use Policy applies to its use |
| ONNX Runtime GenAI (2nd fallback) | MIT | No | Build the AAR **without telemetry**: the telemetry build adds INTERNET and ACCESS_NETWORK_STATE |
| Qwen2.5-0.5B / 1.5B Instruct | Apache-2.0 | One-time download | NOTICE plus license text. Commercial use OK |
| Qwen3-0.6B | Apache-2.0 | One-time download | Same |
| SmolLM2-360M | Apache-2.0 | One-time download | Same |
| Llama 3.2 1B | Llama 3.2 Community License + AUP | One-time download | "Built with Llama", 700M MAU clause, AUP compliance (not chosen) |
| Gemma 3 1B | Gemma Terms of Use + Prohibited Use Policy | Gated download (HF login) | Pass through the terms and use restrictions (not chosen) |
| Gemma 4 E2B | Apache-2.0 | One-time download | Too large for this device |

After the one-time download, inference is fully offline: no telemetry, and no network access from `:llm`.
Enforce this with an `llm`-package unit test (no `java.net` or OkHttp in the `llm` engine; only ModelStore may
download).

---

## 9. Benchmark gate (must replace the estimates above before release)
1. Free storage to ≥ 1.3 GB.
2. Download the primary via ModelStore and verify its sha256.
3. Run 3 rounds of `llama-bench`-equivalent JNI calls (pp128, tg64) on threads 2 and 4.
4. Record PSS, MemAvailable before and after, and thermal status. Write them into this file as **measured** numbers.
5. Ship only if decode ≥ 10 t/s, `:llm` PSS ≤ 900 MB, and Tailscale and the broker were not killed during 10
   consecutive drafts.

## 10. Implementation slices
1. `llm/` package: AIDL, LocalLlmService (`:llm`), Preflight, ModelStore (pure-JVM tests: hash, resume, atomic
   rename), Governor. No runtime yet; returns `NO_RUNTIME`.
2. llama.cpp JNI module (CPU flavor) + LlamaEngine + cancellation. Then the benchmark gate (§9).
3. WritingAssistantActivity streaming UI.
4. SendGate: allowlists, rate limits, audit, SendConfirmActivity, NotificationListener reply path, kill switch tile.
   Tests: AI can never approve; gate ordering; rate limits; no SMS/telephony permissions in the merged manifest.
5. Optional: OpenCL experimental flavor and the Qwen2.5-1.5B upgrade tier.
