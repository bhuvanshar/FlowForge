import Ajv2020 from 'ajv/dist/2020'
import { describe, expect, it } from 'vitest'

import example from '../../../../backend/src/test/resources/workflow/example-definition.v1.json'
import schema from '../../../../backend/src/main/resources/schemas/workflow-definition.v1.schema.json'
import type { WorkflowDefinition } from '@/lib/workflow/definition'

import { fromDefinition, toDefinition } from './serialization'
import { FLOW_NODE_TYPE, type WorkflowFlowNode } from './types'

const validate = new Ajv2020({ allErrors: true }).compile(schema)

describe('serialization', () => {
  it('round-trips the canonical example without loss', () => {
    const def = example as WorkflowDefinition
    const { nodes, edges, viewport } = fromDefinition(def)

    expect(toDefinition(nodes, edges, viewport)).toEqual(def)
  })

  it('produces output that validates against the canonical JSON Schema', () => {
    const { nodes, edges, viewport } = fromDefinition(example as WorkflowDefinition)
    const out = toDefinition(nodes, edges, viewport)

    expect(validate(out), JSON.stringify(validate.errors)).toBe(true)
  })

  it('maps display fields to contract fields and drops editor-only state', () => {
    const node: WorkflowFlowNode = {
      id: 'n1', type: FLOW_NODE_TYPE, position: { x: 10.126, y: -3.001 }, selected: true, dragging: true,
      measured: { width: 100, height: 40 },
      data: { name: 'Fetch', nodeType: 'core.httpRequest', typeVersion: 1, configuration: { url: null }, executionStatus: 'RUNNING' },
    }
    const out = toDefinition([node], [])

    expect(out.nodes[0]).toEqual({
      id: 'n1', type: 'core.httpRequest', typeVersion: 1, name: 'Fetch',
      position: { x: 10.13, y: -3 }, configuration: { url: null },
    })
    expect(out.editor).toBeUndefined()
    expect(JSON.stringify(out)).not.toMatch(/selected|dragging|measured|executionStatus/)
  })

  it('writes the default handle for edges without explicit handles', () => {
    const out = toDefinition([], [{ id: 'e1', source: 'a', target: 'b' }])

    expect(out.edges[0]).toEqual({ id: 'e1', source: 'a', sourceHandle: 'main', target: 'b', targetHandle: 'main' })
  })

  it('labels branch edges for display only', () => {
    const { edges } = fromDefinition(example as WorkflowDefinition)

    expect(edges.map((e) => e.label)).toEqual([undefined, 'true', 'false'])
    expect(JSON.stringify(toDefinition([], edges))).not.toContain('label')
  })

  it('keeps unknown node types and their configuration untouched', () => {
    const def: WorkflowDefinition = {
      schemaVersion: 1,
      nodes: [{ id: 'x', type: 'vendor.custom', typeVersion: 7, name: 'X', position: { x: 1, y: 2 }, configuration: { deep: { a: [1, null] } } }],
      edges: [],
    }
    const { nodes, edges } = fromDefinition(def)

    expect(toDefinition(nodes, edges)).toEqual(def)
  })
})
