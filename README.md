# FlowForge

Self-hosted, n8n-like workflow automation. Single-user, localhost-only MVP.

**Start here:** [Step-by-step run guide](RUN_GUIDE.md) — Docker setup, local development, your first workflow,
restarting, and troubleshooting.

| Path | Contents |
|---|---|
| `backend/` | Java 21 + Spring Boot 3.5 (Maven), PostgreSQL, Flyway |
| `frontend/` | React + TypeScript + Vite, Tailwind CSS v4, shadcn/ui-style components, Zustand, TanStack Query |
| `infra/` | Docker Compose, env template, E2E overlay |
| `e2e/` | Playwright end-to-end suite and mock upstream services |
| `scripts/` | `e2e.sh` (builds the images and runs the end-to-end suite) |
| `docs/` | Architecture, API contracts (+ `api/openapi.json`), workflow schema, deployment, troubleshooting, production readiness, test report, module status |

> **Status:** all 15 modules are implemented and tested together (see [docs/TEST_REPORT.md](docs/TEST_REPORT.md)). FlowForge is a
> single-user tool for one machine and is **not production-ready**: there is no authentication. Read
> [docs/PRODUCTION_READINESS.md](docs/PRODUCTION_READINESS.md) before exposing it to anyone.

## Prerequisites

Java 21+ (JDK 21 or newer; the build targets release 21), Maven 3.9+ (or use `./mvnw`), Node 22+ and npm, Docker with Compose v2.

## Quickest start (macOS): one command

```bash
./start-flowforge.sh
```

Starts Docker Desktop if needed, creates `infra/.env` with a random password and credential key on first use (an existing one is
reused), builds and starts the stack, waits until it is healthy and opens <http://localhost:5173> in Google Chrome.
`./start-flowforge.sh stop` stops it (data is kept); `status` and `logs` show health and the backend log.

## Option A: full stack in Docker

```bash
cp infra/.env.example infra/.env
# edit infra/.env: set POSTGRES_PASSWORD, and add a credential key (needed for Credentials):
echo "FLOWFORGE_CREDENTIALS_KEYS=k1:$(openssl rand -base64 32)" >> infra/.env
docker compose -f infra/docker-compose.yml --env-file infra/.env --profile app up -d --build --wait
```

Open <http://127.0.0.1:5173>. Backend: <http://127.0.0.1:8080/api/v1/health>. All ports bind to `127.0.0.1` only. The first
build downloads dependencies and takes a few minutes.

