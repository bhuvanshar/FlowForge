# Deployment (local, single user)

FlowForge is a **single-user, localhost** application. This guide covers running it on one machine with Docker Compose,
building the artifacts, upgrading, backups and health checks. It is not a production hardening guide: read
[PRODUCTION_READINESS.md](PRODUCTION_READINESS.md) before exposing FlowForge to anyone but yourself.

## 1. Requirements

| Tool | Version | Used for |
|---|---|---|
| Docker Engine / Docker Desktop | 24+ with Compose v2 and BuildKit | the stack, Testcontainers, E2E |
| Java (JDK) | 21+ (the build targets release 21) | building and testing the backend outside Docker |
| Node.js + npm | 22+ | building and testing the frontend, E2E suite |
| Google Chrome | current | E2E browser scenario (or `npx playwright install chromium`) |
| `openssl` | any | generating secrets |

Resources: about 1.5 GB RAM for the three containers (the JVM uses at most 75 % of its container memory), 2 GB disk for images.

## 2. First start (full stack in Docker)

```bash
cp infra/.env.example infra/.env
# 1. set a database password
sed -i.bak "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=$(openssl rand -hex 16)/" infra/.env && rm infra/.env.bak
# 2. credential encryption key (keep a copy somewhere safe: without it stored credentials are unreadable)
sed -i.bak "s|^FLOWFORGE_CREDENTIALS_KEYS=.*|FLOWFORGE_CREDENTIALS_KEYS=k1:$(openssl rand -base64 32)|" infra/.env && rm infra/.env.bak
# 3. optional: hosts the HTTP Request / AI Model nodes may call
#    FLOWFORGE_HTTP_ALLOWED_HOSTS=api.example.com,api.openai.com
docker compose -f infra/docker-compose.yml --env-file infra/.env --profile app up -d --build --wait
```

Then open <http://127.0.0.1:5173> (UI) — the API is at <http://127.0.0.1:8080/api/v1/health>. All ports bind to `127.0.0.1`.

What happens on start:
1. `postgres` starts and becomes healthy (`pg_isready`).
2. `backend` starts, Flyway applies pending migrations (V1…V8), encrypts any legacy plaintext webhook secrets, recovers
   executions left `QUEUED`/`RUNNING` by a crash (`EXECUTION_INTERRUPTED`) and starts the scheduler. It is healthy when
   `/actuator/health/readiness` answers `UP`.
3. `frontend` (nginx) serves the built UI and proxies `/api` to the backend, with buffering disabled for the event stream.

Stop: `docker compose -f infra/docker-compose.yml --env-file infra/.env --profile app down` — data stays in the
`flowforge_flowforge-pgdata` volume. `down -v` **deletes all data**.

### Development mode (database in Docker, apps on the host)

```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env up -d --wait postgres
cd backend && DB_PASSWORD=<POSTGRES_PASSWORD> FLOWFORGE_CREDENTIALS_KEYS=<same as infra/.env> ./mvnw spring-boot:run
cd frontend && npm ci && npm run dev    # http://127.0.0.1:5173, proxies /api to 127.0.0.1:8080
```

The `dev` profile (default outside Docker) also serves Swagger UI at <http://127.0.0.1:8080/swagger-ui.html>; the `prod`
profile used by the containers does not.

## 3. Configuration

