import Ajv2020 from 'ajv/dist/2020'
import { describe, expect, it } from 'vitest'

import catalog from '../../../../../backend/src/main/resources/schemas/node-types.v1.json'
import configSchema from '../../../../../backend/src/main/resources/schemas/node-config.v1.schema.json'
import fixtures from '../../../../../backend/src/test/resources/workflow/node-config-fixtures.v1.json'

import { NODE_TYPES } from '../nodeTypes'

import { ALL_DEFINITIONS } from './definitions'
import { AI_BASE_URL_PATTERN, AI_MODEL_NAME_PATTERN, CREDENTIAL_ID_PATTERN, CRON_PATTERN, HEADER_NAME_PATTERN, MAX_PROMPT_LENGTH, TEMPLATE_PATTERN, TIME_ZONE_PATTERN, URL_PATTERN, VARIABLE_NAME_PATTERN } from './patterns'
import { getConfigDefinition, getDefaultConfiguration, validateNodeConfiguration } from './registry'

const defs = configSchema.$defs as Record<string, unknown>

/** Same self-contained wrapper the backend test builds: the shared $defs plus a root $ref to the node type. */
function compile(type: string) {
  const ajv = new Ajv2020({ allErrors: true })
  return ajv.compile({ $schema: configSchema.$schema, $defs: defs, $ref: `#/$defs/${type}` })
}

const cases = fixtures.cases as Record<string, { valid: Record<string, unknown>[]; invalid: Record<string, unknown>[] }>

describe('node configuration contract (frontend vs backend JSON Schema)', () => {
  it('has a definition, a JSON Schema and a default for every node type in the catalog', () => {
    for (const type of catalog.nodeTypes) {
      expect(getConfigDefinition(type.type), type.type).toBeDefined()
      expect(defs[type.type], type.type).toBeDefined()
      expect(getDefaultConfiguration(type.type)).toEqual(type.defaultConfiguration)
    }
    expect(ALL_DEFINITIONS.map((d) => d.type).sort()).toEqual(catalog.nodeTypes.map((t) => t.type).sort())
    expect(NODE_TYPES.length).toBe(catalog.nodeTypes.length)
  })

  it('uses exactly the validation patterns of the backend schema', () => {
    expect((defs.safeString as { pattern: string }).pattern).toBe(TEMPLATE_PATTERN)
    expect((defs.variableName as { pattern: string }).pattern).toBe(VARIABLE_NAME_PATTERN)
    expect((defs.headerName as { pattern: string }).pattern).toBe(HEADER_NAME_PATTERN)
    const http = defs['core.httpRequest'] as { properties: { url: { allOf: { pattern?: string }[] } } }
    expect(http.properties.url.allOf[1].pattern).toBe(URL_PATTERN)
    expect((defs.cronExpression as { pattern: string }).pattern).toBe(CRON_PATTERN)
    expect((defs.timeZone as { pattern: string }).pattern).toBe(TIME_ZONE_PATTERN)
    expect((defs.credentialId as { pattern: string }).pattern).toBe(CREDENTIAL_ID_PATTERN)
    expect((defs.aiBaseUrl as { pattern: string }).pattern).toBe(AI_BASE_URL_PATTERN)
    expect((defs.aiModelName as { pattern: string }).pattern).toBe(AI_MODEL_NAME_PATTERN)
    expect((defs.promptText as { pattern: string; maxLength: number })).toMatchObject({ pattern: TEMPLATE_PATTERN, maxLength: MAX_PROMPT_LENGTH })
  })

  it('covers every node type with valid and invalid fixtures', () => {
    expect(Object.keys(cases).sort()).toEqual(catalog.nodeTypes.map((t) => t.type).sort())
    for (const c of Object.values(cases)) {
      expect(c.valid.length).toBeGreaterThan(0)
      expect(c.invalid.length).toBeGreaterThan(0)
    }
  })

  for (const [type, { valid, invalid }] of Object.entries(cases)) {
    describe(type, () => {
      const validateWithSchema = compile(type)

      it.each(valid.map((c, i) => [i, c] as const))('accepts valid fixture #%i in JSON Schema and in the editor', (_i, config) => {
        expect(validateWithSchema(config), JSON.stringify(validateWithSchema.errors)).toBe(true)
        const result = validateNodeConfiguration(type, config)
        expect(result.valid, JSON.stringify(result)).toBe(true)
      })

      it.each(invalid.map((c, i) => [i, c] as const))('rejects invalid fixture #%i in JSON Schema and in the editor', (_i, config) => {
        expect(validateWithSchema(config), JSON.stringify(config)).toBe(false)
        expect(validateNodeConfiguration(type, config).valid, JSON.stringify(config)).toBe(false)
      })
    })
  }
})
