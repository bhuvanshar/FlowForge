import { describe, expect, it } from 'vitest'

import catalog from '../../../../backend/src/main/resources/schemas/credential-types.v1.json'

import { CREDENTIAL_TYPES, HOST_REGEX, SECRET_FIELD, validateDraft, type CredentialDraft } from './credentialTypes'

describe('credential types (frontend vs backend catalog)', () => {
  it('mirrors the canonical catalog exactly', () => {
    expect(CREDENTIAL_TYPES).toEqual(catalog.credentialTypes)
    expect(SECRET_FIELD).toEqual(catalog.secretField)
  })

  it.each(['api.example.com', '*.example.com', 'localhost', '127.0.0.1', '[::1]'])('accepts host %s', (host) =>
    expect(HOST_REGEX.test(host)).toBe(true))

  it.each(['https://api.example.com', 'api.example.com/path', '*', 'a..b', 'evil.com:8080'])('rejects host %s', (host) =>
    expect(HOST_REGEX.test(host)).toBe(false))
})

describe('validateDraft', () => {
  const draft = (overrides: Partial<CredentialDraft>): CredentialDraft => ({
    type: 'httpHeader', name: 'Key', description: '', hosts: 'api.example.com', fields: { headerName: 'X-API-Key' },
    secret: { value: 'abcdefgh123' }, ...overrides,
  })

  it('accepts a complete draft', () => {
    expect(validateDraft(draft({}), { withSecret: true })).toEqual({})
  })

  it('reports problems under the API field paths', () => {
    expect(validateDraft(draft({ name: ' ', hosts: 'https://x', fields: { headerName: 'Host' }, secret: { value: 'short' } }),
      { withSecret: true })).toEqual({
      name: 'Name must be 1 to 100 characters',
      allowedHosts: '"https://x" is not a host name (use api.example.com or *.example.com, no scheme or path)',
      'fields.headerName': 'This header is managed by the HTTP client',
      'secret.value': 'Value must be 8 to 4096 characters',
    })
    // Editing never sends a secret, so it is not required then.
    expect(validateDraft(draft({ secret: {} }), { withSecret: false })).toEqual({})
  })
})