All settings are environment variables (see the table in the [README](../README.md#configuration) and `infra/.env.example`).
The Settings page in the UI shows the effective values. The ones that matter most:

| Variable | Default | Notes |
|---|---|---|
| `POSTGRES_PASSWORD` | — (required) | used by both postgres and backend |
| `FLOWFORGE_CREDENTIALS_KEYS` | empty: credentials disabled | `keyId:base64(32 bytes)[,…]`; the last key encrypts. Alternatively `FLOWFORGE_CREDENTIALS_KEY_FILE` |
| `FLOWFORGE_HTTP_ALLOWED_HOSTS` | empty: no outbound HTTP | exact host or `*.domain`; applies to HTTP Request and AI Model nodes |
| `FLOWFORGE_HTTP_ALLOW_PRIVATE_ADDRESSES` | `false` | keep `false` outside development (blocks loopback, private, link-local/metadata) |
| `FLOWFORGE_ALLOWED_HOST_NAMES` | `localhost,127.0.0.1,[::1]` | Host header allowlist (DNS rebinding protection) |
| `FLOWFORGE_SCHEDULER_ENABLED` | `true` | exactly one backend per database may run the scheduler |
| `FRONTEND_PORT` / `BACKEND_PORT` / `POSTGRES_PORT` | 5173 / 8080 / 5432 | host ports on 127.0.0.1; `FLOWFORGE_ALLOWED_ORIGINS` follows `FRONTEND_PORT` |

Changing a variable needs `up -d` again (Compose recreates the affected container).

## 4. Building the artifacts

| Artifact | Command | Output |
|---|---|---|
| Backend jar | `cd backend && ./mvnw -B package` (add `-DskipTests` to skip tests) | `backend/target/flowforge-backend-<version>.jar` |
| Frontend bundle | `cd frontend && npm ci && npm run build` | `frontend/dist/` |
| Container images | `docker compose -f infra/docker-compose.yml --env-file infra/.env --profile app build` | `flowforge-backend`, `flowforge-frontend` |

Reproducibility: base images are pinned by digest (`backend/Dockerfile`, `frontend/Dockerfile`, compose), npm uses
`package-lock.json` with `npm ci`, Maven uses the wrapper (`.mvn/wrapper`) and a fixed `project.build.outputTimestamp`, and the
Docker builds ignore host build output (`.dockerignore`). Maven dependencies are cached between image builds (BuildKit cache
mount), not baked into a layer.

## 5. Health and monitoring

| Endpoint | Meaning |
|---|---|
| `GET /api/v1/health` | application + database status, version (`503` with the same body when the database is down) |
| `GET /actuator/health/liveness` | the JVM is alive (container restart signal) |
| `GET /actuator/health/readiness` | ready for traffic (used by the compose healthcheck) |

```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env --profile app ps      # health of every service
docker compose -f infra/docker-compose.yml --env-file infra/.env --profile app logs -f backend
```

Container logs are rotated (json-file, 5 × 10 MB per service). Executions, node results and execution logs are in the
database and visible in the UI (History). There are no metrics exporters or alerting in this MVP.

## 6. Backups and restore

Two things must be backed up **together**: the database and the credential key(s) in `infra/.env` (or the key file). A
database backup without the key cannot decrypt credentials or webhook secrets; the key alone is useless without the data.

```bash
# Backup (consistent snapshot while running)
docker compose -f infra/docker-compose.yml --env-file infra/.env exec -T postgres \
  pg_dump -U flowforge -d flowforge --format=custom > flowforge-$(date +%Y%m%d-%H%M).dump
cp infra/.env flowforge-env-$(date +%Y%m%d).backup      # store it separately and securely

# Restore into an empty database (stop the backend first so nothing writes)
docker compose -f infra/docker-compose.yml --env-file infra/.env --profile app stop backend
docker compose -f infra/docker-compose.yml --env-file infra/.env exec -T postgres \
  pg_restore -U flowforge -d flowforge --clean --if-exists < flowforge-YYYYMMDD-HHMM.dump
docker compose -f infra/docker-compose.yml --env-file infra/.env --profile app start backend
```

Test a restore occasionally (e.g. into a second compose project: `-p flowforge-restore` with other ports).

## 7. Upgrading

1. Back up (section 6).
2. Get the new source, then rebuild and restart: `docker compose ... --profile app up -d --build --wait`.
3. Flyway applies new migrations at backend start. Released migrations are never edited (checked by
   `MigrationChecksumTest`); a failed migration stops the backend and is reported in its log — restore the backup to roll back.

Runs that are executing during a restart get up to 12 s to finish; longer ones are recorded as `FAILED` with
`EXECUTION_INTERRUPTED` (rerun them after the upgrade);
schedules resume with their missed-run policy (`skip` or `runOnce`).

## 8. Key rotation

1. Append a new key: `FLOWFORGE_CREDENTIALS_KEYS=k1:<old>,k2:<new>` and `up -d` (k2 becomes active).
2. `curl -X POST http://127.0.0.1:8080/api/v1/credentials/re-encrypt` — re-encrypts credentials and webhook secrets.
3. Check `GET /api/v1/credentials/status` shows `credentialsOnOldKeys: 0`, then remove `k1` and `up -d` again.

## 9. Running the test suites

```bash
cd backend && DOCKER_HOST=unix://$HOME/.docker/run/docker.sock ./mvnw -B verify   # unit + integration (Testcontainers)
cd frontend && npm ci && npm run typecheck && npm run lint && npm test && npm run build
scripts/e2e.sh                                                                  # end-to-end on the real images
```

`scripts/e2e.sh` builds the images and starts them as a separate Compose project (`flowforge-e2e`, ports 25173/28080/25432/28090)
with a mock upstream service, runs the Playwright suite in `e2e/`, and removes the project and its volume afterwards
(`E2E_KEEP=1` keeps it running). Results: `e2e/report.json`, HTML report in `e2e/playwright-report/`. See
[TEST_REPORT.md](TEST_REPORT.md) for the latest results.

If `e2e/.e2e.env` already exists (for example after `E2E_KEEP=1`), the script refuses to replace it. This preserves
the password and encryption key used by the retained database. To start fresh, use the teardown command printed
by the script, then remove the environment file only after teardown succeeds. This discards the isolated test
stack and its data. Failed automatic teardown also preserves the file for recovery.
