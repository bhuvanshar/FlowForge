# Module Status

| # | Module | Status |
|---|---|---|
| 1 | Project Foundation | Complete |
| 2 | Database and Workflow Models | Complete |
| 3 | Workflow Management REST APIs | Complete |
| 4 | Visual Workflow Editor | Complete |
| 5 | Node Configuration System | Complete |
| 6 | Backend Graph Validation Engine | Complete |
| 7 | Core Workflow Execution Engine | Complete |
| 8 | Built-in Node Executors | Complete |
| 9 | Execution History and Logs | Complete |
| 10 | Real-Time Workflow Execution Updates | Complete |
| 11 | Webhook and Schedule Triggers | Complete |
| 12 | Credentials and Security | Complete (see below) |
| 13 | AI Integration | Complete (see below) |
| 14 | Workflow Dashboard and UX | Complete (see below) |
| 15 | Final Integration, Testing and Local Deployment | Complete (see below) |

## Post-completion review

The 2026-10-26 follow-up fixed execution admission races, application-log credential masking, AI cancellation,
dashboard navigation, execution-history state and layout, cancellation feedback, schema constraints, and E2E key preservation.
Verification: **568 frontend + 387 backend unit + 132 backend integration tests passed**, one live-AI test skipped,
11 Docker API scenarios passed, plus a manual browser workflow smoke test.
See [the review report](POST_COMPLETION_REVIEW_2026-10-26.md) for scope, regression evidence, and limitations.
The module delivery results below are retained as historical results.

## Module 15: Final Integration, Testing and Local Deployment
Status: **complete**, verified 2026-10-26. No database migration (Flyway still ends at V8). Full results, the requested scenario
list and everything not executed: **[TEST_REPORT.md](TEST_REPORT.md)**. FlowForge is **not production-ready**
([PRODUCTION_READINESS.md](PRODUCTION_READINESS.md)).

Delivered:
- **Cross-module contracts**: `docs/api/openapi.json` generated from the backend and enforced on both sides (`OpenApiContractIT`,
  `frontend/src/lib/api/apiContract.test.ts`); explicit success responses on 17 operations whose schema springdoc had dropped;
  `MigrationChecksumTest` pins released migrations V1–V8.
- **End-to-end suite**: `e2e/` (Playwright, 13 scenarios, Chrome), `infra/docker-compose.e2e.yml` (mock upstream with echo, status,
  delay and an OpenAI-compatible endpoint), `scripts/e2e.sh` (separate `flowforge-e2e` project and ports, fresh secrets, teardown).
