import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as api from '@/lib/api/workflows'
import { renderWithProviders } from '@/test/renderWithProviders'

import { WorkflowListPage } from './WorkflowListPage'

describe('WorkflowListPage', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('lists workflows with links to the editor and a new-workflow button', async () => {
    vi.spyOn(api, 'listWorkflows').mockResolvedValue({
      items: [{
        id: 'a1', name: 'Orders', status: 'ACTIVE', version: 3, lockVersion: 1, nodeCount: 4, edgeCount: 3,
        createdAt: 't', updatedAt: 't',
      }],
      page: 0, size: 50, totalElements: 1, totalPages: 1,
    })
    renderWithProviders(<WorkflowListPage />)

    const link = await screen.findByRole('link', { name: /^Orders/ })
    expect(link).toHaveAttribute('href', '/workflows/a1')
    expect(link).toHaveTextContent('4 nodes')
    expect(screen.getByRole('button', { name: /New workflow/ })).toHaveAttribute('aria-keyshortcuts', 'n')
    expect(screen.getByRole('link', { name: 'Execution history of Orders' })).toHaveAttribute('href', '/workflows/a1/executions')
  })

  it('shows an empty state and an error state', async () => {
    const spy = vi.spyOn(api, 'listWorkflows').mockResolvedValueOnce({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })
    const { unmount } = renderWithProviders(<WorkflowListPage />)
    expect(await screen.findByText(/No workflows yet/)).toBeInTheDocument()
    unmount()

    spy.mockRejectedValueOnce(new Error('down'))
    renderWithProviders(<WorkflowListPage />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load workflows.')
  })
})
