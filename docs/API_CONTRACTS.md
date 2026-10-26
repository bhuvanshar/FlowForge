# API Contracts

Base path: `/api/v1`. JSON. Errors are Problem Details (`application/problem+json`).

**Machine-readable contract:** [`docs/api/openapi.json`](api/openapi.json) is the OpenAPI document generated from the running
backend (Module 15). `OpenApiContractIT` fails when the backend drifts from it, and the frontend's `apiContract.test.ts` checks
every client call path, response/request field and status enum against it, so an API change must update both sides in one step.
This file explains semantics the OpenAPI document cannot express.

## JSON conventions
- Absent optional properties are omitted (not written as `null`); `null` entries inside free-form maps are kept.
- Timestamps are ISO-8601 UTC instants.
- Workflow/execution resources will use the shapes in `docs/WORKFLOW_SCHEMA.md` and the status enums below.
  Module 2 added no endpoints; Module 3 adds the workflow endpoints below.

| Enum | Values |
|---|---|
| `WorkflowStatus` | `DRAFT`, `ACTIVE`, `INACTIVE`, `ARCHIVED` |
| `ExecutionStatus` | `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED` |
| `NodeExecutionStatus` | `PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED`, `SKIPPED` |

## Workflows (`/api/v1/workflows`)

Interactive docs while the backend runs: `http://127.0.0.1:8080/swagger-ui.html`; machine-readable:
`http://127.0.0.1:8080/v3/api-docs`.

| Method and path | Success | Description |
|---|---|---|
| `POST /workflows` | `201` + `Location` | Create a workflow (`DRAFT`, `version` 1). |
| `GET /workflows` | `200` | Paginated list of summaries. |
| `GET /workflows/{id}` | `200` | One workflow with its definition. Archived workflows are still returned. |
| `PUT /workflows/{id}` | `200` | Replace name, description, optional status and definition. Needs `lockVersion`. |
| `DELETE /workflows/{id}` | `204` | Soft delete (archive). `?permanent=true` hard-deletes an already archived workflow. |
| `POST /workflows/{id}/duplicate` | `201` + `Location` | Copy into a new `DRAFT`. Body optional. |
| `POST /workflows/{id}/validate` | `200` | Validate the stored or a submitted definition (see Validation). |

### Resources
`WorkflowResponse`: `id`, `name`, `description?`, `status`, `version`, `lockVersion`, `definition` (see
`WORKFLOW_SCHEMA.md`), `createdAt`, `updatedAt`.
`WorkflowSummaryResponse` (list items): same fields without `definition`, plus `nodeCount`, `edgeCount` and (Module 14) the optional
`lastExecution` `{ id, status, triggerType, createdAt, finishedAt? }`: the newest run of any kind, omitted for workflows that never
ran, without `finishedAt` while queued or running. It costs one extra query per page, whatever its size. Additive: clients that
ignore it keep working.
Page envelope: `{ "items": [...], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0 }`.

Requests:
- Create: `{ "name": "required, 1..200", "description": "optional, max 2000", "definition": optional (default: empty) }`
- Update: `{ "name", "description?", "status?", "definition", "lockVersion" }` (`definition` and `lockVersion` required)
- Duplicate: optional `{ "name": "max 200" }`, default `"<original> (copy)"`

Unknown JSON properties are rejected with `400`. The definition is checked structurally (types, id/handle patterns,
limits; see schema). Graph rules (unique ids, edge endpoints, cycles, node types) arrive with Module 6.
The compact JSON definition may not exceed 512 KiB (`413`).

### List parameters
| Parameter | Default | Notes |
|---|---|---|
| `name` | none | case-insensitive substring; `%` and `_` are literal |
| `status` | none | repeatable (`status=ACTIVE&status=DRAFT`). Without it, `ARCHIVED` workflows are hidden |
| `page` | `0` | zero-based |
| `size` | `20` | 1..100 |
| `sort` | `updatedAt,desc` | `name`, `status`, `version`, `createdAt`, `updatedAt` plus `,asc` or `,desc`. `name` sorts case-insensitively. `id` is a hidden tie-breaker |

### Versions and optimistic locking
- `version`: definition version. Starts at 1 and increases by 1 only when an update actually changes the definition.
- `lockVersion`: concurrency token, increased on every persisted change (rename, status change, definition change,
  archive). Send back the value from your last read in `PUT`. A mismatch returns `409`, and nothing is written.
  A request that changes nothing does not bump either number.
