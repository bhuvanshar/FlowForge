import type { Edge, Node } from '@xyflow/react'

import type { NodeExecutionStatus } from '@/lib/workflow/definition'

/** React Flow node `type` used for every workflow node (the renderer). Not the workflow node type. */
export const FLOW_NODE_TYPE = 'workflowNode'

/** Data carried by each canvas node. Only the first four fields are persisted (see serialization.ts). */
export type WorkflowNodeData = {
  /** Display name (`name` in the workflow definition). */
  name: string
  /** Backend node type identifier, e.g. `core.httpRequest` (`type` in the workflow definition). */
  nodeType: string
  typeVersion: number
  configuration: Record<string, unknown>
  /** Runtime-only; never serialized. Set by the execution module. */
  executionStatus?: NodeExecutionStatus
}

export type WorkflowFlowNode = Node<WorkflowNodeData, typeof FLOW_NODE_TYPE>
export type WorkflowFlowEdge = Edge
