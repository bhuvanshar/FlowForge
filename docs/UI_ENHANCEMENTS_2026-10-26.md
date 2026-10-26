# UI and workflow enhancements — 2026-10-26

## Delivered

- Refreshed dark sidebar, warm neutral workspace, coral accents, breadcrumbs, workflow cards,
  empty/loading/error states, keyboard focus indicators, and reduced-motion support.
- Real workflow counts, debounced server-side search, status filters, sorting, 12-item pagination,
  grid/list views, and URL-persisted dashboard state. Removing the last item on a page clamps
  pagination to the last available page.
- Dashboard actions for running the saved workflow, execution history, duplication, archiving,
  and restoration. Archive confirmation uses a native modal with focus containment and Escape
  dismissal. Restore reads the latest definition and optimistic-lock version and saves as DRAFT.
- Searchable node palette and responsive editor panels. Below 1280px, Nodes and Properties
  open as overlays, preserving canvas space; wide screens retain side-by-side panels.

## Verification

`npm test`: **424 tests passed**. `npm run typecheck`, `npm run lint`, and `npm run build`: passed.
Added dashboard behavior and API contract coverage, plus node-search coverage.

Live browser checks used a separate PostgreSQL database and sample manual-trigger/logger graphs.
Verified server search, alphabetical sort, grid/list switch, page 2, retained settings after reload,
duplicate-to-editor navigation, quick execution reaching SUCCEEDED, archive confirmation and
archived filtering, and restore-to-draft behavior. Checked the narrow editor layout and the
1280px side-by-side breakpoint. The final dashboard reload produced no browser warning/error logs.

Backend source was unchanged in this enhancement pass. The preceding clean backend verification
passed 327 unit tests and 117 integration tests; see CODE_REVIEW_2026-10-26.md for review context.
This is a partial Module 14 delivery, not completion of Modules 13–15.

## Local review preview

The review preview at http://127.0.0.1:5173 uses an isolated backend on port 18080 and the disposable
Docker container `flowforge-ui-review` on port 55432. Its workflows are sample data, explicitly
described as manual-trigger/logger demos. Existing application data was not used for these checks.
The Vite process uses `VITE_PROXY_TARGET=http://127.0.0.1:18080`; this is an environment override,
not a change to the default development configuration. Scheduling is disabled for this preview.

To stop the preview, stop its frontend and backend processes, then run
`docker stop flowforge-ui-review`. The container was started with `--rm`; stopping it removes
the disposable review database. For normal development, use the README startup instructions.
