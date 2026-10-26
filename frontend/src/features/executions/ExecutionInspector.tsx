import { useState } from 'react'

import { cn } from '@/lib/utils'
import type { ExecutionDetail } from '@/lib/api/workflows'

import { firstFailedOrFirst, formatDuration } from './format'
import { JsonView } from './JsonView'
import { StatusBadge } from './StatusBadge'

interface Props {
  execution: ExecutionDetail
  /** Node to show first (e.g. the failed node); defaults to the first failed node, else the first node. */
  initialNodeId?: string
  /** Called when the user selects a node run (the editor highlights it on the canvas). */
  onSelectNode?: (nodeId: string) => void
  className?: string
}

/**
 * Execution order on the left (sequence number, name, status, duration), details of the selected node on the right:
 * input and output JSON, error, timing, and the log lines it wrote.
 */
export function ExecutionInspector({ execution, initialNodeId, onSelectNode, className }: Props) {
  // The user's own choice, remembered per execution. Without one, the inspector follows the node that is running now
  // (live runs), else shows the failed node, else the first one.
  const [manual, setManual] = useState<{ executionId: string; nodeId: string } | undefined>()
  const running = execution.nodes.find((n) => n.status === 'RUNNING')?.nodeId
  const automatic = running ?? initialNodeId ?? firstFailedOrFirst(execution.nodes)
  const selectedId = manual && manual.executionId === execution.id ? manual.nodeId : automatic

  const selected = execution.nodes.find((n) => n.nodeId === selectedId)
  const logs = execution.logs.filter((l) => l.nodeId === selectedId)

  return (
    <div className={cn('flex min-h-0 gap-3', className)}>
      <ol aria-label="Execution order" className="flex w-80 shrink-0 flex-col gap-1 overflow-auto">
        {execution.nodes.map((node) => (
          <li key={node.nodeId}>
            <button
              type="button"
              aria-current={node.nodeId === selectedId ? 'true' : undefined}
              data-node-id={node.nodeId}
              onClick={() => {
                setManual({ executionId: execution.id, nodeId: node.nodeId })
                onSelectNode?.(node.nodeId)
              }}
              className={cn(
                'flex w-full items-center gap-2 rounded-md border px-2 py-1.5 text-left text-sm hover:bg-accent',
                node.nodeId === selectedId && 'border-primary bg-accent',
              )}
            >
              <span className="w-5 shrink-0 text-xs text-muted-foreground">{node.sequence + 1}</span>
              <span className="min-w-0 flex-1 truncate" title={node.nodeName}>
                {node.nodeName}
              </span>
              <StatusBadge status={node.status} />
              <span className="w-14 shrink-0 text-right text-xs text-muted-foreground">{formatDuration(node.durationMs)}</span>
            </button>
          </li>
        ))}
      </ol>

      <div className="min-w-0 flex-1 overflow-auto" aria-label="Node details">
        {!selected && <p className="text-sm text-muted-foreground">Select a node to inspect its input and output.</p>}
        {selected && (
          <div className="flex flex-col gap-3">
            <div className="flex flex-wrap items-center gap-2">
              <h3 className="text-sm font-semibold">{selected.nodeName}</h3>
              <StatusBadge status={selected.status} />
              <span className="font-mono text-xs text-muted-foreground">{selected.nodeType}</span>
              <span className="text-xs text-muted-foreground">Duration: {formatDuration(selected.durationMs)}</span>
            </div>
            {selected.error && (
              <div role="alert" className="rounded-md border border-destructive/40 bg-destructive/10 p-2 text-sm">
                <span className="font-mono text-xs">{selected.error.code}</span>
                <p>{selected.error.message}</p>
              </div>
            )}
            {selected.status === 'SKIPPED' && (
              <p className="text-sm text-muted-foreground">This node was skipped: a branch was not taken or an earlier node stopped the run.</p>
            )}
            <section>
              <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Input</h4>
              <JsonView label="Input" value={selected.input} emptyLabel="No input (the node did not run)" />
            </section>
            <section>
              <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Output</h4>
              <JsonView label="Output" value={selected.output} emptyLabel="No output" />
            </section>
            {logs.length > 0 && (
              <section>
                <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Log</h4>
                <ul aria-label="Node log" className="font-mono text-xs">
                  {logs.map((line, index) => (
                    <li key={index}>
                      [{line.level}] {line.message}
                    </li>
                  ))}
                </ul>
              </section>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
