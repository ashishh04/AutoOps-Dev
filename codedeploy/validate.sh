#!/bin/bash
# Waits for the gateway (8080) and frontend (5173) to answer HTTP.
# Any HTTP response counts: it proves the process is up and listening.
set -uo pipefail
for port in 8080 5173; do
  for i in $(seq 1 60); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$port/" || true)
    if [ "$code" != "000" ]; then
      echo "validate: port $port answered HTTP $code"
      continue 2
    fi
    sleep 10
  done
  echo "validate: port $port did not answer within 10 minutes"
  docker compose -f /opt/autoops/docker-compose.yml ps || true
  exit 1
done
echo "validate: all good"
