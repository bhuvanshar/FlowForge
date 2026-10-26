import type { ExecutionDetail, ExecutionError } from '@/lib/api/workflows'
import type { ExecutionStatus, NodeExecutionStatus } from '@/lib/workflow/definition'

/** Wire format of the backend's Server-Sent Events (see docs/API_CONTRACTS.md). Events carry statuses, never payloads. */
export interface NodeSnapshot {
  nodeId: string
  nodeName: string
  sequence: number
  status: NodeExecutionStatus
  durationMs?: number
  error?: ExecutionError
}

export interface SnapshotEvent {
  executionId: string
  status: ExecutionStatus
  error?: ExecutionError
  startedAt?: string
  finishedAt?: string
  durationMs?: number
  nodes: NodeSnapshot[]
}

export const EVENT_NAMES = [
  'execution.started',
  'node.started',
  'node.succeeded',
  'node.failed',
  'node.skipped',
  'execution.completed',
  'execution.failed',
  'execution.cancelled',
] as const

export interface LiveEvent {
  seq: number
  type: string
  executionId: string
  nodeId?: string
  nodeName?: string
  status: string
  at: string
  durationMs?: number
  error?: ExecutionError
}

const TERMINAL_EVENTS: readonly string[] = ['execution.completed', 'execution.failed', 'execution.cancelled']
export const isTerminalEvent = (name: string) => TERMINAL_EVENTS.includes(name)

export interface LiveNode {
  status: NodeExecutionStatus
  durationMs?: number
  error?: ExecutionError
}

/** What the stream has told us so far. Merged over persisted data by {@link mergeLive}; never the source of truth. */
export interface LiveState {
  executionId: string
  status?: ExecutionStatus
  error?: ExecutionError
  durationMs?: number
  nodes: Record<string, LiveNode>
}

export const emptyLiveState = (executionId: string): LiveState => ({ executionId, nodes: {} })

const NODE_RANK: Record<NodeExecutionStatus, number> = { PENDING: 0, RUNNING: 1, SUCCEEDED: 2, FAILED: 2, SKIPPED: 2 }
const EXECUTION_RANK: Record<ExecutionStatus, number> = { QUEUED: 0, RUNNING: 1, SUCCEEDED: 2, FAILED: 2, CANCELLED: 2 }

/** The later of two node statuses. Statuses only move forward, so duplicated or reordered events cannot regress the view. */
export function laterNodeStatus(a: NodeExecutionStatus, b: NodeExecutionStatus | undefined): NodeExecutionStatus {
  return b !== undefined && NODE_RANK[b] > NODE_RANK[a] ? b : a
}

export function laterExecutionStatus(a: ExecutionStatus, b: ExecutionStatus | undefined): ExecutionStatus {
  return b !== undefined && EXECUTION_RANK[b] > EXECUTION_RANK[a] ? b : a
}

function withNode(state: LiveState, nodeId: string, next: LiveNode): LiveState {
  const current = state.nodes[nodeId]
  const status = laterNodeStatus(current?.status ?? 'PENDING', next.status)
  if (current && current.status === status && status !== next.status) return state // stale update
  return { ...state, nodes: { ...state.nodes, [nodeId]: { ...current, ...next, status } } }
}

/** Applies the `snapshot` event: persisted truth at (re)connect time. */
export function applySnapshot(state: LiveState, snapshot: SnapshotEvent): LiveState {
  let next: LiveState = {
    ...state,
    status: laterExecutionStatus(state.status ?? 'QUEUED', snapshot.status),
    error: snapshot.error ?? state.error,
    durationMs: snapshot.durationMs ?? state.durationMs,
  }
  for (const node of snapshot.nodes) {
    next = withNode(next, node.nodeId, { status: node.status, durationMs: node.durationMs, error: node.error })
  }
  return next
}

/** Applies one live event. Unknown event types and events of another execution are ignored. */
export function applyEvent(state: LiveState, name: string, event: LiveEvent): LiveState {
  if (event.executionId !== state.executionId) return state
  switch (name) {
    case 'execution.started':
      return { ...state, status: laterExecutionStatus(state.status ?? 'QUEUED', 'RUNNING') }
    case 'node.started':
    case 'node.succeeded':
    case 'node.failed':
    case 'node.skipped':
      return event.nodeId
        ? withNode(state, event.nodeId, {
            status: event.status as NodeExecutionStatus,
            durationMs: event.durationMs,
            error: event.error,
          })
        : state
    case 'execution.completed':
    case 'execution.failed':
    case 'execution.cancelled':
      return {
        ...state,
        status: laterExecutionStatus(state.status ?? 'QUEUED', event.status as ExecutionStatus),
        error: event.error ?? state.error,
        durationMs: event.durationMs ?? state.durationMs,
      }
    default:
      return state
  }
}

/**
 * Overlays live statuses on the persisted execution: whichever is further along wins, so the UI shows a transition as soon
 * as the event arrives, and persisted values (inputs, outputs, final timings) take over after the refetch.
 */
export function mergeLive(data: ExecutionDetail, live: LiveState | undefined): ExecutionDetail {
  if (!live || live.executionId !== data.id) return data
  return {
    ...data,
    status: laterExecutionStatus(data.status, live.status),
    error: data.error ?? live.error,
    durationMs: data.durationMs ?? live.durationMs,
    nodes: data.nodes.map((node) => {
      const fromStream = live.nodes[node.nodeId]
      if (!fromStream) return node
      const status = laterNodeStatus(node.status, fromStream.status)
      return {
        ...node,
        status,
        durationMs: node.durationMs ?? fromStream.durationMs,
        error: node.error ?? (status === 'FAILED' ? fromStream.error : undefined),
      }
    }),
  }
}
