/**
 * Workflow definition contract, schema version 1. Must match
 * backend/src/main/resources/schemas/workflow-definition.v1.schema.json and
 * com.flowforge.domain.workflow.WorkflowDefinition (see docs/WORKFLOW_SCHEMA.md).
 */

export const CURRENT_SCHEMA_VERSION = 1
export const DEFAULT_HANDLE = 'main'
export const MAX_NODES = 500
export const MAX_EDGES = 2000

export type WorkflowStatus = 'DRAFT' | 'ACTIVE' | 'INACTIVE' | 'ARCHIVED'
export type ExecutionStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export type NodeExecutionStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED'

export interface NodePosition {
  x: number
  y: number
}

export interface NodeDefinition {
  id: string
  type: string
  typeVersion: number
  name: string
  position: NodePosition
  configuration: Record<string, unknown>
}

export interface EdgeDefinition {
  id: string
  source: string
  sourceHandle: string
  target: string
  targetHandle: string
}

export interface Viewport {
  x: number
  y: number
  zoom: number
}

export interface EditorMetadata {
  viewport?: Viewport
}

export interface WorkflowDefinition {
  schemaVersion: typeof CURRENT_SCHEMA_VERSION
  nodes: NodeDefinition[]
  edges: EdgeDefinition[]
  editor?: EditorMetadata
}

export function emptyWorkflowDefinition(): WorkflowDefinition {
  return { schemaVersion: CURRENT_SCHEMA_VERSION, nodes: [], edges: [] }
}
