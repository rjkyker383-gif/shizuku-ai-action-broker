#!/usr/bin/env bash
R=/workspace/dc-bridge
mkdir -p $R/logs $R/run
if ! curl -fsS -m 2 http://127.0.0.1:8777/health >/dev/null 2>&1 && ! pgrep -f "$R/bin/run_mcp.sh" >/dev/null; then
  setsid nohup $R/bin/run_mcp.sh >/dev/null 2>&1 < /dev/null &
fi
exit 0
