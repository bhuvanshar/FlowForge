import type { ValidationIssue } from '@/lib/api/workflows'

import type { WorkflowFlowEdge } from './types'

/**
 * Adds validation classes (`ff-edge-error` / `ff-edge-warning`, styled in index.css) and an accessible description to
 * edges that have problems. Display-only: the result is passed to React Flow, never stored or serialized.
 */
export function decorateEdges(
  edges: WorkflowFlowEdge[],
  issuesByEdge: Record<string, ValidationIssue[]> | undefined,
): WorkflowFlowEdge[] {
  if (!issuesByEdge) return edges
  return edges.map((edge) => {
    const issues = issuesByEdge[edge.id]
    if (!issues) return edge
    const severity = issues.some((i) => i.severity === 'ERROR') ? 'error' : 'warning'
    return { ...edge, className: `ff-edge-${severity}`, ariaLabel: issues.map((i) => i.message).join(' ') }
  })
}
