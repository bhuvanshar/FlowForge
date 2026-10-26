import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as healthApi from '@/lib/api/health'
import * as workflowsApi from '@/lib/api/workflows'
import { useUiStore } from '@/stores/uiStore'
import { renderApp } from '@/test/renderWithProviders'

describe('App shell', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    useUiStore.setState({ sidebarCollapsed: false })
    vi.spyOn(healthApi, 'getHealth').mockResolvedValue({
      status: 'UP',
      version: '0.1.0',
      timestamp: '2026-01-01T00:00:00Z',
      components: { database: 'UP' },
    })
    vi.spyOn(workflowsApi, 'listWorkflows').mockResolvedValue({
      items: [], page: 0, size: 50, totalElements: 0, totalPages: 0,
    })
  })

  it('renders sidebar, header and content area', async () => {
    renderApp()
    expect(screen.getByRole('complementary', { name: 'Sidebar' })).toBeInTheDocument()
    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(screen.getByRole('main')).toBeInTheDocument()
    expect(await screen.findByText('Backend connected')).toBeInTheDocument()
  })

  it('collapses the sidebar via the header toggle', async () => {
    renderApp()
    await userEvent.click(screen.getByRole('button', { name: 'Toggle sidebar' }))
    expect(useUiStore.getState().sidebarCollapsed).toBe(true)
    expect(screen.queryByText('FlowForge')).not.toBeInTheDocument()
  })

  it('shows a not-found message for unknown routes', async () => {
    renderApp('/nope')
    expect(await screen.findByText('Page not found.')).toBeInTheDocument()
  })
})
