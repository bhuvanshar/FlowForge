/**
 * Which JSON Schemas the AI Model node accepts for validating a model's JSON reply. Port of the backend
 * `ResponseSchemaPolicy` (checked against the shared fixtures `response-schema-fixtures.v1.json` on both sides): a small,
 * side-effect-free subset with no `$ref`, `pattern` or `format`, and bounded size, depth and enum length.
 */

export const MAX_DEPTH = 10
export const MAX_NODES = 500
export const MAX_ENUM_VALUES = 100
export const MAX_CHARS = 16 * 1024

const TYPES = new Set(['object', 'array', 'string', 'number', 'integer', 'boolean', 'null'])
const NUMBER_KEYWORDS = new Set(['minimum', 'maximum', 'exclusiveMinimum', 'exclusiveMaximum', 'minLength', 'maxLength', 'minItems', 'maxItems'])
const COUNT_KEYWORDS = new Set(['minLength', 'maxLength', 'minItems', 'maxItems'])
const SCHEMA_LISTS = new Set(['anyOf', 'oneOf', 'allOf'])
const KEYWORDS = new Set(['type', 'properties', 'required', 'additionalProperties', 'items', 'enum', 'const', 'uniqueItems',
  'description', 'title', 'anyOf', 'oneOf', 'allOf', ...NUMBER_KEYWORDS])

export interface SchemaProblem {
  path: string
  message: string
}

const isObject = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v)
const allText = (v: unknown[]) => v.every((x) => typeof x === 'string')

export function checkResponseSchema(schema: unknown): SchemaProblem[] {
  if (!isObject(schema)) return [{ path: '', message: 'must be a JSON Schema object' }]
  if (JSON.stringify(schema).length > MAX_CHARS) return [{ path: '', message: `is larger than ${MAX_CHARS} characters` }]
  const problems: SchemaProblem[] = []
  const nodes = { count: 0 }
  visit(schema, '', 1, nodes, problems)
  if (nodes.count > MAX_NODES && problems.length === 0) problems.push({ path: '', message: `has more than ${MAX_NODES} schema nodes` })
  return problems
}

function visit(node: unknown, path: string, depth: number, nodes: { count: number }, out: SchemaProblem[]) {
  if (depth > MAX_DEPTH) {
    out.push({ path, message: `is nested deeper than ${MAX_DEPTH} levels` })
    return
  }
  nodes.count++
  if (!isObject(node)) {
    out.push({ path, message: 'must be a schema object' })
    return
  }
  if (nodes.count > MAX_NODES) return
  for (const [name, value] of Object.entries(node)) {
    const at = `${path}/${name}`
    if (!KEYWORDS.has(name)) {
      const extra = name.startsWith('$') || name === 'pattern' || name === 'format' ? ' (references, patterns and formats are not allowed)' : ''
      out.push({ path: at, message: `keyword '${name}' is not supported${extra}` })
      continue
    }
    switch (name) {
      case 'type': {
        const ok = typeof value === 'string' ? TYPES.has(value)
          : Array.isArray(value) && value.length > 0 && allText(value) && value.every((t) => TYPES.has(t as string))
        if (!ok) out.push({ path: at, message: `must be one of ${[...TYPES].join(', ')} (or a list of them)` })
        break
      }
      case 'properties':
        if (!isObject(value)) out.push({ path: at, message: 'must be an object of schemas' })
        else for (const [key, child] of Object.entries(value)) visit(child, `${at}/${key}`, depth + 1, nodes, out)
        break
      case 'required':
        if (!Array.isArray(value) || !allText(value)) out.push({ path: at, message: 'must be a list of property names' })
        break
      case 'additionalProperties':
        if (isObject(value)) visit(value, at, depth + 1, nodes, out)
        else if (typeof value !== 'boolean') out.push({ path: at, message: 'must be true, false or a schema' })
        break
      case 'items':
        visit(value, at, depth + 1, nodes, out)
        break
      case 'enum':
        if (!Array.isArray(value) || value.length === 0 || value.length > MAX_ENUM_VALUES) {
          out.push({ path: at, message: `must be a list of 1 to ${MAX_ENUM_VALUES} values` })
        }
        break
      case 'uniqueItems':
        if (typeof value !== 'boolean') out.push({ path: at, message: 'must be true or false' })
        break
      case 'description':
      case 'title':
        if (typeof value !== 'string' || value.length > 1000) out.push({ path: at, message: 'must be text of at most 1000 characters' })
        break
      case 'const':
        break
      default:
        if (SCHEMA_LISTS.has(name)) {
          if (!Array.isArray(value) || value.length === 0 || value.length > 20) out.push({ path: at, message: 'must be a list of 1 to 20 schemas' })
          else value.forEach((child, i) => visit(child, `${at}/${i}`, depth + 1, nodes, out))
        } else if (COUNT_KEYWORDS.has(name) && (typeof value !== 'number' || !Number.isInteger(value) || value < 0)) {
          out.push({ path: at, message: 'must be a non-negative integer' })
        } else if (NUMBER_KEYWORDS.has(name) && typeof value !== 'number') {
          out.push({ path: at, message: 'must be a number' })
        }
    }
  }
}
