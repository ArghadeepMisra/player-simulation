#!/usr/bin/env bash
# One full measured run, start to finish (spec section 14, "Running one measured run"):
# fresh stack, 5-minute simulation, one detector killed about 90 s in, then both verifier modes.
#
#   scripts/measured-run.sh [label]
#
# The label (default: the processing guarantee) names the output files in reports/. Set PROCESSING_GUARANTEE in .env
# first: exactly_once_v2 (default) or at_least_once for the before-and-after demo. Needs Docker and a .env file.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows would otherwise rewrite /opt/kafka/... paths

cd "$(dirname "$0")/.."
guarantee=$(grep -E '^PROCESSING_GUARANTEE=' .env | cut -d= -f2 || true)
label="${1:-${guarantee:-exactly_once_v2}}"
mkdir -p reports

# The profiles are named so the simulator and verifier containers of an earlier run are removed too.
docker compose --profile sim --profile tools down -v
docker compose up -d --build

echo "Waiting for the 3 detectors to join the group"
until [ "$(docker compose logs telemetry-detector 2>/dev/null | grep -c 'State transition from REBALANCING to RUNNING')" -ge 3 ]; do sleep 3; done

docker compose --profile sim up -d --build telemetry-simulator
sim=$(docker compose ps -q telemetry-simulator)

echo "Simulation running; killing one detector after about 90 s"
until [ "$(docker logs "$sim" 2>&1 | grep -c 'events/s')" -ge 8 ]; do sleep 3; done
bash scripts/chaos-kill.sh | tee "reports/chaos-$label.txt"

until docker logs "$sim" 2>&1 | grep -q 'SIMULATION COMPLETE'; do sleep 5; done
docker logs "$sim" 2>&1 | grep -o '"message":"SIMULATION COMPLETE[^"]*"' | tee -a "reports/chaos-$label.txt"

echo "Waiting for the detectors to drain their lag"
for _ in $(seq 1 60); do
  lag=$(docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
        --describe --group telemetry-detector 2>/dev/null | awk '$2=="match-events"{s+=$6} END{print s+0}')
  [ "$lag" -le 0 ] && break
  sleep 5
done

set +e
docker compose --profile tools run --rm telemetry-verifier --mode=detection --min-recall=0.98 --max-fpr=0.01
echo "detection exit code: $?" | tee -a "reports/chaos-$label.txt"
docker compose --profile tools run --rm telemetry-verifier --mode=integrity --settle=60s
echo "integrity exit code: $?" | tee -a "reports/chaos-$label.txt"
