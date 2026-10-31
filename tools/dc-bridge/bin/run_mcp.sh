#!/usr/bin/env bash
# Desktop Commander (stdio MCP) exposed as Streamable HTTP on 127.0.0.1:8777 ONLY (it is a full box shell: never public).
export PATH=/home/box/.local/bin:/usr/local/bin:/usr/bin:/bin
R=/workspace/dc-bridge
echo $$ > $R/run/mcp_supervisor.pid
while true; do
  echo "$(date -u +%FT%TZ) starting desktop-commander via supergateway" >> $R/logs/mcp.log
  supergateway --stdio "desktop-commander" --outputTransport streamableHttp --host 127.0.0.1 --port 8777 \
    --healthEndpoint /health --logLevel info >> $R/logs/mcp.log 2>&1 || true
  sleep 3
done
