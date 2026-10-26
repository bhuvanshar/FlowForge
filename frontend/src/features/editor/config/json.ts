import { TEMPLATE_REGEX } from './patterns'

export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue }

export function parseJson(text: string): { ok: true; value: JsonValue } | { ok: false; message: string } {
  try {
    return { ok: true, value: JSON.parse(text) as JsonValue }
  } catch (error) {
    const detail = error instanceof Error ? error.message : 'parse error'
    return { ok: false, message: `Invalid JSON: ${detail}` }
  }
}

export function prettyJson(value: unknown): string {
  return JSON.stringify(value, null, 2)
}

/** First string inside a JSON value that is not safe expression text, or undefined. */
export function findUnsafeString(value: unknown): string | undefined {
  if (typeof value === 'string') return TEMPLATE_REGEX.test(value) ? undefined : value
  if (Array.isArray(value)) {
    for (const item of value) {
      const bad = findUnsafeString(item)
      if (bad !== undefined) return bad
    }
  } else if (value !== null && typeof value === 'object') {
    for (const item of Object.values(value)) {
      const bad = findUnsafeString(item)
      if (bad !== undefined) return bad
    }
  }
  return undefined
}

/** Order-insensitive deep equality for JSON values. */
export function jsonEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (typeof a !== typeof b || a === null || b === null || typeof a !== 'object') return false
  if (Array.isArray(a) !== Array.isArray(b)) return false
  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((v, i) => jsonEqual(v, b[i]))
  const ao = a as Record<string, unknown>
  const bo = b as Record<string, unknown>
  const keys = Object.keys(ao)
  return keys.length === Object.keys(bo).length && keys.every((k) => k in bo && jsonEqual(ao[k], bo[k]))
}
