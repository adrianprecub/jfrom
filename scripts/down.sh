#!/usr/bin/env bash
# Tears down the jfrom docker-compose stack started by up.sh.
#
# Usage:
#   ./scripts/down.sh            # stop and remove containers/network
#   ./scripts/down.sh --volumes  # also drop named volumes (grafana-data)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/docker/docker-compose.yml"

log() { printf '==> %s\n' "$*"; }
err() { printf 'ERROR: %s\n' "$*" >&2; }

DROP_VOLUMES=0
for arg in "$@"; do
  case "$arg" in
    --volumes|-v)
      DROP_VOLUMES=1
      ;;
    *)
      err "Unknown argument: $arg (expected --volumes)"
      exit 1
      ;;
  esac
done

DOCKER_INFO_OUTPUT="$(docker info 2>&1)" || {
  if printf '%s' "$DOCKER_INFO_OUTPUT" | grep -qiE 'cannot connect to the docker daemon|connection refused|is the docker daemon running|no such file or directory'; then
    err "Cannot reach the container runtime."
    err "This machine runs podman under the 'docker' CLI. Start it with:"
    err "  podman machine start"
    exit 1
  else
    err "'docker info' failed:"
    printf '%s\n' "$DOCKER_INFO_OUTPUT" >&2
    exit 1
  fi
}

if [ "$DROP_VOLUMES" -eq 1 ]; then
  log "Bringing the stack down and dropping named volumes"
  docker compose -f "$COMPOSE_FILE" down --volumes
else
  log "Bringing the stack down (volumes preserved; use --volumes to drop them)"
  docker compose -f "$COMPOSE_FILE" down
fi

log "Stack is down"
