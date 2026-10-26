import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Activity, Archive, ArrowUpRight, Check, ChevronLeft, ChevronRight, FileEdit, LayoutGrid, List, Plus, RefreshCw,
  Search, Sparkles, Trash2, Workflow, X, Zap, Power, Archive as ArchiveIcon,
} from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { EmptyState } from '@/components/ui/empty-state'
import { Notice } from '@/components/ui/notice'
import { PageHeader } from '@/components/ui/page-header'
import { SegmentedControl } from '@/components/ui/segmented-control'
import { Skeleton } from '@/components/ui/skeleton'
import { toProblemDetail } from '@/lib/api/client'
import { createSample } from '@/lib/api/samples'
import { listWorkflows, type WorkflowSummary } from '@/lib/api/workflows'
import { useHotkeys } from '@/lib/useHotkeys'
import { cn } from '@/lib/utils'
import type { WorkflowStatus } from '@/lib/workflow/definition'

import { SORT_OPTIONS, usePreferences } from '../settings/preferences'
import { WorkflowCard } from './WorkflowCard'
import type { WorkflowAction } from './workflowStatus'
import { WorkflowDetailsDialog } from './WorkflowDetailsDialog'
import { describeActionError, useWorkflowActions, type ActionRequest, type ActionResult } from './useWorkflowActions'

const FILTERS = [
  { value: '', label: 'All workflows' },
  { value: 'ACTIVE', label: 'Active' },
  { value: 'DRAFT', label: 'Drafts' },
  { value: 'INACTIVE', label: 'Inactive' },
  { value: 'ARCHIVED', label: 'Archived' },
] as const

/** The dialog that is open, with the workflow it is about. */
type DialogState =
  | { kind: 'create' }
  | { kind: 'rename' | 'archive' | 'deactivate' | 'delete'; workflow: WorkflowSummary }

/**
 * The dashboard: every workflow in one place with search, filters, sorting, status, last run and the actions to manage them.
 * View, filters, sort and page live in the URL (shareable, survive a reload); defaults come from the Settings page.
 */
