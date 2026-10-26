import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as healthApi from '@/lib/api/health'
import { renderWithProviders } from '@/test/renderWithProviders'

import { ConnectivityIndicator } from './ConnectivityIndicator'

const health = (status: 'UP' | 'DOWN'): healthApi.HealthResponse => ({
  status,
  version: '0.1.0',
  timestamp: '2026-01-01T00:00:00Z',
  components: { database: status },
})

describe('ConnectivityIndicator', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('shows connected when the backend reports UP', async () => {
    vi.spyOn(healthApi, 'getHealth').mockResolvedValue(health('UP'))
    renderWithProviders(<ConnectivityIndicator />)
    expect(await screen.findByText('Backend connected')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveAttribute('data-state', 'connected')
  })

  it('shows degraded when a component is DOWN', async () => {
    vi.spyOn(healthApi, 'getHealth').mockResolvedValue(health('DOWN'))
    renderWithProviders(<ConnectivityIndicator />)
    expect(await screen.findByText('Backend degraded')).toBeInTheDocument()
  })

  it('shows unreachable when the request fails', async () => {
    vi.spyOn(healthApi, 'getHealth').mockRejectedValue(new Error('network'))
    renderWithProviders(<ConnectivityIndicator />)
    expect(await screen.findByText('Backend unreachable')).toBeInTheDocument()
  })
})
