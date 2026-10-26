# Workflow Schema

The workflow definition is a versioned JSON graph. It is the single contract shared by the backend
(validation, execution) and the frontend (editing, visualization).

| Artifact | Location |
|---|---|
| JSON Schema (Draft 2020-12), canonical | `backend/src/main/resources/schemas/workflow-definition.v1.schema.json` |
| Java model | `com.flowforge.domain.workflow` (`WorkflowDefinition`, `NodeDefinition`, `EdgeDefinition`, `NodePosition`, `EditorMetadata`, `Viewport`) |
| TypeScript model | `frontend/src/lib/workflow/definition.ts` |
| Example (used by backend and frontend tests) | `backend/src/test/resources/workflow/example-definition.v1.json` |

Tests keep these aligned: the backend serializes the Java model and validates it against the JSON Schema, both
in memory and as stored in PostgreSQL. The frontend checks its types and limits against the same schema and example.

## Schema version 1

```json
{
  "schemaVersion": 1,
  "nodes": [
    { "id": "trigger", "type": "core.manualTrigger", "typeVersion": 1, "name": "Manual trigger",
      "position": { "x": 0, "y": 0 }, "configuration": {} },
    { "id": "check", "type": "core.if", "typeVersion": 1, "name": "Is large order?",
      "position": { "x": 240, "y": 0 },
      "configuration": { "condition": { "left": "order.total", "operator": "gt", "right": 100 } } },
    { "id": "notify", "type": "core.noOp", "typeVersion": 1, "name": "Large order",
      "position": { "x": 480, "y": -80 }, "configuration": {} }
  ],
  "edges": [
    { "id": "e1", "source": "trigger", "sourceHandle": "main", "target": "check", "targetHandle": "main" },
    { "id": "e2", "source": "check", "sourceHandle": "true", "target": "notify", "targetHandle": "main" }
  ],
  "editor": { "viewport": { "x": 0, "y": 0, "zoom": 1 } }
}
```

The example fixture uses illustrative types (`core.if`, `core.noOp`); the real catalog is below.

### Top level
| Field | Type | Required | Rules |
|---|---|---|---|
| `schemaVersion` | integer | yes | `1` (const). Bumped only for breaking changes to this structure |
| `nodes` | array of node | yes | max 500 |
| `edges` | array of edge | yes | max 2000 |
| `editor` | object | no | editor-only state; never affects execution |

Unknown properties are rejected (`additionalProperties: false`) at every level except `configuration`.

### Node
| Field | Type | Rules |
|---|---|---|
| `id` | string | `^[A-Za-z0-9_-]{1,64}$`; unique within the workflow; stable across edits; referenced by edges and `node_executions.node_id` |
| `type` | string | node type identifier, `^[a-z][a-z0-9]*(\.[a-z][A-Za-z0-9]*)+$`, e.g. `core.httpRequest`. Namespace `core.` is reserved for built-in nodes |
| `typeVersion` | integer ≥ 1 | version of that node type's configuration schema |
| `name` | string 1..100 | display name (uniqueness enforced by the validation module) |
| `position` | `{x, y}` numbers in ±1,000,000 | editor canvas coordinates |
| `configuration` | object | node-type specific. Validated against the node type's own schema (later module). `null` values are preserved |

### Edge and branch handles
| Field | Rules |
|---|---|
| `id` | same pattern as node id; unique within the workflow |
| `source`, `target` | node ids |
| `sourceHandle`, `targetHandle` | `^[A-Za-z][A-Za-z0-9_-]{0,63}$` |

A handle names an output (source) or input (target) port of a node. Most nodes use the default handle `main`.
Branching nodes declare several output handles (e.g. `core.if` → `true` / `false`), and an edge's
`sourceHandle` selects the branch. Each node type declares its handles in its node definition (later module),
and the validator rejects edges that use undeclared handles.

### Editor metadata
`editor.viewport` is `{x, y, zoom}` with zoom in `0.05..10`. Node `position` and `editor` are the only
editor-specific fields.

## Node type catalog (v1)

Canonical file: `backend/src/main/resources/schemas/node-types.v1.json`. The editor registry
(`frontend/src/features/editor/nodeTypes.ts`) is tested against it; the backend node registry (later module) must
implement exactly these identifiers, versions and handles. Never introduce an identifier in only one place.

