# Integration Notes

## Review updates — 26 October 2026
- **Editor requests**: capture `sessionId` when starting asynchronous work and check it before applying responses to the
  global editor store. IDs change on every load/reset, even when reopening the same workflow. See the save, validation,
  run, activation, and reload paths for examples.
- **Engine recovery**: implementations of `ExecutionPersistence` must support `abandon`, atomically closing unfinished
  node records along with an aborted execution. Database unavailability can still prevent recovery writes.
- **Review and verification**: see [Code review and fixes](CODE_REVIEW_2026-10-26.md).

## Extension points created in Module 1
- **Health**: implement `com.flowforge.application.HealthProbe` as a Spring bean to add a component to `/api/v1/health`.
- **Errors**: throw `ResourceNotFoundException` (404) or add `@ExceptionHandler`s to `GlobalExceptionHandler`; keep the `https://flowforge.local/problems/<code>` type scheme.
- **Migrations**: next free version is `V9__*.sql`. Never edit an applied migration.
- **Frontend**: add API functions in `src/lib/api`, features in `src/features/<name>`, routes in `src/routes.tsx`, lazy page imports in `src/lazyPages.tsx`, nav entries in `components/layout/Sidebar.tsx`.
- **CORS / config**: `FlowForgeProperties` (`flowforge.*`) is the place for new typed settings.

## Extension points created in Module 2
- **Repositories** (`com.flowforge.infrastructure.persistence`): `WorkflowRepository`, `WorkflowExecutionRepository`
  (incl. `deleteFinishedBefore` for retention), `NodeExecutionRepository`. Application services in later modules use
  these and map entities to API DTOs. Never return entities from controllers.
- **Workflow contract**: `WorkflowDefinition` + JSON Schema v1 (see `WORKFLOW_SCHEMA.md`). Any change must update
  the schema file, the Java records and `frontend/src/lib/workflow/definition.ts` together. The contract tests fail
  otherwise.
- **Execution records**: create with `WorkflowExecutionEntity.queued(id, workflow)` (snapshots the definition), then
  `markRunning` → `markSucceeded` / `markFailed(ErrorDetails)` / `markCancelled`. Node runs: `NodeExecutionEntity`
  → `markRunning(input)` → `markSucceeded(output)` / `markFailed` / `markSkipped`. Allocate `sequence` monotonically
  per execution.
- **Errors**: always go through `ErrorDetails.sanitized(code, message)` before persisting.
- **Optimistic locking**: `ObjectOptimisticLockingFailureException` on concurrent workflow/execution updates. The API
  module should map it to `409 Conflict` Problem Details.

## Extension points created in Module 3
- **`WorkflowService`** is the only place that mutates workflows. The execution module should load workflows through
  `get(id)` and snapshot via `WorkflowExecutionEntity.queued`. Reject execution of `ARCHIVED` workflows there.
- **Module 6 (graph validation)**: hook into `WorkflowService.create/update` next to `checkDefinitionSize`. Return
  `400 validation-failed` with `errors[]` (same shape as Bean Validation). Decide then whether `ACTIVE` requires a valid graph.
- **Frontend**: add workflow functions in `src/lib/api/workflows.ts` using `apiClient`. TS types are in
  `src/lib/workflow/definition.ts`. Send `lockVersion` on every update, and handle `409` by reloading.
- **Errors**: use `ConflictException`, `InvalidRequestException`, `PayloadTooLargeException`, `ResourceNotFoundException`
  (package `application`) from services; the global handler maps them.
- **Execution history cascade**: hard delete removes executions. Archive is the default so history survives.

## Extension points created in Module 4
- **Node types**: add to `node-types.v1.json` first, then `nodeTypes.ts` (icon, description) so the contract test passes. Bump
  `typeVersion` when a type's configuration schema changes incompatibly.
- **Adding a node type**: (1) `node-types.v1.json` entry with `defaultConfiguration`; (2) `$defs/<type>` in `node-config.v1.schema.json`;
  (3) fixtures in `node-config-fixtures.v1.json`; (4) a `NodeConfigDefinition` in `config/definitions.ts` and `nodeTypes.ts` entry. The contract
  tests fail until all agree.
