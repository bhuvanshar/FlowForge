#!/usr/bin/env bash
# FlowForge launcher for macOS: starts the full stack in Docker and opens it in Google Chrome.
#
#   ./start-flowforge.sh          # start (builds images the first time) and open the app
#   ./start-flowforge.sh stop     # stop the containers (data is kept)
#   ./start-flowforge.sh status   # show container health
#   ./start-flowforge.sh logs     # follow the backend log
#
# Needs Docker Desktop. On first use it creates infra/.env with a random database password and credential encryption key;
# an existing infra/.env is reused (keep it and back it up: the key decrypts your stored credentials).
set -euo pipefail

cd "$(dirname "$0")"
env_file="infra/.env"
compose=(docker compose -f infra/docker-compose.yml --env-file "$env_file" --profile app)

say() { printf '\033[1m==>\033[0m %s\n' "$*"; }
fail() { printf '\033[31mError:\033[0m %s\n' "$*" >&2; exit 1; }

# Value of KEY in infra/.env (last occurrence wins, like Compose), or the given default.
env_value() {
  local value
  value="$(sed -n "s/^$1=//p" "$env_file" 2>/dev/null | tail -n 1 | tr -d '\r"')"
  printf '%s' "${value:-$2}"
}

ensure_docker() {
  command -v docker >/dev/null 2>&1 || fail "Docker is not installed. Install Docker Desktop: https://www.docker.com/products/docker-desktop/"
  docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is missing (update Docker Desktop)."
  if ! docker info >/dev/null 2>&1; then
    say "Starting Docker Desktop…"
    open -a Docker 2>/dev/null || fail "Could not start Docker Desktop. Start it manually and run this script again."
    local waited=0
    until docker info >/dev/null 2>&1; do
      [ "$waited" -ge 180 ] && fail "Docker did not become ready within 3 minutes."
      sleep 3
      waited=$((waited + 3))
    done
  fi
}

ensure_env() {
  if [ ! -f "$env_file" ]; then
    say "Creating $env_file with a random database password and credential key"
    cp infra/.env.example "$env_file"
    chmod 600 "$env_file"
    sed -i '' "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$(openssl rand -hex 16)|" "$env_file"
    sed -i '' "s|^FLOWFORGE_CREDENTIALS_KEYS=.*|FLOWFORGE_CREDENTIALS_KEYS=k1:$(openssl rand -base64 32)|" "$env_file"
    echo "    Back up $env_file: without its key, stored credentials cannot be decrypted."
  elif [ -z "$(env_value FLOWFORGE_CREDENTIALS_KEYS '')" ]; then
    # No key means credentials were disabled, so nothing is encrypted yet and adding one is safe.
    say "Adding a credential encryption key to $env_file"
    printf '\nFLOWFORGE_CREDENTIALS_KEYS=k1:%s\n' "$(openssl rand -base64 32)" >> "$env_file"
  fi
  [ "$(env_value POSTGRES_PASSWORD '')" = "change-me" ] && echo "    Note: POSTGRES_PASSWORD is still the example value in $env_file."
  return 0
}

# Fails when another program (not FlowForge) already listens on a port FlowForge needs.
check_port() {
  local port="$1" name="$2" variable="$3"
  if [ -z "$("${compose[@]}" ps --status running -q "$name" 2>/dev/null)" ] \
      && lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    fail "Port $port is already in use by another program ($(lsof -nP -iTCP:"$port" -sTCP:LISTEN | awk 'NR==2 {print $1" pid "$2}')).
       Stop it, or choose another port: set $variable=<port> in $env_file."
  fi
}

open_browser() {
  local url="$1"
  if open -Ra "Google Chrome" 2>/dev/null; then
    open -a "Google Chrome" "$url"
  else
    echo "    Google Chrome is not installed; opening your default browser."
    open "$url"
  fi
}

start() {
  ensure_docker
  ensure_env
  local frontend_port backend_port
  frontend_port="$(env_value FRONTEND_PORT 5173)"
  backend_port="$(env_value BACKEND_PORT 8080)"
  check_port "$frontend_port" frontend FRONTEND_PORT
  check_port "$backend_port" backend BACKEND_PORT
  check_port "$(env_value POSTGRES_PORT 5432)" postgres POSTGRES_PORT

  say "Building and starting FlowForge (the first build takes a few minutes)…"
  if ! "${compose[@]}" up -d --build --wait --wait-timeout 600; then
    echo
    "${compose[@]}" ps
    fail "FlowForge did not start. Recent backend log: ./start-flowforge.sh logs   (see docs/TROUBLESHOOTING.md)"
  fi

  local url="http://localhost:$frontend_port"
  say "FlowForge is running: $url   (API: http://localhost:$backend_port/api/v1/health)"
  open_browser "$url"
  echo "    Stop it with: ./start-flowforge.sh stop"
}

case "${1:-start}" in
  start) start ;;
  stop)
    ensure_docker
    say "Stopping FlowForge (your data is kept)…"
    "${compose[@]}" stop
    ;;
  status) ensure_docker; "${compose[@]}" ps ;;
  logs) ensure_docker; "${compose[@]}" logs -f --tail 100 backend ;;
  *) fail "Unknown command '$1'. Use: start | stop | status | logs" ;;
esac
