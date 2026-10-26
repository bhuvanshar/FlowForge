import { describe, expect, it } from 'vitest'

import fixtures from '../../../../../backend/src/test/resources/workflow/response-schema-fixtures.v1.json'

import { checkResponseSchema, MAX_DEPTH, MAX_NODES } from './responseSchemaPolicy'

describe('response schema policy (editor vs backend)', () => {
  it.each(fixtures.valid.map((s, i) => [i, s] as const))('accepts shared valid schema #%i', (_i, schema) => {
    expect(checkResponseSchema(schema)).toEqual([])
  })

  it.each(fixtures.invalid.map((s, i) => [i, s] as const))('rejects shared invalid schema #%i', (_i, schema) => {
    expect(checkResponseSchema(schema).length).toBeGreaterThan(0)
  })

  it('explains references, patterns and unknown keywords', () => {
    expect(checkResponseSchema({ $ref: 'http://169.254.169.254/' })).toEqual([
      { path: '/$ref', message: "keyword '$ref' is not supported (references, patterns and formats are not allowed)" },
    ])
    expect(checkResponseSchema({ properties: { a: { pattern: '(a+)+$' } } })[0].path).toBe('/properties/a/pattern')
    expect(checkResponseSchema({ if: {} })[0].message).toBe("keyword 'if' is not supported")
    expect(checkResponseSchema([])).toEqual([{ path: '', message: 'must be a JSON Schema object' }])
  })

  it('bounds depth and size', () => {
    let deep: Record<string, unknown> = { type: 'object' }
    for (let i = 0; i < MAX_DEPTH + 1; i++) deep = { properties: { a: deep } }
    expect(checkResponseSchema(deep).some((p) => p.message.includes('nested deeper'))).toBe(true)

    const wide = { properties: Object.fromEntries(Array.from({ length: MAX_NODES + 5 }, (_, i) => [`p${i}`, {}])) }
    expect(checkResponseSchema(wide).some((p) => p.message.includes(`more than ${MAX_NODES}`))).toBe(true)
    const huge = { properties: Object.fromEntries(Array.from({ length: 40 }, (_, i) => [`p${i}`, { description: 'd'.repeat(500) }])) }
    expect(checkResponseSchema(huge)[0].message).toContain('larger than')
  })
})
