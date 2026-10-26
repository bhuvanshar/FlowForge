import { describe, expect, it } from 'vitest'

import example from '../../../../backend/src/test/resources/workflow/example-definition.v1.json'
import schema from '../../../../backend/src/main/resources/schemas/workflow-definition.v1.schema.json'

import {
  CURRENT_SCHEMA_VERSION,
  emptyWorkflowDefinition,
  MAX_EDGES,
  MAX_NODES,
  type WorkflowDefinition,
} from './definition'

// Shared fixtures from the backend keep the TypeScript contract aligned with the canonical JSON Schema.
describe('workflow definition contract', () => {
  it('accepts the canonical backend example', () => {
    const definition = example as WorkflowDefinition
    expect(definition.schemaVersion).toBe(CURRENT_SCHEMA_VERSION)
    expect(definition.edges.map((e) => e.sourceHandle)).toEqual(['main', 'true', 'false'])
  })

  it('uses the same top-level keys and limits as the JSON Schema', () => {
    expect(Object.keys(schema.properties).sort()).toEqual(['edges', 'editor', 'nodes', 'schemaVersion'])
    expect(schema.properties.schemaVersion.const).toBe(CURRENT_SCHEMA_VERSION)
    expect(schema.properties.nodes.maxItems).toBe(MAX_NODES)
    expect(schema.properties.edges.maxItems).toBe(MAX_EDGES)
    expect(schema.$defs.node.required.sort()).toEqual(
      ['configuration', 'id', 'name', 'position', 'type', 'typeVersion'],
    )
    expect(schema.$defs.edge.required.sort()).toEqual(['id', 'source', 'sourceHandle', 'target', 'targetHandle'])
  })

  it('creates an empty definition at the current schema version', () => {
    expect(emptyWorkflowDefinition()).toEqual({ schemaVersion: 1, nodes: [], edges: [] })
  })
})
