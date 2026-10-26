import { describe, expect, it } from 'vitest'

import { TEMPLATE_REGEX } from './patterns'
import { getConfigDefinition, getDefaultConfiguration, validateFormValues, validateNodeConfiguration } from './registry'

const issuesOf = (type: string, values: Record<string, unknown>) => {
  const result = validateFormValues(type, values)
  return result.valid ? [] : result.issues
}
// A field can fail several rules at once; the form shows the first, so compare the set of failing fields.
const paths = (type: string, values: Record<string, unknown>) => [...new Set(issuesOf(type, values).map((i) => i.path))]

const http = (overrides: Record<string, unknown> = {}) => ({
  method: 'GET', url: 'https://example.com', headers: [], query: [], body: '', timeoutSeconds: '30', ...overrides,
})

describe('safe expression text', () => {
  it.each([
    'plain text', '', '{{ input.user.id }}', '{{input}}', 'a {{ items[0].name }} b {{ x_1 }}', '{ single } braces', 'line\nbreak {{ a }}',
  ])('accepts %j', (text) => expect(TEMPLATE_REGEX.test(text)).toBe(true))

  it.each([
    '{{ foo() }}', '{{ a + b }}', "{{ 'x' }}", '{{ a.b( }}', '{{ }}', '{{ 1abc }}', '{{ unterminated', '{{{ a }}}', '{{ a }', '{{ a; b }}',
    '{{ require("fs") }}', '{{ a ? b : c }}', '{{ a["b"] }}', '{{ a.-b }}',
  ])('rejects %j (no calls, operators, quotes or code)', (text) => expect(TEMPLATE_REGEX.test(text)).toBe(false))
})

describe('required fields and field-level errors', () => {
  it('requires a variable, a name and a valid name', () => {
    expect(issuesOf('core.setVariables', { variables: [] })).toEqual([{ path: 'variables', message: 'Add at least one variable' }])
    expect(paths('core.setVariables', { variables: [{ name: '', value: 'x' }] })).toEqual(['variables.0.name'])
    expect(paths('core.setVariables', { variables: [{ name: '9a', value: 'x' }] })).toEqual(['variables.0.name'])
    expect(paths('core.setVariables', { variables: [{ name: 'ok', value: '{{ f() }}' }] })).toEqual(['variables.0.value'])
  })

  it('reports duplicate variable names on the later row', () => {
    const rows = [{ name: 'a', value: '1' }, { name: 'b', value: '2' }, { name: 'a', value: '3' }]
    expect(issuesOf('core.setVariables', { variables: rows })).toEqual([
      { path: 'variables.2.name', message: 'Variable names must be unique' },
    ])
  })

  it('requires field, operator and (for binary operators) a comparison value', () => {
    expect(paths('core.condition', { field: '', operator: 'equals', value: '' }).sort()).toEqual(['field', 'value'])
    expect(paths('core.condition', { field: 'a', operator: 'isEmpty', value: '' })).toEqual([])
    expect(paths('core.condition', { field: 'a', operator: 'nope', value: 'x' })).toEqual(['operator'])
  })

  it('requires transform rows with unique output fields', () => {
    expect(paths('core.transform', { operations: [] })).toEqual(['operations'])
    const row = (field: string) => ({ field, source: '{{ a }}', operation: 'trim' })
    expect(issuesOf('core.transform', { operations: [row('x'), row('x')] })).toEqual([
      { path: 'operations.1.field', message: 'Output fields must be unique' },
    ])
    expect(paths('core.transform', { operations: [{ field: 'x', source: '', operation: 'eval' }] }).sort()).toEqual([
      'operations.0.operation', 'operations.0.source',
    ])
  })

  it('requires a logger message that is safe', () => {
    expect(paths('core.logger', { message: '' })).toEqual(['message'])
    expect(paths('core.logger', { message: 'x {{ process.exit() }}' })).toEqual(['message'])
    expect(paths('core.logger', { message: 'ok {{ a.b }}' })).toEqual([])
  })

  it('validates the HTTP request fields', () => {
    expect(paths('core.httpRequest', http())).toEqual([])
    expect(paths('core.httpRequest', http({ url: '' }))).toEqual(['url'])
    expect(paths('core.httpRequest', http({ url: 'ftp://x' }))).toEqual(['url'])
    expect(paths('core.httpRequest', http({ url: '{{ vars.base }}/x' }))).toEqual([])
    expect(paths('core.httpRequest', http({ method: 'TRACE' }))).toEqual(['method'])
    expect(paths('core.httpRequest', http({ headers: [{ name: 'Bad Name', value: 'x' }] }))).toEqual(['headers.0.name'])
    expect(paths('core.httpRequest', http({ query: [{ name: '', value: 'x' }] }))).toEqual(['query.0.name'])
  })

  it.each([['0'], ['301'], ['abc'], ['1.5'], ['']])('rejects timeout %j', (timeout) => {
    expect(paths('core.httpRequest', http({ timeoutSeconds: timeout }))).toEqual(['timeoutSeconds'])
  })

  it.each([['1'], ['30'], ['300']])('accepts timeout %j', (timeout) => {
    expect(paths('core.httpRequest', http({ timeoutSeconds: timeout }))).toEqual([])
  })

  it('treats header names case-insensitively for uniqueness', () => {
    const headers = [{ name: 'Accept', value: 'a' }, { name: 'accept', value: 'b' }]
    expect(paths('core.httpRequest', http({ headers }))).toEqual(['headers.1.name'])
  })
})

