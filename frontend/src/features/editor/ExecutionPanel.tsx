import { useQuery } from '@tanstack/react-query'
import { ChevronDown, ChevronUp } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'

import { ExecutionInspector } from '@/features/executions/ExecutionInspector'
import { formatDuration, isTerminalExecution } from '@/features/executions/format'
import { StatusBadge } from '@/features/executions/StatusBadge'
import { ConnectionBadge } from '@/features/executions/ConnectionBadge'
import { useLiveExecution } from '@/features/executions/useLiveExecution'
import { listExecutions } from '@/lib/api/workflows'
import type { NodeExecutionStatus } from '@/lib/workflow/definition'

import { useEditorStore } from './editorStore'

/**
 * Bottom execution inspector. Shows the execution started from the editor (or, on opening a saved workflow, its latest one):
 * nodes in execution order with status and duration, and the input/output of the selected node. Node statuses are also
 * shown on the canvas. Opens automatically when a run starts.
 */
export function ExecutionPanel() {
  const workflowId = useEditorStore((s) => s.workflowId)
  const activeId = useEditorStore((s) => s.activeExecutionId)
  const setActive = useEditorStore((s) => s.setActiveExecutionId)
  const setRunStatuses = useEditorStore((s) => s.setRunStatuses)
  const selectOnly = useEditorStore((s) => s.selectOnly)
  const request = useEditorStore((s) => s.inspectorRequest)
  const [open, setOpen] = useState(false)
  const [seenRequest, setSeenRequest] = useState(request)
  if (request !== seenRequest) {
    // A run was just started from the editor: open the panel (derived during render, not in an effect).
    setSeenRequest(request)
    setOpen(true)
  }

  // Opening a saved workflow: pick up its most recent execution (without opening the panel).
  const latest = useQuery({
    queryKey: ['executions', workflowId, 'latest'],
    queryFn: () => listExecutions(workflowId!, { size: 1 }),
    enabled: !!workflowId && !activeId,
    staleTime: 0,
  })
  const latestId = latest.data?.items[0]?.id
  useEffect(() => {
    if (!activeId && latestId) setActive(latestId)
  }, [activeId, latestId, setActive])

  const live = useLiveExecution(activeId ?? undefined)
  const data = live.execution
  useEffect(() => {
    if (!data) {
      setRunStatuses(null)
      return
    }
    setRunStatuses(Object.fromEntries(data.nodes.map((n) => [n.nodeId, n.status as NodeExecutionStatus])))
  }, [data, setRunStatuses])
  useEffect(() => () => setRunStatuses(null), [setRunStatuses])

  return (
    <section aria-label="Execution" className="shrink-0 border-t bg-card">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="flex h-9 w-full items-center gap-3 px-4 text-xs font-semibold uppercase tracking-wide text-muted-foreground hover:bg-accent"
      >
        Execution
        {data && (
          <span className="flex items-center gap-2 font-normal normal-case tracking-normal">
            <StatusBadge status={data.status} />
            <span>{formatDuration(data.durationMs)}</span>
            <span>v{data.workflowVersion}</span>
            <ConnectionBadge connection={live.connection} />
          </span>
        )}
        <span className="ml-auto">
          {open ? <ChevronDown className="size-4" aria-hidden /> : <ChevronUp className="size-4" aria-hidden />}
        </span>
      </button>
      {open && (
        <div className="h-64 overflow-hidden border-t p-3">
          {!activeId && <p className="text-sm text-muted-foreground">No executions yet. Save the workflow and click Run.</p>}
          {activeId && live.isLoading && <p className="text-sm text-muted-foreground">Loading execution…</p>}
          {activeId && live.isError && !data && (
            <p role="alert" className="text-sm text-destructive">Could not load the execution.</p>
          )}
          {data && (
            <div className="flex h-full flex-col gap-2">
              <div className="flex items-center gap-3 text-xs text-muted-foreground">
                {data.error && (
                  <span className="truncate text-destructive" title={data.error.message}>
                    {data.error.code}: {data.error.message}
                  </span>
                )}
                {!isTerminalExecution(data.status) && <span>Running…</span>}
                <Link to={`/executions/${data.id}`} className="ml-auto shrink-0 hover:underline">
                  Open details
                </Link>
                {workflowId && (
                  <Link to={`/workflows/${workflowId}/executions`} className="shrink-0 hover:underline">
                    History
                  </Link>
                )}
              </div>
              <ExecutionInspector
                execution={data}
                className="min-h-0 flex-1"
                onSelectNode={(nodeId) => selectOnly(nodeId)}
              />
            </div>
          )}
        </div>
      )}
    </section>
  )
}