- **Backend validation (Module 6)**: load `node-config.v1.schema.json`, validate each node's `configuration` against `$defs/<type>`, then apply the
  uniqueness rules listed in `WORKFLOW_SCHEMA.md`. Reuse the fixtures. Treat expressions as path references only.
- **Execution**: resolve `{{ path }}` against run data only; never evaluate text as code. HTTP node must add SSRF protection and enforce `timeoutSeconds`.
- **Execution display**: set `data.executionStatus` (a `NodeExecutionStatus`) on canvas nodes; `WorkflowNode` already renders it.
  `ExecutionPanel.tsx` is the mount point for run results.
- **Validation (Module 6)**: server `400` responses with `errors[]` are shown in the save error banner. Mapping errors to
  nodes on the canvas needs node ids in the error payload.
- **Run button**: add to `EditorToolbar.tsx`. Disable it while the editor is dirty or unsaved (`selectIsDirty`, `workflowId === null`)
  so executions always use a saved version.

## Extension points created in Module 6
- **Execution (next modules)**: call `GraphValidator.validate(snapshot)` before running and refuse invalid graphs; use
  `ValidationResult.executionOrder()` as the deterministic order. Inject `GraphValidator` (bean in `ValidationConfig`).
- **Loops / merge nodes**: the rules for `CYCLE_DETECTED` and `UNSUPPORTED_MERGE` live in `GraphValidator.checkCycles` and
  `checkConnectionCounts`. A future loop or merge node type should be marked in the catalog (e.g. a new `role`) and exempted there.
- **New rules**: add a `ValidationCode` (never rename existing ones), a check pass, unit tests in `GraphValidatorTest`, and a row
  in `API_CONTRACTS.md`. The editor shows any code generically.
- **Activation**: `PUT` with `status: ACTIVE` returns `422 workflow-invalid` with `issues`. A future Activate button should show
  them through the same Problems panel (`setValidation`).

## Extension points created in Module 7
- **Node implementations (Module 8)**: add one `@Component` per node type implementing `NodeExecutor` (see `ManualTriggerExecutor`).
  Contract: synchronous, stateless and thread-safe, honour thread interruption and `context.isCancelled()`, never evaluate config as
  code, return `NodeExecutionResult.success(output)` / `branch(output, "true"|"false")` / `failure(code, message)` or throw
  `NodeExecutionException(code, message)`. Override `timeout(node)` (HTTP: `timeoutSeconds` plus a small margin). Read upstream data
  from `request.input()`, earlier nodes from `context.outputOf(id)`, shared variables from `context.variables()`.
  All six catalog types now have executors; 501 only appears for types without one.
- **Test support**: integration tests use the real executors and `support/LocalHttpServer`; the test profile allows HTTP to
  localhost.
- **Execution UI (later)**: `POST /workflows/{id}/execute` → poll `GET /executions/{id}` → node statuses map to `WorkflowNode`'s
  `data.executionStatus`. A list endpoint per workflow is not implemented yet (`WorkflowExecutionRepository.findByWorkflowIdOrderByCreatedAtDesc` exists).
- **Expressions**: `ExecutionContext` exposes outputs by node id and `variables()`; the `{{ path }}` resolver belongs to Module 8.

## Extension points created in Module 8
- **New node types**: catalog + schema + fixtures + editor definition (see Module 5 notes) + a `NodeExecutor` bean in
  `infrastructure.nodes`. Resolve settings with `ExpressionResolver` and `ExpressionScope(input, context)`; report expression
  problems via `NodeConfig.resolving(...)` so they surface as `EXPRESSION_ERROR` with the setting name.
- **Outbound calls from other nodes** must use the `workflowHttpClient` bean and `OutboundRequestPolicy.checkUrl` for every URL
  (including redirects); never create a separate client.
- **Credentials** (done in Module 12): nodes reference credentials by `credentialId`; there is deliberately no
  `{{ credentials.x }}` expression root, so a secret can never be copied into arbitrary fields, bodies or logs.
- **Execution UI**: `GET /executions/{id}` now has `logs`.

