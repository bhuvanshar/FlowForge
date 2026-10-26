# Troubleshooting

Commands below use `dc` as shorthand for
`docker compose -f infra/docker-compose.yml --env-file infra/.env --profile app` (run from the repository root).

## Start-up

| Symptom | Cause | Fix |
|---|---|---|
| `required variable POSTGRES_PASSWORD is missing` | `infra/.env` missing or incomplete | `cp infra/.env.example infra/.env` and set `POSTGRES_PASSWORD` |
| `backend` never becomes healthy; log shows `password authentication failed` | the volume was initialised with a different password (PostgreSQL only reads `POSTGRES_PASSWORD` on first start) | use the original password, or (losing all data) `dc down -v` and start again |
| `Bind for 127.0.0.1:5173 failed: port is already allocated` | another process (often a local `npm run dev`) uses the port | stop it, or set `FRONTEND_PORT` / `BACKEND_PORT` / `POSTGRES_PORT` in `infra/.env` |
| Backend log: `Validate failed: Migrations have failed validation` / checksum mismatch | a released migration file was edited | restore the original `V*.sql` (see `MigrationChecksumTest`) and put the change in a new `V9+` file |
| Backend log: `Schema-validation: missing column` | entity changed without a migration | add a Flyway migration |
| Image build stalls at `dependency:go-offline` | slow network inside Docker; the first build downloads ~1,700 artifacts | wait (≈2 min on a normal connection); later builds reuse the BuildKit cache. Everything comes from Maven Central only (`backend/.mvn/settings.xml`) |
| Image build seems to hang for hours | the machine slept during the build | rerun the build; keep the machine awake |

## Browser and API

### Vite: `504 (Outdated Optimize Dep)`

This development-server error means a requested optimized dependency is stale or missing. It can occur after
`npm ci` replaces `node_modules` while Vite is running, or when a tab retains an older dependency URL.
Stop the frontend development server with `Ctrl+C`, then restart it from `frontend`:

```bash
npm run dev:fresh
```

The script runs `vite --force` to rebuild optimized dependencies. If your backend uses a custom port, keep the
same proxy target, for example `VITE_PROXY_TARGET=http://127.0.0.1:18080 npm run dev:fresh`.
Hard-refresh the page (`Cmd+Shift+R` on macOS, `Ctrl+Shift+R` on Windows/Linux). If the browser still serves old
modules, enable **Disable cache** in DevTools' Network tab and reload. Stop Vite before future dependency installs;
normal launches can continue to use `npm run dev`.

