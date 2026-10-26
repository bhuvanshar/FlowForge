import type { Tone } from '@/components/ui/tones'
import type { ExecutionStatus, WorkflowStatus } from '@/lib/workflow/definition'

export type WorkflowAction =
  | 'run' | 'rename' | 'duplicate' | 'activate' | 'deactivate' | 'archive' | 'restore' | 'delete'


/** How a workflow's lifecycle status is shown. Active means its webhook/schedule trigger is live. */
export const WORKFLOW_STATUS: Record<WorkflowStatus, { label: string; tone: Tone; hint: string }> = {
  ACTIVE: { label: 'Active', tone: 'success', hint: 'Its webhook or schedule trigger is live' },
  DRAFT: { label: 'Draft', tone: 'warning', hint: 'Not activated yet' },
  INACTIVE: { label: 'Inactive', tone: 'neutral', hint: 'Automatic triggers are stopped' },
  ARCHIVED: { label: 'Archived', tone: 'neutral', hint: 'Hidden from the main list; history is kept' },
}

export const EXECUTION_TONE: Record<ExecutionStatus, Tone> = {
  QUEUED: 'neutral',
  RUNNING: 'info',
  SUCCEEDED: 'success',
  FAILED: 'danger',
  CANCELLED: 'warning',
}

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/** "just now", "5 min ago", "3 h ago", "2 d ago", otherwise the date. Empty for an unreadable timestamp. */
export function relativeTime(iso: string | undefined, now: number = Date.now()): string {
  if (!iso) return ''
  const then = new Date(iso).getTime()
  if (Number.isNaN(then)) return ''
  const age = now - then
  if (age < MINUTE) return 'just now'
  if (age < HOUR) return `${Math.floor(age / MINUTE)} min ago`
  if (age < DAY) return `${Math.floor(age / HOUR)} h ago`
  if (age < 14 * DAY) return `${Math.floor(age / DAY)} d ago`
  return new Date(then).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

/** Which menu actions a workflow in this status offers. Archived workflows are read-only: restore, copy or delete them. */
export function menuActions(status: WorkflowStatus): WorkflowAction[] {
  switch (status) {
    case 'ARCHIVED': return ['restore', 'duplicate', 'delete']
    case 'ACTIVE': return ['rename', 'duplicate', 'deactivate', 'archive']
    default: return ['rename', 'duplicate', 'activate', 'archive']
  }
}

