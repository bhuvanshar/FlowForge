import { AlertCircle, AlertTriangle, ChevronDown, ChevronUp, Loader2 } from 'lucide-react'
import { useMemo, useState } from 'react'

import type { ValidationIssue } from '@/lib/api/workflows'
import { cn } from '@/lib/utils'

import { selectValidationStale, useEditorStore } from './editorStore'

interface Props {
  /** Brings the node or edge of an issue into view (it is already selected by the panel). */
  onFocusIssue?: (issue: ValidationIssue) => void
}

/** Bottom panel listing backend validation problems. Clicking a problem selects the affected node or connection. */
export function ProblemsPanel({ onFocusIssue }: Props) {
  const [open, setOpen] = useState(true)
  const validation = useEditorStore((s) => s.validation)
  const status = useEditorStore((s) => s.validationStatus)
  const stale = useEditorStore(selectValidationStale)
  const isNew = useEditorStore((s) => s.workflowId === null)
  const nodes = useEditorStore((s) => s.nodes)
  const selectOnly = useEditorStore((s) => s.selectOnly)
  const selectEdgeOnly = useEditorStore((s) => s.selectEdgeOnly)

  const names = useMemo(() => new Map(nodes.map((n) => [n.id, n.data.name])), [nodes])
  const issues = useMemo(() => {
    const all = validation?.result.issues ?? []
    return [...all.filter((i) => i.severity === 'ERROR'), ...all.filter((i) => i.severity === 'WARNING')]
  }, [validation])
  const errors = validation?.result.errorCount ?? 0
  const warnings = validation?.result.warningCount ?? 0

  let summary: string
  if (isNew) summary = 'Save the workflow to validate it'
  else if (!validation) summary = status === 'failed' ? 'Validation failed to run' : 'Validating…'
  else if (errors === 0 && warnings === 0) summary = 'No problems'
  else summary = `${errors} ${errors === 1 ? 'error' : 'errors'}, ${warnings} ${warnings === 1 ? 'warning' : 'warnings'}`

  const focus = (issue: ValidationIssue) => {
    if (issue.nodeId && names.has(issue.nodeId)) selectOnly(issue.nodeId)
    else if (issue.edgeId) selectEdgeOnly(issue.edgeId)
    onFocusIssue?.(issue)
  }

  return (
    <section aria-label="Problems" className="shrink-0 border-t bg-card">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="flex h-9 w-full items-center gap-2 px-4 text-xs font-semibold uppercase tracking-wide text-muted-foreground hover:bg-accent"
      >
        Problems
        <span data-testid="problems-summary" className="font-normal normal-case tracking-normal">
          {summary}
        </span>
        {status === 'running' && <Loader2 className="size-3.5 animate-spin" aria-label="Validating" />}
        {stale && status !== 'running' && <span className="font-normal normal-case tracking-normal">(outdated)</span>}
        {status === 'failed' && validation && (
          <span className="font-normal normal-case tracking-normal text-destructive">(last check failed)</span>
        )}
        <span className="ml-auto">
          {open ? <ChevronDown className="size-4" aria-hidden /> : <ChevronUp className="size-4" aria-hidden />}
        </span>
      </button>
      {/* Nothing to show when valid: the header already says "No problems". */}
      {open && validation && issues.length > 0 && (
        <div className="max-h-40 overflow-auto border-t">
          <ul>
            {issues.map((issue, index) => (
              <li key={`${issue.code}-${issue.nodeId ?? ''}-${issue.edgeId ?? ''}-${issue.path ?? ''}-${index}`}>
                <button
                  type="button"
                  data-code={issue.code}
                  onClick={() => focus(issue)}
                  className="flex w-full items-start gap-2 px-4 py-1.5 text-left text-sm hover:bg-accent"
                >
                  <IssueIcon severity={issue.severity} />
                  <span className="flex-1">{issue.message}</span>
                  {issue.nodeId && names.has(issue.nodeId) && (
                    <span className="shrink-0 text-xs text-muted-foreground">{names.get(issue.nodeId)}</span>
                  )}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  )
}

export function IssueIcon({ severity, className }: { severity: ValidationIssue['severity']; className?: string }) {
  return severity === 'ERROR' ? (
    <AlertCircle className={cn('mt-0.5 size-4 shrink-0 text-destructive', className)} aria-label="Error" />
  ) : (
    <AlertTriangle className={cn('mt-0.5 size-4 shrink-0 text-amber-500', className)} aria-label="Warning" />
  )
}
