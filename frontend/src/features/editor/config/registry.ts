import { ALL_DEFINITIONS } from './definitions'
import type { FormValues, NodeConfigDefinition } from './fieldSpec'
import { jsonEqual } from './json'

const BY_TYPE = new Map(ALL_DEFINITIONS.map((d) => [d.type, d]))

export function getConfigDefinition(type: string): NodeConfigDefinition | undefined {
  return BY_TYPE.get(type)
}

/** Configuration a new node of this type starts with. Unknown types get `{}`. */
export function getDefaultConfiguration(type: string): Record<string, unknown> {
  return getConfigDefinition(type)?.defaultConfiguration() ?? {}
}

export interface ConfigIssue {
  path: string
  message: string
}

export type ConfigValidation = { valid: true } | { valid: false; issues: ConfigIssue[] }

export function validateFormValues(type: string, values: FormValues): ConfigValidation {
  const definition = getConfigDefinition(type)
  if (!definition) return { valid: true }
  const result = definition.schema.safeParse(values)
  if (result.success) return { valid: true }
  return { valid: false, issues: result.error.issues.map((i) => ({ path: i.path.join('.'), message: i.message })) }
}

/**
 * Validates a persisted `configuration` the way the backend schema does. The form schema alone is lenient about
 * things the form cannot represent (unknown properties, wrong value types), so the configuration must also survive
 * a lossless round trip through the form model. Types without a definition are accepted untouched.
 */
export function validateNodeConfiguration(type: string, configuration: Record<string, unknown>): ConfigValidation {
  const definition = getConfigDefinition(type)
  if (!definition) return { valid: true }
  const values = definition.toFormValues(configuration)
  const result = validateFormValues(type, values)
  if (!result.valid) return result
  if (!jsonEqual(definition.toConfiguration(values), configuration)) {
    return { valid: false, issues: [{ path: '', message: 'Configuration does not match the expected shape' }] }
  }
  return { valid: true }
}