See [Vite's dependency-cache documentation](https://vite.dev/guide/dep-pre-bundling.html#caching).

### API and browser responses

| Symptom | Cause | Fix |
|---|---|---|
| `403` `host-not-allowed` | request used a host name not in `FLOWFORGE_ALLOWED_HOST_NAMES` (DNS-rebinding guard) | open the UI as `localhost` or `127.0.0.1`; add deliberate names to the variable |
| `403` `cross-origin-request-blocked` | a state-changing request came from another site (Origin / Sec-Fetch-Site) | call the API from the FlowForge UI, curl or scripts (no browser Origin header) |
| CORS error in the browser console when the UI runs on another port | `FLOWFORGE_ALLOWED_ORIGINS` does not list that origin | Compose derives it from `FRONTEND_PORT`; for a custom setup set the exact origin |
| `409` on save | someone (another tab) saved first (`lockVersion` mismatch) | reload the workflow and reapply the change |
| `413` | request or webhook body over 1 MiB / 256 KiB | send less data |
| Swagger UI returns 404 | containers run the `prod` profile, which disables API docs | use the dev setup (`./mvnw spring-boot:run`), or read `docs/api/openapi.json` |
| Run history shows the run but the canvas does not update live | a proxy buffers Server-Sent Events | disable buffering for `/api/v1/executions/*/events` (the bundled nginx does); the UI falls back to polling |

## Executions

| Error code | Meaning | Fix |
|---|---|---|
| `HTTP_HOST_NOT_ALLOWED` | host not in `FLOWFORGE_HTTP_ALLOWED_HOSTS` (empty = no outbound HTTP) | add it, e.g. `api.example.com` or `*.example.com`, and `dc up -d` |
| `HTTP_ADDRESS_BLOCKED` | the host resolves to a private/loopback/link-local address | intended protection; for a local service in development only, `FLOWFORGE_HTTP_ALLOW_PRIVATE_ADDRESSES=true` |
| `HTTP_STATUS_ERROR` | the server answered 4xx/5xx | check the node's error message (status and URL) and the remote service |
| `HTTP_TIMEOUT` / `NODE_TIMEOUT` / `EXECUTION_TIMEOUT` | node timeout (≤ 30 s) or the 10-minute execution limit | raise the node's `timeoutSeconds` (capped by the server), split the work |
| `CREDENTIAL_HOST_NOT_ALLOWED` | the credential's allowed hosts do not include the request's host | edit the credential's allowed hosts |
| `CREDENTIAL_UNAVAILABLE` / credentials page says encryption unavailable | no key configured, wrong key, or data altered | set `FLOWFORGE_CREDENTIALS_KEYS` to the key the data was encrypted with (restore it from backup) |
| `AI_AUTH_FAILED` | provider rejected the key | rotate the credential's secret |
| `AI_RATE_LIMITED` / `AI_PROVIDER_ERROR` | retries (bounded, ≤ 4 requests) did not succeed | retry later; lower the request rate |
| `AI_OUTPUT_INVALID` | the model's JSON did not match the response schema, even after repair | simplify the schema or the prompt; use `jsonMode: jsonObject` when the provider supports it |
| `EXECUTION_INTERRUPTED` | the backend stopped while the run was in progress (runs get 12 s to finish on a graceful stop) | run it again |
| `EXECUTION_REJECTED` / API `503` | the execution queue (100) or the per-workflow limit (10) is full | wait for runs to finish; raise `FLOWFORGE_EXECUTION_MAX_ACTIVE_PER_WORKFLOW` carefully |

## Triggers

| Symptom | Fix |
|---|---|
| Webhook answers `404` | the workflow is not **active**, the id is wrong, or the workflow was archived |
| Webhook answers `401` | missing/wrong signature or token; timestamp outside ±5 minutes (check the caller's clock); the body was changed after signing |
| Webhook answers `409` `webhook-replayed` | the exact signed delivery was already accepted; sign each delivery with a fresh timestamp |
| Schedule does not fire | workflow not active, `FLOWFORGE_SCHEDULER_ENABLED=false`, or the cron/time zone differs from what you expect — the trigger panel shows `nextFireAt` and `lastError` |
| Runs missed while the backend was down | policy `skip` drops them; `runOnce` runs one catch-up (within the 60 s grace) |

## Tests

| Symptom | Fix |
|---|---|
| Testcontainers: `Could not find a valid Docker environment` (macOS) | `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock ./mvnw verify` |
| `OpenApiContractIT` fails | the API changed: `UPDATE_OPENAPI_SNAPSHOT=true ./mvnw verify -Dit.test=OpenApiContractIT`, review the diff of `docs/api/openapi.json`, run the frontend tests (`apiContract.test.ts`) |
| `scripts/e2e.sh`: `browserType.launch: Chromium distribution 'chrome' is not found` | install Google Chrome, or `cd e2e && npx playwright install chromium` and `E2E_BROWSER_CHANNEL=chromium scripts/e2e.sh` |
| E2E ports in use | set `E2E_FRONTEND_PORT`, `E2E_BACKEND_PORT`, `E2E_POSTGRES_PORT`, `E2E_MOCK_PORT` |
| Investigate an E2E failure | `E2E_KEEP=1 scripts/e2e.sh --grep <name>`, open `e2e/playwright-report/index.html`, `npx playwright show-trace e2e/test-results/<test>/trace.zip`, then `docker compose -p flowforge-e2e logs backend` |

## Collecting information for a bug report

```bash
dc ps
dc logs --since 30m backend > backend.log      # contains no secrets: values are redacted
curl -s http://127.0.0.1:8080/api/v1/health
curl -s http://127.0.0.1:8080/api/v1/settings  # effective configuration, no secrets
```