Stop: `docker compose -f infra/docker-compose.yml --env-file infra/.env --profile app down` (data persists in the
`flowforge_flowforge-pgdata` volume; add `-v` to delete it). Backups, upgrades, key rotation and health checks:
[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md). Problems: [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

## Option B: local development (database in Docker)

```bash
cp infra/.env.example infra/.env        # set POSTGRES_PASSWORD
cd infra && docker compose --env-file .env up -d postgres && cd ..

# Backend (terminal 1). DB_PASSWORD must match POSTGRES_PASSWORD.
cd backend
DB_PASSWORD=<your POSTGRES_PASSWORD> ./mvnw spring-boot:run

# Frontend (terminal 2)
cd frontend
npm install
npm run dev
```

Frontend: <http://127.0.0.1:5173> (the Vite dev server proxies `/api` to `http://127.0.0.1:8080`).

## Managing workflows

The dashboard supports server-side search, status filters, sorting, pagination, and grid/list views.
Search and view settings are stored in the URL, so they survive a refresh and can be bookmarked.
Use each workflow's actions to run its saved version, view execution history, duplicate it, or archive it.
Archiving preserves the workflow and history while stopping automatic triggers. Restore it from the
**Archived** tab as a draft, then review and activate it when ready.

## The dashboard

Open <http://127.0.0.1:5173> for the dashboard: every workflow with its status (Active / Draft / Inactive / Archived), the result of
its last run, search, status filters, sorting (name, created, updated), grid or list view and paging. **New workflow** (or press `n`)
creates one; the menu on each card renames, duplicates, activates or deactivates, archives and, for archived workflows, restores or
permanently deletes it; **Run** starts it and the clock icon opens its execution history. Press `/` to search. **Credentials** and
**Settings** are in the sidebar: Settings holds this browser's dashboard defaults, the server's effective configuration (read-only,
changed with the environment variables shown there) and the keyboard shortcuts.

## Using the editor

Open <http://127.0.0.1:5173>, click **New workflow**, name it, then drag nodes from the palette (or double-click them),
connect output handle to input handle, and save with **Save** or Ctrl/Cmd+S. Shortcuts: Ctrl/Cmd+Z undo,
Ctrl/Cmd+Shift+Z or Ctrl+Y redo, Delete/Backspace remove selection, Shift+1 fit view.
Search the node palette by name or description. On smaller screens, use **Nodes** and **Properties**
to open the editor panels; close a panel to return to the canvas.

## Credentials

Store API tokens and passwords under **Credentials** (sidebar) instead of typing them into node headers. Secrets are encrypted
with AES-256-GCM, can be rotated or replaced but never read back, and are only sent to the hosts you list. In an HTTP Request
node choose the credential under **Authentication**; the workflow JSON contains only its id, and run history and logs show
`[REDACTED]` wherever the secret would appear.

Credentials need an encryption key that is kept outside the database and the repository (`infra/.env` is git-ignored):

```bash
echo "FLOWFORGE_CREDENTIALS_KEYS=k1:$(openssl rand -base64 32)" >> infra/.env
```

For local development (Option B) export the same variable before `./mvnw spring-boot:run`. Keep a backup of the key: without it
stored secrets cannot be decrypted. The same key also encrypts webhook secrets; webhook secrets created before a key was
configured are encrypted automatically at the next start. Key rotation: append `,k2:<new key>`, restart,
`POST /api/v1/credentials/re-encrypt`, then remove `k1`.

**Trust boundary.** FlowForge has no login: it is a single-user tool for this machine. Both services listen on 127.0.0.1
only; the API refuses requests for other host names (DNS rebinding) and state-changing requests from other web sites (CSRF).
Do not publish the ports to a network. See `docs/ARCHITECTURE.md` (Trust boundary).

## AI Model node

Drop an **AI Model** node into a workflow, choose a model and write the prompts (they may use `{{ input.x }}`, `{{ vars.x }}`,
`{{ trigger.body.x }}`, `{{ nodes.<id>.output.x }}`). Set **Response format** to JSON and add a response schema to get a validated
`{{ input.json.<field> }}` for the next nodes (retries for rate limits, provider errors and unusable JSON are bounded). The API
key comes from a stored credential (**Credentials** page, type "Bearer token"); it is never part of the workflow.

1. Store the key: Credentials → New credential → Bearer token, allowed host e.g. `api.openai.com`.
2. Allow the host for outbound calls: `FLOWFORGE_HTTP_ALLOWED_HOSTS=api.openai.com` in `infra/.env` (the same allowlist as the HTTP
   Request node; a local model such as Ollama at `http://localhost:11434/v1` also needs `localhost` and
   `FLOWFORGE_HTTP_ALLOW_PRIVATE_ADDRESSES=true`).
3. Try it: on the Workflows page click **Try the AI sample** (Manual Trigger → Set Variables → AI Model → Condition → Logger),
   choose your credential on the AI Model node, save and **Run**. Or `POST /api/v1/samples/ai-review-triage/create`.

Automated tests never call a real provider. An optional live test runs when you set a key
(`FLOWFORGE_LIVE_AI_API_KEY`, optionally `FLOWFORGE_LIVE_AI_BASE_URL`, `FLOWFORGE_LIVE_AI_MODEL`, `FLOWFORGE_LIVE_AI_HOST`):

```bash
cd backend && FLOWFORGE_LIVE_AI_API_KEY=sk-... ./mvnw verify -Dit.test=AiLiveIT
```

## Webhook and schedule triggers

Use a **Webhook Trigger** or **Schedule Trigger** node instead of the Manual Trigger, save, then click **Activate**. Selecting the
trigger node shows its state: for webhooks the URL (`http://127.0.0.1:5173/api/v1/hooks/<id>`), the secret (reveal, copy,
rotate) and an example request; for schedules the next and last run. **Deactivate** stops them. A signed delivery:

```bash
BODY='{"email":"a@example.com"}'; TS=$(date +%s)
SIG=$(printf '%s.%s' "$TS" "$BODY" | openssl dgst -sha256 -hmac "$SECRET" | sed 's/^.* //')
curl -X POST "$URL" -H "X-FlowForge-Timestamp: $TS" -H "X-FlowForge-Signature: sha256=$SIG" \
  -H 'Content-Type: application/json' -d "$BODY"
```

The backend and the bundled frontend bind to 127.0.0.1, so webhooks are reachable from this machine only unless you expose
them deliberately (for example through a tunnel). Details: `docs/API_CONTRACTS.md` (Triggers).

## Try the workflow API

Interactive docs (dev profile only, i.e. Option B): <http://127.0.0.1:8080/swagger-ui.html>; the reviewed contract is
`docs/api/openapi.json`. With curl:

```bash
curl -s -X POST http://127.0.0.1:8080/api/v1/workflows -H 'Content-Type: application/json' -d '{"name":"My workflow"}'
curl -s "http://127.0.0.1:8080/api/v1/workflows?sort=name,asc&size=10"
curl -s -X POST http://127.0.0.1:8080/api/v1/workflows/<id>/duplicate
curl -s -X DELETE http://127.0.0.1:8080/api/v1/workflows/<id>                  # archive
curl -s -X DELETE "http://127.0.0.1:8080/api/v1/workflows/<id>?permanent=true"  # hard delete (archived only)
```

In the editor, **Run** executes the saved workflow; the canvas and the bottom panel update live (Server-Sent Events, with automatic
reconnect and a polling fallback) and show each node's input and output; **History** (toolbar, or
`/workflows/<id>/executions`) lists past runs. Via the API:

