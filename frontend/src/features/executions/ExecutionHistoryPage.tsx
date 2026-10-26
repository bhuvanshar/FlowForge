import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { getWorkflow, listExecutions } from '@/lib/api/workflows'
import type { ExecutionStatus } from '@/lib/workflow/definition'

import { formatDateTime, formatDuration, formatTrigger } from './format'
import { StatusBadge } from './StatusBadge'

const PAGE_SIZE = 20
const FILTERS: { value: ExecutionStatus | ''; label: string }[] = [
  { value: '', label: 'All' },
  { value: 'SUCCEEDED', label: 'Succeeded' },
  { value: 'FAILED', label: 'Failed' },
  { value: 'RUNNING', label: 'Running' },
  { value: 'CANCELLED', label: 'Cancelled' },
]

export function ExecutionHistoryPage() {
  const { workflowId = '' } = useParams()
  return <WorkflowExecutionHistory key={workflowId} workflowId={workflowId} />
}

function WorkflowExecutionHistory({ workflowId }: { workflowId: string }) {
  const [status, setStatus] = useState<ExecutionStatus | ''>('')
  const [page, setPage] = useState(0)

  const workflow = useQuery({ queryKey: ['workflow-name', workflowId], queryFn: () => getWorkflow(workflowId), retry: false })
  const history = useQuery({
    queryKey: ['executions', workflowId, status, page],
    queryFn: () => listExecutions(workflowId, { status: status ? [status] : undefined, page, size: PAGE_SIZE }),
    // Keep fresh while something is still running.
    refetchInterval: (query) =>
      query.state.data?.items.some((e) => e.status === 'QUEUED' || e.status === 'RUNNING') ? 1500 : false,
  })
  // Derive the correction before committing an empty page without navigation controls.
  if (history.data && page > 0 && page >= history.data.totalPages) {
    setPage(Math.max(0, history.data.totalPages - 1))
  }

  return (
    <div className="flex flex-col gap-4 p-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <p className="text-xs text-muted-foreground">
            <Link to="/" className="hover:underline">Workflows</Link>
            {' / '}
            <Link to={`/workflows/${workflowId}`} className="hover:underline">{workflow.data?.name ?? 'Workflow'}</Link>
          </p>
          <h1 className="text-lg font-semibold">Execution history</h1>
        </div>
        <div role="group" aria-label="Filter by status" className="flex flex-wrap gap-1">
          {FILTERS.map((f) => (
            <Button
              key={f.label}
              size="sm"
              variant={status === f.value ? 'default' : 'outline'}
              aria-pressed={status === f.value}
              onClick={() => {
                setStatus(f.value)
                setPage(0)
              }}
            >
              {f.label}
            </Button>
          ))}
        </div>
      </div>

      {history.isPending && <p className="text-sm text-muted-foreground">Loading executions…</p>}
      {history.isError && <p role="alert" className="text-sm text-destructive">Could not load the execution history.</p>}
      {history.data && history.data.items.length === 0 && (
        <p className="text-sm text-muted-foreground">
          {status ? 'No executions with this status.' : 'This workflow has not been executed yet.'}
        </p>
      )}
      {history.data && history.data.items.length > 0 && (
        <>
          <div className="overflow-x-auto" role="region" aria-label="Execution history results" tabIndex={0}>
            <table className="w-full min-w-[640px] text-sm">
              <thead>
                <tr className="border-b text-left text-xs uppercase tracking-wide text-muted-foreground">
                  <th className="py-2 pr-3 font-medium">Started</th>
                  <th className="pr-3 font-medium">Status</th>
                  <th className="pr-3 font-medium">Trigger</th>
                  <th className="pr-3 font-medium">Duration</th>
                  <th className="pr-3 font-medium">Version</th>
                  <th className="pr-3 font-medium">Nodes</th>
                  <th className="font-medium">Result</th>
                </tr>
              </thead>
              <tbody>
                {history.data.items.map((e) => (
                  <tr key={e.id} className="border-b hover:bg-accent">
                    <td className="py-2 pr-3">
                      <Link to={`/executions/${e.id}`} className="font-medium hover:underline">
                        {formatDateTime(e.startedAt ?? e.createdAt)}
                      </Link>
                    </td>
                    <td className="pr-3"><StatusBadge status={e.status} /></td>
                    <td className="pr-3 text-xs" title={e.scheduledFor ? `Scheduled for ${formatDateTime(e.scheduledFor)}` : undefined}>
                      {formatTrigger(e.triggerType)}
                    </td>
                    <td className="pr-3">{formatDuration(e.durationMs)}</td>
                    <td className="pr-3">v{e.workflowVersion}</td>
                    <td className="pr-3 text-xs text-muted-foreground">
                      {e.succeededNodes}/{e.nodeCount} ok{e.skippedNodes > 0 && `, ${e.skippedNodes} skipped`}
                    </td>
                    <td className="max-w-md truncate text-xs" title={e.error?.message}>
                      {e.error ? (
                        <span className="text-destructive">
                          {e.failedNodeName && <strong>{e.failedNodeName}: </strong>}
                          {e.error.message}
                        </span>
                      ) : (
                        <span className="text-muted-foreground">–</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="flex items-center justify-between text-sm text-muted-foreground">
            <span>
              Page {history.data.page + 1} of {Math.max(1, history.data.totalPages)} · {history.data.totalElements} executions
            </span>
            <div className="flex gap-2">
              <Button size="sm" variant="outline" disabled={page === 0} onClick={() => setPage(page - 1)}>
                Previous
              </Button>
              <Button size="sm" variant="outline" disabled={page + 1 >= history.data.totalPages} onClick={() => setPage(page + 1)}>
                Next
              </Button>
            </div>
          </div>
        </>
      )}
    </div>
  )
}
