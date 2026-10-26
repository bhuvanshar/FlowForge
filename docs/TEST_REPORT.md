# Test report — Module 15 (final integration)

Date: 2026-10-26. Everything below was executed on one machine against the files as delivered; nothing is extrapolated.

**Environment:** macOS 26.6.2, Docker 29.9.2 (Desktop, 18 CPUs, 8 GB VM), Maven 3.9.16 with JDK 22.0.2 on the host
(build targets release 21; images use Temurin 21), Node 26.0.0, Playwright 1.64.0 with Google Chrome 155, PostgreSQL 16
(Testcontainers and Compose).

## Post-completion follow-up

The subsequent [review and fixes](POST_COMPLETION_REVIEW_2026-10-26.md) passed **568 frontend tests,
387 backend unit tests, and 132 backend integration tests**, with one live-AI test skipped. Typecheck, lint,
and production builds passed. Eleven Docker API E2E scenarios passed; the editor save/reload/run flow was
verified manually in the browser. The linked report explains the two excluded automated browser scenarios
and the checks not repeated. The original Module 15 results below remain historical evidence.

## Original Module 15 summary

| Suite | Command | Passed | Failed | Skipped / not executed |
|---|---|---:|---:|---:|
| Backend unit (JUnit 5, Mockito) | `cd backend && ./mvnw -B verify` | 384 | 0 | 0 |
| Backend integration (Testcontainers PostgreSQL) | same | 131 | 0 | 1 (`AiLiveIT`) |
| Frontend (Vitest, Testing Library) | `cd frontend && npm test` | 552 (45 files) | 0 | 0 |
| Frontend static checks | `npm run typecheck`, `npm run lint`, `npm run build` | clean (0 lint warnings) | — | — |
| End-to-end (Playwright, real Docker images) | `scripts/e2e.sh` | 13 | 0 | 0 (0 flaky, no retries configured) |
| Backup / restore procedure (DEPLOYMENT.md §6) | executed manually on a throwaway project | 1 | 0 | — |
| Reproducible builds | two clean builds compared | jar and `dist/` byte-identical | — | — |

**Totals: 1,080 automated tests passed (384 + 131 + 552 + 13), 0 failed, 1 skipped; plus 1 manual procedure check passed.** The E2E suite was run twice from a clean state (both 13/13); the
second run used the final versions of every file.

## End-to-end scenarios (requested list → test)

All run through nginx (`http://127.0.0.1:25173`) against the production images (`prod` profile), PostgreSQL 16 and a mock
upstream container that is the only allowlisted outbound host.

