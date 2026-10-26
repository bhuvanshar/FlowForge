import {
  CURRENT_SCHEMA_VERSION,
  DEFAULT_HANDLE,
  type EdgeDefinition,
  type NodeDefinition,
  type Viewport,
  type WorkflowDefinition,
} from '@/lib/workflow/definition'

import { FLOW_NODE_TYPE, type WorkflowFlowEdge, type WorkflowFlowNode } from './types'

const round = (n: number) => Math.round(n * 100) / 100

/** Label shown on edges leaving a branch handle (e.g. "true"); purely visual. */
export function edgeLabel(sourceHandle: string): string | undefined {
  return sourceHandle === DEFAULT_HANDLE ? undefined : sourceHandle
}

export function toNodeDefinition(node: WorkflowFlowNode): NodeDefinition {
  return {
    id: node.id,
    type: node.data.nodeType,
    typeVersion: node.data.typeVersion,
    name: node.data.name,
    position: { x: round(node.position.x), y: round(node.position.y) },
    configuration: node.data.configuration,
  }
}

export function toEdgeDefinition(edge: WorkflowFlowEdge): EdgeDefinition {
  return {
    id: edge.id,
    source: edge.source,
    sourceHandle: edge.sourceHandle ?? DEFAULT_HANDLE,
    target: edge.target,
    targetHandle: edge.targetHandle ?? DEFAULT_HANDLE,
  }
}

/** Canvas state to canonical workflow JSON (schema version 1). Selection, measurements and status are dropped. */
export function toDefinition(
  nodes: readonly WorkflowFlowNode[],
  edges: readonly WorkflowFlowEdge[],
  viewport?: Viewport,
): WorkflowDefinition {
  const definition: WorkflowDefinition = {
    schemaVersion: CURRENT_SCHEMA_VERSION,
    nodes: nodes.map(toNodeDefinition),
    edges: edges.map(toEdgeDefinition),
  }
  if (viewport) {
    definition.editor = { viewport: { x: round(viewport.x), y: round(viewport.y), zoom: Math.round(viewport.zoom * 1000) / 1000 } }
  }
  return definition
}

export function fromDefinition(definition: WorkflowDefinition): {
  nodes: WorkflowFlowNode[]
  edges: WorkflowFlowEdge[]
  viewport?: Viewport
} {
  return {
    nodes: definition.nodes.map((n) => ({
      id: n.id,
      type: FLOW_NODE_TYPE,
      position: { x: n.position.x, y: n.position.y },
      data: { name: n.name, nodeType: n.type, typeVersion: n.typeVersion, configuration: n.configuration },
    })),
    edges: definition.edges.map((e) => ({
      id: e.id,
      source: e.source,
      sourceHandle: e.sourceHandle,
      target: e.target,
      targetHandle: e.targetHandle,
      label: edgeLabel(e.sourceHandle),
    })),
    viewport: definition.editor?.viewport,
  }
}
