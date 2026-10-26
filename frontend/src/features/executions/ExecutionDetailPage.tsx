import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { Notice } from '@/components/ui/notice'
import { toProblemDetail } from '@/lib/api/client'
import { cancelExecution, type ExecutionDetail } from '@/lib/api/workflows'
import axios from 'axios'

import { formatDateTime, formatDuration, formatTrigger, isTerminalExecution } from './format'
import { ExecutionInspector } from './ExecutionInspector'
import { StatusBadge } from './StatusBadge'
import { ConnectionBadge } from './ConnectionBadge'
import { useLiveExecution } from './useLiveExecution'

export function ExecutionDetailPage() {
  const { executionId = '' } = useParams()
  const live = useLiveExecution(executionId)
  const queryClient = useQueryClient()
  const cancel = useMutation({
    mutationFn: (id: string) => cancelExecution(id),
    onSuccess: (data, id) => {
      queryClient.setQueryData<ExecutionDetail>(['execution', id], (current) =>
        current && isTerminalExecution(current.status) ? current : data)
      void queryClient.invalidateQueries({ queryKey: ['execution', id] })
      void queryClient.invalidateQueries({ queryKey: ['executions'] })
      void queryClient.invalidateQueries({ queryKey: ['workflows'] })
    },
    onError: (_error, id) => {
      // A conflict often means the run finished while the cancellation was in flight.
      void queryClient.invalidateQueries({ queryKey: ['execution', id] })
    },
  })

  if (live.isLoading) return <p className="p-6 text-sm text-muted-foreground">Loading execution…</p>
  if (live.isError && !live.execution) {
    const notFound = axios.isAxiosError(live.error) && live.error.response?.status === 404
    return (
      <p role="alert" className="p-6 text-sm text-destructive">
        {notFound ? 'Execution not found.' : 'Could not load the execution.'}
      </p>
    )
  }

  const e = live.execution
  if (!e) return <p className="p-6 text-sm text-muted-foreground">Loading execution…</p>
  const failedNode = e.nodes.find((n) => n.status === 'FAILED')
  return (
    <div className="flex flex-col gap-4 p-6">
      <div>
        <p className="text-xs text-muted-foreground">
          <Link to="/" className="hover:underline">Workflows</Link>
          {' / '}
          <Link to={`/workflows/${e.workflowId}/executions`} className="hover:underline">Execution history</Link>
        </p>
        <div className="flex items-center gap-3">
          <h1 className="text-lg font-semibold">Execution</h1>
          <StatusBadge status={e.status} />
          <ConnectionBadge connection={live.connection} />
          {!isTerminalExecution(e.status) && (
            <Button size="sm" variant="outline" disabled={cancel.isPending && cancel.variables === executionId} onClick={() => cancel.mutate(executionId)}>
              Cancel
            </Button>
          )}
        </div>
        <p className="font-mono text-xs text-muted-foreground">{e.id}</p>
      </div>

      {cancel.isError && cancel.variables === executionId && (
        <Notice tone="danger" onDismiss={() => cancel.reset()}>
          Could not cancel this execution. {toProblemDetail(cancel.error)?.detail ?? 'Check the connection and try again.'}
        </Notice>
      )}

      <dl className="grid grid-cols-2 gap-x-6 gap-y-1 text-sm md:grid-cols-4">
        <div><dt className="text-xs text-muted-foreground">Workflow version</dt><dd>v{e.workflowVersion}</dd></div>
        <div>
          <dt className="text-xs text-muted-foreground">Trigger</dt>
          <dd>
            {formatTrigger(e.triggerType)}
            {e.scheduledFor && <span className="text-muted-foreground"> · for {formatDateTime(e.scheduledFor)}</span>}
          </dd>
        </div>
        <div><dt className="text-xs text-muted-foreground">Started</dt><dd>{formatDateTime(e.startedAt ?? e.createdAt)}</dd></div>
        <div><dt className="text-xs text-muted-foreground">Finished</dt><dd>{formatDateTime(e.finishedAt)}</dd></div>
        <div><dt className="text-xs text-muted-foreground">Duration</dt><dd>{formatDuration(e.durationMs)}</dd></div>
      </dl>

      {e.error && (
        <div role="alert" className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm">
          <p className="font-semibold">
            {e.status === 'CANCELLED' ? 'Cancelled' : 'Failed'}
            {failedNode && <> at node “{failedNode.nodeName}”</>}
          </p>
          <p><span className="font-mono text-xs">{e.error.code}</span> {e.error.message}</p>
        </div>
      )}

      <ExecutionInspector execution={e} className="h-[28rem]" />

      <section aria-label="Execution log">
        <h2 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Execution log</h2>
        {e.logs.length === 0 ? (
          <p className="text-sm text-muted-foreground">No log lines.</p>
        ) : (
          <ul className="rounded-md bg-muted p-2 font-mono text-xs">
            {e.logs.map((line, index) => (
              <li key={index}>
                {new Date(line.at).toLocaleTimeString()} [{line.level}] {line.nodeId && <span>({line.nodeId}) </span>}
                {line.message}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
