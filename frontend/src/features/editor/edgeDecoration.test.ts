import { describe, expect, it } from 'vitest'

import type { ValidationIssue } from '@/lib/api/workflows'

import { decorateEdges } from './edgeDecoration'

const edges = [
  { id: 'a', source: 'x', target: 'y' },
  { id: 'b', source: 'y', target: 'z' },
  { id: 'c', source: 'z', target: 'w' },
]
const issue = (severity: ValidationIssue['severity'], message: string): ValidationIssue => ({ code: 'X', severity, message })

describe('decorateEdges', () => {
  it('returns the same edges when there is no validation result', () => {
    expect(decorateEdges(edges, undefined)).toBe(edges)
  })

  it('marks edges by worst severity and describes their problems', () => {
    const out = decorateEdges(edges, {
      a: [issue('WARNING', 'w1')],
      b: [issue('WARNING', 'w2'), issue('ERROR', 'e1')],
    })

    expect(out.map((e) => e.className)).toEqual(['ff-edge-warning', 'ff-edge-error', undefined])
    expect(out[1].ariaLabel).toBe('w2 e1')
    expect(out[2]).toBe(edges[2])
  })

  it('never mutates the stored edges', () => {
    decorateEdges(edges, { a: [issue('ERROR', 'e')] })
    expect(edges[0]).not.toHaveProperty('className')
  })
})