| `type` | Label | `typeVersion` | Input handles | Output handles |
|---|---|---|---|---|
| `core.manualTrigger` | Manual Trigger | 1 | none | `main` |
| `core.webhookTrigger` | Webhook Trigger | 1 | none | `main` |
| `core.scheduleTrigger` | Schedule Trigger | 1 | none | `main` |
| `core.setVariables` | Set Variables | 1 | `main` | `main` |
| `core.condition` | Condition | 1 | `main` | `true`, `false` |
| `core.transform` | Transform | 1 | `main` | `main` |
| `core.logger` | Logger | 1 | `main` | `main` |
| `core.httpRequest` | HTTP Request | 1 | `main` | `main` |
| `core.aiModel` | AI Model | 1 | `main` | `main` |

New nodes start with `defaultConfiguration` from the catalog (below). The editor preserves unknown
node types and their configuration unchanged (generic `main`/`main` handles), so a workflow from a newer backend can be
opened and saved without loss.

## Node configuration schemas (v1)

Canonical file: `backend/src/main/resources/schemas/node-config.v1.schema.json` (JSON Schema 2020-12). One definition per node
type under `$defs`, keyed by the type identifier; validate a node's `configuration` against `$defs/<type>`. All objects use
`additionalProperties: false`. `node-types.v1.json` holds each type's `defaultConfiguration` (the starting config of a
new node; it may be incomplete on purpose).

| Type | Persisted `configuration` | Rules |
|---|---|---|
| `core.manualTrigger` | `{ testInput?: object \| array }` | optional; plain JSON, no expressions |
| `core.webhookTrigger` | `{ method, authentication, inputMode, testInput? }` | `method` GET POST PUT PATCH DELETE; `authentication` `signature` (default) \| `token` \| `none` (rejected unless the server allows it); `inputMode` `body` \| `request`; `testInput` as for the manual trigger (used for editor runs). Default `{ method: "POST", authentication: "signature", inputMode: "body" }`. The webhook id and secret are **not** configuration: the server generates them |
| `core.scheduleTrigger` | `{ cron, timezone, missedRunPolicy, testInput? }` | `cron` 5 fields (`minute hour day-of-month month day-of-week`) or 6 with leading seconds, Spring cron syntax (`*/15`, `1-5`, `MON-FRI`, `JAN`, `?`, `L`, `W`, `#`), max 120 chars; `timezone` IANA id (`Europe/Berlin`, `UTC`); `missedRunPolicy` `skip` \| `runOnce`. Default `{ cron: "0 * * * *", timezone: "UTC", missedRunPolicy: "skip" }` |
| `core.setVariables` | `{ variables: [{ name, value }] }` | 1..50 rows; `name` `^[A-Za-z_][A-Za-z0-9_]{0,63}$`; `value` safe text |
| `core.condition` | `{ field, operator, value? }` | `field` safe text, required; `operator` one of `equals notEquals greaterThan greaterThanOrEqual lessThan lessThanOrEqual contains notContains startsWith endsWith isEmpty isNotEmpty`; `value` required (non-empty) unless operator is `isEmpty`/`isNotEmpty`, where it must be absent |
| `core.transform` | `{ operations: [{ field, source, operation }] }` | 1..50 rows; `field` like a variable name; `source` safe text, required; `operation` one of `copy toUpperCase toLowerCase trim toNumber toString parseJson stringifyJson length` |
| `core.logger` | `{ message }` | safe text, 1..2000 chars |
| `core.httpRequest` | `{ method, url, headers, query, body?, timeoutSeconds, credentialId? }` | `method` GET POST PUT PATCH DELETE HEAD; `url` safe text starting `http://`, `https://` or `{{`; `headers`/`query` arrays (max 50) of `{name, value}` (header names are RFC 9110 tokens); `body` any JSON whose strings are safe text, **absent for GET/HEAD**; `timeoutSeconds` integer 1..300; `credentialId` optional lower-case UUID of a stored credential (Module 12; `$defs/credentialId`). All keys except `body` and `credentialId` are always present |
| `core.aiModel` | `{ provider, baseUrl, model, credentialId?, systemPrompt?, userPrompt, temperature?, maxTokens?, responseFormat, jsonMode?, responseSchema?, timeoutSeconds, maxRetries }` | `provider` `openaiCompatible`; `baseUrl` http(s) URL without query, fragment or `{{ }}` (requests go to `<baseUrl>/chat/completions`), max 300; `model` name (`[A-Za-z0-9][A-Za-z0-9._:/@+-]*`, max 200); `credentialId` optional (bearer token or API-key header credential; leave out for local models without auth); `systemPrompt`/`userPrompt` templates with the safe `{{ }}` references, up to 20000 characters (`$defs/promptText`), `userPrompt` required; `temperature` 0..2; `maxTokens` integer 1..32768; `responseFormat` `text` \| `json`; **when `json`, `jsonMode` is required** (`jsonObject`: also send `response_format: json_object`; `prompt`: instructions only, for servers without it) and `responseSchema` is optional; both are forbidden for `text`; `timeoutSeconds` 1..300 (one budget for all attempts); `maxRetries` 0..3. Default `{ provider: "openaiCompatible", baseUrl: "https://api.openai.com/v1", model: "gpt-4o-mini", responseFormat: "text", timeoutSeconds: 60, maxRetries: 2 }` (the prompt is left for the user, so a new node is flagged incomplete) |