- **Fixes from the audit**: bounded shutdown drain (`spring.lifecycle.timeout-per-shutdown-phase: 12s`, fits the 30 s compose
  grace); Maven Central-only mirror (`backend/.mvn/settings.xml`; Flyway's parent POM added third-party repositories);
  test profile moved out of the jar; `.dockerignore` for both images; digest-pinned base images; Maven cache mount; fixed
  `project.build.outputTimestamp` (byte-identical jar), rotated container logs, `stop_grace_period`; compose passes
  `FLOWFORGE_HTTP_ALLOWED_PORTS`, `FLOWFORGE_EXECUTION_WORKERS`, `FLOWFORGE_EXECUTION_MAX_ACTIVE_PER_WORKFLOW`; dead code removed
  (2 repository methods, 2 frontend exports); stale notes corrected (INTEGRATION_NOTES, ARCHITECTURE, README Swagger note).
- **Docs**: `DEPLOYMENT.md` (setup, artifacts, health, backup/restore, upgrade, key rotation), `TROUBLESHOOTING.md`,
  `PRODUCTION_READINESS.md`, `TEST_REPORT.md`; README quick start and status.

### Test results
| Check | Result |
|---|---|
| Backend `./mvnw verify` | 384 unit + 131 integration passed, 1 skipped (`AiLiveIT`, needs a real key) |
| Frontend `npm test` / typecheck / lint / build | 552 passed in 45 files / clean / 0 warnings / built |
| `scripts/e2e.sh` | 13 passed, 0 failed, 0 flaky (two clean runs) |
| Backup and restore (DEPLOYMENT.md §6) | executed: deleted data restored, webhook secret decrypts with the backed-up key |
| Reproducibility | jar and frontend bundle byte-identical across clean rebuilds; `npm audit` 0 (frontend, e2e) |

### Not verified
- Live AI provider; browsers other than Chrome; manual screen-reader audit; load/soak testing; upgrade of a populated
  pre-V8 database beyond the tested webhook-secret migration; the README commands on the default `flowforge` project and port
  5173 (in use on this machine — the same compose file ran as `flowforge-e2e` and `flowforge-bkp`).

## Module 14: Workflow Dashboard and User Experience
Status: **complete**, verified 2026-10-26. No database migration (Flyway still ends at V8). Builds on the earlier partial dashboard
work (see "Dashboard and UX follow-up" below).

Delivered:
- **Dashboard** (`/`): create (dialog with name and description, then the editor), rename (dialog; uses the latest definition and lock
  version so only the label changes), duplicate, archive (confirmation), restore, permanent delete (danger confirmation, archived
  workflows only), activate / deactivate (deactivate asks first), run; server-side search, status filters, sort by name, creation
  date and modification date, grid and list views, pagination; URL-persisted state; lifecycle badge (Active / Draft / Inactive /
  Archived) and the **last execution** (status, relative time, link to it; "Not run yet") on every card; per-card links to the editor
  and the execution history; counts; empty (first run, no match, per filter), loading (skeleton with a status message) and error
  states with retry; a "More actions" menu per card (keyboard operable, not clipped by the card).
- **Settings** (`/settings`): dashboard preferences stored in this browser (default view, default sort, page size, reset), the
  server's effective configuration read-only with the environment variable that changes each value, and the keyboard shortcuts.
- **Navigation**: sidebar Workflows / Credentials / Settings with `aria-current`, breadcrumb and browser tab title per section,
  skip-to-content link, focus moved to the page content after navigation, history/credentials/settings reachable from the dashboard.
- **Keyboard**: `/` focuses the search, `n` starts a new workflow, Escape closes dialogs and menus, full arrow-key menus, dialogs
  focus the safe choice first and return focus to the opener.
- **Design system**: colour, status, shadow and radius tokens in `index.css` (status pairs checked for WCAG AA contrast, brand colour
  darkened to 5.4:1 on white) and reusable components in `components/ui`: `Badge`, `Notice`, `EmptyState`, `PageHeader`, `Skeleton`,
  `TextField`/`TextAreaField`/`SelectField`, `SegmentedControl`, `Dialog`, `ConfirmDialog`, `ActionMenu`. Execution status badges
  use the same tones. No third-party branding or assets.
- **Backend (additive)**: `lastExecution` on `GET /workflows` items (one batched query per page) and read-only `GET /settings`.

### Test results
| Check | Result |
|---|---|
| Frontend `npm test` | 545 passed in 44 files (new 70: UI components 17 incl. menu keyboard behaviour, dialogs, notices, fields, segmented control; dashboard interactions 25 incl. create/rename/activate/deactivate/archive/restore/delete flows with failures, status and last-run display, per-status actions, loading/error/empty states, shortcuts, preferences; settings page 6; shell and navigation 8 incl. skip link, `aria-current`, titles and focus; preferences 4; hotkeys 3; status helpers 8; API contract tests 4); typecheck, lint (0 warnings) and build clean |
| Backend unit tests | 383 passed (unchanged count; `WorkflowControllerTest` adapted) |
| Backend integration tests | 131 run, 130 passed, 1 skipped (live AI test); new `DashboardApiIT` 4: newest execution per workflow (several runs, failed run, never run, in-flight run without finish time), sorting and filtering with the extra field, settings read-only with no secrets, Host guard applies |
| Real browser (Docker backend + Vite on 5174) | Four seeded workflows (active/succeeded, inactive/succeeded, draft/failed, draft/never run): badges and last-run links correct; card menu opens in a portal, flips upward at the bottom of the screen and is operable from the keyboard; rename dialog focuses the name field, Enter saves, the list re-sorts and shows the notice; `n` opens the new-workflow dialog with focus in the name field, `/` focuses the search, Escape closes the dialog; Settings shows the live server configuration; 375 px wide layout checked; test data deleted |

Found while verifying in the browser: in development (React StrictMode runs effects twice) the first page load moved focus to the main
region and showed an outline; fixed (focus moves only when the path really changes) and covered by the shell tests.

### Not verified
- Screen readers (VoiceOver, NVDA): roles, names, descriptions and focus order are tested with Testing Library and checked by hand in
  Chrome, not with assistive software; colour contrast is computed from the tokens, not measured with a tool on rendered pages.
- Dark mode: not built (the application ships one light theme; the token structure allows a dark set later).
- Large lists (hundreds of workflows): the list is paginated server-side (12/24/48 per page); not load tested.
- Editing a workflow's name from inside the editor is unchanged (the toolbar field).

## Module 13: AI Integration
Status: **complete**, verified 2026-10-26. No database migration (Flyway still ends at V8).

Delivered: node type `core.aiModel` (catalog, JSON Schema, shared fixtures, editor form with model/base-URL suggestions, credential
picker, JSON mode and schema editor); provider abstraction (`ChatProvider`, `ChatProviderRegistry`) with the OpenAI-compatible
chat-completions provider; `AiChatService` with a bounded failure policy (at most 4 requests in one time budget; retries with
backoff and `Retry-After` for rate limits, 5xx, connection failures, garbled/empty replies and unusable JSON, with a correction round
for JSON; no retry for auth, bad request, refusal, timeout, oversize or policy errors); prompt templates with the safe `{{ }}`
interpolation and size limits; system + user messages; temperature and `max_tokens`; structured JSON output with strict parsing,
code-fence tolerance and validation against a restricted JSON Schema subset (`ResponseSchemaPolicy`: no `$ref`/`pattern`/`format`,
shared fixtures with the editor); usage and provider-reported cost (summed over attempts) in the output and a log line; credentials
from Module 12 (bearer or API-key header; owner and host bound; masked by value); no redirects; the HTTP Request node's outbound
allowlist and non-public-address protection; sample workflow `ai-review-triage` (Manual Trigger → Set Variables → AI Model →
Condition → two Loggers) with `GET /samples`, `POST /samples/{id}/create` and a "Try the AI sample" dashboard action; optional live
test (`AiLiveIT`).

### Test results
| Check | Result |
|---|---|
| Backend unit tests | 383 passed (new 56: `AiModelExecutorTest` 37 against a scripted mock provider: request shape, interpolation, JSON mode and schema, fences, repair rounds, bounded retries, rate limits and `Retry-After`, 4xx/5xx mapping, auth text never quoted, timeout, size limit, redirect, SSRF/allowlist, credentials incl. owner/host rules and masking, cancellation; `AiChatServiceTest` 7, `ResponseSchemaPolicyTest` 5, `AiOutputParserTest` 4, validator rules 3) |
| Backend integration tests | 127 run, 126 passed, 1 skipped (new `AiWorkflowIT` 9: the sample end to end on both branches with a stored credential, key never in workflows/history/logs/responses/DB even when the model echoes it, repair and usage summing, schema failures, 429 recovery / 401 / 503 with exact request counts, credential validation at validate/activate/run, wrong-host credential never sent, sample endpoint input checks and CSRF guard; skipped: `AiLiveIT`, runs only with `FLOWFORGE_LIVE_AI_API_KEY`) |
| Frontend `npm test` | 475 passed in 38 files (new: AI form tests incl. unsafe schemas and round trip, schema policy against the shared fixtures, node type/palette, credential catalog and pattern contracts, dashboard sample button); typecheck and build clean |
| Docker backend + local mock provider | Sample created through the API with a credential and pointed at a mock provider on the host: validation valid; first run: the mock's first answer was malformed on purpose, the node asked again (second request carried 4 messages), `attempts: 2`, usage and cost summed (total 290, cost 0.00084), warning and usage lines in the log, Condition branch taken, Loggers ran; second run 1 attempt. The mock saw `Authorization: Bearer <key>` and `response_format: json_object` on every request; the key is in no API response and in no row of any table (checked with SQL); data deleted |

### Not verified
- A real provider: the live test exists but was not run (no key was used). The OpenAI wire format is implemented from its public
  chat-completions contract and exercised against mocks only.
- Reasoning models that reject `max_tokens` (leave the limit empty), servers that ignore `json_object` (use `jsonMode: prompt`).
- The sample in the browser UI: the frontend container could not start because another local process held port 5173, so the
  dashboard button and the AI form were verified by component tests only.

## Dashboard and UX follow-up (2026-10-26)

Delivered a refreshed workspace shell, workflow cards, real workflow counts, server-side search,
status filters, sorting, URL-persisted view settings, pagination, and grid/list views. Dashboard
actions support quick run, duplication, execution history, confirmed archiving, and restoration
as a draft. The node palette is searchable; editor panels become toggleable overlays below 1280px.

Verification: **424 frontend tests passed**, with clean type checking, lint, and production build.
Live browser checks covered search, sorting, pagination, URL persistence after reload, grid/list
views, run-to-success, duplication, archive/restore, and responsive editor layouts. These changes
add no backend migration or runtime dependency. Module 14 is still partial; AI integration,
broader settings, and final deployment verification remain separate work.

See [UI enhancement notes](UI_ENHANCEMENTS_2026-10-26.md) for the preview setup and scope.

## Module 12: Credentials and Security

### Follow-up (2026-10-26): webhook secrets encrypted at rest
`webhook_triggers.secret` is now encrypted with the credential keyring (Flyway `V8`: `secret_key_id`, `secret_nonce`,
`secret_ciphertext`, check constraint "exactly one of plaintext or ciphertext"). `WebhookSecretStore` seals new and rotated
secrets (associated data binds them to workflow and webhook id) and decrypts on demand for verification and reveal. Migration
path: rows created before V8 or while no key was configured stay readable as plaintext and are encrypted at the next start with
a keyring and by `POST /credentials/re-encrypt` (which now also re-encrypts webhook secrets on older keys); `/credentials/status`
reports `webhookSecretsToEncrypt` and the Credentials page shows it. Undecryptable secrets fail closed (`503 webhook-unavailable`
/ `secret-unavailable`, hint `…????`).

| Check | Result |
|---|---|
| Backend unit tests | 324 passed (new `WebhookSecretStoreTest` 5: encryption and reveal, plaintext mode without keyring, legacy migration at startup, key rotation + old key removal, ciphertext bound to its webhook) |
| Backend integration tests | 117 passed (new `WebhookSecretEncryptionIT` 5: only ciphertext stored and deliveries/rotation still work, legacy plaintext works then is encrypted on startup with the same secret, re-encrypt endpoint encrypts plaintext, tampered ciphertext fails closed without leaks, database constraint; `MigrationIT` updated for V8; all webhook ITs now run against encrypted secrets) |
| Frontend `npm test` | 396 passed (new: pending-encryption notice) |
| Docker | Started without a key: V8 applied, new webhook stored in plaintext, delivery 202, status `webhookSecretsToEncrypt: 1`. Restarted with the key: log "Encrypted 1 webhook secret(s) with key 'k1'", row has no plaintext (secret column null, key `k1`, 12-byte nonce, secret not found in the row), same secret revealed, delivery 202, status 0; data deleted, stack stopped |

Status: **complete**, verified 2026-10-25.

Delivered: credentials store (Flyway `V7`: `credentials`, `workflows.owner_id`) with AES-256-GCM encryption bound to each row
(associated data), keyring from environment or key file (never DB/repository; random per-JVM key in tests), write-only secrets,
CRUD + rotation + usage + key re-encryption API, owner scoping via `OwnerContext` (single local owner, ready for users), permitted
usage (type → node types, allowed hosts), shared credential type catalog; HTTP Request node `credentialId` (bearer, basic, API
key header) with host binding, cross-origin redirect stripping, header-conflict check; value-based secret masking of stored
node data, errors, logs and events; validation of credential references and rejection of literal secrets / client-managed
headers in workflow JSON. Hardening: Host allowlist (DNS rebinding), cross-site write blocking (CSRF), strict CORS, security
headers, body/JSON/header limits, generic error bodies, per-workflow execution cap, request-body cap for the HTTP node,
Swagger off in prod, health details hidden, nginx CSP and headers. Frontend: Credentials page (list, create, edit, rotate,
delete with usage), credential picker in the HTTP Request form, editor rules for header secrets. Trust boundary documented.

### Test results
| Check | Result |
|---|---|
| Backend unit tests | 319 passed (new 27: AES-GCM 7 (round trip, fresh nonces, tamper/nonce/associated-data detection, key rotation, wrong key, disabled, malformed keyrings without key echo, key file), `SecretMask` 3, engine masking 2, `CredentialService` 6 (ciphertext only, owner isolation and usage rules, rotation + re-encryption + old key removal, swapped ciphertext, validation, no key), HTTP node credentials 6, validator header/credential rules 3) |
| Backend integration tests | 112 passed (new 18: `CredentialApiIT` 6 (secrets never returned, no plaintext in the row, update vs rotate, validation without echo, in-use deletion, other owner invisible, re-encrypt), `CredentialExecutionIT` 6 (acceptance: server receives the token, nothing in history/logs/APIs/DB even when echoed; basic + header incl. derived value; rotation; allowed hosts; foreign/tampered credentials; literal secrets refused), `SecurityHardeningIT` 6 (Host allowlist, cross-site writes, CORS, size/nesting limits, headers and leak-free errors, per-workflow cap)); `AllNodesWorkflowIT`, `ExecutionHistoryApiIT` and `ExecutionEventsSseIT` now authenticate with stored credentials instead of literal headers; `MigrationIT` updated for V7 |
| Frontend `npm test` | 395 passed in 34 files (new 24: credentials page 6, credential catalog contract + host/draft rules 13, credential picker and header rules in the HTTP form 2, credentialId pattern contract and fixtures); typecheck and build clean; lint: one pre-existing warning |
| Docker stack | Bearer credential created through nginx; workflow called `https://postman-echo.com/headers`, which echoed the token: execution SUCCEEDED, the request echo, the echoed header and the Logger line all show `[REDACTED]`, the API response and every table (workflows, executions, node runs, logs, credentials) contain no plaintext. `Host: attacker.example` → 403, cross-site POST → 403, frontend served with CSP/X-Frame-Options |
| Real browser | Credentials page lists the credential with `•••••••• v1` and last use; no CSP violations; the HTTP node's Authentication picker shows the saved credential; data deleted, stack stopped |

### Not verified
- Key file permissions warning on a real multi-user system (only exercised via a temp file in tests).
- A real key rotation in Docker (adding a second key and calling re-encrypt); covered by unit tests with real AES-GCM keys.
- Browsers' own CORS/CSRF behaviour end to end (requests were simulated with `Origin` / `Sec-Fetch-Site` headers).

## Module 11: Webhook and Schedule Triggers
Status: **complete**, verified 2026-10-25.

Delivered: node types `core.webhookTrigger` and `core.scheduleTrigger` (catalog, JSON Schema, shared fixtures, Zod forms,
executors); Flyway `V6` (`webhook_triggers`, `schedule_triggers`, `trigger_type`/`scheduled_for` on executions with a unique
occurrence index); trigger lifecycle tied to workflow status (`TriggerSynchronizer` in every write transaction, `POST
/workflows/{id}/activate|deactivate`, active workflows must stay valid); `GET /workflows/{id}/triggers`, secret reveal/rotate;
webhook endpoint `/api/v1/hooks/{webhookId}` with HMAC-SHA256 signatures (timestamp window, replay guard) or bearer token,
unauthenticated mode off by default (`FLOWFORGE_WEBHOOKS_ALLOW_UNAUTHENTICATED`), 256 KiB limit, JSON-only, body/request input
mapping, Problem Details for 401/404/405/409/413/415/429; `ScheduleRunner` (poller with `SKIP LOCKED` claims, time zones, `skip` /
`runOnce` missed-run policies, restart recovery, duplicate prevention); trigger type in execution responses. Frontend: trigger forms
with cron/time zone checks, Activate/Deactivate in the toolbar, trigger status panel (webhook URL, secret reveal/copy/rotate,
example request; schedule next/last run), trigger column in history and on the detail page.

### Test results
| Check | Result |
|---|---|
| Backend unit tests | 292 passed (new 16: `CronScheduleTest` 8 incl. DST, `WebhookSignaturesTest` 3 incl. reference HMAC and replay guard, validator cron/time zone/unauthenticated policy 2, `WorkflowServiceTest` lifecycle 3; fixtures extended) |
| Backend integration tests | 94 passed (new 27: `TriggerLifecycleIT` 8, `WebhookApiIT` 9 (signature ok/bad/changed body/stale/future, replay, token via both headers, 405/404/inactive/reactivated, 413/415/form/400/vendor JSON/empty body, request mode without credentials, policy change to `none`, rotation), `ScheduleTriggerIT` 9 (on-time run and advance, repeated poll, 6 concurrent polls → 1 run, rewound schedule + unique index, skip and runOnce after downtime, grace period, crash between commit and dispatch + restarted runner, deactivation, manual runs), `SchedulerPollingIT` 1 (real background poller, per-second cron, stops on deactivation); `MigrationIT` updated for V6) |
| Frontend `npm test` | 371 passed in 32 files (new 43: contract fixtures for both triggers, cron/time zone checks 16, editor trigger UI 6 (URL, secret reveal/rotate, example; activate/deactivate with lock version; save-first and 422 handling; schedule next/last run; unsaved workflow), history/detail trigger display); typecheck and build clean; lint: one pre-existing warning |
| Docker stack via nginx (curl) | Webhook: 404 before activation, 202 signed → execution SUCCEEDED (`WEBHOOK`, log "Order for a@example.com"), 409 replay, 401 unsigned, 405 + `Allow: POST`. Schedule `*/5 * * * * *` Europe/Berlin: runs every 5 s, backend container restarted mid-schedule → runs resumed, 5 runs with 5 distinct occurrences, none after deactivation |
| Real browser | Trigger panel showed the active webhook URL, masked secret with reveal/rotate and the example; history listed scheduled runs with trigger "Schedule"; data deleted, stack stopped |

### Not verified
- Downtime longer than the grace period in Docker (covered by `ScheduleTriggerIT` with chosen instants, not by stopping the
  stack for minutes).
- Real DST transitions at run time (unit tested on computed occurrences).
- Webhooks from outside this machine (default bindings are localhost only) and behind other reverse proxies.
- Load: many webhooks per second or hundreds of schedules (batch size and replay-cache limits are not load tested).

## Module 10: Real-Time Workflow Execution Updates
Status: **complete**, verified 2026-10-25.

Delivered: `GET /api/v1/executions/{id}/events` (SSE) with snapshot-first recovery, eight event types published by the engine
after each database write, in-memory broadcaster with bounded queues, heartbeat and connection cap; frontend live state (pure
reducers + `useLiveExecution` with reconnect, snapshot recovery, polling fallback and debounced refetch of persisted results),
running-node highlight and live statuses on the canvas, live bottom inspector and execution detail page, connection badge, nginx
config for unbuffered SSE, documented single-instance limitations.

### Test results
| Check | Result |
|---|---|
| Backend unit tests | 276 passed (new 14: engine events 6 incl. a check that every event is published only after its state is persisted, broadcaster 8 incl. overflow drop, capacity, cleanup) |
| Backend integration tests | 67 passed (new `ExecutionEventsSseIT` 10 over a real HTTP server: snapshot then live events to completion and stream close, mid-run connect recovery, finished execution = snapshot only, failure/skip/cancel events, several clients, subscription cleanup on disconnect, no payloads or credentials in events, 404 problem detail, OpenAPI) |
| Frontend `npm test` | 328 passed in 30 files (new 36: reducer 13, `useLiveExecution` 12 with a fake EventSource incl. reconnect/snapshot recovery/polling fallback/no polling while open, editor live behaviour 7, detail page 2); typecheck and build clean; lint: one pre-existing warning |
| Real browser, Docker stack, two 3 s HTTP nodes | Run: first slow node highlighted blue on the canvas with "Running · Live" in the panel and the inspector on that node; statuses turned green as nodes finished; refreshing the page mid-run showed the in-progress run (Running · Live) and it completed on its own to "Succeeded 6.07 s"; refreshing after completion restored all node statuses from the database; data deleted |

### Not verified
- Behaviour through other reverse proxies than the bundled nginx config (the browser run used it).
- Many simultaneous streams (the 200-connection cap and queue overflow are unit tested, not load tested).
- Backend restart while a stream is open (covered by design: the browser reconnects and gets a snapshot; not exercised end to end).
- Failed and cancelled runs in the real browser (integration and component tests cover them).

## Module 9: Execution History and Logs
Status: **complete**, verified 2026-10-25.

Delivered: `GET /workflows/{id}/executions` (pagination, status filter, durations, node counts, failed node),
`GET /executions/{id}/nodes` (status filter), `durationMs` and snapshot-based `nodeName` on executions and node runs, stored-payload
redaction (`PayloadRedactor`) before the existing size cap. Frontend: execution history page, execution detail page (failure and
affected node, execution order, node input/output, execution log, cancel), reusable inspector, JSON view, Run button and History link in
the editor toolbar, bottom execution inspector (latest run shown when a saved workflow is opened), node statuses on the canvas,
History link in the workflow list.

### Test results
| Check | Result |
|---|---|
| Backend unit tests | 262 passed (new: `PayloadRedactorTest` 3, history controller tests 2) |
| Backend integration tests | 57 passed (new `ExecutionHistoryApiIT` 7: newest-first pagination with counts and durations, status filters and failed node, exact node results in order with names, history unchanged after the workflow is edited/archived and snapshot stored, credentials redacted in stored history while the real values reach the server, log caps/sanitizing, parameter validation and 404s) |
| Frontend `npm test` | 294 passed in 26 files (new 38: format 4, `JsonView` 3, `ExecutionInspector` 7, history/detail pages 9, editor run and inspector 9, plus updated suites); typecheck, build clean; lint: one pre-existing warning |
| Real browser, Docker stack | Run from the editor: green node dots, skipped branch grey, inspector listing nodes in order with durations; reload → History page listed the run (62 ms, v1, 4/5 ok, 1 skipped) → detail page showed the exact inputs/outputs, `password` and `Authorization` stored as `[REDACTED]`, and the execution log; data deleted |

Note: two frontend runs showed bogus ~16-minute test durations and timeouts while a Docker build ran in parallel; reruns on an idle
machine pass in about 7 s. Treat parallel heavy jobs as the cause, not the tests.

### Not verified
- Very long histories (thousands of rows) and large payload rendering in the browser.
- Live polling across a backend restart.
- Failed and cancelled runs in the real browser (covered by component and integration tests).

## Module 8: Built-in Node Executors
Status: **complete**, verified 2026-10-25.

Delivered: executors for all six node types; safe `ExpressionResolver` (`input`, `trigger.body`, `vars`, `nodes.<id>.output`;
property lookup only); execution logs (Flyway V5 `execution_logs`, `logs` in the execution API); HTTP Request with hostname
allowlist, non-public address blocking, DNS pinning via a guarded resolver, manual re-checked redirects, size/time limits,
cancellation abort and secret redaction (Apache HttpClient 5); Docker/env configuration (`FLOWFORGE_HTTP_ALLOWED_HOSTS`,
`FLOWFORGE_HTTP_ALLOW_PRIVATE_ADDRESSES`); editor expression hint lists the roots. Synthetic test executors removed.

### Test results
| Check | Result |
|---|---|
| Backend unit tests | 257 passed (new 116: `ExpressionResolverTest` 19 incl. injection attempts and schema-grammar equality; `BuiltInExecutorsTest` 36 covering trigger, variables, 25 condition cases, transform selection/renaming/mapping/type errors, logger sanitizing and limits; `OutboundRequestPolicyTest` 46 covering the allowlist, URL rules, 36 address classifications, guarded DNS, a DNS-rebinding simulation and redaction; `HttpRequestExecutorTest` 15 against a real local server covering JSON/text, expressions in URL/query/headers/body, 4xx/5xx, timeout, size limits, redirects incl. blocked targets, loops, method rules, credential stripping, SSRF blocks, allowlist, invalid URLs, header injection and cancellation) |
| Backend integration tests | 50 passed (new `AllNodesWorkflowIT` 5: six-node workflow true path and false path, HTTP 500 failure, condition type error, input-driven SSRF attempt; `ExecutionApiIT` now runs on the real executors and a local server) |
| Frontend | 256 passed; typecheck clean (hint text only) |
| Docker stack | same six-node workflow: with private addresses blocked, calling `http://frontend/` failed with `HTTP_ADDRESS_BLOCKED` (172.18.x); with them allowed (dev), true path ran all six types (log "Fetched 459 chars (HTTP 200) for ADA@EXAMPLE.COM", Authorization stored as `[REDACTED]`), false path ran only the other logger; data deleted |

### Not verified
- Calls to real internet hosts (all tests use a local server or Docker containers; no external network was used).
- IPv6-only targets and TLS edge cases (certificate errors map to `HTTP_CONNECTION_ERROR`, untested).
- A node thread blocked inside the HTTP client is aborted by the watchdog within ~25 ms of a cancel or timeout (tested); other
  long-running non-HTTP work is not interruptible.

## Module 7: Core Workflow Execution Engine
Status: **complete**, verified 2026-10-25.

Delivered: `ExecutionPlanner` (deterministic Kahn plan, rejects joins/loops), `ExecutionEngine` (sequential execution, output
propagation, branch selection, skipped-branch semantics, failure stop, node and execution timeouts, cancellation by interrupt),
`NodeExecutor` SPI + `NodeExecutorRegistry` + `NodeExecutionResult`, `ExecutionContext`, `ExecutionStateManager` (cancellation
tokens, startup recovery), `ExecutionPersistenceService` (one short transaction per state change, payload truncation),
`WorkflowExecutionService` (load → validate → plan → snapshot → queue), async worker pool, endpoints
`POST /workflows/{id}/execute` (202 + execution id), `GET /executions/{id}`, `POST /executions/{id}/cancel`. Only
`core.manualTrigger` has a production executor; other node types answer 501 until Module 8.

### Test results
| Check | Result |
|---|---|
| Backend unit tests | 141 passed (new 37: `ExecutionEngineTest` 24 covering ordering, data propagation, fan-out, branching and transitive skipping, failures and error sanitizing, node/executor/execution timeouts, cancellation before start/during a node/between nodes, worker interruption, determinism; `ExecutionPlannerTest` 6; `NodeExecutorRegistryTest` 2; `ExecutionControllerTest` 5) |
| Backend integration tests | 45 passed (new: `ExecutionApiIT` 10 with synthetic executors: linear run with persisted inputs/outputs, default test input, skipped branches, failures, cancel of a running node, truncated payloads with full in-memory propagation, determinism, snapshot isolation, 422/409/404 without side effects, startup recovery) |
| Timing-sensitive engine tests repeated 5× | stable |
| Frontend | unchanged; not re-run |
| curl against Docker stack | trigger-only workflow: 202 + Location, SUCCEEDED with the request input and with the default test input; workflow containing a Logger: 501 naming `core.logger`; invalid workflow: 422; cancel of a finished execution: 409; test data deleted |

### Not verified
- Behaviour under many concurrent executions (only a handful in parallel in tests); queue-full 503 is tested at the controller only.
- Shutdown while executions run (covered by the interrupt unit test and startup recovery IT, not by stopping a real container mid-run).
- Real node behaviour (Module 8).

## Module 6: Backend Graph Validation Engine
Status: **complete**, verified 2026-10-25.

Delivered: `GraphValidator` (deterministic passes; iterative DFS for cycles, BFS for reachability, Kahn's algorithm for execution
order), 25 stable issue codes with severity, node id, edge id and JSON path; catalog-backed `NodeTypeRegistry` and JSON Schema
`ConfigurationValidator` (networknt now in compile scope); `POST /api/v1/workflows/{id}/validate` (stored or submitted definition);
activation requires a valid graph (422 `workflow-invalid`); catalog gained `role` (trigger/action). Editor: automatic debounced
validation of the unsaved canvas, Validate button, node badges/borders, red/amber edges, Problems panel with click-to-focus,
per-node problems in the properties panel.

### Test results
| Check | Result |
|---|---|
| Backend unit tests | 104 passed (new: `GraphValidatorTest` 39 covering every rule, determinism, limits and ordering; `JsonSchemaConfigurationValidatorTest` 4; validation/422 controller tests 4; activation service tests 3) |
| Backend integration tests | 35 passed (new: `WorkflowValidationApiIT` 5: stored/submitted validation, precise issues, 404/400, 422 activation, invalid drafts still savable) |
| Frontend `npm test` | 256 passed in 21 files (new: problems panel/store/highlighting 13, edge decoration 3, editor validation flow 8) |
| Frontend typecheck, build | clean; lint: same single pre-existing warning |
| Real browser, Docker stack | loaded a deliberately broken workflow: nodes outlined with counts, cycle edge red, Problems panel listed 5 errors + 1 warning; clicking a problem selected and focused the node and listed its problems; fixing the URL and applying re-validated the unsaved canvas (4 errors); data deleted afterwards |

Existing tests changed by the coordinated contract change: three `WorkflowApiIT` steps and one `WorkflowServiceTest` case that set
`ACTIVE` on invalid graphs now use `INACTIVE` or a valid graph.

Fixes during verification: issue ordering doc corrected (edge rules are reported per edge); condition node badges overlapped the
branch labels (header now leaves room).

### Not verified
- React Flow does not render edges in jsdom, so the edge highlight is unit-tested through `decorateEdges` and confirmed only manually in
  the browser.
- Performance near the 500-node / 2000-edge limits (validation is linear in nodes + edges plus schema checks; not measured).
- Keyboard shortcut Shift+1 sometimes appeared not to work in the automated browser while a wheel-zoom animation was still running;
  the handler fires (verified), so this was not changed.

## Module 5: Node Configuration System
Status: **complete**, verified 2026-10-25.

Delivered: schema-driven configuration forms (React Hook Form + Zod) for all six node types, shown in the right panel; field-level
errors; JSON editing with validation; safe `{{ path }}` expressions (no code evaluation); per-node drafts with Apply/Revert,
reset on node switch, unsaved-change handling and save-time validation; incomplete-configuration indicator on nodes; shared
backend/frontend contract (`node-config.v1.schema.json`, default configurations, shared fixtures).

### Test results
| Check | Result |
|---|---|
| Frontend `npm test` | 232 passed in 18 files (new: config contract 62, definitions 43, forms and node switching 30, end-to-end editor configuration 5, plus updated suites) |
| Frontend typecheck, build | clean; lint: same single pre-existing warning |
| Backend `./mvnw verify` | 54 unit + 30 integration passed (new: `NodeConfigSchemaTest` validates 59 shared fixtures) |
| Real browser, Docker stack | configured an HTTP Request (POST, invalid URL error shown, header, expression value, JSON body), saved with Ctrl+S, refreshed, reselected the node: all values reloaded; API stored the full configuration; test data deleted |

Bugs found by tests and fixed: a body typed under GET (hidden field) silently blocked Apply with an invisible error (body is now
ignored for GET/HEAD); contract tests confirmed JSON Schema and Zod agree on all fixtures.

### Not verified
- Only the HTTP Request form was exercised by hand in a real browser. The other five forms are covered by component and integration tests in jsdom.
- Backend does not validate configurations yet (Module 6). The shared contract is tested from both sides, but no server rejects bad configs today.
- Accessibility beyond labels and aria attributes (no screen-reader or keyboard-only pass); very large JSON bodies in the form.

## Module 4: Visual Workflow Editor
Status: **complete**, verified 2026-10-25.

Delivered: React Flow editor (palette, canvas with minimap/controls, properties and execution placeholders, toolbar),
reusable `WorkflowNode`, six node types, Zustand editor store with undo/redo and dirty tracking, canonical
serialization, save/load via Module 3 APIs (create then update with `lockVersion`), workflow list page, keyboard
shortcuts, unsaved-change warnings, node type catalog contract (`node-types.v1.json`) shared with the backend.

### Test results
| Check | Result |
|---|---|
| Frontend `npm test` | 90 passed in 14 files (store 26, serialization 6, node registry 4, node component 11, palette 3, toolbar 6, shortcuts 6, panels 4, editor page 12, list page 2, app shell/health 10) |
| Frontend typecheck, build | clean; lint: 1 pre-existing warning (`buttonVariants` export) |
| Backend `./mvnw verify` | all passed; new `NodeTypeCatalogTest` |
| Real browser against Docker stack | built a 3-node graph (palette add, drag, two connections incl. `true` branch), saved with Ctrl+S (URL became `/workflows/<id>`), refreshed: same nodes, positions, edges, labels, "All changes saved"; API returned the same definition; test data deleted |

Bugs found by tests/manual run and fixed: `PropertiesPanel` selector created a new array per call (infinite render loop);
nodes added from the palette overlapped (now laid out on a grid).

### Not verified
- Native HTML5 drag-and-drop from the palette in a real browser: the automation tool cannot start it, so I dispatched
  synthetic dragover/drop events (worked) and unit-tested the drag payload. Please try a real mouse drag once.
- Touch devices, browsers other than the embedded Chromium, Windows keyboard shortcuts (Ctrl variants are tested in jsdom).
- Graphs near the 500-node limit (no performance test).
- `beforeunload` prompt in a real browser (tested through events only).

## Module 3: Workflow Management REST APIs
Status: **complete**, verified 2026-10-25.

Delivered: `WorkflowController`, `WorkflowService`, DTOs and mapper, name/status filter specifications, pagination
and sorting, optimistic locking via `lockVersion`, soft delete (archive) plus guarded hard delete, duplicate,
springdoc OpenAPI at `/v3/api-docs` and `/swagger-ui.html`, new Problem Detail types (`conflict`,
`concurrent-modification`, `invalid-request`, `payload-too-large`). `ResourceNotFoundException` moved from `api.error` to `application`.

### Test results
| Check | Result |
|---|---|
| Backend unit tests | 51 passed (new: `WorkflowServiceTest` 14, `WorkflowControllerTest` 13) |
| Backend integration tests (Testcontainers) | 30 passed (new: `WorkflowApiIT` 7: lifecycle, stale update, duplicate, list/sort/filter, validation, 413, OpenAPI) |
| Frontend | unchanged; not re-run |
| curl against `docker compose --profile app` | create 201, update, stale update 409, duplicate, list sorted, soft delete 204, hard delete 204, then 404; OpenAPI and Swagger UI served |

Bug found by tests and fixed: name sort was case-sensitive (database collation); it is now case-insensitive.

### Not verified
- Postman itself (curl only). Concurrent writers racing within the same millisecond were not stress-tested; the
  `concurrent-modification` path relies on JPA's version check and has no dedicated race test.
- Frontend does not use these endpoints yet.

### Limitations
- Strict JSON parsing (`fail-on-unknown-properties`) also applies when Hibernate reads stored JSONB. A future
  schema addition must be deployed with a migration or reader update before data containing it is written.
- Graph validation is deferred to Module 6; ACTIVE can be set on any structurally valid workflow.
- Service uses the Spring Data repository and entities directly (no separate port), and the controller's mapper reads entities.
- Search uses `LIKE '%x%'` without a trigram index; fine for a single-user dataset.
- Swagger UI is enabled in all profiles (localhost-only binding).

## Module 2: Database and Workflow Models
Status: **complete**, verified 2026-10-25.

Delivered: Flyway `V2__create_workflows`, `V3__create_workflow_executions`, `V4__create_node_executions`.
Domain records and enums: `WorkflowDefinition`, `NodeDefinition`, `EdgeDefinition`, `NodePosition`,
`EditorMetadata`, `Viewport`, `WorkflowStatus`, `ExecutionStatus`, `NodeExecutionStatus`, `ErrorDetails`.
JPA entities and Spring Data repositories. JSON Schema v1. TypeScript contract types.

### Test results
| Check | Result |
|---|---|
| Backend unit tests (surefire) | 24 passed (adds `WorkflowDefinitionJsonTest` 7, `ErrorDetailsTest` 7) |
| Backend integration tests (Testcontainers, empty `postgres:16-alpine`) | 23 passed: `MigrationIT` 8, `WorkflowRepositoryIT` 5, `ExecutionRepositoryIT` 7, `FlowForgeApplicationIT` 3 |
| Frontend `npm test` | 10 passed (adds `definition.test.ts` 3); typecheck, lint, build clean |
| `docker compose --profile app up --build` on the existing Module 1 volume | V2–V4 applied on top of V1; Hibernate `validate` passed on Temurin 21; `/api/v1/health` UP |

Covered: migrations on an empty DB, check constraints, FK cascades, entity persistence, JSONB round trips
(definition, snapshot, arbitrary node input/output incl. nulls), stored JSON valid against the schema, optimistic
locking conflict, retention delete with cascade, 1 MiB caps.

Bugs found and fixed during testing: definition-shape CHECKs passed when `schemaVersion` was missing (NULL result);
global `non_null` Jackson inclusion dropped `null` values inside node configuration; credential redaction missed
`Authorization=Bearer <token>`.

### Not verified
- Performance with large volumes of executions (no load test); retention scheduling does not exist yet.
- Graph-level validation (not in scope).

## Module 1: Project Foundation
Status: **complete**, verified 2026-10-25 (macOS, JDK 26 compiling with `--release 21`, Node 26, Docker 29).

### Test results
| Check | Result |
|---|---|
| Backend unit tests (`mvn verify`, surefire) | 10 passed |
| Backend integration test (`FlowForgeApplicationIT`, Testcontainers `postgres:16-alpine`) | 3 passed |
| Frontend `npm test` (Vitest) | 7 passed |
| Frontend `npm run typecheck`, `npm run build`, `npm run lint` | clean |
| `docker compose --profile app up --build`: all 3 services healthy | verified |
| `GET http://127.0.0.1:8080/api/v1/health` and via frontend proxy | `200`, database `UP` |
| UI loaded in browser, shows "Backend connected" | verified |

### Not verified
- Runtime on a real JDK 21 (built and tested with JDK 26 using `--release 21`; the Docker image builds and runs on Temurin 21).
- Behaviour with DB outage end-to-end (covered by unit tests only: `DatabaseHealthProbeTest`, `HealthControllerTest` 503 case).
- shadcn components were added by hand (`components.json` + `Button`), not via the shadcn CLI.
- Windows/Linux hosts.