## Extension points created in Module 9
- **Retention**: history grows without bound until a scheduled job calls `WorkflowExecutionRepository.deleteFinishedBefore(cutoff)`
  (exists since Module 2, indexed, cascades to nodes and logs). The policy (age, per-workflow count) is not decided yet.
- **Live progress**: the UI polls (1 s). A server-sent-events stream could replace polling later without API changes to the
  resource shapes.
- **Redaction rules**: `PayloadRedactor.SENSITIVE_KEY` is the single place to extend. Add a unit test per new key.
- **Re-run / compare / export** are not implemented; `workflow_snapshot` already contains everything needed.

## Extension points created in Module 10
- **New execution events**: add a `Type` to `ExecutionEvent`, publish it from `ExecutionEngine` *after* the persistence call, mirror
  the name in `frontend/src/features/executions/liveState.ts` (`EVENT_NAMES`, `applyEvent`) and add tests on both sides.
- **Scaling out**: implement `ExecutionEventSink` on a shared bus and make `ExecutionEventBroadcaster` consume from it; the SSE
  endpoint and the client need no changes (see the limitations list in `ARCHITECTURE.md`).
- **Other consumers** (notifications, webhooks, metrics) can implement `ExecutionEventSink`; compose them with a fan-out sink.
- **Run-level events not tied to an execution** (e.g. workflow activated) are out of scope and need their own stream.

## Extension points created in Module 11
- **`TriggerSynchronizer`** (application port) is called in every workflow write transaction. New trigger kinds (e.g. a queue
  consumer) add a registration table and extend `TriggerRegistrationService.synchronize`; nothing else in `WorkflowService` changes.
- **`WorkflowExecutionService`** now has `prepare` / `enqueueScheduled` / `dispatch` / `startTriggered(workflowId, TriggerType, input)`
  besides `start`; any automatic trigger should use them so validation, snapshots, capacity and events stay identical.
- **`TriggerType`** on executions (`MANUAL`, `WEBHOOK`, `SCHEDULE`; DB check constraint) and `scheduled_for`. Adding a type needs a
  migration of `chk_workflow_executions_trigger_type`.
- **Webhook secrets** are encrypted at rest since Module 12 (V8); the API (`/triggers/webhook/secret`, `/rotate`) is unchanged.
- **Authentication module**: `/api/v1/hooks/**` must stay outside user authentication (it has its own), everything else under
  `/api/v1` is the management API to protect. Webhook settings show in the UI via `GET /workflows/{id}/triggers`.
- **Multi-instance**: replace `ReplayGuard` with a shared store and add leader election or keep `SKIP LOCKED` polling on every
  instance (already safe against duplicates).
- Frontend: `lib/api/triggers.ts`, `features/editor/TriggerPanel.tsx` (status panel under trigger settings), `triggers.ts`
  (query key, curl example), `config/cron.ts` (editor cron/time zone checks), activation toggle in `EditorToolbar`.

## Extension points created in Module 12
- **`OwnerContext`** (application.security): replace `LocalOwnerContext` with an authenticated implementation. Credentials are
  already owner-scoped; add owner filters to workflow queries and executions when users arrive. Background runs must keep using
  the workflow's owner (`ExecutionJob.ownerId`).
- **New credential types**: add to `credential-types.v1.json` and `credentialTypes.ts` (contract test), extend the DB check
  `chk_credentials_type`, and teach the consuming node how to apply it (see `HttpRequestExecutor.withCredential`). Always
  `context.secrets().register(...)` every value sent.
- **Other nodes using credentials**: depend on `CredentialResolver`, resolve with `request.context().ownerId()`, and add the node
  type to the credential type's `usableBy`.
- **`SecretCipher`** port: a KMS/HSM-backed implementation can replace `AesGcmSecretCipher` (same `Sealed` shape).
- **`CredentialReferences`** port (domain.validation): validation of `credentialId` references.
- **Webhook secrets** are encrypted with the same keyring (`WebhookSecretStore`); anything that needs a webhook secret must go
  through `reveal`/`store`, never the entity's columns. Key rotation (`re-encrypt`) covers credentials and webhook secrets.
