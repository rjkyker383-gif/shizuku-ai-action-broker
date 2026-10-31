# Type anywhere: "finish my sentences" (1.9.1-dev)

The broker suggests the next few words in **any app's focused text box** through its AccessibilityService. You
tap the chip to insert the words at the cursor. **It never presses Enter, Send or an IME action. You always send.**

## On the phone
- **Enable:** Settings → Accessibility → *Shizuku AI Broker* (the same service as the a11y_* tools). The settings
  screen is at dashboard → Autonomy → **Type-anywhere assist**.
- **Chip:** pause typing for about 0.9 s and a Mod-style chip appears above the field. The badge shows the source:
  - `AI` (cyan): on-device LLM.
  - `REMOTE` (amber): box model. The field text left the phone.
  - `WORD` (green): offline word prediction, not AI.
  Tap the chip to insert, or ✕ to dismiss.
- **Manual triggers:**
  - Quick-settings tile **Assist**: closes the shade, then suggests.
  - Optional floating ✦ button.
  - MCP `input_suggest show_chip=true`.
- **All apps are on by default.** You can exclude packages in settings. Default exclusions: systemui, settings,
  permission controller and Play Store.
- **Hard refusals.** Fields are refused if they are:
  - `isPassword`
  - a password, visible-password, web-password or numeric-password input type
  - labelled (view id, hint or description) with password/pass/pwd/PIN/OTP/one-time/2FA/MFA/verification code/
    security code/CVV/CVC/card number/credit card/expiry/SSN/routing or account number
  
  For those fields nothing is read, suggested or inserted. The existing `a11y_text` tool now has the same guard.
- **Insertion order:**
  1. `ACTION_SET_TEXT`, with the text spliced at the cursor and the caret placed after it.
  2. Clipboard + `ACTION_PASTE`. This overwrites your clipboard.
  3. Last resort: priv-server `input text`, only for plain ASCII. It types characters only and never sends a key event.
- **Audit:** each insert records the package, length, source and method. Field text is never logged.
  Accepted words train the predictor only while *memory* is enabled (stored in app-private `predict_user.tsv`).

## Completion router (no phone model needed)
1. **On-device LLM.** Used only if a verified model is downloaded, `llm_enabled` is on and thermal is not STOP.
   On the TC22 there is no model today (storage), so this is skipped.
2. **Box server.** Opt-in (`remote_enabled`) and needs a token. It uses `POST /complete` with
   `Authorization: Bearer COMPLETE_TOKEN`.
3. **Offline word predictor.** About 0.5 MB in the APK. It completes the current word and adds 1–3 more words.
4. **Otherwise:** no chip, and `input_suggest` returns `skipped` with the reasons. It never makes up a suggestion.

## Box side (`/workspace/complete-bridge`, copy in `tools/complete-bridge`)
- `llama-server`: official llama.cpp release b12017 (CPU), localhost only, `127.0.0.1:8792`. The model is
  Qwen2.5-0.5B-Instruct Q4_K_M (Apache-2.0), pinned with sha256 `74a4da8c…`.
- `complete_server.py` on `127.0.0.1:8776`:
  - Uses a Bearer token (`COMPLETE_TOKEN` in `/workspace/muse-bridge/.env`, mode 600, never printed).
  - Caps input at 2000 characters and logs lengths only.
- `bin/ensure_bridge.sh`:
  - Starts both and runs `adb reverse tcp:8776 tcp:8776`, so the phone reaches the box at `http://127.0.0.1:8776/complete`.
  - Runs from cron every minute.
- **Phone setup** (once, via MCP from the box):
  `input_assist_config op=set remote_enabled=true remote_token=<COMPLETE_TOKEN> confirm=true`.
  The token is stored in the broker's encrypted SecurePrefs.
- **Public route:** not added. The ngrok gateway has no auth of its own. `/complete` is token-gated, so a
  `complete` route could be added later if you want the phone to reach it without adb.

