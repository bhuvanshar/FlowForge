import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as api from '@/lib/api/settings'
import type { ServerSettings } from '@/lib/api/settings'
import { renderWithProviders } from '@/test/renderWithProviders'

import { usePreferences } from './preferences'
import { SettingsPage } from './SettingsPage'

const SETTINGS: ServerSettings = {
  application: { version: '0.1.0' },
  execution: { workerThreads: 2, queueCapacity: 100, nodeTimeoutSeconds: 30, maxDurationSeconds: 600, maxActivePerWorkflow: 10, maxStoredPayloadBytes: 262144 },
  outbound: { allowedHosts: ['api.openai.com', 'localhost'], allowPrivateAddresses: false, allowedPorts: [], maxResponseBytes: 1048576, maxRequestBytes: 1048576, maxRedirects: 3 },
  triggers: { schedulerEnabled: true, schedulerPollSeconds: 5, allowUnauthenticatedWebhooks: false, webhookMaxBodyBytes: 262144 },
  credentials: { encryptionAvailable: true, activeKeyId: 'k1', keyCount: 1 },
  ai: { maxPromptChars: 100000, maxResponseBytes: 1048576 },
  security: { allowedHostNames: ['localhost', '127.0.0.1'], allowedOrigins: ['http://localhost:5173'], maxRequestBytes: 1048576 },
}

describe('SettingsPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    window.localStorage.clear()
    usePreferences.getState().reset()
    vi.spyOn(api, 'getServerSettings').mockResolvedValue(SETTINGS)
  })

  it('has one page heading and a labelled region for each kind of setting', async () => {
    renderWithProviders(<SettingsPage />)
    expect(screen.getByRole('heading', { level: 1, name: 'Settings' })).toBeInTheDocument()
    for (const name of ['Dashboard preferences', 'Server configuration', 'Keyboard shortcuts']) {
      expect(screen.getByRole('region', { name })).toBeInTheDocument()
    }
    await screen.findByText('FlowForge 0.1.0')
  })

  it('changes dashboard preferences immediately and can reset them', async () => {
    renderWithProviders(<SettingsPage />)
    await userEvent.selectOptions(screen.getByLabelText('Default view'), 'list')
    await userEvent.selectOptions(screen.getByLabelText('Default sort'), 'name,asc')
    await userEvent.selectOptions(screen.getByLabelText('Workflows per page'), '24')

    expect(usePreferences.getState()).toMatchObject({ defaultView: 'list', defaultSort: 'name,asc', pageSize: 24 })
    await userEvent.click(screen.getByRole('button', { name: 'Reset to defaults' }))
    expect(screen.getByLabelText('Default view')).toHaveValue('grid')
    expect(screen.getByLabelText('Workflows per page')).toHaveValue('12')
  })

  it('shows what the server runs with, including how to change it', async () => {
    renderWithProviders(<SettingsPage />)
    const region = screen.getByRole('region', { name: 'Server configuration' })
    await within(region).findByText('FlowForge 0.1.0')

    expect(region).toHaveTextContent('2 at a time, 100 waiting, at most 10 per workflow')
    expect(region).toHaveTextContent('30 s per node, 10 min per execution')
    expect(within(within(region).getByRole('list', { name: 'Allowed hosts' })).getAllByRole('listitem')).toHaveLength(2)
    expect(region).toHaveTextContent('FLOWFORGE_HTTP_ALLOWED_HOSTS')
    expect(region).toHaveTextContent('Blocked')
    expect(region).toHaveTextContent('active key “k1”, 1 key loaded')
    expect(region).toHaveTextContent('Not allowed')
    expect(within(region).getByRole('link', { name: /Manage credentials/ })).toHaveAttribute('href', '/credentials')
  })

  it('flags settings that need attention', async () => {
    vi.spyOn(api, 'getServerSettings').mockResolvedValue({
      ...SETTINGS,
      outbound: { ...SETTINGS.outbound, allowedHosts: [], allowPrivateAddresses: true },
      credentials: { encryptionAvailable: false, keyCount: 0 },
      triggers: { ...SETTINGS.triggers, schedulerEnabled: false, allowUnauthenticatedWebhooks: true },
    })
    renderWithProviders(<SettingsPage />)
    const region = screen.getByRole('region', { name: 'Server configuration' })

    expect(await within(region).findByText('None allowed')).toBeInTheDocument()
    expect(region).toHaveTextContent('Allowed (development only)')
    expect(region).toHaveTextContent('No key configured')
    expect(region).toHaveTextContent('Credentials cannot be stored.')
    expect(region).toHaveTextContent('Off')
    expect(region).toHaveTextContent('Allowed')
  })

  it('shows a loading placeholder, then an error with retry when the server cannot be reached', async () => {
    const get = vi.spyOn(api, 'getServerSettings').mockRejectedValueOnce(new Error('down'))
    renderWithProviders(<SettingsPage />)
    expect(screen.getByText('Loading server settings…')).toBeInTheDocument()
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the server settings')

    get.mockResolvedValue(SETTINGS)
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await screen.findByText('FlowForge 0.1.0')
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
  })

  it('lists the keyboard shortcuts', () => {
    renderWithProviders(<SettingsPage />)
    const region = screen.getByRole('region', { name: 'Keyboard shortcuts' })
    expect(region).toHaveTextContent('Jump to the workflow search')
    expect(within(region).getAllByText('Esc').length).toBeGreaterThan(0)
  })
})
