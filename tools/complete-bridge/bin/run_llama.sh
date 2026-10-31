#!/usr/bin/env bash
# Supervised llama-server (CPU) for sentence completion. Localhost only.
D=/workspace/broker-tools/llama-bin/llama-b12017
M=/workspace/models/qwen2.5-0.5b-instruct-q4_k_m.gguf
R=/workspace/complete-bridge
echo $$ > $R/run/llama_supervisor.pid
while true; do
  echo "$(date -u +%FT%TZ) starting llama-server" >> $R/logs/llama.log
  LD_LIBRARY_PATH=$D "$D/llama-server" -m "$M" --host 127.0.0.1 --port 8792 -c 2048 -t 4 -np 1 \
     --no-webui --alias qwen2.5-0.5b-instruct-q4_k_m >> $R/logs/llama.log 2>&1 || true
  sleep 3
done