| Requested scenario | Test (`e2e/tests/…`) | Result | What it proves |
|---|---|---|---|
| Create → Save → Reload → Execute | `editor-ui.spec.ts` › create → save → reload → execute | passed (1.6 s) | In Chrome: dashboard dialog creates the workflow, palette adds Manual Trigger + Logger, handles are connected by drag, the Logger is configured in the properties panel, Save → "All changes saved", reload restores name/nodes/edge/config from the server, Run shows live status on the canvas and the Logger's message in the execution panel; the API agrees |
| Condition branching | `branching-and-http.spec.ts` › routes each run down exactly one branch | passed | `amount 150` → true branch runs, false branch `SKIPPED`; `amount 50` → the reverse; logs only from the taken branch |
| HTTP request success | › success: the request reaches the server… | passed | method, templated query/body/header reach the mock; response (`status`, echoed body) feeds the next node |
| HTTP request failure | › failure: an error status fails the node… | passed | `503` → node and run `FAILED` with `HTTP_STATUS_ERROR`, downstream node does not run |
| (outbound control) | › outbound control: hosts outside the allowlist… | passed | `example.com` refused with `HTTP_HOST_NOT_ALLOWED`; the mock received nothing |
| AI model response | `ai-and-credentials.spec.ts` › a provider response is parsed, validated and drives the branch | passed | the bundled sample (Manual → Set Variables → AI Model → Condition → Logger) with a stored key: JSON mode request, parsed + schema-validated output, usage metadata, negative review → Escalate branch; the key reaches the provider and appears in no execution or workflow response |
| (AI failure handling) | › malformed output and rate limits are retried within the bounded policy | passed | malformed JSON → repaired on attempt 2; `429 Retry-After: 1` → succeeds on attempt 2; persistent `500` → `AI_PROVIDER_ERROR` after ≤ 3 requests |
| Webhook trigger | `triggers.spec.ts` › a signed delivery starts a run… | passed | inactive → `404`; activate; unsigned / wrong secret / stale timestamp → `401`; signed → `202` + `Location`, run with `triggerType WEBHOOK` and the body as input; replay → `409`; tampered body → `401`; deactivate → `404` |
| Scheduled trigger | › an active schedule fires on time and stops when deactivated | passed (28 s) | `*/10 * * * * *` UTC fires a `SCHEDULE` run with `scheduledFor` on a 10-second boundary; after deactivation `nextFireAt` is gone and no new runs appear for 16 s |
| Credential usage | `ai-and-credentials.spec.ts` › a stored credential authenticates the request and is never exposed | passed | bearer credential sent to the mock, which echoes it back; execution, history list and workflow show `[REDACTED]`, never the token; usage lists the workflow; delete while referenced → `409`; Credentials page lists it without the secret; a credential for another host → `CREDENTIAL_HOST_NOT_ALLOWED`, nothing sent |
| Execution failure and recovery | `failure-recovery.spec.ts` › a failed run is fixed by editing the workflow… | passed | failed run (`500`) → edit (version 2) → rerun succeeds; history keeps both, the failed run still references version 1 |
| (crash/restart recovery) | › a backend restart drains short runs, records long ones as interrupted… | passed (18 s) | `docker compose restart backend` while two runs are active: the 3 s run completes, the 28 s run becomes `FAILED EXECUTION_INTERRUPTED`, stop finishes inside the 30 s grace, the next run succeeds |
| (real-time updates) | › the event stream delivers a snapshot, node transitions and the terminal event | passed | through nginx: `snapshot` first, `node.succeeded`, `execution.completed` last, stream closes, no node payloads; a late subscriber gets only the snapshot |

## Module verification matrix (task list → evidence)

