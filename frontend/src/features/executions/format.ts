import type { Tone } from '@/components/ui/tones'
import type { NodeRun } from '@/lib/api/workflows'
import type { ExecutionStatus, NodeExecutionStatus } from '@/lib/workflow/definition'

export function formatDuration(ms: number | undefined | null): string {
  if (ms === undefined || ms === null) return '–'
  if (ms < 1000) return `${ms} ms`
  const seconds = ms / 1000
  if (seconds < 60) return `${seconds.toFixed(seconds < 10 ? 2 : 1)} s`
  const minutes = Math.floor(seconds / 60)
  return `${minutes} min ${Math.round(seconds - minutes * 60)} s`
}

export function formatDateTime(iso: string | undefined): string {
  if (!iso) return '–'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString()
}

const TRIGGER_LABELS: Record<string, string> = { MANUAL: 'Manual', WEBHOOK: 'Webhook', SCHEDULE: 'Schedule' }

/** "Manual", "Webhook" or "Schedule"; executions from before Module 11 carry no trigger type and were manual. */
export function formatTrigger(triggerType: string | undefined): string {
  return TRIGGER_LABELS[triggerType ?? 'MANUAL'] ?? triggerType ?? 'Manual'
}

export const isTerminalExecution = (status: ExecutionStatus) =>
  status === 'SUCCEEDED' || status === 'FAILED' || status === 'CANCELLED'

export type AnyStatus = ExecutionStatus | NodeExecutionStatus

/** Human label and design-token tone for execution and node statuses. */
export const STATUS_STYLE: Record<AnyStatus, { label: string; tone: Tone }> = {
  QUEUED: { label: 'Queued', tone: 'neutral' },
  PENDING: { label: 'Pending', tone: 'neutral' },
  RUNNING: { label: 'Running', tone: 'info' },
  SUCCEEDED: { label: 'Succeeded', tone: 'success' },
  FAILED: { label: 'Failed', tone: 'danger' },
  CANCELLED: { label: 'Cancelled', tone: 'warning' },
  SKIPPED: { label: 'Skipped', tone: 'neutral' },
}

/** The node to open first in an inspector: the first failed node, else the first node. */
export function firstFailedOrFirst(nodes: NodeRun[]): string | undefined {
  return (nodes.find((n) => n.status === 'FAILED') ?? nodes[0])?.nodeId
}