### Node behaviour at run time (Module 8)
| Type | Output | Notes / error codes |
|---|---|---|
| `core.manualTrigger` | the execution input (request `input`, else `testInput`, else `{}`) | |
| `core.webhookTrigger` | webhook runs: the mapped request (see `API_CONTRACTS.md`); manual runs: `input`, else `testInput`, else `{}` | |
| `core.scheduleTrigger` | scheduled runs: `{ scheduledFor, firedAt, timezone, cron, late }`; manual runs: as above | |
| `core.setVariables` | its input, unchanged | sets `vars.<name>` in order (a row may use earlier rows); `EXPRESSION_ERROR` |
| `core.condition` | its input, unchanged; activates `true` or `false` | `field` is a path into the input (`order.total`) or a template; numbers compare numerically, booleans as booleans, otherwise text; `greater*/less*` need two numbers and `startsWith/endsWith` need text → `TYPE_MISMATCH`; empty = missing/null/""/[]/{} |
| `core.transform` | a new object with exactly the configured fields | selection, renaming, mapping; ops `copy toUpperCase toLowerCase trim toNumber toString parseJson stringifyJson length` are type-checked → `TRANSFORM_TYPE_ERROR` |
| `core.logger` | its input, unchanged | writes the resolved, sanitized message (INFO) to the execution log |
| `core.aiModel` | `{ text, json?, model, provider, finishReason?, usage?, attempts, request }` | see "AI Model node" below; errors `AI_*` |
| `core.httpRequest` | `{ status, statusText, url, redirects, headers, body, request: {method, url, headers} }` | `body` is parsed JSON for JSON content types, else text; status ≥ 400 → `HTTP_STATUS_ERROR`; see Security notes for the other codes |

### Safe expressions
Text fields accept `{{ path }}` references where `path` is a property path: `input.user.id`, `items[0].name`.
Roots (resolved by the backend `ExpressionResolver`):

| Reference | Value |
|---|---|
| `{{ input.x }}` | the node's input (its parent's output) |
| `{{ trigger.body.x }}` | the execution input |
| `{{ vars.x }}` | workflow variables set by Set Variables |
| `{{ nodes.<nodeId>.output.x }}` | output of a node that already ran (the node id must look like an identifier, e.g. `httpRequest_1a2b3c4d`) |

A value that is exactly one reference keeps its JSON type (`"{{ input.total }}"` → `120.5`); references inside other
text are replaced by text (objects/arrays as compact JSON, missing/null as ""). A missing path is `null`, not an error;
an unknown root or anything that is not a path is `EXPRESSION_ERROR`. Anything else
inside `{{ }}` (calls, operators, quotes, `;`, ternaries) is rejected, and text outside `{{ }}` is literal (single braces
are fine). There is **no code evaluation**: the execution module must resolve paths against run data only and must never
`eval` or interpret expressions as code. The pattern is `$defs/safeString.pattern`; the frontend uses the identical string
(tested). Strings inside `body` are checked recursively.