describe('JSON editing and validation', () => {
  it('manual trigger test input: empty is fine, object/array are fine, scalars and syntax errors are not', () => {
    expect(paths('core.manualTrigger', { testInput: '' })).toEqual([])
    expect(paths('core.manualTrigger', { testInput: '  \n ' })).toEqual([])
    expect(paths('core.manualTrigger', { testInput: '{"a":1}' })).toEqual([])
    expect(paths('core.manualTrigger', { testInput: '[1,2]' })).toEqual([])
    expect(issuesOf('core.manualTrigger', { testInput: '{"a":' })[0].message).toMatch(/^Invalid JSON/)
    expect(issuesOf('core.manualTrigger', { testInput: '42' })[0].message).toBe('Test input must be a JSON object or array')
    expect(paths('core.manualTrigger', { testInput: 'null' })).toEqual(['testInput'])
  })

  it('http body must be valid JSON with safe strings, and is not allowed for GET and HEAD', () => {
    expect(paths('core.httpRequest', http({ method: 'POST', body: '{"a": ["{{ input.x }}", 1, null]}' }))).toEqual([])
    expect(issuesOf('core.httpRequest', http({ method: 'POST', body: '{oops' }))[0].message).toMatch(/^Invalid JSON/)
    expect(issuesOf('core.httpRequest', http({ method: 'POST', body: '{"a":"{{ eval() }}"}' }))[0].message).toMatch(/Only \{\{ path/)
    // Hidden for GET/HEAD: ignored by the form, and a persisted GET configuration that has a body is invalid.
    expect(paths('core.httpRequest', http({ method: 'GET', body: '{oops' }))).toEqual([])
    const withBody = { method: 'HEAD', url: 'https://x.io', headers: [], query: [], timeoutSeconds: 30, body: {} }
    expect(validateNodeConfiguration('core.httpRequest', withBody).valid).toBe(false)
    expect(paths('core.httpRequest', http({ method: 'DELETE', body: '"just a string"' }))).toEqual([])
  })
})

describe('serialization of configuration', () => {
  const roundTrip = (type: string, config: Record<string, unknown>) => {
    const def = getConfigDefinition(type)!
    return def.toConfiguration(def.toFormValues(config))
  }

  it('round-trips every kind of node configuration without losing data', () => {
    const configs: [string, Record<string, unknown>][] = [
      ['core.manualTrigger', { testInput: { user: { id: 42, tags: ['a', null], ok: true } } }],
      ['core.setVariables', { variables: [{ name: 'a', value: '{{ input.a }}' }, { name: 'b', value: '' }] }],
      ['core.condition', { field: '{{ input.n }}', operator: 'lessThanOrEqual', value: '10' }],
      ['core.condition', { field: 'x', operator: 'isNotEmpty' }],
      ['core.transform', { operations: [{ field: 'n', source: '{{ input.s }}', operation: 'toNumber' }] }],
      ['core.logger', { message: 'a\nb {{ input.c }}' }],
      ['core.httpRequest', {
        method: 'PATCH', url: 'https://x.io/{{ id }}', headers: [{ name: 'X-A', value: '1' }, { name: 'X-B', value: '{{ t }}' }],
        query: [{ name: 'q', value: 'a' }, { name: 'q', value: 'b' }], body: { list: [1, { deep: '{{ a.b }}' }] }, timeoutSeconds: 120,
      }],
    ]
    for (const [type, config] of configs) {
      expect(roundTrip(type, config), type).toEqual(config)
      expect(validateNodeConfiguration(type, config).valid, type).toBe(true)
    }
  })

  it('keeps duplicate query parameters in order', () => {
    const query = [{ name: 'q', value: '1' }, { name: 'a', value: '2' }, { name: 'q', value: '3' }]
    const out = roundTrip('core.httpRequest', { ...getDefaultConfiguration('core.httpRequest'), url: 'https://x.io', query })
    expect(out.query).toEqual(query)
  })

  it('omits optional parts when empty: no testInput, no body on GET/HEAD, no value for unary operators', () => {
    const t = getConfigDefinition('core.manualTrigger')!
    expect(t.toConfiguration({ testInput: '  ' })).toEqual({})
    const h = getConfigDefinition('core.httpRequest')!
    expect(h.toConfiguration(http({ method: 'GET', body: '{"a":1}' }))).not.toHaveProperty('body')
    expect(h.toConfiguration(http({ method: 'POST', body: '' }))).not.toHaveProperty('body')
    expect(h.toConfiguration(http({ method: 'POST', body: '{"a":1}' }))).toHaveProperty('body', { a: 1 })
    expect(h.toConfiguration(http({ timeoutSeconds: '45' })).timeoutSeconds).toBe(45)
    const c = getConfigDefinition('core.condition')!
    expect(c.toConfiguration({ field: 'a', operator: 'isEmpty', value: 'stale' })).toEqual({ field: 'a', operator: 'isEmpty' })
  })

  it('pretty-prints stored JSON in the form and tolerates old or malformed configurations', () => {
    expect(getConfigDefinition('core.manualTrigger')!.toFormValues({ testInput: { a: 1 } })).toEqual({ testInput: '{\n  "a": 1\n}' })
    expect(getConfigDefinition('core.setVariables')!.toFormValues({})).toEqual({ variables: [] })
    expect(getConfigDefinition('core.httpRequest')!.toFormValues({ headers: 'nope', timeoutSeconds: 5 })).toMatchObject({
      method: 'GET', headers: [], timeoutSeconds: '5',
    })
  })

  it('treats configurations with extra or mistyped data as invalid, and unknown node types as untouched', () => {
    expect(validateNodeConfiguration('core.logger', { message: 'x', level: 'info' }).valid).toBe(false)
    expect(validateNodeConfiguration('core.httpRequest', { ...getDefaultConfiguration('core.httpRequest'), url: 'https://x.io', timeoutSeconds: '30' }).valid).toBe(false)
    expect(validateNodeConfiguration('vendor.custom', { anything: [1] }).valid).toBe(true)
    expect(getDefaultConfiguration('vendor.custom')).toEqual({})
  })
})
