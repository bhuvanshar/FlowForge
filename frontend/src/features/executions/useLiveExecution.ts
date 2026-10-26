import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useRef, useState } from 'react'

import { getExecution, type ExecutionDetail } from '@/lib/api/workflows'

import { openExecutionEvents, READY_STATE_CLOSED } from './eventSource'
import { isTerminalExecution } from './format'
import {
  applyEvent,
  applySnapshot,
  emptyLiveState,
  EVENT_NAMES,
  isTerminalEvent,
  mergeLive,
  type LiveEvent,
  type LiveState,
  type SnapshotEvent,
} from './liveState'

/**
 * - `idle`: no stream needed (nothing selected, or the execution already finished)
 * - `connecting`: opening the first connection
 * - `open`: live updates are flowing
 * - `reconnecting`: the connection dropped; the browser retries and the server resends a fresh snapshot
 * - `polling`: streaming is unavailable (closed by the server or unsupported); the persisted state is polled instead
 * - `ended`: the execution finished and the stream was closed
 */
export type Connection = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'polling' | 'ended'

export const POLL_MS = 1000
/** Burst of events triggers one refetch of the persisted result. */
export const REFETCH_DEBOUNCE_MS = 150

export interface LiveExecution {
  /** Persisted execution with live statuses overlaid; undefined until the first load. */
  execution: ExecutionDetail | undefined
  connection: Connection
  isLoading: boolean
  isError: boolean
  error: unknown
}

/**
 * Loads an execution (REST, the source of truth) and keeps it live.
 *
 * While the execution is unfinished the hook opens the SSE stream. Every snapshot or event updates the live overlay at once
 * and schedules a refetch of the persisted result (inputs, outputs, timings), so events that were missed or arrive out of
 * order can never leave the display wrong: after the refetch, persisted values win. If the stream is unavailable the hook
 * falls back to polling every second.
 */
export function useLiveExecution(executionId: string | undefined): LiveExecution {
  const queryClient = useQueryClient()
  // Overlay and connection state are tagged with the execution they belong to, so showing another execution starts clean
  // without resetting state during render or in an effect.
  const [liveState, setLiveState] = useState<{ id: string; value: LiveState } | undefined>()
  const [connectionState, setConnectionState] = useState<{ id: string; value: Connection } | undefined>()
  const connectionRef = useRef<Connection>('idle')
  const update = useCallback(
    (id: string, next: Connection) => {
      connectionRef.current = next
      setConnectionState({ id, value: next })
    },
    [],
  )

  const query = useQuery({
    queryKey: ['execution', executionId],
    queryFn: () => getExecution(executionId!),
    enabled: !!executionId,
    refetchInterval: (q) => {
      const status = q.state.data?.status
      if (!status || isTerminalExecution(status)) return false
      // Polling is only needed when the stream cannot be relied on.
      return connectionRef.current === 'open' ? false : POLL_MS
    },
  })
  const status = query.data?.status
  const needsStream = !!executionId && !!status && !isTerminalExecution(status)
  const streamSupported = typeof EventSource !== 'undefined'

  useEffect(() => {
    if (!executionId || !needsStream || !streamSupported) return
    const source = openExecutionEvents(executionId)
    if (!source) return
    connectionRef.current = 'idle'
    const patch = (change: (s: LiveState) => LiveState) =>
      setLiveState((current) => ({
        id: executionId,
        value: change(current?.id === executionId ? current.value : emptyLiveState(executionId)),
      }))
    let timer: ReturnType<typeof setTimeout> | undefined
    const refetchSoon = () => {
      if (timer) return
      timer = setTimeout(() => {
        timer = undefined
        void queryClient.invalidateQueries({ queryKey: ['execution', executionId] })
      }, REFETCH_DEBOUNCE_MS)
    }

    source.addEventListener('open', () => update(executionId, 'open'))
    source.addEventListener('snapshot', (e) => {
      update(executionId, 'open')
      patch((s) => applySnapshot(s, parse<SnapshotEvent>(e)))
      refetchSoon() // the persisted result may be ahead of what we last loaded (reconnect, missed events)
    })
    for (const name of EVENT_NAMES) {
      source.addEventListener(name, (e) => {
        patch((s) => applyEvent(s, name, parse<LiveEvent>(e)))
        refetchSoon()
        if (isTerminalEvent(name)) {
          source.close()
          update(executionId, 'ended')
          void queryClient.invalidateQueries({ queryKey: ['execution', executionId] })
        }
      })
    }
    source.addEventListener('error', () => {
      if (source.readyState === READY_STATE_CLOSED) {
        update(executionId, 'polling') // the server refused or ended the stream; fall back to persisted state
        refetchSoon()
      } else {
        update(executionId, 'reconnecting') // browser retries; the next snapshot resynchronizes
      }
    })

    return () => {
      if (timer) clearTimeout(timer)
      source.close()
    }
  }, [executionId, needsStream, streamSupported, queryClient, update])

  const live = liveState && liveState.id === executionId ? liveState.value : undefined
  const recorded = connectionState && connectionState.id === executionId ? connectionState.value : 'idle'
  let connection: Connection = recorded
  if (needsStream && !streamSupported) connection = 'polling'
  else if (!needsStream && recorded !== 'ended') connection = 'idle' // finished (or already finished): no stream needed
  else if (needsStream && recorded === 'idle') connection = 'connecting' // stream requested, nothing heard yet

  return {
    execution: query.data ? mergeLive(query.data, live) : undefined,
    connection,
    isLoading: query.isPending && !!executionId,
    isError: query.isError,
    error: query.error,
  }
}

function parse<T>(event: Event): T {
  return JSON.parse((event as MessageEvent<string>).data) as T
}
