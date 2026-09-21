#!/usr/bin/env bash
# One-command entry point for the jfr2grafana docker-compose POC:
#   1. build the agent jar with Maven
#   2. bring up sample-app + victoriametrics + grafana
#   3. wait for the app to be healthy and probe the agent's /metrics
#   4. print the URLs to open
#
# The container runtime here is podman driven through the `docker` CLI
# (Rancher Desktop). The podman VM is frequently stopped; we translate the
# resulting "Cannot connect to the Docker daemon" error into an actionable
# "start podman" message instead of letting the raw docker/compose error
# through.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/docker/docker-compose.yml"

APP_HEALTH_URL="http://localhost:8080/health"
AGENT_METRICS_URL="http://localhost:9404/metrics"
GRAFANA_URL="http://localhost:3000"
VM_URL="http://localhost:8428"
APP_URL="http://localhost:8080"

log() { printf '==> %s\n' "$*"; }
err() { printf 'ERROR: %s\n' "$*" >&2; }

cd "$REPO_ROOT"

# --- 1. build the agent jar --------------------------------------------
log "Building jfr2grafana-agent.jar with Maven (agent module)"
if ! mvn -q -B -pl agent -am -DskipTests package; then
  err "Maven build of the 'agent' module failed (see output above)."
  err "The sample-app image will still build and run, but with no metrics,"
  err "because it falls back to running without -javaagent when the jar is missing."
  err "Fix the Maven build and re-run ./scripts/up.sh."
  exit 1
fi

AGENT_JAR="$REPO_ROOT/agent/target/jfr2grafana-agent.jar"
if [ -f "$AGENT_JAR" ]; then
  log "agent jar ready: agent/target/jfr2grafana-agent.jar"
else
  # Should not happen if mvn package succeeded, but don't silently proceed.
  err "mvn package succeeded but $AGENT_JAR is missing."
  exit 1
fi

# --- 2. bring up the stack ----------------------------------------------
log "Checking the container runtime is reachable"
DOCKER_INFO_OUTPUT="$(docker info 2>&1)" || {
  if printf '%s' "$DOCKER_INFO_OUTPUT" | grep -qiE 'cannot connect to the docker daemon|connection refused|is the docker daemon running|no such file or directory'; then
    err "Cannot reach the container runtime."
    err "This machine runs podman under the 'docker' CLI, and the podman VM is"
    err "often stopped. Start it, then re-run ./scripts/up.sh:"
    err "  podman machine start"
    err "  # (or open Rancher Desktop if that's how podman is managed here)"
  else
    err "'docker info' failed:"
    printf '%s\n' "$DOCKER_INFO_OUTPUT" >&2
  fi
  exit 1
}

log "Bringing up the stack (docker compose up -d --build)"
docker compose -f "$COMPOSE_FILE" up -d --build

# --- 3. wait for health ---------------------------------------------------
log "Waiting for sample-app to report healthy"
APP_HEALTHY=0
for _ in $(seq 1 60); do
  CID="$(docker compose -f "$COMPOSE_FILE" ps -q sample-app 2>/dev/null || true)"
  if [ -n "$CID" ]; then
    STATUS="$(docker inspect --format '{{.State.Health.Status}}' "$CID" 2>/dev/null || true)"
    if [ "$STATUS" = "healthy" ]; then
      APP_HEALTHY=1
      break
    fi
  fi
  sleep 2
done

if [ "$APP_HEALTHY" -ne 1 ]; then
  err "sample-app did not become healthy within the timeout."
  err "Current state:"
  docker compose -f "$COMPOSE_FILE" ps >&2 || true
  exit 1
fi
log "sample-app is healthy"

log "Probing agent metrics endpoint ($AGENT_METRICS_URL)"
AGENT_UP=0
for _ in $(seq 1 15); do
  if curl -fsS "$AGENT_METRICS_URL" >/dev/null 2>&1; then
    AGENT_UP=1
    break
  fi
  sleep 1
done

if [ "$AGENT_UP" -eq 1 ]; then
  log "agent /metrics is responding"
else
  # Not fatal: the agent's premain may not exist yet (parallel work in
  # progress on the agent module). The stack still comes up; there's just
  # no metrics until that lands.
  log "agent /metrics did not respond yet - this is expected if the agent's"
  log "premain isn't implemented yet. The app itself is up; metrics will"
  log "appear once the agent module is complete and the image is rebuilt."
fi

# --- 4. print URLs ---------------------------------------------------------
cat <<EOF

jfr2grafana stack is up:
  Grafana         $GRAFANA_URL      (anonymous, lands on the home dashboard)
  VictoriaMetrics $VM_URL
  sample-app      $APP_URL
  agent /metrics  $AGENT_METRICS_URL

Tear it down with: ./scripts/down.sh
EOF
