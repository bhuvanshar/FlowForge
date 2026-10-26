/**
 * Credential types. Mirrors backend/src/main/resources/schemas/credential-types.v1.json exactly (checked by
 * credentialTypes.test.ts); the app cannot import that file directly because the frontend image is built without the
 * backend sources.
 */

export interface CredentialFieldSpec {
  name: string
  label: string
  secret: boolean
  maxLength?: number
  pattern?: string
}

export interface CredentialTypeSpec {
  type: string
  label: string
  description: string
  usableBy: string[]
  fields: CredentialFieldSpec[]
}

export const SECRET_FIELD = { minLength: 8, maxLength: 4096, pattern: '^[^\\r\\n]+$' }

export const CREDENTIAL_TYPES: CredentialTypeSpec[] = [
  {
    type: 'httpBearer',
    label: 'Bearer token',
    description: 'Sent as Authorization: Bearer <token>',
    usableBy: ['core.httpRequest', 'core.aiModel'],
    fields: [{ name: 'token', label: 'Token', secret: true }],
  },
  {
    type: 'httpBasic',
    label: 'Basic authentication',
    description: 'Sent as Authorization: Basic base64(username:password)',
    usableBy: ['core.httpRequest', 'core.aiModel'],
    fields: [
      { name: 'username', label: 'Username', secret: false, maxLength: 200, pattern: '^[^:\\r\\n]+$' },
      { name: 'password', label: 'Password', secret: true },
    ],
  },
  {
    type: 'httpHeader',
    label: 'API key header',
    description: 'Sent as <header name>: <value>, e.g. X-API-Key',
    usableBy: ['core.httpRequest', 'core.aiModel'],
    fields: [
      { name: 'headerName', label: 'Header name', secret: false, maxLength: 100, pattern: "^[!#$%&'*+.^_`|~0-9A-Za-z-]+$" },
      { name: 'value', label: 'Value', secret: true },
    ],
  },
]

const BY_TYPE = new Map(CREDENTIAL_TYPES.map((t) => [t.type, t]))

export function getCredentialType(type: string): CredentialTypeSpec | undefined {
  return BY_TYPE.get(type)
}

/** Host names a credential may be sent to: `api.example.com`, `*.example.com` or a bracketed IPv6 literal. */
const LABEL = '(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)'
export const HOST_REGEX = new RegExp(`^(?:\\*\\.)?(?:${LABEL}\\.)*${LABEL}$|^\\[[0-9a-f:.]+]$`)

/** Headers managed by the HTTP client (same list as the backend HeaderRules). */
export const FORBIDDEN_HEADERS = new Set(['host', 'content-length', 'transfer-encoding', 'connection', 'keep-alive',
  'upgrade', 'te', 'trailer', 'expect', 'proxy-connection'])

/** Headers that carry credentials: a literal value in workflow JSON would store a secret in plain text. */
export const SENSITIVE_HEADERS = new Set(['authorization', 'proxy-authorization', 'cookie', 'x-api-key', 'api-key',
  'x-auth-token'])

export function parseHosts(text: string): string[] {
  return text.split(/[\s,]+/).map((h) => h.trim().toLowerCase()).filter(Boolean)
}

export interface CredentialDraft {
  type: string
  name: string
  description: string
  hosts: string
  fields: Record<string, string>
  secret: Record<string, string>
}

/** Editor-side checks mirroring the backend (which has the final word). Keys are the API's error field paths. */
export function validateDraft(draft: CredentialDraft, { withSecret }: { withSecret: boolean }): Record<string, string> {
  const errors: Record<string, string> = {}
  const spec = getCredentialType(draft.type)
  if (!spec) return { type: 'Choose a type' }
  const name = draft.name.trim()
  if (name.length === 0 || name.length > 100) errors.name = 'Name must be 1 to 100 characters'
  if (draft.description.length > 500) errors.description = 'At most 500 characters'
  const hosts = parseHosts(draft.hosts)
  if (hosts.length === 0 || hosts.length > 20) {
    errors.allowedHosts = 'List 1 to 20 hosts this credential may be sent to'
  } else {
    const bad = hosts.find((h) => !HOST_REGEX.test(h))
    if (bad) errors.allowedHosts = `"${bad}" is not a host name (use api.example.com or *.example.com, no scheme or path)`
  }
  for (const field of spec.fields) {
    if (field.secret) {
      if (!withSecret) continue
      const value = draft.secret[field.name] ?? ''
      if (value.length < SECRET_FIELD.minLength || value.length > SECRET_FIELD.maxLength) {
        errors[`secret.${field.name}`] = `${field.label} must be ${SECRET_FIELD.minLength} to ${SECRET_FIELD.maxLength} characters`
      } else if (/[\r\n]/.test(value)) {
        errors[`secret.${field.name}`] = `${field.label} must not contain line breaks`
      }
    } else {
      const value = (draft.fields[field.name] ?? '').trim()
      if (value === '') errors[`fields.${field.name}`] = `${field.label} is required`
      else if (field.maxLength && value.length > field.maxLength) errors[`fields.${field.name}`] = `At most ${field.maxLength} characters`
      else if (field.pattern && !new RegExp(field.pattern).test(value)) errors[`fields.${field.name}`] = `${field.label} has an invalid format`
      else if (field.name === 'headerName' && (FORBIDDEN_HEADERS.has(value.toLowerCase()) || value.toLowerCase() === 'content-type')) {
        errors[`fields.${field.name}`] = 'This header is managed by the HTTP client'
      }
    }
  }
  return errors
}
