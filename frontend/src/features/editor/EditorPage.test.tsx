import { act, cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, type AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as healthApi from '@/lib/api/health'
import type { CreateWorkflowRequest, UpdateWorkflowRequest, WorkflowResponse } from '@/lib/api/workflows'
import type { WorkflowDefinition } from '@/lib/workflow/definition'
import { renderApp } from '@/test/renderWithProviders'

import { useEditorStore } from './editorStore'

// In-memory stand-in for the Module 3 API, so save -> reload can be tested end to end.
const server = vi.hoisted(() => ({ rows: new Map<string, unknown>() }))
const api = vi.hoisted(() => ({
  getWorkflow: vi.fn(),
  createWorkflow: vi.fn(),
  updateWorkflow: vi.fn(),
  listWorkflows: vi.fn(),
  validateWorkflow: vi.fn(), executeWorkflow: vi.fn(), listExecutions: vi.fn(), getExecution: vi.fn(),
}))
vi.mock('@/lib/api/workflows', () => api)

const store = () => useEditorStore.getState()

const EXISTING: WorkflowResponse = {
  id: 'wf-1', name: 'Orders', description: 'desc', status: 'DRAFT', version: 2, lockVersion: 4,
  createdAt: 't', updatedAt: 't',
  definition: {
    schemaVersion: 1,
    nodes: [
      { id: 'trigger', type: 'core.manualTrigger', typeVersion: 1, name: 'Start', position: { x: 10.5, y: 20 }, configuration: {} },
      { id: 'check', type: 'core.condition', typeVersion: 1, name: 'Big?', position: { x: 250, y: 20 }, configuration: { k: null } },
    ],
    edges: [{ id: 'e1', source: 'trigger', sourceHandle: 'main', target: 'check', targetHandle: 'main' }],
    editor: { viewport: { x: 5, y: 6, zoom: 1.5 } },
  },
}

function problem(status: number, data: object = {}) {
  return new AxiosError('failed', 'ERR', undefined, undefined, { status, data } as AxiosResponse)
}

function installFakeServer() {
  server.rows.clear()
  server.rows.set(EXISTING.id, structuredClone(EXISTING))
  let counter = 0
  api.getWorkflow.mockImplementation(async (id: string) => {
    const row = server.rows.get(id)
    if (!row) throw problem(404)
    return structuredClone(row)
  })
  api.createWorkflow.mockImplementation(async (request: CreateWorkflowRequest) => {
    const created: WorkflowResponse = {
      id: `created-${++counter}`, name: request.name, description: request.description, status: 'DRAFT', version: 1,
      lockVersion: 0, definition: request.definition as WorkflowDefinition, createdAt: 't', updatedAt: 't',
    }
    server.rows.set(created.id, structuredClone(created))
    return structuredClone(created)
  })
  api.updateWorkflow.mockImplementation(async (id: string, request: UpdateWorkflowRequest) => {
    const row = server.rows.get(id) as WorkflowResponse
    if (row.lockVersion !== request.lockVersion) throw problem(409, { status: 409 })
    const changed = JSON.stringify(row.definition) !== JSON.stringify(request.definition)
    const next: WorkflowResponse = {
      ...row, name: request.name, description: request.description, definition: request.definition,
      version: row.version + (changed ? 1 : 0), lockVersion: row.lockVersion + 1,
    }
    server.rows.set(id, structuredClone(next))
    return structuredClone(next)
  })
  api.validateWorkflow.mockResolvedValue({ valid: true, errorCount: 0, warningCount: 0, issues: [], executionOrder: [] })
  api.listWorkflows.mockResolvedValue({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })
  api.listExecutions.mockResolvedValue({ items: [], page: 0, size: 1, totalElements: 0, totalPages: 0 })
}

async function openEditor(route: string) {
  const utils = renderApp(route)
  await screen.findByRole('toolbar', { name: 'Editor toolbar' })
  return utils
}

async function addNodeFromPalette(label: string) {
  await userEvent.dblClick(within(screen.getByRole('complementary', { name: 'Node palette' })).getByRole('button', { name: label }))
}

describe('EditorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    store().reset()
    installFakeServer()
    vi.spyOn(healthApi, 'getHealth').mockResolvedValue({ status: 'UP', version: '1', timestamp: 't', components: {} })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  it('shows all five regions: palette, canvas, properties, toolbar and execution panel', async () => {
    await openEditor('/workflows/wf-1')

    expect(screen.getByRole('complementary', { name: 'Node palette' })).toBeInTheDocument()
    expect(screen.getByTestId('canvas-area')).toBeInTheDocument()
    expect(screen.getByRole('complementary', { name: 'Properties' })).toBeInTheDocument()
    expect(screen.getByRole('toolbar', { name: 'Editor toolbar' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Execution' })).toBeInTheDocument()
  })

  it('loads a saved workflow: name, nodes, edge labels and clean state', async () => {
    await openEditor('/workflows/wf-1')

    expect(screen.getByRole('textbox', { name: 'Workflow name' })).toHaveValue('Orders')
    expect(await screen.findAllByTestId('workflow-node')).toHaveLength(2)
    expect(screen.getByText('Start')).toBeInTheDocument()
    expect(screen.getByTestId('dirty-indicator')).toHaveTextContent('All changes saved')
    expect(store().nodes[0].position).toEqual({ x: 10.5, y: 20 })
  })

  it('saves an edit with the canonical definition and the lockVersion from the last read', async () => {
    await openEditor('/workflows/wf-1')
    await addNodeFromPalette('Logger')
    expect(screen.getByTestId('dirty-indicator')).toHaveTextContent('Unsaved changes')

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(api.updateWorkflow).toHaveBeenCalledTimes(1))
    const [id, request] = api.updateWorkflow.mock.calls[0] as [string, UpdateWorkflowRequest]
    expect(id).toBe('wf-1')
    expect(request).toMatchObject({ name: 'Orders', description: 'desc', lockVersion: 4 })
    expect(request.definition.schemaVersion).toBe(1)
    expect(request.definition.nodes.map((n) => n.type)).toEqual(['core.manualTrigger', 'core.condition', 'core.logger'])
    expect(request.definition.edges).toEqual(EXISTING.definition.edges)
    expect(request.definition.editor?.viewport).toBeDefined()
    expect(JSON.stringify(request.definition)).not.toMatch(/selected|measured|dragging/)

    await waitFor(() => expect(screen.getByTestId('dirty-indicator')).toHaveTextContent('All changes saved'))
    expect(store()).toMatchObject({ lockVersion: 5, version: 3 })
  })

  it('creates a new workflow on first save, then edits update it', async () => {
    const { router } = await openEditor('/workflows/new')
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()

    await userEvent.type(screen.getByRole('textbox', { name: 'Workflow name' }), 'Fresh flow')
    await addNodeFromPalette('Manual Trigger')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/workflows/created-1'))
    expect(api.createWorkflow).toHaveBeenCalledTimes(1)
    const request = api.createWorkflow.mock.calls[0][0] as CreateWorkflowRequest
    expect(request.name).toBe('Fresh flow')
    expect(request.definition?.nodes).toHaveLength(1)
    expect(request.definition?.nodes[0]).toMatchObject({ type: 'core.manualTrigger', typeVersion: 1, name: 'Manual Trigger' })
    expect(window.confirm).not.toHaveBeenCalled() // no unsaved-changes prompt for the post-save redirect
    await waitFor(() => expect(screen.getByTestId('dirty-indicator')).toHaveTextContent('All changes saved'))
    expect(api.getWorkflow).not.toHaveBeenCalled() // store already holds it; undo history is kept

    await addNodeFromPalette('Logger')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(api.updateWorkflow).toHaveBeenCalledWith('created-1', expect.objectContaining({ lockVersion: 0 })))
  })

  it('reloads the same graph with the same ids and positions after leaving and reopening (browser refresh)', async () => {
    const first = await openEditor('/workflows/new')
    await userEvent.type(screen.getByRole('textbox', { name: 'Workflow name' }), 'Persisted')
    await addNodeFromPalette('Manual Trigger')
    await addNodeFromPalette('HTTP Request')
    const [a, b] = store().nodes
    act(() => store().onConnect({ source: a.id, target: b.id, sourceHandle: 'main', targetHandle: 'main' }))
    act(() => store().onNodesChange([{ type: 'position', id: b.id, position: { x: 321.25, y: -45.5 } }]))
    const before = {
      nodes: store().nodes.map((n) => ({ id: n.id, type: n.data.nodeType, name: n.data.name, position: n.position })),
      edges: store().edges.map((e) => [e.id, e.source, e.sourceHandle, e.target, e.targetHandle]),
    }
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(first.router.state.location.pathname).toBe('/workflows/created-1'))
    await waitFor(() => expect(screen.getByTestId('dirty-indicator')).toHaveTextContent('All changes saved'))

    // Simulate a refresh: tear everything down (store included) and open the URL fresh.
    first.unmount()
    cleanup()
    store().reset()
    await openEditor('/workflows/created-1')

    expect(api.getWorkflow).toHaveBeenCalledWith('created-1')
    expect(screen.getByRole('textbox', { name: 'Workflow name' })).toHaveValue('Persisted')
    expect(await screen.findAllByTestId('workflow-node')).toHaveLength(2)
    expect({
      nodes: store().nodes.map((n) => ({ id: n.id, type: n.data.nodeType, name: n.data.name, position: n.position })),
      edges: store().edges.map((e) => [e.id, e.source, e.sourceHandle, e.target, e.targetHandle]),
    }).toEqual(before)
    expect(screen.getByTestId('dirty-indicator')).toHaveTextContent('All changes saved')
  })

  it('rejects a stale save with a conflict message and can reload the latest version', async () => {
    await openEditor('/workflows/wf-1')
    server.rows.set('wf-1', { ...structuredClone(EXISTING), name: 'Changed elsewhere', lockVersion: 9 })
    await addNodeFromPalette('Logger')

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('changed elsewhere')
    expect(screen.getByTestId('dirty-indicator')).toHaveTextContent('Unsaved changes')

    await userEvent.click(screen.getByRole('button', { name: 'Reload latest' }))

    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Workflow name' })).toHaveValue('Changed elsewhere'))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(store().lockVersion).toBe(9)
    expect(screen.getByTestId('dirty-indicator')).toHaveTextContent('All changes saved')
  })

  it('shows server validation errors from a failed save', async () => {
    await openEditor('/workflows/wf-1')
    api.updateWorkflow.mockRejectedValueOnce(problem(400, { status: 400, errors: [{ field: 'name', message: 'must not be blank' }] }))
    await addNodeFromPalette('Logger')

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('name: must not be blank')
  })

  it('keeps edits and reports a failed conflict reload', async () => {
    await openEditor('/workflows/wf-1')
    api.updateWorkflow.mockRejectedValueOnce(problem(409, { status: 409 }))
    await addNodeFromPalette('Logger')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await screen.findByRole('button', { name: 'Reload latest' })
    api.getWorkflow.mockRejectedValueOnce(new Error('offline'))

    await userEvent.click(screen.getByRole('button', { name: 'Reload latest' }))

    expect(await screen.findByText(/Could not reload the workflow/)).toBeInTheDocument()
    expect(store().nodes).toHaveLength(3)
    expect(screen.getByTestId('dirty-indicator')).toHaveTextContent('Unsaved changes')
    expect(screen.getByRole('button', { name: 'Reload latest' })).toBeEnabled()
  })

  it('ignores a delayed conflict reload after switching workflows', async () => {
    const { router } = await openEditor('/workflows/wf-1')
    api.updateWorkflow.mockRejectedValueOnce(problem(409, { status: 409 }))
    await addNodeFromPalette('Logger')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await screen.findByRole('button', { name: 'Reload latest' })
    let resolve!: (value: WorkflowResponse) => void
    api.getWorkflow.mockReturnValueOnce(new Promise((done) => { resolve = done }))
    await userEvent.click(screen.getByRole('button', { name: 'Reload latest' }))
    server.rows.set('wf-2', { ...EXISTING, id: 'wf-2', name: 'Other' })
    await act(async () => { await router.navigate('/workflows/wf-2') })
    await waitFor(() => expect(store().workflowId).toBe('wf-2'))

    await act(async () => resolve({ ...EXISTING, lockVersion: 9 }))

    expect(store().workflowId).toBe('wf-2')
    expect(screen.getByRole('textbox', { name: 'Workflow name' })).toHaveValue('Other')
  })

  it('warns before the tab is closed only while there are unsaved changes', async () => {
    await openEditor('/workflows/wf-1')
    const clean = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(clean)
    expect(clean.defaultPrevented).toBe(false)

    await addNodeFromPalette('Logger')
    const dirty = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(dirty)
    expect(dirty.defaultPrevented).toBe(true)
  })

  it('asks for confirmation before in-app navigation with unsaved changes', async () => {
    const { router } = await openEditor('/workflows/wf-1')
    await addNodeFromPalette('Logger')
    vi.mocked(window.confirm).mockReturnValueOnce(false)

    await userEvent.click(screen.getByRole('link', { name: /Workflows/ }))
    await waitFor(() => expect(window.confirm).toHaveBeenCalledTimes(1))
    expect(router.state.location.pathname).toBe('/workflows/wf-1')

    vi.mocked(window.confirm).mockReturnValueOnce(true)
    await userEvent.click(screen.getByRole('link', { name: /Workflows/ }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/'))
  })

  it('navigates away without a prompt when there is nothing unsaved', async () => {
    const { router } = await openEditor('/workflows/wf-1')

    await userEvent.click(screen.getByRole('link', { name: /Workflows/ }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/'))
    expect(window.confirm).not.toHaveBeenCalled()
  })

  it('reports a missing workflow', async () => {
    renderApp('/workflows/does-not-exist')
    expect(await screen.findByText('Workflow not found.')).toBeInTheDocument()
  })

  it('makes archived workflows read-only', async () => {
    server.rows.set('wf-1', { ...structuredClone(EXISTING), status: 'ARCHIVED' })
    await openEditor('/workflows/wf-1')
    await addNodeFromPalette('Logger')

    expect(screen.getByText('This workflow is archived and read-only.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
  })
})
