import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as healthApi from '@/lib/api/health'
import * as samples from '@/lib/api/samples'
import * as settingsApi from '@/lib/api/settings'
import * as credentialsApi from '@/lib/api/credentials'
import * as api from '@/lib/api/workflows'
import { renderApp } from '@/test/renderWithProviders'

import { sectionOf } from './sections'

describe('sectionOf', () => {
  it.each([
    ['/', 'Workflows'],
    ['/workflows/abc', 'Workflow editor'],
    ['/workflows/abc/executions', 'Executions'],
    ['/executions/run-1', 'Executions'],
    ['/credentials', 'Credentials'],
    ['/settings', 'Settings'],
  ])('%s belongs to %s', (path, section) => expect(sectionOf(path)).toBe(section))
})

describe('application shell and navigation', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.spyOn(healthApi, 'getHealth').mockResolvedValue({ status: 'UP', version: '1', timestamp: 't', components: {} })
    vi.spyOn(api, 'listWorkflows').mockResolvedValue({
      items: [{ id: 'orders', name: 'Orders', status: 'ACTIVE', version: 1, lockVersion: 0, nodeCount: 2, edgeCount: 1, createdAt: 't', updatedAt: 't' }],
      page: 0, size: 12, totalElements: 1, totalPages: 1,
    })
    vi.spyOn(api, 'listExecutions').mockResolvedValue({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
    vi.spyOn(api, 'getWorkflow').mockResolvedValue({ id: 'orders', name: 'Orders', status: 'ACTIVE', version: 1, lockVersion: 0,
      definition: { schemaVersion: 1, nodes: [], edges: [] }, createdAt: 't', updatedAt: 't' })
    vi.spyOn(credentialsApi, 'listCredentials').mockResolvedValue([])
    vi.spyOn(credentialsApi, 'getEncryptionStatus').mockResolvedValue({ available: true, activeKeyId: 'k1', credentialsOnOldKeys: 0 })
    vi.spyOn(settingsApi, 'getServerSettings').mockRejectedValue(new Error('not needed here'))
    vi.spyOn(samples, 'listSamples').mockResolvedValue([])
  })

  it('has a skip link as the first tab stop that targets the focusable main region', async () => {
    renderApp('/')
    const skip = await screen.findByRole('link', { name: 'Skip to main content' })
    await userEvent.tab()
    expect(skip).toHaveFocus()
    expect(skip).toHaveAttribute('href', '#main')
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main')
    expect(screen.getByRole('main')).toHaveAttribute('tabindex', '-1')
  })

  it('offers Workflows, Credentials and Settings and marks the current page', async () => {
    renderApp('/')
    await screen.findByRole('heading', { name: 'Workflows', level: 1 })
    const nav = screen.getByRole('navigation', { name: 'Main' })
    expect(within(nav).getAllByRole('link').map((l) => l.getAttribute('aria-label'))).toEqual(['Workflows', 'Credentials', 'Settings'])
    expect(within(nav).getByRole('link', { name: 'Workflows' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Settings' })).not.toHaveAttribute('aria-current')
  })

  it('moves between dashboard, editor, execution history, credentials and settings, with matching titles and breadcrumbs', async () => {
    const { router } = renderApp('/')
    await screen.findByRole('link', { name: /^Orders,/ })
    expect(document.title).toBe('Workflows · FlowForge')

    await userEvent.click(screen.getByRole('link', { name: 'Execution history of Orders' }))
    await screen.findByRole('heading', { name: /Execution history/ })
    expect(router.state.location.pathname).toBe('/workflows/orders/executions')
    expect(document.title).toBe('Executions · FlowForge')
    expect(screen.getByRole('navigation', { name: 'Breadcrumb' })).toHaveTextContent('Executions')

    await userEvent.click(screen.getByRole('link', { name: 'Credentials' }))
    await screen.findByRole('heading', { name: 'Credentials', level: 1 })
    expect(document.title).toBe('Credentials · FlowForge')
    expect(screen.getByRole('link', { name: 'Credentials' })).toHaveAttribute('aria-current', 'page')

    await userEvent.click(screen.getByRole('link', { name: 'Settings' }))
    await screen.findByRole('heading', { name: 'Settings', level: 1 })
    expect(document.title).toBe('Settings · FlowForge')
    expect(router.state.location.pathname).toBe('/settings')

    await userEvent.click(screen.getByRole('link', { name: 'Workflows' }))
    await userEvent.click(await screen.findByRole('link', { name: /^Orders,/ }))
    await screen.findByRole('toolbar', { name: 'Editor toolbar' })
    expect(router.state.location.pathname).toBe('/workflows/orders')
    expect(document.title).toBe('Workflow editor · FlowForge')
  })

  it('moves keyboard focus to the new page after navigating, but not on the first load', async () => {
    renderApp('/')
    await screen.findByRole('heading', { name: 'Workflows', level: 1 })
    expect(screen.getByRole('main')).not.toHaveFocus()
    await userEvent.click(screen.getByRole('link', { name: 'Settings' }))
    await screen.findByRole('heading', { name: 'Settings', level: 1 })
    expect(screen.getByRole('main')).toHaveFocus()
  })

  it('shows the not-found page for unknown addresses inside the shell', async () => {
    renderApp('/no/such/page')
    expect(await screen.findByRole('navigation', { name: 'Main' })).toBeInTheDocument()
    expect(screen.getByRole('main')).toHaveTextContent(/not found|doesn.t exist/i)
  })
})