- Typical flow: `GET` → edit → `PUT` with the `lockVersion` you got. On `409`, reload, merge, retry.

### Delete semantics (soft vs hard)
- `DELETE /workflows/{id}` archives (`status=ARCHIVED`). Idempotent. Execution history is kept.
- Archived workflows are hidden from the default list (use `status=ARCHIVED`), stay readable by id and cannot be
  edited (`409`), except that `PUT` with a non-`ARCHIVED` `status` restores them.
- `DELETE /workflows/{id}?permanent=true` removes the row and, via cascade, its executions and node executions.
  Allowed only when already archived (`409` otherwise). It is irreversible.

### Errors
`400` validation (`validation-failed` with `errors[]`) or invalid request (`invalid-request`); `404` `not-found`;
`409` `conflict` (stale `lockVersion`, archived, permanent delete of a non-archived workflow) or
`concurrent-modification` (lost a race); `413` `payload-too-large`; `422` `workflow-invalid` (activation or execution of an invalid graph, with `issues`); `501` `node-type-not-executable`; `503` `execution-capacity`; `500` `internal-error`.

## Validation (`POST /api/v1/workflows/{id}/validate`)

Validates a workflow graph without executing it. Body is optional:
`{ "definition": <WorkflowDefinition> }` validates that definition (e.g. the editor's unsaved canvas); no body validates the
stored definition. Nothing is written. `200` for any existing workflow (check `valid`), `404` if the workflow does not exist,
`400` if the body is not a structurally valid definition (unknown properties, bad id patterns).

```json
{
  "valid": false,
  "errorCount": 2,
  "warningCount": 1,
  "issues": [
    { "code": "CYCLE_DETECTED", "severity": "ERROR", "message": "Loop detected: 'Notify' → 'Log' → 'Notify'. Loops are not supported yet.",
      "nodeId": "h", "edgeId": "e4", "path": "edges[3]" },
    { "code": "CONFIGURATION_INVALID", "severity": "ERROR", "message": "'Notify': url: must start with http://, https:// or a {{ }} reference",
      "nodeId": "h", "path": "nodes[2].configuration.url" },
    { "code": "BRANCH_NOT_CONNECTED", "severity": "WARNING", "message": "The 'false' branch of 'Big order?' is not connected; nothing runs when it is taken.",
      "nodeId": "c", "path": "nodes[1]" }
  ],
  "executionOrder": []
}
```
- `nodeId`, `edgeId` and `path` are omitted when not applicable. `path` points into the definition JSON.
- `severity`: `ERROR` makes the workflow invalid; `WARNING` does not.
- `executionOrder`: ids of nodes reachable from the trigger in topological order (ties by definition order); empty when there is
  no trigger or a cycle.
- Issue order is deterministic: by validation pass (size, nodes, edges, connection counts, triggers, branches, cycles, connectivity,
  configuration), then by position in the definition.

### Issue codes (stable)
| Code | Severity | Meaning (ids set) |
|---|---|---|
| `TOO_MANY_NODES` / `TOO_MANY_EDGES` | ERROR | over 500 nodes / 2000 edges |
| `TOO_MANY_OUTGOING_EDGES` | ERROR | more than 20 edges leave one output handle (node) |
| `DUPLICATE_NODE_ID` / `DUPLICATE_EDGE_ID` | ERROR | id reused (node / edge) |
| `DUPLICATE_NODE_NAME` | ERROR | display name reused (node) |
| `UNKNOWN_NODE_TYPE` / `UNSUPPORTED_NODE_TYPE_VERSION` | ERROR | type or `typeVersion` not in the catalog (node) |
| `CONFIGURATION_INVALID` | ERROR | configuration violates its schema or uniqueness rules (node; path to the field) |
| `EDGE_SOURCE_NOT_FOUND` / `EDGE_TARGET_NOT_FOUND` | ERROR | edge references a missing node (edge) |
| `SELF_LOOP` | ERROR | node connected to itself (node, edge) |
| `INVALID_SOURCE_HANDLE` / `INVALID_TARGET_HANDLE` | ERROR | handle not declared by the node type; triggers have no inputs (node, edge) |
| `DUPLICATE_CONNECTION` | ERROR | same source/handle/target/handle twice (node, edge) |
| `UNSUPPORTED_MERGE` | ERROR | second incoming edge on one input; joining branches is not supported yet (node, edge) |
| `NO_TRIGGER` / `MULTIPLE_TRIGGERS` | ERROR | not exactly one trigger (extra triggers: node) |
| `CYCLE_DETECTED` | ERROR | loop; reported once per closing edge (node, edge) |
| `DISCONNECTED_NODE` | ERROR | non-trigger node without any connection (node) |
| `UNREACHABLE_NODE` | ERROR | connected but not reachable from the trigger (node) |
| `NO_BRANCH_CONNECTED` | ERROR | Condition with neither `true` nor `false` connected (node) |
| `BRANCH_NOT_CONNECTED` | WARNING | one Condition branch unconnected (node) |
| `TRIGGER_NOT_CONNECTED` | WARNING | trigger has no outgoing edge (node) |

### Active workflows must have a valid graph
Whenever the resulting status is `ACTIVE`, the definition is validated: `PUT /workflows/{id}` that activates **or saves an
already active workflow**, and `POST /workflows/{id}/activate` (which validates the saved definition). With errors the response is
`422` (`type .../problems/workflow-invalid`) with the same `issues` array as an extension, and nothing is written. (Module 11
change: before, an already active workflow could be saved in any state. An ACTIVE workflow now fires automatically, so to save an
incomplete graph, deactivate it in the same `PUT` with `"status": "INACTIVE"`.) Drafts and inactive workflows can be saved in any
state.

## Triggers (Module 11)

A workflow is **active** (`status: ACTIVE`) exactly while its automatic trigger is registered: a webhook accepts deliveries and a
schedule fires. Manual runs (`/execute`) work for any non-archived workflow regardless of status.

### `POST /api/v1/workflows/{id}/activate` and `/deactivate`
No body. `200` with the workflow (new `status` and `lockVersion`; the definition and `version` are unchanged). Both are
idempotent. Activate validates the **saved** definition (`422` with `issues`, see above); both answer `409` for archived workflows.
Deactivating sets `INACTIVE`; runs already started continue. Archiving or permanently deleting a workflow also removes its
triggers from service. `PUT` with a `status` change has the same effect on triggers.

### `GET /api/v1/workflows/{id}/triggers`
```json
{
  "workflowId": "…", "status": "ACTIVE", "triggerType": "core.webhookTrigger",
  "webhook": { "webhookId": "8r7coBzNryYBA2QPVMLp_orCDmORydKE", "path": "/api/v1/hooks/8r7coBzNryYBA2QPVMLp_orCDmORydKE",
               "nodeId": "t", "method": "POST", "authentication": "signature", "inputMode": "body", "active": true,
               "secretHint": "…M7tg" },
  "schedule": { "nodeId": "t", "cron": "0 9 * * MON-FRI", "timezone": "Europe/Berlin", "missedRunPolicy": "skip",
                "active": true, "nextFireAt": "…", "lastFireAt": "…", "lastExecutionId": "…", "lastMissedAt": "…",
                "lastError": "…" }
}
```
`webhook` is present when the **saved** definition has a webhook trigger (the registration is created on save, so the URL is known
before activation), `schedule` when it has a schedule trigger. Absent fields are omitted (`nextFireAt` only while active). The
secret itself is never part of this response.

### Webhook secret
- `GET /api/v1/workflows/{id}/triggers/webhook/secret` → `{ "secret": "…" }` (43 URL-safe characters, 256 bits).
- `POST /api/v1/workflows/{id}/triggers/webhook/secret/rotate` → `{ "secret": "<new>" }`; the old secret stops working at once.

Both send `Cache-Control: no-store`; `404` when the saved definition has no webhook trigger; `503 secret-unavailable` when the
stored secret cannot be decrypted (key missing or data altered). The webhook id and secret are generated by the server, kept
across deactivate/activate and edits, and are new for a duplicated workflow. At rest the secret is encrypted with the credential
keyring (Module 12); `secretHint` is `…????` when it cannot be decrypted.

### `/api/v1/hooks/{webhookId}` (the webhook endpoint)
Methods GET, POST, PUT, PATCH, DELETE; the trigger's `method` must match. Checks, first failure wins:

| Check | Failure |
|---|---|
| Body ≤ 256 KiB (`flowforge.triggers.webhook-max-body-bytes`; by `Content-Length`, then while reading) | `413` `payload-too-large` |
| Registration exists and is active | `404` `not-found` (unknown, inactive, archived and deleted look the same) |
| Method matches | `405` `method-not-allowed` + `Allow` |
| Authentication (below) | `401` `unauthorized` (`WWW-Authenticate: Bearer realm="flowforge-webhook"` for token triggers) |
| Signed delivery not seen before | `409` `webhook-replayed`; `429` `too-many-requests` + `Retry-After` if the replay cache is full |
| (stored secret decryptable) | `503` `webhook-unavailable`, checked when authentication needs the secret; no details |
| Non-empty body is JSON (`application/json` or `application/*+json`) | `415` `unsupported-media-type`; malformed JSON `400` `invalid-request` |

Then the run is created exactly like a manual one (`422`/`501`/`503` as for `/execute` in the unlikely case the active workflow no
longer runs) and the response is `202`, `Location: /api/v1/executions/{executionId}`, body
`{ "executionId", "workflowId", "status": "QUEUED" }`. The workflow runs asynchronously; the caller does not get its result.

**Authentication** (`configuration.authentication` of the trigger):
- `signature` (default): headers `X-FlowForge-Timestamp: <Unix seconds>` and
  `X-FlowForge-Signature: sha256=<hex(HMAC-SHA256(secret, timestamp + "." + rawBody))>`. The timestamp must be within ±300 s of the
  server clock (`signature-tolerance`), and each signature is accepted once while its timestamp is in the window.
- `token`: `Authorization: Bearer <secret>` or `X-FlowForge-Token: <secret>`. No replay protection (use `signature` for that).
- `none`: only when the server sets `FLOWFORGE_WEBHOOKS_ALLOW_UNAUTHENTICATED=true`; otherwise activation fails validation and
  deliveries get `401`. Secrets are compared in constant time.

**Trigger input** (`configuration.inputMode`): `body` → the parsed JSON body (`{}` when empty); `request` →
`{ method, path, query: {name: value | [values]}, headers: {lower-case name: value | [values]}, body, receivedAt }` where
`Authorization`, `Proxy-Authorization`, `Cookie`, `X-FlowForge-Signature` and `X-FlowForge-Token` are always removed (max 50
headers, values cut at 1024 chars). Nodes read it as `{{ trigger.body.… }}`.

### Schedules
A schedule trigger fires at each occurrence of its cron expression in its time zone (see `docs/ARCHITECTURE.md` for the
scheduler). Each scheduled run has `triggerType: "SCHEDULE"` and `scheduledFor` (the occurrence; unique per workflow) and gets the
trigger input `{ "scheduledFor": "…", "firedAt": "…", "timezone": "…", "cron": "…", "late": false }`.

## Samples (Module 13)

| Method | Path | Body | Result |
|---|---|---|---|
| `GET` | `/samples` | – | `[{ id, name, description }]` |
| `POST` | `/samples/{id}/create` | optional `{ credentialId }` | `201` `Workflow` (DRAFT, version 1), `Location`; `404` unknown sample; `400` malformed `credentialId` |

Currently one sample, `ai-review-triage`: **Manual Trigger → Set Variables → AI Model → Condition → Logger** (`true` branch: a
Logger that escalates, `false` branch: a Logger that records "no action"). It classifies a customer review into
`{ sentiment, urgency, summary }` (JSON schema), branches on `json.sentiment == "negative"` and logs the summary. Nothing is
executed or activated; `credentialId`, when given, is stored on the AI Model node (only its id), otherwise pick one in the editor.
Before running: store a credential for the provider's host and allow that host in `FLOWFORGE_HTTP_ALLOWED_HOSTS`.

## Credentials (Module 12)

Secret values are **write-only**: they are accepted by create and rotate and are never returned by any endpoint (all responses
of `/api/v1/credentials/**` also carry `Cache-Control: no-store`). Everything is scoped to the current owner; another owner's
credential is a `404`.

| Method | Path | Body | Result |
|---|---|---|---|
| `GET` | `/credentials[?type=httpBearer]` | – | `[Credential]` sorted by name |
| `POST` | `/credentials` | `{ type, name, description?, allowedHosts, fields?, secret }` | `201` `Credential`, `Location` |
| `GET` | `/credentials/{id}` | – | `Credential` |
| `PUT` | `/credentials/{id}` | `{ name, description?, allowedHosts, fields?, lockVersion }` (no secret) | `Credential`; `409` stale `lockVersion` |
| `POST` | `/credentials/{id}/rotate` | `{ secret, lockVersion? }` | `Credential` with `secretVersion + 1`, `rotatedAt` |
| `GET` | `/credentials/{id}/usage` | – | `{ workflows: [{ id, name, status }] }` |
| `DELETE` | `/credentials/{id}` | – | `204`; `409 credential-in-use` with `workflows` while referenced |
| `GET` | `/credentials/status` | – | `{ available, activeKeyId?, credentialsOnOldKeys, webhookSecretsToEncrypt }` |
| `POST` | `/credentials/re-encrypt` | – | `{ reEncrypted, webhookSecrets, activeKeyId }`: key rotation; re-encrypts credentials and webhook secrets on older keys and encrypts plaintext webhook secrets |

```json
{ "id": "…", "name": "CRM", "type": "httpBasic", "description": "…", "allowedHosts": ["crm.example.com"],
  "fields": { "username": "ada" }, "secretFields": ["password"], "secretVersion": 2,
  "createdAt": "…", "updatedAt": "…", "rotatedAt": "…", "lastUsedAt": "…", "lockVersion": 3 }
```
Validation errors are `400 validation-failed` with `errors[].field` such as `name`, `allowedHosts[0]`, `fields.username`,
`secret.password` (messages never contain the submitted values). Duplicate names (case-insensitive) are `409`. Without an
encryption key on the server, create/rotate/re-encrypt answer `503 credentials-unavailable` and the frontend disables them.

## Executions

### `POST /api/v1/workflows/{id}/execute`
Runs the **saved** workflow asynchronously (unsaved editor changes are not included). Optional body:
`{ "input": <any JSON> }`. Without it, the trigger's `configuration.testInput` is used, or `{}`.

Synchronously, before anything runs: load (404), reject archived (409), validate (422 `workflow-invalid` with `issues`), plan,
check every node type has an executor (501 `node-type-not-executable` with `nodeTypes`), check input size (413, 512 KiB),
snapshot the definition and create the execution with one `PENDING` row per node, then queue it (503 `execution-capacity` when
the queue is full).

`202 Accepted`, `Location: /api/v1/executions/{executionId}`:
```json
{ "executionId": "…", "workflowId": "…", "status": "QUEUED" }
```

### `GET /api/v1/executions/{executionId}`
```json
{
  "id": "…", "workflowId": "…", "workflowVersion": 3, "status": "FAILED", "triggerType": "MANUAL",
  "error": { "code": "HTTP_STATUS_ERROR", "message": "Node 'call' failed: Server answered 503" },
  "createdAt": "…", "startedAt": "…", "finishedAt": "…",
  "nodes": [
    { "nodeId": "t", "nodeType": "core.manualTrigger", "sequence": 0, "status": "SUCCEEDED",
      "input": { "order": 7 }, "output": { "order": 7 }, "startedAt": "…", "finishedAt": "…" },
    { "nodeId": "call", "nodeType": "core.httpRequest", "sequence": 1, "status": "FAILED", "input": { "order": 7 },
      "error": { "code": "HTTP_STATUS_ERROR", "message": "Server answered 503" }, "startedAt": "…", "finishedAt": "…" },
    { "nodeId": "log", "nodeType": "core.logger", "sequence": 2, "status": "SKIPPED", "finishedAt": "…" }
  ]
}
```
`triggerType` is `MANUAL`, `WEBHOOK` or `SCHEDULE` (Module 11; also on history items). Scheduled runs add `scheduledFor`.
`logs` (always present) lists sanitized execution log lines in order: `{ "nodeId", "level", "message", "at" }` (written by
Logger nodes; at most 500 per execution). `nodes` are in plan order (`sequence`). A payload larger than `flowforge.execution.max-stored-payload-bytes` (256 KiB) is stored as
`{ "_truncated": true, "originalSizeBytes": n, "preview": "<first 2000 chars>" }`; downstream nodes still received the full value.

### `GET /api/v1/executions/{executionId}/events` (Server-Sent Events)
Live status changes of one execution. `Content-Type: text/event-stream`. The server writes fields without the optional space (`event:snapshot`, `data:{…}`); both forms are valid SSE. `404` (Problem Detail) for an unknown execution, `503`
when too many live connections are open (max 200 per server). Example (`event:` / `data:` lines abbreviated):
```
event: snapshot
data: {"executionId":"…","status":"RUNNING","startedAt":"…","durationMs":null,"nodes":[
        {"nodeId":"t","nodeName":"Start","sequence":0,"status":"SUCCEEDED","durationMs":4},
        {"nodeId":"call","nodeName":"Call API","sequence":1,"status":"RUNNING"},
        {"nodeId":"log","nodeName":"Log result","sequence":2,"status":"PENDING"}],"at":"…"}

id: 1
event: node.succeeded
data: {"seq":1,"type":"NODE_SUCCEEDED","executionId":"…","nodeId":"call","nodeName":"Call API","status":"SUCCEEDED","at":"…","durationMs":1240}
```
| Event | When | `status` | Extra fields |
|---|---|---|---|
| `snapshot` | always first, on every (re)connect | execution status | `error`, `startedAt`, `finishedAt`, `durationMs`, `nodes[]` (status, durationMs, error per node) |
| `execution.started` | the run began | `RUNNING` | |
| `node.started` / `node.succeeded` / `node.failed` / `node.skipped` | node transitions | node status | `nodeId`, `nodeName`, `durationMs` (succeeded/failed), `error` (failed) |
| `execution.completed` / `execution.failed` / `execution.cancelled` | terminal; the server then ends the stream | `SUCCEEDED` / `FAILED` / `CANCELLED` | `durationMs`, `error` (failed) |

- **The database is the source of truth.** Events are published only *after* the change is committed, carry statuses and timing
  but never node input/output, and are not stored. For results, call `GET /executions/{id}` (or `/nodes`).
- **Recovery protocol**: the server subscribes the connection to live events *first*, then reads the snapshot from the database,
  sends it, then forwards events. A client that connects late, reconnects after a drop, or missed events therefore starts from
  persisted state. `Last-Event-ID` is accepted but ignored: there is no replay. Clients must apply statuses monotonically
  (`PENDING` < `RUNNING` < `SUCCEEDED`/`FAILED`/`SKIPPED`), which makes duplicates and ordering races harmless.
- An execution that has already finished yields the snapshot only, then the stream ends.
- `seq` is the delivery order within the in-memory channel of this execution (starts at 1 and is not persisted); `id:` repeats it.
- A `: keepalive` comment is sent every 15 s (keeps proxies from timing out and detects dead clients). A stream lasts at most 30
  minutes; the browser's `EventSource` reconnects automatically and receives a fresh snapshot.
- A subscriber that cannot keep up (256 queued events) is dropped; its client reconnects and resynchronizes.
- Behind a reverse proxy, disable response buffering for this path (`proxy_buffering off`). The bundled nginx config does; the
  backend also sends `X-Accel-Buffering: no`.

### `GET /api/v1/workflows/{id}/executions`
Execution history of a workflow, newest first. Query: `status` (repeatable: `QUEUED RUNNING SUCCEEDED FAILED CANCELLED`),
`page` (default 0), `size` (default 20, max 100). `404` for an unknown workflow; archived workflows still list their history.
```json
{ "items": [ { "id": "…", "workflowId": "…", "workflowVersion": 3, "status": "FAILED",
    "error": { "code": "HTTP_STATUS_ERROR", "message": "Node 'Call API' failed: …" },
    "createdAt": "…", "startedAt": "…", "finishedAt": "…", "durationMs": 1250,
    "nodeCount": 3, "succeededNodes": 1, "failedNodes": 1, "skippedNodes": 1,
    "failedNodeId": "call", "failedNodeName": "Call API" } ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1 }
```
`durationMs` is absent until the execution has started and finished. `failedNodeId`/`failedNodeName` are absent when no node failed.

### `GET /api/v1/executions/{executionId}/nodes`
`{ "executionId": "…", "nodes": [ NodeRun … ] }`: the node runs of one execution in execution order, optionally filtered by
`status` (`PENDING RUNNING SUCCEEDED FAILED SKIPPED`, repeatable). Same `NodeRun` objects as in `GET /executions/{id}`.
`NodeRun` = `nodeId`, `nodeName` (the name in the snapshot that execution ran, unaffected by later edits), `nodeType`,
`sequence`, `status`, `input`, `output`, `error {code,message}`, `startedAt`, `finishedAt`, `durationMs`.
`GET /executions/{id}` additionally has `durationMs` at the top level.

### History guarantees
- **Snapshots**: every execution stores the exact workflow definition and version it ran (`workflow_executions.workflow_snapshot`,
  `workflow_version`), so history, node names and results do not change when the workflow is edited, archived or restored.
  Permanently deleting a workflow deletes its history.
- **Redaction (stored data)**: before persisting node input/output, values under keys containing `authorization`, `password`,
  `secret`, `token`, `api key`, `cookie`, `credential`, `private key` or `signature` become `"[REDACTED]"`, and `Bearer …` /
  `Basic …` strings are masked. Downstream nodes still receive the real values in memory. The HTTP node additionally redacts
  sensitive headers and query parameters. Error messages and log lines are sanitized (credentials, control characters).
  Matching is by key name, so a harmless field like `tokenCount` is also masked.
- **Size limits**: a stored input/output over 256 KiB becomes `{ "_truncated": true, "originalSizeBytes": n, "preview": "…" }`;
  at most 500 log lines per execution, each at most 2000 characters; error messages at most 2000 characters.

### `POST /api/v1/executions/{executionId}/cancel`
`202` with the current execution (it may still show `QUEUED`/`RUNNING`: cancellation is asynchronous); `409` if already finished;
`404` if unknown. The running node is interrupted and recorded `FAILED` with code `CANCELLED`, the rest `SKIPPED`, the execution
`CANCELLED`.

### Execution semantics (MVP)
- Sequential: one node at a time in plan order (topological, ties by definition position). Several executions may run in
  parallel (`flowforge.execution.worker-threads`, default 2).
- Data: the trigger outputs the execution input. Every other node gets its single parent's output as `input`. Executors may
  also read any earlier node's output and shared variables through the execution context.
- Branches: a Condition activates `true` or `false`. Nodes behind the other handle, and everything below them, are `SKIPPED`.
  Taking an unconnected branch simply ends that path. The execution still succeeds.
- Multiple incoming edges (joins) and loops are rejected before execution (validation and planner); they are never executed
  with guessed semantics.
- Failure: the first failing node ends the execution. Not-yet-run nodes become `SKIPPED`; the execution is `FAILED` with the node's
  error code and message `Node '<name>' failed: …`. There is no retry or continue-on-failure yet.
- Timeouts: per node (`node-timeout`, default 30 s, executors may override) → `NODE_TIMEOUT`; per execution (`max-duration`,
  default 10 min) → `EXECUTION_TIMEOUT`.
- Error codes set by the engine: `NODE_ERROR` (unexpected exception), `NODE_TIMEOUT`, `EXECUTION_TIMEOUT`, `CANCELLED`,
  `NO_EXECUTOR`, `INVALID_OUTPUT_HANDLE`, `EXECUTION_INTERRUPTED` (server stopped), `EXECUTION_REJECTED`, `ENGINE_ERROR`. Executors
  add their own: `EXPRESSION_ERROR`, `TYPE_MISMATCH`, `TRANSFORM_TYPE_ERROR`, `INVALID_CONFIGURATION`, and for HTTP
  `HTTP_STATUS_ERROR`, `HTTP_TIMEOUT`, `HTTP_HOST_NOT_ALLOWED`, `HTTP_PORT_NOT_ALLOWED`, `HTTP_ADDRESS_BLOCKED`,
  `HTTP_REDIRECT_BLOCKED`, `TOO_MANY_REDIRECTS`, `RESPONSE_TOO_LARGE`, `HTTP_DNS_ERROR`, `HTTP_CONNECTION_ERROR`, `INVALID_URL`,
  `INVALID_HEADER`. Messages are sanitized (credentials redacted, no stack traces). Node behaviour: `WORKFLOW_SCHEMA.md`.

## Settings (Module 14)

`GET /api/v1/settings` (read-only, `Cache-Control: no-cache`): the effective server configuration for the Settings page. It contains no
secrets (only whether credential encryption is available, the active key's name and how many keys are loaded) and cannot be changed
through the API: values come from environment variables and need a restart.

```json
{
  "application": { "version": "0.1.0-SNAPSHOT" },
  "execution": { "workerThreads": 2, "queueCapacity": 100, "nodeTimeoutSeconds": 30, "maxDurationSeconds": 600,
                 "maxActivePerWorkflow": 10, "maxStoredPayloadBytes": 262144 },
  "outbound": { "allowedHosts": ["api.openai.com"], "allowPrivateAddresses": false, "allowedPorts": [],
                "maxResponseBytes": 1048576, "maxRequestBytes": 1048576, "maxRedirects": 3 },
  "triggers": { "schedulerEnabled": true, "schedulerPollSeconds": 5, "allowUnauthenticatedWebhooks": false, "webhookMaxBodyBytes": 262144 },
  "credentials": { "encryptionAvailable": true, "activeKeyId": "k1", "keyCount": 1 },
  "ai": { "maxPromptChars": 100000, "maxResponseBytes": 1048576 },
  "security": { "allowedHostNames": ["localhost", "127.0.0.1", "[::1]"], "allowedOrigins": ["http://localhost:5173"], "maxRequestBytes": 1048576 }
}
```

## GET /api/v1/health
Reports aggregate and per-component health. `200` when all components are `UP`, `503` when any is `DOWN`
(same body shape).

```json
{
  "status": "UP",
  "version": "0.1.0-SNAPSHOT",
  "timestamp": "2026-10-25T03:37:27.425345798Z",
  "components": { "database": "UP" }
}
```
`status` and component values: `UP` | `DOWN`. New components are added by implementing `HealthProbe`.

## Actuator
`/actuator/health` (including `/liveness`, `/readiness`) and `/actuator/info` are exposed for container health checks.
Not part of the `/api/v1` contract.

## Error format
```json
{
  "type": "https://flowforge.local/problems/validation-failed",
  "title": "Validation failed",
  "status": 400,
  "detail": "Request validation failed.",
  "instance": "/api/v1/...",
  "errors": [{ "field": "name", "message": "must not be blank" }]
}
```
Problem codes: `validation-failed` (400), `invalid-request` (400), `not-found` (404), `conflict` (409), `concurrent-modification` (409), `payload-too-large` (413), `workflow-invalid` (422, with `issues`), `node-type-not-executable` (501, with `nodeTypes`), `execution-capacity` (503), `internal-error` (500); Module 11: `unauthorized` (401), `method-not-allowed` (405), `webhook-replayed` (409), `unsupported-media-type` (415), `too-many-requests` (429); Module 13 adds node error codes only (`AI_*`, see `WORKFLOW_SCHEMA.md`), no new problem types. Module 12: `host-not-allowed` (403), `cross-origin-request-blocked` (403), `credential-in-use` (409, with `workflows`), `credentials-unavailable` (503), `secret-unavailable` (503), `webhook-unavailable` (503).

Error bodies never echo request content: malformed JSON gives a fixed message ("The request body is not valid JSON for this
endpoint.", "Unknown property 'x'.", or the nesting/size limit message); bad path parameters give "Parameter 'id' has an invalid
value."; database constraint races give a generic `409 conflict`; anything unexpected is a generic `500` (details only in the
server log). `execution-capacity` (503) is also returned when a workflow already has
`flowforge.execution.max-active-per-workflow` (10) queued or running executions.
Framework errors (404 unknown path, 405, 415, ...) use Spring's standard Problem Details with type `about:blank`.

## CORS
Management paths (`/api/v1/workflows/**`, `/executions/**`, `/credentials/**`, `/samples/**`, `/health`) allow exactly the origins in
`FLOWFORGE_ALLOWED_ORIGINS` (localhost:5173 by default; `*` or paths are rejected at startup), methods GET/POST/PUT/DELETE,
headers `Content-Type`, `Accept`, `Last-Event-ID`, `Cache-Control`, exposed header `Location`, no browser credentials, max age
600 s. Webhooks get no CORS headers.

## Request guards (Module 12)
Applied to every request before any controller (`RequestGuardFilter`):
- `Host` must be one of `FLOWFORGE_ALLOWED_HOST_NAMES` (default `localhost,127.0.0.1,[::1]`) → else `403 host-not-allowed`
  (DNS rebinding protection).
- Non-GET/HEAD/OPTIONS requests to the management API with an `Origin` that is neither allowed nor the server's own, or with
  `Sec-Fetch-Site: cross-site`, → `403 cross-origin-request-blocked` (CSRF protection for the unauthenticated API). Webhooks
  are exempt.
- Management API bodies ≤ 1 MiB (`flowforge.security.max-request-bytes`, by `Content-Length` and while streaming) → `413`.
  JSON is parsed with a nesting limit of 100 and string limit of 2 MB → `400`.
- Response headers: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`,
  `Cross-Origin-Resource-Policy: same-origin`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'` on API
  responses. OpenAPI/Swagger UI are disabled in the `prod` profile; actuator exposes only `health` (no details) and `info`.