```bash
curl -s -X POST http://127.0.0.1:8080/api/v1/workflows/<id>/execute -H 'Content-Type: application/json' -d '{"input":{"order":7}}'
curl -s http://127.0.0.1:8080/api/v1/executions/<executionId>
curl -s "http://127.0.0.1:8080/api/v1/workflows/<id>/executions?status=FAILED&size=10"
curl -s http://127.0.0.1:8080/api/v1/executions/<executionId>/nodes
curl -N http://127.0.0.1:8080/api/v1/executions/<executionId>/events   # live event stream
curl -s -X POST http://127.0.0.1:8080/api/v1/executions/<executionId>/cancel
```

Updates (`PUT`) must include the `lockVersion` from the last read; see `docs/API_CONTRACTS.md`.

## Tests and builds

```bash
cd backend && ./mvnw verify      # unit tests + Testcontainers integration tests (needs Docker)
cd frontend && npm test          # Vitest (includes the API contract test against docs/api/openapi.json)
cd frontend && npm run typecheck && npm run build && npm run lint
scripts/e2e.sh                   # end-to-end: real images + mock services + Playwright (needs Docker and Chrome)
```

`OpenApiContractIT` fails when the backend API no longer matches `docs/api/openapi.json`; after an intended change regenerate it
with `UPDATE_OPENAPI_SNAPSHOT=true ./mvnw verify -Dit.test=OpenApiContractIT`, review the diff, and run the frontend tests.
Latest results: [docs/TEST_REPORT.md](docs/TEST_REPORT.md).

If Testcontainers cannot find Docker (Docker Desktop on macOS), run with
`DOCKER_HOST=unix://$HOME/.docker/run/docker.sock ./mvnw verify`.

## Configuration

The HTTP Request node may only call hosts listed in `FLOWFORGE_HTTP_ALLOWED_HOSTS` (e.g. `api.example.com,*.example.org`);
empty disables outbound HTTP. Private/loopback/metadata addresses stay blocked unless `FLOWFORGE_HTTP_ALLOW_PRIVATE_ADDRESSES=true`
(development only). Set them in `infra/.env` or the environment.

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/flowforge` / `flowforge` / `flowforge` | Backend datasource |
| `SERVER_ADDRESS` | `127.0.0.1` | Backend bind address (containers set `0.0.0.0`) |
| `SERVER_PORT` | `8080` | Backend port |
| `FLOWFORGE_ALLOWED_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` | CORS origins |
| `SPRING_PROFILES_ACTIVE` | `dev` | `dev`, `test`, `prod` |
| `FLOWFORGE_HTTP_ALLOWED_HOSTS` | empty (deny all) | Outbound HTTP allowlist |
| `FLOWFORGE_HTTP_ALLOW_PRIVATE_ADDRESSES` | `false` | Allow non-public target addresses (dev only) |
| `FLOWFORGE_CREDENTIALS_KEYS` | empty (credentials disabled) | Credential encryption keyring `keyId:base64Key[,…]` (AES-256) |
| `FLOWFORGE_CREDENTIALS_KEY_FILE` | empty | File with one `keyId:base64Key` per line (alternative to the variable) |
| `FLOWFORGE_CREDENTIALS_ACTIVE_KEY_ID` | last key listed | Key used for new encryptions |
| `FLOWFORGE_ALLOWED_HOST_NAMES` | `localhost,127.0.0.1,[::1]` | Host names the server answers to (DNS rebinding protection) |
| `FLOWFORGE_EXECUTION_WORKERS` | `2` | Executions running in parallel |
| `FLOWFORGE_EXECUTION_MAX_ACTIVE_PER_WORKFLOW` | `10` | Queued + running executions per workflow |
| `FLOWFORGE_HTTP_ALLOWED_PORTS` | empty (any port) | Outbound port allowlist, e.g. `443` |
| `FLOWFORGE_WEBHOOKS_ALLOW_UNAUTHENTICATED` | `false` | Allow webhook triggers with authentication `none` |
| `FLOWFORGE_SCHEDULER_ENABLED` | `true` | Run schedule triggers in this backend (exactly one instance per database) |
| `VITE_API_BASE_URL` | `/api/v1` | Frontend API base URL |

See `docs/` for architecture and contracts.