- **Authentication module**: keep `RequestGuardFilter` (Host/Origin rules stay useful), add real authentication in front of the
  management API, leave `/api/v1/hooks/**` to its own signature/token authentication.

## Extension points created in Module 14
- **New pages**: add the route in `routes.tsx` (lazy in `lazyPages.tsx`), a `Sidebar` item and a case in
  `components/layout/sections.ts` (breadcrumb and tab title).
- **New dashboard action**: add it to `WorkflowAction`/`menuActions` (`workflowStatus.ts`), `MENU` (`WorkflowCard.tsx`) and
  `useWorkflowActions.ts`; confirmations use `ConfirmDialog`.
- **More server settings** in `GET /settings`: add a record to `SettingsService` and a row in `SettingsPage`; never put secrets there.
- **Multi-user**: preferences are per browser today; per-user preferences would move to an API behind `OwnerContext`.
- **Dark theme**: add a second token set under `.dark` in `index.css` and replace the remaining hard-coded colours in the editor
  canvas; components already use tokens.
- The list does not filter by trigger type or last run result yet (`lastExecution` is display-only); a server-side filter would need
  an indexed query.

## Extension points created in Module 13
- **`ChatProvider`** (application.ai): implement as a Spring bean with a new `id()` for providers whose wire format differs
  (Anthropic Messages API, Gemini, ...), add the id to the `provider` enum in `node-config.v1.schema.json` and
  `AI_PROVIDERS` in the editor; retries, JSON validation, limits and secret masking come from `AiChatService` and the executor.
- **`CredentialHeaders`**: the shared way an HTTP node turns a credential into a header (used by HTTP Request and AI Model).
- **`ResponseSchemaPolicy` / `AiOutputParser`**: reusable for any node that validates machine-readable output; the editor port
  and the shared fixtures keep the two sides equal.
- **Samples**: add a file under `backend/src/main/resources/samples/` and list it in `SampleWorkflowService`; samples must not
  contain credentials.
- **Agents / tools / streaming** are deliberately absent: the node is one request and one answer. Tool calling would extend
  `ChatRequest`/`ChatResult` and needs its own safety design (which nodes a model may trigger).
- Usage and cost are in the node output (`usage`), so a later metering module can aggregate `node_executions.output` or consume
  an event; nothing is stored separately yet.

## Integration contracts verified in Module 15
- **REST contract**: `docs/api/openapi.json` is generated from the backend; `OpenApiContractIT` (backend) and
  `src/lib/api/apiContract.test.ts` (frontend: call paths, response/request fields via `satisfies` key lists, status enums) both
  test against it. Change the API → regenerate the snapshot (`UPDATE_OPENAPI_SNAPSHOT=true`), review, update the client.
  Every operation documents its success response explicitly (springdoc omits the inferred 2xx schema once an operation or its
  controller declares any `@ApiResponse`).
- **Node types and configuration**: `node-types.v1.json`, `node-config.v1.schema.json` and `node-config-fixtures.v1.json` are
  shared files tested by both sides (`nodeTypes.test.ts`, `configContract.test.ts`, `NodeTypeCatalogTest`,
  `NodeConfigSchemaTest`).
- **Migrations**: `MigrationChecksumTest` pins V1–V8 (released files are immutable; new changes go to V9+), `MigrationIT` checks
  the schema, Hibernate validates the mappings at start.
- **End to end**: `scripts/e2e.sh` + `e2e/` run the real images with a mock upstream (`infra/docker-compose.e2e.yml`). New node
  types or triggers should add a scenario there; the mock (`e2e/mock-services/server.mjs`) has echo, status, delay and an
  OpenAI-compatible endpoint with `[[malformed]]`, `[[rate-limit]]` and `[[fail]]` markers.
- **Shutdown**: `spring.lifecycle.timeout-per-shutdown-phase` (12 s) bounds the execution drain; keep two phases within the
  compose `stop_grace_period` (30 s).
- **Maven repositories**: `backend/.mvn/settings.xml` mirrors every repository to Maven Central (Flyway's parent POM declares
  third-party repositories). A dependency that is not on Central needs an explicit, reviewed exception there.