export function WorkflowListPage() {
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const client = useQueryClient()
  const prefs = usePreferences()
  const search = params.get('q') ?? ''
  const filter = FILTERS.find((f) => f.value === params.get('status'))?.value ?? ''
  const sort = SORT_OPTIONS.find((s) => s.value === params.get('sort'))?.value ?? prefs.defaultSort
  const rawPage = Number(params.get('page') ?? 0)
  const page = Number.isSafeInteger(rawPage) && rawPage >= 0 && rawPage < 1_000_000 ? rawPage : 0
  const view = params.get('view') === 'list' ? 'list' : params.get('view') === 'grid' ? 'grid' : prefs.defaultView
  const pageSize = prefs.pageSize
  const [debouncedSearch, setDebouncedSearch] = useState(search)
  const [notice, setNotice] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const [dialog, setDialog] = useState<DialogState | null>(null)
  const [dialogError, setDialogError] = useState('')
  const searchInput = useRef<HTMLInputElement>(null)
  const actions = useWorkflowActions()

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search), 250)
    return () => clearTimeout(timer)
  }, [search])

  function change(key: string, value: string) {
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        if (value) next.set(key, value)
        else next.delete(key)
        if (key !== 'page' && key !== 'view') next.delete('page')
        return next
      },
      { replace: key === 'q' },
    )
  }
  function clearFilters() {
    setParams((current) => {
      const next = new URLSearchParams(current)
      for (const key of ['q', 'status', 'page']) next.delete(key)
      return next
    })
  }

  // Single-key shortcuts: "/" jumps to the search field, "n" starts a new workflow.
  const hotkeys = useMemo(
    () => ({ '/': () => searchInput.current?.focus(), n: () => openDialog({ kind: 'create' }) }),
    [],
  )
  useHotkeys(hotkeys)
  function openDialog(next: DialogState) {
    setNotice(null)
    setDialogError('')
    setDialog(next)
  }

  const query = useQuery({
    queryKey: ['workflows', 'list', debouncedSearch.trim(), filter, sort, page, pageSize],
    queryFn: () =>
      listWorkflows({
        name: debouncedSearch.trim() || undefined,
        status: filter ? [filter] : undefined,
        page,
        size: pageSize,
        sort,
      }),
    staleTime: 0,
  })
  const counts = useQuery({
    queryKey: ['workflows', 'counts'],
    queryFn: async () => {
      const results = await Promise.all(
        [undefined, 'ACTIVE', 'DRAFT', 'ARCHIVED'].map((status) =>
          listWorkflows({ size: 1, status: status ? [status as WorkflowStatus] : undefined }),
        ),
      )
      return results.map((r) => r.totalElements)
    },
  })
  const sample = useMutation({
    mutationFn: () => createSample('ai-review-triage'),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['workflows'] })
    },
  })
  const createAiSample = () => sample.mutate(undefined, {
    onSuccess: (workflow) => navigate(`/workflows/${workflow.id}`),
  })
  const data = query.data

  function handled(result: ActionResult) {
    if (result.to) navigate(result.to)
    else if (result.message) setNotice({ tone: 'success', text: result.message })
  }
  /** Runs an action started from a dialog: close on success, keep the dialog open with the reason on failure. */
  function fromDialog(request: ActionRequest) {
    setDialogError('')
    actions.perform(request, {
      onSuccess: (result) => {
        setDialog(null)
        handled(result)
      },
      onError: (error) => setDialogError(describeActionError(error, request.kind)),
    })
  }
  /** Runs an action started from a card button: failures appear as a message above the list. */
  function fromCard(kind: 'run' | 'duplicate' | 'activate' | 'restore', workflow: WorkflowSummary) {
    setNotice(null)
    actions.perform({ kind, workflow }, {
      onSuccess: handled,
      onError: (error) => setNotice({ tone: 'danger', text: describeActionError(error, kind) }),
    })
  }
  function onCardAction(kind: WorkflowAction, workflow: WorkflowSummary) {
    if (kind === 'rename' || kind === 'archive' || kind === 'deactivate' || kind === 'delete') openDialog({ kind, workflow })
    else void fromCard(kind, workflow)
  }

  // Archiving the final item on a page should take the user to the preceding page.
  useEffect(() => {
    if (data && page > 0 && page >= data.totalPages) {
      setParams(
        (current) => {
          const next = new URLSearchParams(current)
          next.set('page', String(Math.max(0, data.totalPages - 1)))
          return next
        },
        { replace: true },
      )
    }
  }, [data, page, setParams])
  const filtered = !!search.trim() || !!filter
  const stats = [
    { label: 'Total workflows', detail: 'Excluding archived', icon: Workflow, color: 'bg-primary-soft text-primary-soft-foreground' },
    { label: 'Active', detail: 'Triggers are live', icon: Activity, color: 'bg-success-soft text-success-foreground' },
    { label: 'Drafts', detail: 'Ideas in progress', icon: FileEdit, color: 'bg-info-soft text-info-foreground' },
    { label: 'Archived', detail: 'Safely stored away', icon: Archive, color: 'bg-neutral-soft text-neutral-foreground' },
  ]
  const openCreate = () => openDialog({ kind: 'create' })

  return (
    <div className="mx-auto max-w-[1500px] px-5 py-7 sm:px-8 lg:px-10">
      {dialog?.kind === 'create' && (
        <WorkflowDetailsDialog
          mode="create"
          busy={actions.pending}
          error={dialogError}
          onCancel={() => setDialog(null)}
          onSubmit={(name, description) => void fromDialog({ kind: 'create', name, description })}
        />
      )}
      {dialog?.kind === 'rename' && (
        <WorkflowDetailsDialog
          mode="rename"
          initialName={dialog.workflow.name}
          initialDescription={dialog.workflow.description ?? ''}
          busy={actions.pending}
          error={dialogError}
          onCancel={() => setDialog(null)}
          onSubmit={(name, description) => void fromDialog({ kind: 'rename', workflow: dialog.workflow, name, description })}
        />
      )}
      {dialog?.kind === 'archive' && (
        <ConfirmDialog
          icon={ArchiveIcon}
          title={`Archive “${dialog.workflow.name}”?`}
          description="Automatic triggers will stop. Your workflow and execution history will be preserved. You can restore it later from the Archived tab."
          confirmLabel="Archive workflow"
          cancelLabel="Keep workflow"
          busy={actions.pending}
          error={dialogError}
          onCancel={() => setDialog(null)}
          onConfirm={() => void fromDialog({ kind: 'archive', workflow: dialog.workflow })}
        />
      )}
      {dialog?.kind === 'deactivate' && (
        <ConfirmDialog
          icon={Power}
          title={`Deactivate “${dialog.workflow.name}”?`}
          description="Its webhook will stop accepting requests and its schedule will stop firing. Runs already in progress finish. You can activate it again at any time."
          confirmLabel="Deactivate"
          cancelLabel="Keep active"
          busy={actions.pending}
          error={dialogError}
          onCancel={() => setDialog(null)}
          onConfirm={() => void fromDialog({ kind: 'deactivate', workflow: dialog.workflow })}
        />
      )}
      {dialog?.kind === 'delete' && (
        <ConfirmDialog
          icon={Trash2}
          tone="danger"
          title={`Delete “${dialog.workflow.name}” permanently?`}
          description="The workflow and all of its execution history are removed from this machine. This cannot be undone."
          confirmLabel="Delete permanently"
          cancelLabel="Keep it"
          busy={actions.pending}
          error={dialogError}
          onCancel={() => setDialog(null)}
          onConfirm={() => void fromDialog({ kind: 'delete', workflow: dialog.workflow })}
        />
      )}

      <PageHeader
        eyebrow="Your automation workspace"
        title="Workflows"
        description="Turn the things you do into things that happen."
        actions={
          <Button onClick={openCreate} aria-keyshortcuts="n" className="shadow-sm">
            <Plus className="size-4" aria-hidden />
            New workflow
          </Button>
        }
      />

      <section
        className="forge-banner relative mb-7 flex items-center justify-between overflow-hidden rounded-2xl border p-6 sm:px-8 sm:py-7"
        aria-label="Build your next workflow"
      >
        <div className="relative z-10 max-w-md">
          <span className="mb-3 inline-flex items-center gap-1.5 text-[10px] font-bold uppercase tracking-[0.14em] text-primary">
            <Zap className="size-3.5" aria-hidden />
            Make room for what matters
          </span>
          <h2 className="text-2xl font-semibold tracking-tight">Less busywork. More possibilities.</h2>
          <p className="mt-2 max-w-sm text-sm leading-6 text-muted-foreground">
            Connect your tools, shape your data, and let your workflows take it from here.
          </p>
          <button
            type="button"
            onClick={openCreate}
            className="mt-4 inline-flex items-center gap-2 text-xs font-semibold text-foreground hover:text-primary"
          >
            Build something useful
            <ArrowUpRight className="size-3.5" aria-hidden />
          </button>
          <button
            type="button"
            onClick={createAiSample}
            disabled={sample.isPending}
            className="mt-3 flex items-center gap-2 text-xs font-semibold text-foreground hover:text-primary disabled:opacity-60 sm:ml-5 sm:mt-4 sm:inline-flex"
          >
            <Sparkles className="size-3.5" aria-hidden />
            {sample.isPending ? 'Creating…' : 'Try the AI sample'}
          </button>
        </div>
        <div aria-hidden className="hidden items-center pr-4 lg:flex">
          <span className="flow-demo-node -rotate-6">
            <Zap className="size-5 text-orange-600" />
            <span>Trigger</span>
          </span>
          <span className="flow-demo-line" />
          <span className="flow-demo-node -translate-y-3">
            <Workflow className="size-5 text-blue-600" />
            <span>Automate</span>
          </span>
          <span className="flow-demo-line" />
          <span className="flow-demo-node rotate-6">
            <Check className="size-5 text-emerald-600" />
            <span>Done</span>
          </span>
        </div>
      </section>

      <div className="mb-8 grid grid-cols-2 gap-3 lg:grid-cols-4">
        {stats.map(({ label, detail, icon: Icon, color }, i) => (
          <div key={label} className="flex items-center gap-4 rounded-xl border bg-card p-4">
            <span className={cn('flex size-10 shrink-0 items-center justify-center rounded-xl', color)}>
              <Icon className="size-[18px]" aria-hidden />
            </span>
            <div>
              <p className="text-[11px] font-medium text-muted-foreground">{label}</p>
              <p className="mt-0.5 text-2xl font-semibold tracking-tight">{counts.data?.[i] ?? '—'}</p>
              <p className="mt-0.5 hidden text-[10px] text-muted-foreground sm:block">{detail}</p>
            </div>
          </div>
        ))}
      </div>

      <div className="mb-5 flex flex-wrap items-center justify-between gap-4 border-b pb-3">
        <SegmentedControl
          label="Filter workflows by status"
          value={filter}
          options={FILTERS.map((f) => ({ value: f.value, label: f.label }))}
          onChange={(value) => change('status', value)}
        />
        <Button
          variant="ghost"
          size="sm"
          onClick={() => {
            void client.invalidateQueries({ queryKey: ['workflows'] })
          }}
          disabled={query.isFetching}
          aria-label="Refresh workflows"
        >
          <RefreshCw className={cn('size-3.5', query.isFetching && 'animate-spin')} aria-hidden />
          Refresh
        </Button>
      </div>
      <div className="mb-5 flex flex-wrap items-center gap-3">
        <div className="relative min-w-48 flex-1 sm:max-w-sm">
          <Search aria-hidden className="pointer-events-none absolute left-3 top-3 size-4 text-muted-foreground" />
          <input
            ref={searchInput}
            aria-label="Search workflows"
            aria-keyshortcuts="/"
            placeholder="Search workflows…"
            value={search}
            maxLength={200}
            onChange={(e) => change('q', e.target.value)}
            className="h-10 w-full rounded-lg border bg-card pl-9 pr-9 text-sm placeholder:text-muted-foreground"
          />
          {search ? (
            <button
              type="button"
              aria-label="Clear search"
              onClick={() => change('q', '')}
              className="absolute right-2 top-2 rounded p-1 text-muted-foreground hover:bg-muted"
            >
              <X className="size-4" aria-hidden />
            </button>
          ) : (
            <kbd
              aria-hidden
              className="pointer-events-none absolute right-3 top-2.5 hidden rounded border bg-muted px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground sm:block"
            >
              /
            </kbd>
          )}
        </div>
        <label className="ml-auto flex items-center gap-2 text-xs text-muted-foreground">
          <span className="hidden sm:inline">Sort by</span>
          <select
            aria-label="Sort workflows"
            value={sort}
            onChange={(e) => change('sort', e.target.value)}
            className="h-10 rounded-lg border bg-card px-3 text-xs text-foreground"
          >
            {SORT_OPTIONS.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
        <SegmentedControl
          label="Workflow view"
          variant="icon"
          value={view}
          onChange={(value) => change('view', value)}
          options={[
            { value: 'grid', label: 'Grid', ariaLabel: 'Grid view', icon: <LayoutGrid className="size-4" aria-hidden /> },
            { value: 'list', label: 'List', ariaLabel: 'List view', icon: <List className="size-4" aria-hidden /> },
          ]}
        />
      </div>

      {notice && (
        <Notice tone={notice.tone} onDismiss={() => setNotice(null)} className="mb-4">
          {notice.text}
        </Notice>
      )}
      {sample.isError && (
        <Notice tone="danger" onDismiss={() => sample.reset()} className="mb-4">
          Could not create the sample: {toProblemDetail(sample.error)?.detail ?? 'the server could not be reached.'}
        </Notice>
      )}

      {query.isPending && <ListSkeleton view={view} />}
      {query.isError && (
        <div role="alert" className="rounded-2xl border bg-card p-10 text-center">
          <p className="text-sm text-destructive-foreground">Could not load workflows.</p>
          <p className="mt-1 text-xs text-muted-foreground">Check that the backend is running, then try again.</p>
          <Button variant="outline" size="sm" className="mt-4" onClick={() => void query.refetch()}>
            Try again
          </Button>
        </div>
      )}
      {data && !query.isError && data.items.length === 0 && (
        <EmptyState
          icon={filtered ? Search : Workflow}
          title={filtered ? 'No matching workflows' : 'No workflows yet. Let’s build your first.'}
          description={
            filtered
              ? 'Try a different name or clear your filters to see more workflows.'
              : 'Start with a trigger, connect a few steps, and make everyday tasks run themselves.'
          }
          action={
            filtered ? (
              <Button variant="outline" size="sm" onClick={clearFilters}>
                Clear filters
              </Button>
            ) : (
              <>
                <Button onClick={openCreate}>Create your first workflow</Button>
                <Button variant="outline" onClick={createAiSample} disabled={sample.isPending}>
                  <Sparkles className="size-4" aria-hidden />
                  Start from the AI sample
                </Button>
              </>
            )
          }
        />
      )}
      {data && !query.isError && data.items.length > 0 && (
        <>
          <ul
            aria-label="Workflows"
            className={cn(view === 'grid' ? 'grid gap-4 md:grid-cols-2 xl:grid-cols-3' : 'flex flex-col gap-3')}
          >
            {data.items.map((w) => (
              <WorkflowCard key={w.id} workflow={w} view={view} busy={actions.pending} onAction={onCardAction} />
            ))}
          </ul>
          <div className="mt-6 flex flex-wrap items-center justify-between gap-3 text-xs text-muted-foreground">
            <p>
              Showing {page * pageSize + 1}–{page * pageSize + data.items.length} of {data.totalElements} workflows
            </p>
            <div className="flex items-center gap-3">
              <Button
                variant="outline"
                size="sm"
                aria-label="Previous page"
                disabled={page === 0 || query.isFetching}
                onClick={() => change('page', String(page - 1))}
              >
                <ChevronLeft className="size-3.5" aria-hidden />
              </Button>
              <span>
                Page {page + 1} of {Math.max(1, data.totalPages)}
              </span>
              <Button
                variant="outline"
                size="sm"
                aria-label="Next page"
                disabled={page + 1 >= data.totalPages || query.isFetching}
                onClick={() => change('page', String(page + 1))}
              >
                <ChevronRight className="size-3.5" aria-hidden />
              </Button>
            </div>
          </div>
        </>
      )}
    </div>
  )
}

/** Placeholder cards while the first page loads (the screen-reader text is the actual status message). */
function ListSkeleton({ view }: { view: 'grid' | 'list' }) {
  return (
    <div role="status" aria-live="polite">
      <span className="sr-only">Loading workflows…</span>
      <div className={cn(view === 'grid' ? 'grid gap-4 md:grid-cols-2 xl:grid-cols-3' : 'flex flex-col gap-3')}>
        {Array.from({ length: view === 'grid' ? 6 : 4 }, (_, i) => (
          <div key={i} className="rounded-2xl border bg-card p-5">
            <Skeleton className="mb-4 size-11 rounded-xl" />
            <Skeleton className="h-4 w-2/3" />
            <Skeleton className="mt-3 h-3 w-full" />
            <Skeleton className="mt-5 h-3 w-1/2" />
          </div>
        ))}
      </div>
    </div>
  )
}