## Desktop Commander (`/workspace/dc-bridge`, copy in `tools/dc-bridge`)
- `@wonderwhy-er/desktop-commander` 0.2.52 runs through `supergateway` 4.2.1 as Streamable HTTP MCP on
  **127.0.0.1:8777/mcp (localhost only)**. Telemetry is off (`telemetryEnabled=false`).
- **It is a full shell on the box, so it is NOT published on the ngrok gateway.** Publishing it is your call, and it
  would need auth in front.
- Ask it to run `tc22-type` with its `start_process` tool:
  `/workspace/broker-tools/tc22-type --source box` (add `--dry-run` to preview, or `--show` to print the text).

### tc22-type
1. `input_focused_read confirm=true`.
2. A completion from the box model, or from the phone router with `--source phone`.
3. `input_focused_insert mode=append confirm=true`.

It refuses blocked fields, supports `--only-package` and never submits.

## Running it on your own PC with adb
1. `npm i -g @wonderwhy-er/desktop-commander` (or `npx @wonderwhy-er/desktop-commander@latest setup` for
   Claude Desktop). Add it to your MCP client as a stdio server.
2. Connect the TC22: `adb connect <tc22-ip>:5555` (or USB), then `adb forward tcp:17999 tcp:17999`.
3. Put `BROKER_TOKEN=` and `COMPLETE_TOKEN=` in a local `.env` with mode 600. Edit `ENV`/`COMPLETE_URL` at the top of
   `tools/tc22-type`.
4. For a completion model on the PC, use llama.cpp's `llama-server` with the same GGUF and run `tools/complete-bridge`.
   To let the phone use it directly, run `adb reverse tcp:8776 tcp:8776`.
5. In your MCP client, ask Desktop Commander to run `tc22-type`.

## MCP tools
| tool | tier | what |
|---|---|---|
| `input_focused_read` | MEDIUM | package, class, view id, hint, length, cursor, blocked. Text only with `confirm=true` and never for blocked fields. |
| `input_focused_insert` | HIGH | `text`, `mode=append\|replace`, needs `confirm=true`. Refuses blocked fields and excluded apps. `submitted:false`, always. |
| `input_suggest` | MEDIUM | `source=auto\|local_llm\|box\|predictor`, `show_chip`. Returns suggestion, source, remote flag, latency and skipped reasons. |
| `input_assist_config` | MEDIUM | `op=status\|set`. Remote settings need `confirm=true`. The token is never returned. |

None of these are autonomous-actor tools (they are in `NEVER_AUTONOMOUS`).

## 1.9.3: Typewriter insert (default)
- **Default insert mode is typewriter.** Tapping a chip types the suggestion out with shell `input text` as uid 2000,
  the same as `adb shell input text`.
  - It goes through the broker's own priv server, with Shizuku as the fallback, so no box or adb is needed.
  - It types in 1–3 character chunks, with 35–90 ms gaps plus jitter and a pause after punctuation. The speed slider
    in Assist settings goes from 0.25× to 3×.
  - Set-text mode is still available: in settings, or `input_assist_config insert_mode=direct`.
- **Narrow command gate:** `TypeCommand` only lets `input text <escaped printable ASCII>` through.
  - Spaces become `%s`.
  - `()<>|;&*\'"`$~!#?[]{}^=` are backslash-escaped.
  - Never keyevent, tap or swipe, so there is no ENTER and no IME action.
  - Newlines and tabs become spaces.
  - Emoji and non-ASCII chunks are inserted with accessibility set-text at the cursor, or skipped and logged.
- **Checked before every chunk:** the same package, the same focused node, the screen is on, and the field is not
  password/OTP/card. If any of those fails, typing aborts and "typing stopped: <reason>" is shown.
- **Bubble while typing:** it turns into an amber ■ ("typing…"). Tap it to stop.
- **Audit:** records the package, length, typed and pasted counts, duration and method. Never the text.
- **MCP:** `input_focused_insert mode=type confirm=true`.
- **tc22-type modes:** `--mode type` (default; the phone types), `--mode direct`, and `--mode adb` (box-side
  `adb shell input text` per chunk, re-reading the focused field before each chunk).
