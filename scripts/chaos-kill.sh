#!/usr/bin/env bash
# Run this about 90 s into a run. It kills one detector replica, waits until both survivors log Kafka Streams'
# own "State transition from REBALANCING to RUNNING" line, and prints the elapsed time.
# Leave the killed replica down until the run ends: the point is to prove that 2 survivors lose nothing.
# Bring it back afterwards with: docker compose up -d
set -euo pipefail
# Git Bash on Windows rewrites paths such as /opt/kafka/... into C:/Program Files/Git/opt/...; this stops that.
export MSYS_NO_PATHCONV=1

victim=$(docker compose ps -q telemetry-detector | head -n 1)
if [ -z "$victim" ]; then
  echo "No running telemetry-detector replica found" >&2
  exit 1
fi
# The trailing Z makes docker read the time as UTC, not as the machine's local time.
since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
start_ms=$(date +%s%3N)
echo "Killing ${victim:0:12} at $(date -u +%H:%M:%S.%3N)"
docker kill "$victim" > /dev/null

while true; do
  running=$(docker compose logs --since "$since" telemetry-detector 2>/dev/null \
            | grep -c "State transition from REBALANCING to RUNNING" || true)
  if [ "$running" -ge 2 ]; then break; fi
  if [ $(( $(date +%s%3N) - start_ms )) -gt 60000 ]; then echo "Timed out after 60 s"; exit 1; fi
  sleep 0.2
done

echo "Failover complete in $(( $(date +%s%3N) - start_ms )) ms"
# The group must show all 6 match-events partitions with a consumer assigned.
docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group telemetry-detector
