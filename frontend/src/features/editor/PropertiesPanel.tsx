import { useMemo } from 'react'

import { NodeConfigForm } from './config/NodeConfigForm'
import { useEditorStore } from './editorStore'
import { IssueIcon } from './ProblemsPanel'
import { getNodeTypeSpec } from './nodeTypes'
import { TriggerPanel } from './TriggerPanel'
import { isAutomaticTrigger } from './triggers'

/** Right panel: facts about the selected node and its type-specific settings form. */
export function PropertiesPanel() {
  const nodes = useEditorStore((s) => s.nodes)
  // Filter outside the selector: a selector returning a new array each call makes zustand re-render forever.
  const selected = useMemo(() => nodes.filter((n) => n.selected), [nodes])
  const node = selected.length === 1 ? selected[0] : undefined
  const issues = useEditorStore((s) => (node ? s.validation?.byNode[node.id] : undefined))
  const workflowId = useEditorStore((s) => s.workflowId)

  return (
    <aside aria-label="Properties" className="flex w-80 shrink-0 flex-col gap-3 overflow-auto border-l bg-card p-4">
      <h2 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Properties</h2>
      {selected.length === 0 && <p className="text-sm text-muted-foreground">Select a node to configure it.</p>}
      {selected.length > 1 && <p className="text-sm text-muted-foreground">{selected.length} nodes selected.</p>}
      {node && (
        <>
          <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
            <dt className="text-muted-foreground">Name</dt>
            <dd className="break-words">{node.data.name}</dd>
            <dt className="text-muted-foreground">Type</dt>
            <dd>{getNodeTypeSpec(node.data.nodeType).label}</dd>
            <dt className="text-muted-foreground">Node ID</dt>
            <dd className="break-all font-mono text-xs">{node.id}</dd>
          </dl>
          {issues && issues.length > 0 && (
            <ul aria-label="Node problems" className="flex flex-col gap-1 rounded-md border border-destructive/30 bg-destructive/5 p-2">
              {issues.map((issue, index) => (
                <li key={index} className="flex items-start gap-2 text-xs">
                  <IssueIcon severity={issue.severity} className="size-3.5" />
                  <span>{issue.message}</span>
                </li>
              ))}
            </ul>
          )}
          <hr />
          <NodeConfigForm node={node} />
          {isAutomaticTrigger(node.data.nodeType) && <TriggerPanel workflowId={workflowId} nodeType={node.data.nodeType} />}
        </>
      )}
    </aside>
  )
}
