import {
  Archive, ArrowUpRight, Copy, GitBranch, History, Pencil, Play, Power, PowerOff, RotateCcw, Trash2, Workflow,
} from 'lucide-react'
import { Link } from 'react-router-dom'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ActionMenu, type MenuItem } from '@/components/ui/menu'
import { StatusBadge } from '@/features/executions/StatusBadge'
import type { WorkflowSummary } from '@/lib/api/workflows'
import { cn } from '@/lib/utils'

import { menuActions, relativeTime, WORKFLOW_STATUS, type WorkflowAction } from './workflowStatus'

function updatedLabel(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? 'Date unavailable'
    : date.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

const MENU: Record<Exclude<WorkflowAction, 'run'>, Pick<MenuItem, 'label' | 'icon' | 'tone'>> = {
  rename: { label: 'Rename…', icon: Pencil },
  duplicate: { label: 'Duplicate', icon: Copy },
  activate: { label: 'Activate', icon: Power },
  deactivate: { label: 'Deactivate…', icon: PowerOff },
  archive: { label: 'Archive…', icon: Archive },
  restore: { label: 'Restore as draft', icon: RotateCcw },
  delete: { label: 'Delete permanently…', icon: Trash2, tone: 'danger' },
}

export function WorkflowCard({
  workflow: w,
  view,
  busy,
  onAction,
}: {
  workflow: WorkflowSummary
  view: 'grid' | 'list'
  busy: boolean
  onAction: (action: WorkflowAction, workflow: WorkflowSummary) => void
}) {
  const status = WORKFLOW_STATUS[w.status]
  const last = w.lastExecution
  return (
    <li
      className={cn(
        'workflow-card group flex flex-col overflow-hidden rounded-2xl border bg-card',
        view === 'list' && 'sm:flex-row sm:items-center',
      )}
    >
      <Link
        to={`/workflows/${w.id}`}
        aria-label={`${w.name}, ${w.nodeCount} nodes, ${status.label}`}
        className={cn('block min-w-0 flex-1 p-5 pb-3', view === 'list' && 'sm:flex sm:items-center sm:gap-5 sm:pb-5')}
      >
        <span className="mb-4 flex size-11 items-center justify-center rounded-xl border border-primary/15 bg-primary-soft text-primary-soft-foreground sm:mb-0 sm:shrink-0">
          <Workflow className="size-5" aria-hidden />
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="flex items-center gap-2 text-[15px] font-semibold tracking-tight">
            <span className="truncate">{w.name}</span>
            <ArrowUpRight
              className="size-4 shrink-0 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100 group-focus-within:opacity-100"
              aria-hidden
            />
          </h2>
          <p className="mt-1.5 truncate text-xs leading-5 text-muted-foreground">
            {w.description || 'No description yet.'}
          </p>
          <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-muted-foreground">
            <span className="inline-flex items-center gap-1">
              <GitBranch className="size-3.5" aria-hidden />
              {w.nodeCount} nodes
            </span>
            <span>v{w.version}</span>
            <span>Updated {updatedLabel(w.updatedAt)}</span>
          </div>
        </div>
      </Link>

      <div
        className={cn(
          'flex flex-wrap items-center gap-x-3 gap-y-1.5 px-5 pb-4',
          view === 'list' && 'sm:w-80 sm:shrink-0 sm:pb-0',
        )}
      >
        <Badge tone={status.tone} dot title={status.hint} data-testid="workflow-status">
          {status.label}
        </Badge>
        {last ? (
          <Link
            to={`/executions/${last.id}`}
            aria-label={`Last run of ${w.name}`}
            className="inline-flex items-center gap-1.5 rounded text-[11px] text-muted-foreground hover:text-foreground"
          >
            <span>Last run</span>
            <StatusBadge status={last.status} />
            <span>{relativeTime(last.finishedAt ?? last.createdAt)}</span>
          </Link>
        ) : (
          <span className="text-[11px] text-muted-foreground">{w.status === 'ARCHIVED' ? 'Never run' : 'Not run yet'}</span>
        )}
      </div>

      <div
        className={cn(
          'flex items-center gap-1 border-t bg-muted/30 px-4 py-2.5',
          view === 'list' && 'sm:mr-3 sm:border-0 sm:bg-transparent sm:px-1',
        )}
      >
        {w.status !== 'ARCHIVED' && (
          <Button
            variant="outline"
            size="sm"
            className="h-8 text-xs"
            disabled={busy}
            aria-label={`Run ${w.name}`}
            onClick={() => onAction('run', w)}
          >
            <Play className="size-3" aria-hidden />
            Run
          </Button>
        )}
        <Button asChild variant="ghost" size="icon" className="ml-auto">
          <Link
            to={`/workflows/${w.id}/executions`}
            aria-label={`Execution history of ${w.name}`}
            title="Execution history"
          >
            <History className="size-4" aria-hidden />
          </Link>
        </Button>
        <ActionMenu
          label={`More actions for ${w.name}`}
          disabled={busy}
          items={menuActions(w.status).map((action) => ({
            key: action,
            ...MENU[action as Exclude<WorkflowAction, 'run'>],
            onSelect: () => onAction(action, w),
          }))}
        />
      </div>
    </li>
  )
}
