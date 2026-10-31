#!/usr/bin/env bash
# Idempotent: llama-server (127.0.0.1:8792) + /complete (127.0.0.1:8776) + adb reverse for the TC22. Safe for cron.
R=/workspace/complete-bridge
ADB=/workspace/android-tools/platform-tools/adb
mkdir -p $R/logs $R/run
if ! curl -fsS -m 2 http://127.0.0.1:8792/health >/dev/null 2>&1 && ! pgrep -f "$R/bin/run_llama.sh" >/dev/null; then
  setsid nohup $R/bin/run_llama.sh >/dev/null 2>&1 < /dev/null &
fi
if ! curl -fsS -m 2 http://127.0.0.1:8776/health >/dev/null 2>&1 && ! pgrep -f "$R/bin/run_complete.sh" >/dev/null; then
  setsid nohup $R/bin/run_complete.sh >/dev/null 2>&1 < /dev/null &
fi
# phone -> box: the TC22 reaches http://127.0.0.1:8776 through adb reverse (no public exposure)
if timeout 5 "$ADB" -s 127.0.0.1:5557 get-state >/dev/null 2>&1; then
  timeout 5 "$ADB" -s 127.0.0.1:5557 reverse --list 2>/dev/null | grep -q "tcp:8776" || \
    timeout 5 "$ADB" -s 127.0.0.1:5557 reverse tcp:8776 tcp:8776 >/dev/null 2>&1 || true
fi
exit 0