## Still to build (not part of the MVP)
- Authentication and tenancy: fill `OwnerContext` from an authenticated principal and filter workflows by owner (credentials
  already are). See `PRODUCTION_READINESS.md`.
- A durable execution queue and scheduler leadership for more than one backend instance; an execution-history retention job.

## Known limitations
- `V1__baseline.sql` is a no-op (`SELECT 1`).
- The database accepts any definition with the right top-level shape; graph rules are enforced by the validation service
  (activation, saving an active workflow, `/validate`, every run).
- Payload caps are enforced only by DB constraints (they cause a `DataIntegrityViolationException`). Truncation is
  the execution module's job.
- Workflows and credentials carry `owner_id` (V7, constant `local`); only credential lookups are owner-scoped so far.
- Deleting a workflow cascades to its execution history.
- Execution is single-instance: running executions live in one JVM, and startup marks leftovers as interrupted. Running several
  backends on one database would need a distributed queue/lease first.
- Outbound HTTP is denied until `FLOWFORGE_HTTP_ALLOWED_HOSTS` is set. Inside Docker, other containers resolve to private
  addresses and are blocked unless `FLOWFORGE_HTTP_ALLOW_PRIVATE_ADDRESSES=true` (development only).
- Live updates are in-memory and single-instance (see `ARCHITECTURE.md`); the UI falls back to polling when SSE is unavailable.
- History is not pruned automatically, and the executions list has no text search or date filter.
- Expressions can reference nodes only by identifier-like ids (`nodes.<id>`), not by display name.
- No per-item (array) iteration: each node processes one JSON value.
- A node thread stuck in non-interruptible code keeps running after a timeout or cancel (the execution still ends). Executors
  must use interruptible I/O and their own timeouts.
- No retries, continue-on-failure, partial re-runs or per-item (array) execution yet.
- Saving (`POST`/`PUT`) still accepts invalid graphs and configurations for drafts and inactive workflows. Activation, saving an
  active workflow and the validate endpoint enforce graph rules.
- Triggers (Module 11): single scheduler instance, 5 s polling granularity, in-memory webhook replay cache (webhook secrets are
  encrypted since V8), no rate limiting on the webhook endpoint (size and auth checks only); see `ARCHITECTURE.md`. Webhooks are reachable from
  this machine only with the default bindings.
- Joining branches (merge) and loops are rejected by design until dedicated node types exist.
- JSON values come back from PostgreSQL JSONB without key order, so object keys in a saved JSON body or test input may be re-ordered.
- `@hookform/resolvers` is not used (its optional peer tree conflicts with npm here); `config/zodResolver.ts` is a small replacement.
- The editor has no copy/paste, multi-level grouping or auto-layout (graph problems are shown from the backend validation).
- `window.confirm` is used for the unsaved-changes and reload prompts (no custom dialog component yet).
- No authentication on the management API; the trust boundary is the local machine (see `ARCHITECTURE.md`, "Trust
  boundary"). Webhook deliveries authenticate with a signature or token.
- AI Model (Module 13): one request/response per node (no streaming, tools, images, embeddings, conversation memory or agents);
  only the OpenAI chat-completions wire format; `max_tokens` is the only output limit parameter; costs only when the provider
  returns them; no cross-run rate limiting or budget; the prompt is stored in the workflow and in node input history; prompt
  injection through inserted data is mitigated, not preventable.
- Credentials (Module 12): only HTTP credential types (bearer, basic, API key header); no OAuth flows; secrets shorter than 8
  characters are refused (value masking needs a minimum length); losing the keyring loses all stored secrets (credentials and
  webhook secrets: webhooks then answer 503 until their secret is rotated); workflows are owner-tagged but not owner-filtered yet.
- Testcontainers 1.x needs `backend/src/test/resources/docker-java.properties` (`api.version=1.44`) with Docker Engine 29+; on macOS Docker Desktop also set `DOCKER_HOST` (see README).
- `infra/.env` and `e2e/.e2e.env` are local-only and git-ignored; the repo is not yet a git repository.
- Runs interrupted by a restart are not resumed; they are marked `EXECUTION_INTERRUPTED` (by the worker, or by startup recovery
  when the connection pool is already closed) and must be started again.
