import { describe, expect, it } from 'vitest'

import catalog from '../../../../backend/src/main/resources/schemas/node-types.v1.json'

import { getNodeTypeSpec, isKnownNodeType, NODE_TYPES } from './nodeTypes'

describe('node type registry', () => {
  it('matches the canonical backend catalog exactly (ids, versions, labels, handles)', () => {
    const fromRegistry = NODE_TYPES.map((s) => ({
      type: s.type, typeVersion: s.typeVersion, label: s.label, role: s.role, inputs: s.inputs, outputs: s.outputs,
    }))
    expect(fromRegistry).toEqual(catalog.nodeTypes.map(({ defaultConfiguration: _d, ...rest }) => rest))
  })

  it('uses identifiers allowed by the workflow schema', () => {
    for (const spec of NODE_TYPES) expect(spec.type).toMatch(/^[a-z][a-z0-9]*(\.[a-z][A-Za-z0-9]*)+$/)
  })

  it('offers the built-in node types, triggers first', () => {
    expect(NODE_TYPES.map((s) => s.label)).toEqual([
      'Manual Trigger', 'Webhook Trigger', 'Schedule Trigger', 'Set Variables', 'Condition', 'Transform', 'Logger',
      'HTTP Request', 'AI Model',
    ])
  })

  it('gives unknown types a generic spec instead of failing', () => {
    expect(isKnownNodeType('vendor.custom')).toBe(false)
    expect(getNodeTypeSpec('vendor.custom')).toMatchObject({ label: 'vendor.custom', inputs: ['main'], outputs: ['main'] })
  })
})
