import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { renderWithProviders } from '@/test/renderWithProviders'

import { useEditorStore } from './editorStore'
import { PropertiesPanel } from './PropertiesPanel'

const credentialsApi = vi.hoisted(() => ({
  CREDENTIALS_QUERY_KEY: ['credentials'],
  listCredentials: vi.fn(async () => [
    { id: '3f2b8c1e-5d4a-4b6f-9e7d-1a2b3c4d5e6f', name: 'Users API', type: 'httpBearer', allowedHosts: ['api.example.com'],
      fields: {}, secretFields: ['token'], secretVersion: 1, createdAt: 't', updatedAt: 't', lockVersion: 0 },
  ]),
}))
vi.mock('@/lib/api/credentials', () => credentialsApi)

const store = () => useEditorStore.getState()

describe('PropertiesPanel', () => {
  beforeEach(() => {
    store().reset()
    store().newWorkflow()
  })

  it('asks for a selection when nothing is selected', () => {
    renderWithProviders(<PropertiesPanel />)
    expect(screen.getByText('Select a node to configure it.')).toBeInTheDocument()
  })

  it('shows the identity of the selected node and its settings form', () => {
    const id = store().addNode('core.httpRequest', { x: 0, y: 0 })!
    renderWithProviders(<PropertiesPanel />)

    expect(screen.getAllByText('HTTP Request', { selector: 'dd' })).toHaveLength(2) // name and type
    expect(screen.getByText(id)).toBeInTheDocument()
    expect(screen.getByRole('form', { name: 'Node settings' })).toBeInTheDocument()
    expect(screen.getByLabelText('URL')).toBeInTheDocument()
  })

  it('summarises multi-selection', () => {
    store().addNode('core.logger', { x: 0, y: 0 })
    store().addNode('core.logger', { x: 1, y: 1 })
    store().onNodesChange(store().nodes.map((n) => ({ type: 'select' as const, id: n.id, selected: true })))
    renderWithProviders(<PropertiesPanel />)

    expect(screen.getByText('2 nodes selected.')).toBeInTheDocument()
  })
})
