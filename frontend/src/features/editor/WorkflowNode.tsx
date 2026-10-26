import { Handle, Position, type NodeProps } from '@xyflow/react'
import { AlertTriangle } from 'lucide-react'
import { useMemo } from 'react'

import { cn } from '@/lib/utils'
import type { NodeExecutionStatus } from '@/lib/workflow/definition'

import { validateNodeConfiguration } from './config/registry'
import { useEditorStore } from './editorStore'
import { getNodeTypeSpec } from './nodeTypes'
import type { WorkflowFlowNode } from './types'

const STATUS_STYLES: Record<NodeExecutionStatus, string> = {
  PENDING: 'bg-muted-foreground/50',
  RUNNING: 'animate-pulse bg-blue-500',
  SUCCEEDED: 'bg-success',
  FAILED: 'bg-destructive',
  SKIPPED: 'bg-muted-foreground/30 ring-1 ring-muted-foreground',
}

/**
 * Reusable canvas node: icon, display name, node type, input handles (left), output handles (right) and an optional
 * execution status indicator. Handle ids come from the node type's spec, so edges reference backend handle ids.
 */
export function WorkflowNode({ id, data, selected }: NodeProps<WorkflowFlowNode>) {
  const spec = getNodeTypeSpec(data.nodeType)
  const Icon = spec.icon
  const branching = spec.outputs.length > 1
  const runStatus = useEditorStore((s) => s.runStatuses?.[id])
  const executionStatus = runStatus ?? data.executionStatus
  const issues = useEditorStore((s) => s.validation?.byNode[id])
  const errorCount = issues?.filter((i) => i.severity === 'ERROR').length ?? 0
  const warningCount = (issues?.length ?? 0) - errorCount
  const complete = useMemo(
    () => validateNodeConfiguration(data.nodeType, data.configuration).valid,
    [data.nodeType, data.configuration],
  )

  return (
    <div
      data-testid="workflow-node"
      data-node-type={data.nodeType}
      data-running={executionStatus === 'RUNNING' ? 'true' : undefined}
      data-validation={errorCount > 0 ? 'error' : warningCount > 0 ? 'warning' : undefined}
      className={cn(
        'relative w-48 rounded-xl border bg-card px-4 py-3 shadow-sm',
        warningCount > 0 && 'border-amber-500',
        errorCount > 0 && 'border-destructive ring-2 ring-destructive/40',
        executionStatus === 'RUNNING' && 'border-blue-500 ring-2 ring-blue-400/60',
        selected && 'border-primary ring-2 ring-ring',
        executionStatus === 'FAILED' && 'border-destructive',
      )}
    >
      {spec.inputs.map((id) => (
        <Handle key={id} type="target" position={Position.Left} id={id} className="!size-3" />
      ))}
      {/* Branch labels sit at the right edge; keep header badges clear of them. */}
      <div className={cn('flex items-center gap-2', branching && 'pr-8')}>
        <Icon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
        <span className="min-w-0 flex-1 truncate text-sm font-medium" title={data.name}>
          {data.name}
        </span>
        {(errorCount > 0 || warningCount > 0) && (
          <span
            role="img"
            aria-label={problemLabel(errorCount, warningCount)}
            title={issues?.map((i) => i.message).join('\n')}
            className={cn(
              'shrink-0 rounded-full px-1.5 text-[10px] font-semibold text-white',
              errorCount > 0 ? 'bg-destructive' : 'bg-amber-500',
            )}
          >
            {errorCount + warningCount}
          </span>
        )}
        {!complete && (
          <span role="img" aria-label="Configuration incomplete" title="Configuration incomplete" className="shrink-0 text-amber-500">
            <AlertTriangle className="size-3.5" aria-hidden />
          </span>
        )}
        {executionStatus && (
          <span
            role="img"
            aria-label={`Status: ${executionStatus}`}
            data-status={executionStatus}
            title={executionStatus}
            className={cn('size-2.5 shrink-0 rounded-full', STATUS_STYLES[executionStatus])}
          />
        )}
      </div>
      <div className="mt-0.5 truncate text-xs text-muted-foreground" title={data.nodeType}>
        {spec.label}
      </div>
      {spec.outputs.map((id, index) => (
        <Handle
          key={id}
          type="source"
          position={Position.Right}
          id={id}
          className="!size-3"
          style={branching ? { top: `${((index + 1) / (spec.outputs.length + 1)) * 100}%` } : undefined}
        />
      ))}
      {branching &&
        spec.outputs.map((id, index) => (
          <span
            key={id}
            className="pointer-events-none absolute right-4 -translate-y-1/2 text-[10px] text-muted-foreground"
            style={{ top: `${((index + 1) / (spec.outputs.length + 1)) * 100}%` }}
          >
            {id}
          </span>
        ))}
    </div>
  )
}

function problemLabel(errors: number, warnings: number): string {
  const parts = []
  if (errors > 0) parts.push(`${errors} ${errors === 1 ? 'error' : 'errors'}`)
  if (warnings > 0) parts.push(`${warnings} ${warnings === 1 ? 'warning' : 'warnings'}`)
  return parts.join(', ')
}
