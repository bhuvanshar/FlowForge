# Production readiness review

**Verdict: FlowForge is not production-ready.** It is a working, tested single-user application for one machine
(`127.0.0.1`). This review lists what exists and what is missing in the five areas that decide whether it could serve more
than its local owner. Everything marked *missing* must be addressed before exposing it to a network, other users or
business-critical workloads.

## 1. Authentication and authorization

| Exists | Missing |
|---|---|
| Trust boundary documented (ARCHITECTURE.md): one local user, services bound to 127.0.0.1 | **Any user authentication.** Whoever reaches port 5173/8080 controls every workflow and can run outbound requests with stored credentials |
| Host-header allowlist (DNS rebinding), cross-site request blocking (Origin / Sec-Fetch-Site), strict CORS | Sessions, CSRF tokens for an authenticated UI, user management, roles |
| `OwnerContext` seam: workflows and credentials carry `owner_id` and every credential lookup is owner-scoped (tested) | Multi-user / workspace tenancy (only the constant owner `local` exists) |
| Webhooks authenticate with HMAC signatures (replay-protected) or tokens; unauthenticated webhooks are off by default | Rate limiting per caller; audit log of who changed what |

## 2. Outbound network controls

| Exists | Missing |
|---|---|
| Deny-by-default host allowlist (`FLOWFORGE_HTTP_ALLOWED_HOSTS`) for HTTP Request and AI Model nodes | Egress enforcement outside the application (firewall / proxy); today the JVM is the only control |
| Non-public addresses (loopback, private, link-local incl. cloud metadata, CGNAT, multicast) blocked by default, checked by the connection's own DNS resolver (no check-then-connect gap), on every redirect | Per-workflow or per-credential egress policies; outbound request quotas |
| Optional port allowlist, redirect limit (3), request/response size limits, timeouts | TLS client certificates, custom CA management |
| Credentials only sent to their own allowed hosts; secrets redacted from history and logs | |

`FLOWFORGE_HTTP_ALLOW_PRIVATE_ADDRESSES=true` (used by the E2E overlay and local-model setups) removes the address protection
and must never be used where the backend can reach internal services.

## 3. Concurrency and scaling

| Exists | Missing |
|---|---|
| Bounded worker pool and queue (`queue-capacity` 100, `503` when full), per-workflow active limit, node and execution timeouts | **Horizontal scaling.** The execution queue is in memory; a second backend on the same database would double-run recovery and its own queue |
| Optimistic locking on workflows and credentials (`409` on stale writes) | A durable job queue (executions waiting in memory are lost on crash and recorded as `EXECUTION_INTERRUPTED`) |
| Scheduler claims due occurrences with `FOR UPDATE SKIP LOCKED` and a unique occurrence index (no duplicate scheduled runs) | Leader election for the scheduler (`FLOWFORGE_SCHEDULER_ENABLED` must be true on exactly one instance) |
| Startup recovery of interrupted executions; graceful shutdown records running executions | Retry/resume of failed executions from the failed node |
| SSE fan-out limited (200 connections, slow subscribers dropped) | Load and soak testing (not performed) |

## 4. Backups and data lifecycle

| Exists | Missing |
|---|---|
| Documented `pg_dump`/`pg_restore` procedure and key backup (DEPLOYMENT.md §6) | **Automated, scheduled, off-machine backups** and regular restore tests |
| Flyway migrations, immutable once released (checksum test), schema validated by Hibernate at start | Point-in-time recovery (WAL archiving) |
| Credential and webhook secrets encrypted with AES-256-GCM, keys outside the database, rotation + re-encrypt | A secret manager / KMS for the keys (today: env file or key file) |
| Execution payloads truncated to `max-stored-payload-bytes` | **Retention:** execution history grows without bound (the repository supports deleting old executions, but no job runs it) |

## 5. Operational monitoring

| Exists | Missing |
|---|---|
| `/api/v1/health` (app + DB), actuator liveness/readiness, compose healthchecks and restart policy | **Metrics** (Prometheus/Micrometer endpoint is not exposed), dashboards |
| Structured error codes in execution history; rotated container logs | **Alerting** on failed executions, failed schedules, backend restarts, disk usage |
| Schedule state shows last error / missed runs; Settings page shows effective configuration | Centralised logs, tracing, log redaction review beyond the engine's secret masking |

## Other gaps

- **TLS**: none; acceptable on loopback only. Any network exposure needs a TLS-terminating reverse proxy *and* authentication.
- **Supply chain**: images pinned by digest and dependencies from Maven Central / npm lockfile only, but no image scanning,
  SBOM or signed artifacts.
- **Expressions** are a restricted template language (no code execution); workflows cannot run arbitrary code by design —
  keep it that way.
- **Browser support / accessibility**: automated checks (Testing Library roles, keyboard tests) only; no manual
  screen-reader audit.

## Minimum before any shared deployment

1. Real authentication and per-user authorization (fill in `OwnerContext` from the authenticated principal), behind TLS.
2. Network egress controls outside the app, and `allow-private-addresses=false`.
3. Automated encrypted backups of database **and** keys, with a tested restore.
4. Metrics + alerting on failures and resource usage; an execution-history retention job.
5. A durable queue and scheduler leadership before running more than one backend.
