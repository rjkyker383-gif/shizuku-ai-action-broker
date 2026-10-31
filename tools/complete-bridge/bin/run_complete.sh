#!/usr/bin/env bash
R=/workspace/complete-bridge
echo $$ > $R/run/complete_supervisor.pid
while true; do
  echo "$(date -u +%FT%TZ) starting complete_server" >> $R/logs/complete.log
  python3 $R/complete_server.py >> $R/logs/complete.log 2>&1 || true
  sleep 3
done
