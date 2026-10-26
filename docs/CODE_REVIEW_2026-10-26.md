# Code review and fixes — 26 October 2026

Reviewed the existing editor request lifecycle, execution engine, credential redaction, and related persistence,
trigger, HTTP, and event code. This was a targeted review of Modules 1–12, not completion of Modules 13–15.
The workspace contains no Git metadata; changes were made directly to its source files.

## Findings fixed

| Finding | Impact | Change |
| --- | --- | --- |
| Delayed save responses updated the global editor store without checking which editor sent them. | Navigating away, creating another draft, or reopening the same workflow could apply an old workflow ID, lock version, and saved signature to current edits. | Each load/reset creates an editor session ID. Save responses still invalidate server caches, but only update or navigate the originating session. Edits made during the same save remain dirty. |
| Validation requests survived editor changes and unmounts. | Results or failures from an old editor could populate the new editor's Problems panel. | Check the editor session, request sequence, and content signature; invalidate pending responses during effect cleanup. |
| Run, activation, and conflict-reload callbacks lacked consistent session checks. | A delayed response could display another workflow's run or replace current editor state. A failed reload also rejected without a visible recovery message. | Apply responses only to their originating session. Reload uses a tracked mutation, disables duplicate reloads, and preserves edits with an error message on failure. |
| Cancellation was checked before active nodes, but not before reporting overall success. | Cancellation arriving as the last node completed could produce `SUCCEEDED`. | Check cancellation before final success; preserve the completed node's result while marking the execution `CANCELLED`. |
| Unexpected engine errors finished only the execution row. | Node rows could remain `RUNNING` or `PENDING` under a failed execution. | Reuse transactional abandonment to fail running nodes, skip pending nodes, and preserve completed nodes before publishing the terminal event. Recovery still requires database availability. |
| Value-based credential masking handled JSON strings but not numbers. | An all-digit credential echoed as a JSON number could be stored in execution history. | Mask matching numeric values in the persisted copy, preserving the original runtime payload and unrelated numbers. |

The initial regression run reproduced six frontend failures and four backend failures before the fixes.
Added 13 regression tests overall and strengthened an existing engine-failure test.

## Improvements

- Load editor, credentials, and execution pages on demand, with an accessible loading message inside the app shell.
- Initial dashboard JavaScript decreased from approximately **836.06 KB to 439.71 KB** (entry plus preloaded shared
  JavaScript, minified but uncompressed), about **47%**. Gzipped JavaScript decreased from **264.24 KB to 142.67 KB**.
  These are build artifact sizes, not measured network timings. Deferred pages download when opened.
- Remove the mixed component/utility export that caused the existing button Fast Refresh lint warning.
- Correct extension documentation: Flyway's next available migration is **V9**, and routes live in `src/routes.tsx`.

## Verification

| Command | Result |
| --- | --- |
| `cd frontend && npm test` | 406 tests passed across 35 files |
| `cd frontend && npm run typecheck` | Passed |
| `cd frontend && npm run lint` | Passed with no warnings |
| `cd frontend && npm run build` | Passed; no oversized-chunk warning |
| `cd backend && ./mvnw verify` | 327 unit tests and 117 integration tests passed; no failures, errors, or skips |

Backend integration tests used Testcontainers and real PostgreSQL in Docker. The initial sandboxed backend attempt
could not support Mockito attachment/local services; the complete verification passed with the required host access.
Verification used Java 22.0.2 targeting release 21 and Node 26.0.0.

No new dependencies, API payload changes, or database migrations were introduced. Browser end-to-end tests and a full
production Docker Compose deployment were not performed. Module 13 AI integration and the remaining Module 14/15
work remain separate development tasks.
