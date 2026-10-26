import { apiClient } from '@/lib/api/client'

/** Browser constant: the connection is closed and will not retry (EventSource.CLOSED). */
export const READY_STATE_CLOSED = 2

/** Opens the live event stream of an execution. Uses the global `EventSource` at call time so tests can replace it. */
export function openExecutionEvents(executionId: string): EventSource | null {
  if (typeof EventSource === 'undefined') return null
  const base = apiClient.defaults.baseURL ?? '/api/v1'
  return new EventSource(`${base}/executions/${encodeURIComponent(executionId)}/events`)
}
