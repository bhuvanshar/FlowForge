import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, type AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as healthApi from '@/lib/api/health'
import type { Credential } from '@/lib/api/credentials'
import { renderApp } from '@/test/renderWithProviders'

const api = vi.hoisted(() => ({
  CREDENTIALS_QUERY_KEY: ['credentials'],
  listCredentials: vi.fn(), getEncryptionStatus: vi.fn(), createCredential: vi.fn(), updateCredential: vi.fn(),
  rotateCredential: vi.fn(), deleteCredential: vi.fn(), getCredentialUsage: vi.fn(),
}))
vi.mock('@/lib/api/credentials', () => api)

const BASIC: Credential = {
  id: 'c-1', name: 'CRM', type: 'httpBasic', allowedHosts: ['crm.example.com'], fields: { username: 'ada' },
  secretFields: ['password'], secretVersion: 3, createdAt: 't', updatedAt: 't', lastUsedAt: '2026-01-02T03:04:05Z',
  lockVersion: 7,
}

function problem(status: number, data: Record<string, unknown>) {
  return new AxiosError('fail', String(status), undefined, undefined, { status, data: { status, ...data } } as AxiosResponse)
}

async function open() {
  renderApp('/credentials')
  return screen.findByRole('heading', { name: 'Credentials' })
}

describe('credentials page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.spyOn(healthApi, 'getHealth').mockResolvedValue({ status: 'UP', version: '1', timestamp: 't', components: {} })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    api.listCredentials.mockResolvedValue([BASIC])
    api.getEncryptionStatus.mockResolvedValue({ available: true, activeKeyId: 'k1', credentialsOnOldKeys: 0 })
  })

  it('lists credentials without any secret value and links from the sidebar', async () => {
    await open()
    const row = (await screen.findByText('CRM')).closest('tr')!
    expect(row).toHaveTextContent('Basic authentication')
    expect(row).toHaveTextContent('crm.example.com')
    expect(row).toHaveTextContent('Username: ada')
    expect(row).toHaveTextContent('•••••••• v3')
    expect(screen.getByRole('link', { name: 'Credentials' })).toHaveAttribute('href', '/credentials')
  })

  it('creates a credential with a write-only secret and clears it afterwards', async () => {
    const user = userEvent.setup()
    api.createCredential.mockResolvedValue({ ...BASIC, id: 'c-2', name: 'Payments', type: 'httpBearer', fields: {} })
    await open()
    await user.click(screen.getByRole('button', { name: 'New credential' }))
    const form = screen.getByRole('form', { name: 'New credential' })
    await user.selectOptions(within(form).getByLabelText('Type'), 'httpBearer')
    await user.type(within(form).getByLabelText('Name'), 'Payments')
    await user.type(within(form).getByLabelText('Allowed hosts'), 'api.pay.example, *.pay.example')
    const secret = within(form).getByLabelText('Token')
    expect(secret).toHaveAttribute('type', 'password')
    expect(secret).toHaveAttribute('autocomplete', 'new-password')
    await user.type(secret, 'tok_live_0123456789')
    await user.click(within(form).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(api.createCredential).toHaveBeenCalledWith({
      type: 'httpBearer', name: 'Payments', description: undefined, allowedHosts: ['api.pay.example', '*.pay.example'],
      fields: {}, secret: { token: 'tok_live_0123456789' },
    }))
    expect(await screen.findByText('Created "Payments".')).toHaveAttribute('role', 'status')
    expect(screen.queryByRole('form')).not.toBeInTheDocument()
    expect(document.body.textContent).not.toContain('tok_live_0123456789')
  })

  it('validates before sending and shows server field errors', async () => {
    const user = userEvent.setup()
    api.createCredential.mockRejectedValue(problem(400, { errors: [{ field: 'allowedHosts[0]', message: 'nope' },
      { field: 'name', message: 'is taken' }] }))
    await open()
    await user.click(screen.getByRole('button', { name: 'New credential' }))
    const form = screen.getByRole('form', { name: 'New credential' })
    await user.click(within(form).getByRole('button', { name: 'Save' }))
    expect(within(form).getByText('Name must be 1 to 100 characters')).toBeInTheDocument()
    expect(within(form).getByText('Token must be 8 to 4096 characters')).toBeInTheDocument()
    expect(api.createCredential).not.toHaveBeenCalled()

    await user.type(within(form).getByLabelText('Name'), 'X')
    await user.type(within(form).getByLabelText('Allowed hosts'), 'a.example')
    await user.type(within(form).getByLabelText('Token'), 'tok_live_0123456789')
    await user.click(within(form).getByRole('button', { name: 'Save' }))
    expect(await within(form).findByText('is taken')).toBeInTheDocument()
  })

  it('edits details without the secret and rotates the secret separately', async () => {
    const user = userEvent.setup()
    api.updateCredential.mockResolvedValue({ ...BASIC, name: 'CRM prod' })
    api.rotateCredential.mockResolvedValue({ ...BASIC, secretVersion: 4 })
    await open()
    await user.click(await screen.findByRole('button', { name: 'Edit CRM' }))
    const edit = screen.getByRole('form', { name: 'Edit "CRM"' })
    expect(within(edit).queryByLabelText('Password')).not.toBeInTheDocument()
    await user.clear(within(edit).getByLabelText('Name'))
    await user.type(within(edit).getByLabelText('Name'), 'CRM prod')
    await user.click(within(edit).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(api.updateCredential).toHaveBeenCalledWith('c-1', {
      name: 'CRM prod', description: undefined, allowedHosts: ['crm.example.com'], fields: { username: 'ada' }, lockVersion: 7,
    }))

    await user.click(await screen.findByRole('button', { name: 'Rotate secret of CRM' }))
    const rotate = screen.getByRole('form', { name: 'Rotate secret of "CRM"' })
    await user.type(within(rotate).getByLabelText('New password'), 'new-password-123')
    await user.click(within(rotate).getByRole('button', { name: 'Rotate secret' }))
    await waitFor(() => expect(api.rotateCredential).toHaveBeenCalledWith('c-1', { password: 'new-password-123' }))
    expect(await screen.findByText('Rotated the secret of "CRM" (version 4).')).toHaveAttribute('role', 'status')
  })

  it('explains why a credential in use cannot be deleted', async () => {
    const user = userEvent.setup()
    api.deleteCredential.mockRejectedValue(problem(409, { workflows: [{ id: 'wf-1', name: 'Sync CRM', status: 'ACTIVE' }] }))
    await open()
    await user.click(await screen.findByRole('button', { name: 'Delete CRM' }))
    const alert = await screen.findByText(/is used by 1 workflow/)
    expect(alert.closest('[role="alert"]')).toHaveTextContent('Sync CRM (ACTIVE)')
    expect(screen.getByRole('link', { name: 'Sync CRM' })).toHaveAttribute('href', '/workflows/wf-1')
  })

  it('reports secrets that still need encryption', async () => {
    api.getEncryptionStatus.mockResolvedValue({ available: true, activeKeyId: 'k2', credentialsOnOldKeys: 1, webhookSecretsToEncrypt: 2 })
    await open()
    expect(await screen.findByRole('note')).toHaveTextContent(
      '1 credential(s) and 2 webhook secret(s) are not yet encrypted with the active key. Run POST /api/v1/credentials/re-encrypt')
  })

  it('disables creation when the server has no encryption key', async () => {
    api.getEncryptionStatus.mockResolvedValue({ available: false, credentialsOnOldKeys: 0 })
    await open()
    expect(await screen.findByText(/Credentials are disabled/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'New credential' })).toBeDisabled()
  })
})