### Rules JSON Schema cannot express
Variable names unique within a node; transform output fields unique within a node; header names unique case-insensitively.
Enforced by the editor and by the backend `JsonSchemaConfigurationValidator`. HTTP Request (Module 12): header names managed by
the client (`Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `Keep-Alive`, `Upgrade`, `TE`, `Trailer`, `Expect`,
`Proxy-Connection`) are refused, and credential headers (`Authorization`, `Proxy-Authorization`, `Cookie`, `X-API-Key`,
`API-Key`, `X-Auth-Token`) may not have a literal value: use `credentialId` (or a `{{ }}` reference to run-time data). The
backend also checks that `credentialId` refers to an existing credential of the current owner whose type the node may use
(validation issue on `credentialId`). Schedule triggers: the cron expression must parse
and the time zone must exist (backend: Spring `CronExpression`, `java.time.ZoneId`; the editor checks field counts, numeric ranges
and `Intl` time zones, the backend has the final word through validation issues). Webhook triggers: `authentication: "none"` is a
validation error unless the server allows unauthenticated webhooks (server policy, so it is not in the shared fixtures).

### Shared contract tests
`backend/src/test/resources/workflow/node-config-fixtures.v1.json` lists valid and invalid configurations per type. The backend
(`NodeConfigSchemaTest`) validates them with the JSON Schema; the frontend (`configContract.test.ts`) checks that Ajv with the
same schema **and** the editor's Zod validation both agree with each fixture. Add a fixture whenever a rule changes.

### AI Model node (Module 13)
Sends a prompt to a chat model through a provider (`ChatProvider`; today `openaiCompatible`: `POST <baseUrl>/chat/completions` as
spoken by OpenAI, OpenRouter, Groq, Ollama, vLLM, ...). Its outbound access is exactly that of the HTTP Request node: the host must
be in `FLOWFORGE_HTTP_ALLOWED_HOSTS` (e.g. `api.openai.com`; local models also need `localhost` and
`FLOWFORGE_HTTP_ALLOW_PRIVATE_ADDRESSES=true`), non-public addresses are refused at connect time, **redirects are never followed**
(they would carry the key elsewhere), responses are capped (`flowforge.ai.max-response-bytes`, 1 MiB), prompts are capped
(`flowforge.ai.max-prompt-chars`, 100000).

**Prompts.** `systemPrompt` and `userPrompt` are templates: `{{ input.x }}`, `{{ vars.x }}`, `{{ trigger.body.x }}`,
`{{ nodes.<id>.output.x }}` (objects are inserted as compact JSON). Referenced data is **untrusted input to the model** and can try
to steer it: keep rules in the system prompt, delimit inserted data (the sample wraps it in `<review>` tags), validate the answer
with a schema, and never treat it as code or as a command to FlowForge: the answer is only data for the next nodes.

**JSON output.** With `responseFormat: "json"` the node adds an instruction to the system message (plus the schema text), sends
`response_format: {"type":"json_object"}` unless `jsonMode` is `prompt`, tolerates a Markdown code fence around the reply (nothing
else), parses it strictly (no trailing text) and validates it against `responseSchema` when given. `responseSchema` is a JSON Schema
**subset** (`ResponseSchemaPolicy`, shared fixtures `response-schema-fixtures.v1.json`, enforced by validation, the editor and at
run time): keywords `type`, `properties`, `required`, `additionalProperties`, `items`, `enum`, `const`, `uniqueItems`, `minimum`,
`maximum`, `exclusiveMinimum`, `exclusiveMaximum`, `minLength`, `maxLength`, `minItems`, `maxItems`, `anyOf`, `oneOf`, `allOf`,
`title`, `description`; **no** `$ref`/`$schema`/`$id` (nothing is ever fetched), **no** `pattern` or `format` (hostile regular
expressions), at most 16 KB, depth 10, 500 schema nodes, 100 enum values.

**Output** `{ text, json?, model, provider, finishReason?, usage?, attempts, request }`: `text` is the raw reply, `json` the parsed
and validated value (JSON mode only), `model` what the provider reports, `usage` `{ prompt, completion, total, cost }` **only as far
as the provider returns it** (`cost` only when the provider sends a price, e.g. OpenRouter's `usage.cost`; FlowForge never estimates
prices) summed over all attempts, `attempts` the number of requests made, `request` `{ baseUrl, model, responseFormat,
temperature?, authenticatedWith? }` (no prompts, no key). The usage keys avoid the word "token" on purpose: stored history redacts
keys containing it. A log line `AI call finished: model ..., attempts ..., prompt ..., completion ..., total ..., cost ...` is
written for every successful call. Use it downstream like any output, e.g. `{{ input.json.sentiment }}`, a Condition on the field
`json.sentiment`, or `{{ nodes.ai.output.usage.total }}`.

**Failure policy** (bounded, `AiChatService`): at most `1 + maxRetries` requests (never more than 4) inside the one
`timeoutSeconds` budget; a retry whose wait does not fit in the remaining budget is not started.

| Situation | Error code | Retried |
|---|---|---|
| 429 rate limit (honours `Retry-After` up to `flowforge.ai.max-retry-after`, 10 s; longer = final) | `AI_RATE_LIMITED` | yes |
| 5xx, connection failure, garbled or empty reply (HTTP 200 but no usable completion) | `AI_PROVIDER_ERROR`, `AI_CONNECTION_ERROR`, `AI_BAD_RESPONSE`, `AI_EMPTY_RESPONSE` | yes |
| JSON mode: reply not valid JSON or violating the schema | `AI_OUTPUT_INVALID` | yes, the model is shown its bad reply (cut to 2000 characters) and the problem |
| reply cut off by `maxTokens` while JSON is invalid | `AI_OUTPUT_TRUNCATED` | no (raise `maxTokens`) |
| 401/403 (the provider's text is never quoted: it often contains part of the key) | `AI_AUTH_FAILED` | no |
| 400/404/422 (the provider's reason is shown) | `AI_BAD_REQUEST` | no |
| model refusal | `AI_REFUSED` | no |
| time budget spent | `AI_TIMEOUT` | no |
| response over the size limit, redirect, outbound policy (`HTTP_HOST_NOT_ALLOWED`, `HTTP_ADDRESS_BLOCKED`, ...) | `AI_RESPONSE_TOO_LARGE`, `AI_REQUEST_REFUSED`, policy codes | no |
| prompt too large / empty after resolving | `AI_PROMPT_TOO_LARGE`, `INVALID_CONFIGURATION` | no |
| credential problems | `CREDENTIAL_NOT_FOUND`, `CREDENTIAL_NOT_PERMITTED`, `CREDENTIAL_UNAVAILABLE`, `CREDENTIAL_HOST_NOT_ALLOWED` | no |

Waits between retries: exponential from `flowforge.ai.retry-base-delay` (500 ms) up to `retry-max-delay` (8 s) with up to 25 %
jitter. `max_tokens` is the limit parameter sent; providers that only accept `max_completion_tokens` (some reasoning models) need
`maxTokens` left empty.

### Credentials (Module 12)
Secrets never belong in workflow JSON. A node references a stored credential by id (`credentialId`); the definition, its
snapshots and exports contain only that id. Credential types (catalog `backend/src/main/resources/schemas/credential-types.v1.json`,
mirrored in `frontend/src/features/credentials/credentialTypes.ts` and contract-tested):

| `type` | Fields (non-secret) | Secret fields | Sent as | Usable by |
|---|---|---|---|---|
| `httpBearer` | – | `token` | `Authorization: Bearer <token>` | `core.httpRequest`, `core.aiModel` |
| `httpBasic` | `username` (no `:`) | `password` | `Authorization: Basic base64(username:password)` | `core.httpRequest`, `core.aiModel` |
| `httpHeader` | `headerName` (token, not a client-managed header) | `value` | `<headerName>: <value>` | `core.httpRequest`, `core.aiModel` |

Secret values are 8..4096 characters without line breaks. Each credential lists `allowedHosts` (1..20, `api.example.com` or
`*.example.com`): the node fails with `CREDENTIAL_HOST_NOT_ALLOWED` before sending anything to another host, and drops the
credential's header on any redirect to another origin. Run-time errors: `CREDENTIAL_NOT_FOUND` (missing or another owner's),
`CREDENTIAL_NOT_PERMITTED` (type not usable by the node), `CREDENTIAL_UNAVAILABLE` (key missing or ciphertext invalid),
`CREDENTIAL_HEADER_CONFLICT` (the node also sets the same header). The request echo shows the header as `[REDACTED]` and
`request.authenticatedWith: { id, name, type }`. Every secret value the node sends (including the derived Basic value) is
masked by value in stored inputs/outputs, errors and logs, even when the remote server echoes it back.

### Security notes
Headers and bodies are stored in plain text in the workflow definition: use credentials for secrets. The HTTP node enforces
outbound protection at run time (Module 8):
- **Allowlist**: only hosts in `flowforge.http.allowed-hosts` (`FLOWFORGE_HTTP_ALLOWED_HOSTS`; exact or `*.domain`). Empty = no
  outbound HTTP. `HTTP_HOST_NOT_ALLOWED`; optional port list → `HTTP_PORT_NOT_ALLOWED`.
- **Non-public addresses blocked** even for allowed hosts (loopback, RFC 1918, link-local incl. 169.254.169.254, CGNAT,
  multicast, reserved/documentation ranges, IPv6 ULA/link-local, IPv4-mapped/NAT64 forms) → `HTTP_ADDRESS_BLOCKED`.
  `flowforge.http.allow-private-addresses=true` is for development only.
- **No DNS rebinding**: addresses are validated inside the HTTP client's DNS resolver, and the client connects only to those
  validated addresses. If any resolved address is non-public the host is refused.
- **Redirects**: followed manually, max 3 (`TOO_MANY_REDIRECTS`), each target re-checked (`HTTP_REDIRECT_BLOCKED`); 301/302/303
  switch to GET without body, 307/308 keep method and body; `Authorization`/`Cookie` headers are dropped when the host changes.
- **Limits**: response body ≤ 1 MiB (`RESPONSE_TOO_LARGE`), whole request ≤ `timeoutSeconds` (`HTTP_TIMEOUT`), connect ≤ 5 s;
  the request is aborted on cancellation. No cookies, retries, proxies from the environment or credentials in URLs (`INVALID_URL`).
  Header values with line breaks or client-managed header names → `INVALID_HEADER`. Resolved request bodies above
  `flowforge.http.max-request-bytes` (1 MiB) → `REQUEST_TOO_LARGE`.
- **Redaction**: sensitive request/response header values (`Authorization`, `Cookie`, `Set-Cookie`, names containing
  token/secret/key/…) and sensitive query parameters are replaced by `[REDACTED]` in stored outputs and error messages.

### Editor mapping
Canvas state is converted by `frontend/src/features/editor/serialization.ts`:
`data.nodeType` ↔ `type`, `data.name` ↔ `name`, `data.typeVersion`, `data.configuration`, `position` rounded to
2 decimals, edges always written with explicit handles (default `main`), `editor.viewport` from the canvas on every save.
Selection, measurements, edge labels/arrows and `executionStatus` are display-only and never serialized.
Node ids are `<shortType>_<8 hex>` (e.g. `httpRequest_a1b2c3d4`), edge ids `e_<8 hex>`; both satisfy the id pattern.

## Rules enforced where

| Rule | Enforced by |
|---|---|
| Structure, types, patterns, limits | JSON Schema; Bean Validation annotations on the Java records (same patterns) |
| JSONB is an object with numeric `schemaVersion`, array `nodes` and array `edges` | DB check constraints (`chk_workflows_definition_shape`, `chk_workflow_executions_snapshot_shape`) |
| Size ≤ 1 MiB of JSON text | DB check constraints. The API module should reject earlier with a 413/400 Problem Detail |
| Graph rules: unique ids and names, known types/versions/handles, edge endpoints, exactly one trigger, no cycles, no merges, connectivity and reachability, Condition branches, limits, node configuration | `GraphValidator` (Module 6), via `POST /workflows/{id}/validate` and on activation |

## Graph model supported in version 1 (enforced by `GraphValidator`)
- Exactly one trigger node (`role: "trigger"` in the catalog). It has no inputs. Every other node must be reachable from it.
- Directed acyclic graph: loops are rejected until a loop node exists.
- One incoming edge per input handle: joining branches is rejected until a merge node exists. Fan-out (one output to several
  nodes, max 20 per handle) is allowed.
- A Condition must connect at least one of `true`/`false`; an unconnected branch is a warning (that path just ends).
- Execution order is the topological order of the reachable nodes, ties broken by definition order (deterministic).

## Serialization conventions
- Absent optional properties are omitted (no `"editor": null`). `null` values inside `configuration` are kept.
- Positions are numbers. Java writes doubles, e.g. `240.0`, which is equal in value to `240`.
- PostgreSQL `jsonb` does not preserve key order or whitespace. Nothing may depend on either.

## Versioning and evolution
- `schemaVersion` describes the graph structure. `typeVersion` describes each node's configuration. `workflows.version`
  counts definition edits. These are independent.
- Additive, optional fields can be added within version 1 only if older readers ignore them. Because the schema uses
  `additionalProperties: false`, any new field requires updating the JSON Schema, the Java records and the TS types
  together.
- A breaking change introduces `workflow-definition.v2.schema.json`, `CURRENT_SCHEMA_VERSION = 2`, and an upgrader
  from v1. Executions keep their original snapshot and are never rewritten.

## Persistence
- `workflows.definition` (JSONB) holds the current definition.
- `workflow_executions.workflow_snapshot` (JSONB) is an immutable copy taken when an execution is created, together with
  `workflow_version`. Editing a workflow never changes past executions.