| # | Task | Evidence | Status |
|---|---|---|---|
| 1 | Frontend/backend API contracts | new `OpenApiContractIT` (snapshot `docs/api/openapi.json`) + new `apiContract.test.ts` (28 distinct client calls: 27 REST + the event stream, every response/request type's fields, status enums); both mutation-checked (a renamed path and a renamed field were caught) | verified |
| 2 | Node type ids and config schemas | shared catalog/schema/fixture files tested by `nodeTypes.test.ts`, `configContract.test.ts`, `NodeTypeCatalogTest`, `NodeConfigSchemaTest`, `JsonSchemaConfigurationValidatorTest` | verified |
| 3 | Database migrations | `MigrationIT` (8), new `MigrationChecksumTest` (V1–V8 pinned, no gaps), Hibernate `ddl-auto: validate` at every start, restore check shows V1–V8 | verified |
| 4 | Workflow persistence and versioning | `WorkflowApiIT`, `WorkflowRepositoryIT`, optimistic-locking tests; E2E reload and version-2 edit | verified |
| 5 | Execution engine | `ExecutionEngineTest` (+ nested suites), `ExecutionApiIT`, `AllNodesWorkflowIT`; E2E branching, failure, restart | verified |
| 6 | All six built-in executors (Set Variables, Condition, Transform, Logger, HTTP Request, AI Model) + 3 triggers | executor unit tests, `AllNodesWorkflowIT`, `AiWorkflowIT`; E2E covers all except Transform (Transform: unit + `AllNodesWorkflowIT` only) | verified |
| 7 | Webhook and schedule triggers | `WebhookApiIT`, `TriggerLifecycleIT`, `ScheduleTriggerIT`, `SchedulerPollingIT`; E2E webhook + schedule | verified |
| 8 | Credential security | `CredentialApiIT`, `CredentialExecutionIT`, `SecurityHardeningIT`, `WebhookSecretEncryptionIT`, cipher unit tests; E2E credential + AI key leakage checks | verified |
| 9 | AI model integration | `AiChatServiceTest`, provider/parser tests, `AiWorkflowIT` (mocked provider); E2E against an OpenAI-compatible mock | verified with mocks; **live provider not executed** |
| 10 | Execution history and real-time updates | `ExecutionHistoryApiIT`, `ExecutionEventsSseIT`, frontend live-state tests; E2E SSE through nginx and live canvas updates | verified |
| 11 | Dead code and temporary mocks | removed 2 unused repository methods, 2 unused frontend exports, a misnamed test file; test-only config moved out of the jar; no mock code ships (mocks live in tests and `e2e/mock-services`) | done |
| 12 | Dependency and configuration conflicts | Flyway parent POM pulled third-party repositories → Central-only mirror (`.mvn/settings.xml`); Spring's 30 s shutdown drain raced Compose's 30 s grace → bounded 12 s drain; springdoc dropped 17 success schemas → explicit; host `node_modules` leaked into the image build → `.dockerignore`; stale docs corrected; `npm audit` 0 vulnerabilities (frontend and e2e) | done |
| 13 | End-to-end tests | `e2e/` (13 scenarios), `infra/docker-compose.e2e.yml`, `scripts/e2e.sh` | done |
| 14 | Production artifacts | `flowforge-backend-0.1.0-SNAPSHOT.jar` (SHA-256 `90c88ae5…08ee`, identical on rebuild), `frontend/dist` (identical on rebuild), images `flowforge-backend` / `flowforge-frontend` | done |
| 15 | Docker Compose | digest-pinned images, healthchecks, 127.0.0.1-only ports, rotated logs, stop grace, env passthrough; E2E overlay | done |
| 16 | Deployment and troubleshooting docs | `DEPLOYMENT.md`, `TROUBLESHOOTING.md`, `PRODUCTION_READINESS.md`, README | done |

## Failures found and fixed during this module

| Found by | Problem | Fix |
|---|---|---|
| contract snapshot | 17 operations published no success schema (class/method `@ApiResponse` suppressed springdoc's inferred 2xx) | explicit `200` responses |
| E2E restart test | a 25 s run *succeeded* across a restart: Spring 6.1 executors wait up to 30 s on shutdown, equal to the container grace period, so long runs raced SIGKILL; the code comment claimed immediate interruption | `spring.lifecycle.timeout-per-shutdown-phase: 12s`, comment and docs corrected, test covers drain + interrupt |
| image build | `dependency:go-offline` queried repo.gradle.org, an S3 bucket and GitHub Packages for every artifact (declared by `flyway-parent`); 325 s and a wider supply chain | Central-only mirror: 94 s on a cold cache, all 1,745 downloads from Central |
| E2E (test code) | SSE parser expected `event: x` (server writes `event:x`); mock provider classified the repair prompt instead of the review | test and mock fixed (no product change) |

## Not executed / not covered

| Item | Why | Risk |
|---|---|---|
| `AiLiveIT` (real AI provider) | needs a real API key (`FLOWFORGE_LIVE_AI_API_KEY`); skipped by design | provider-specific wire differences are untested; the OpenAI-compatible format is tested with mocks |
| README "Option A" on the default ports and the default `flowforge` project | port 5173 is used by another process on this machine and the `flowforge` project/volume holds existing data, so they were not touched; the identical compose file ran as projects `flowforge-e2e` and `flowforge-bkp` | low: only ports and project name differ |
| Browsers other than Chrome; mobile viewports in E2E | Playwright uses the installed Chrome only | layout/behaviour in Firefox/Safari unverified |
| Manual accessibility audit (screen reader) | automated role/keyboard tests only | see Module 14 notes |
| Load, soak, concurrency beyond 2 workers | not in scope | throughput and queue behaviour under load unknown |
| Upgrade from an older *populated* database | migrations run from empty in tests; legacy plaintext webhook secret migration is tested (`WebhookSecretEncryptionIT`) | low for V1–V8; future migrations need their own data tests |
| Key rotation through the documented curl steps | covered by integration tests (`CredentialApiIT`, `WebhookSecretEncryptionIT`), not run by hand | low |
| Production readiness | not claimed; see `PRODUCTION_READINESS.md` (no authentication, single instance, no automated backups or alerting) | — |

## Reproducing

```bash
cd backend && DOCKER_HOST=unix://$HOME/.docker/run/docker.sock ./mvnw -B verify
cd frontend && npm ci && npm run typecheck && npm run lint && npm test && npm run build
scripts/e2e.sh        # results: e2e/report.json, e2e/playwright-report/index.html
```
