#!/usr/bin/env bash
# Runs the end-to-end suite against the real Docker images.
#
#   scripts/e2e.sh                 # build, start, test, tear down (volumes included)
#   E2E_KEEP=1 scripts/e2e.sh      # leave the stack running afterwards (UI at http://127.0.0.1:25173)
#   scripts/e2e.sh --grep webhook  # extra arguments go to "playwright test"
#
# The stack runs as the separate Compose project "flowforge-e2e" on its own ports and volume, so it never touches the
# normal "flowforge" project or its data. A fresh database password and credential key are generated for every run and
# kept only in e2e/.e2e.env (git-ignored). Needs Docker, Node 22+ and Google Chrome (or E2E_BROWSER_CHANNEL=chromium
# after "npx playwright install chromium").
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
env_file="$root/e2e/.e2e.env"
compose=(docker compose -p flowforge-e2e -f "$root/infra/docker-compose.yml" -f "$root/infra/docker-compose.e2e.yml"
  --env-file "$env_file" --profile app)

# A kept stack still uses these credentials. Overwriting them would make its database inaccessible and lose
# the key needed to decrypt its credentials. Require explicit teardown before generating a fresh environment.
if [[ -e "$env_file" ]]; then
  echo "An existing E2E environment was found; its database password and encryption key were preserved." >&2
  echo "To discard the isolated test stack, run:" >&2
  printf '  %q' "${compose[@]}" down -v --remove-orphans >&2
  printf '\n  rm -- %q\n' "$env_file" >&2
  exit 1
fi

frontend_port="${E2E_FRONTEND_PORT:-25173}"
cat > "$env_file" <<EOF
POSTGRES_PASSWORD=$(openssl rand -hex 16)
POSTGRES_PORT=${E2E_POSTGRES_PORT:-25432}
BACKEND_PORT=${E2E_BACKEND_PORT:-28080}
FRONTEND_PORT=${frontend_port}
MOCK_PORT=${E2E_MOCK_PORT:-28090}
SPRING_PROFILES_ACTIVE=prod
FLOWFORGE_CREDENTIALS_KEYS=e2e:$(openssl rand -base64 32)
EOF
chmod 600 "$env_file"

cleanup() {
  if [[ "${E2E_KEEP:-0}" != "1" ]]; then
    if "${compose[@]}" down -v --remove-orphans; then
      rm -f "$env_file"
    else
      echo "E2E teardown failed; preserving $env_file so the stack remains recoverable." >&2
      return 1
    fi
  else
    echo "Stack left running: ${compose[*]} down -v"
  fi
}
trap cleanup EXIT

echo "==> Building and starting the flowforge-e2e stack"
"${compose[@]}" up -d --build --wait --wait-timeout 300

echo "==> Running Playwright"
cd "$root/e2e"
[[ -d node_modules ]] || npm ci --no-audit --no-fund
E2E_BASE_URL="http://127.0.0.1:${frontend_port}" \
E2E_MOCK_URL="http://127.0.0.1:${E2E_MOCK_PORT:-28090}" \
E2E_COMPOSE="${compose[*]}" \
  npx playwright test "$@"
