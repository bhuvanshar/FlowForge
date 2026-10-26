import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as healthApi from '@/lib/api/health'
import type { ValidationResult, WorkflowResponse } from '@/lib/api/workflows'
import type { WorkflowDefinition } from '@/lib/workflow/definition'
import { renderApp } from '@/test/renderWithProviders'

import { decorateEdges } from './edgeDecoration'
import { useEditorStore } from './editorStore'

const api = vi.hoisted(() => ({
  getWorkflow: vi.fn(), createWorkflow: vi.fn(), updateWorkflow: vi.fn(), listWorkflows: vi.fn(), validateWorkflow: vi.fn(), executeWorkflow: vi.fn(), listExecutions: vi.fn(), getExecution: vi.fn(),
}))
vi.mock('@/lib/api/workflows', () => api)

const store = () => useEditorStore.getState()

const WORKFLOW: WorkflowResponse = {
  id: 'wf-1', name: 'Orders', status: 'DRAFT', version: 1, lockVersion: 2, createdAt: 't', updatedAt: 't',
  definition: {
    schemaVersion: 1,
    nodes: [
      { id: 't', type: 'core.manualTrigger', typeVersion: 1, name: 'Start', position: { x: 0, y: 0 }, configuration: {} },
      { id: 'c', type: 'core.condition', typeVersion: 1, name: 'Check', position: { x: 250, y: 0 }, configuration: { field: 'a', operator: 'equals', value: '1' } },
      { id: 'l', type: 'core.logger', typeVersion: 1, name: 'Log', position: { x: 500, y: 0 }, configuration: { message: '' } },
    ],
    edges: [
      { id: 'e1', source: 't', sourceHandle: 'main', target: 'c', targetHandle: 'main' },
      { id: 'e2', source: 'c', sourceHandle: 'true', target: 'l', targetHandle: 'main' },
      { id: 'e3', source: 'l', sourceHandle: 'main', target: 'c', targetHandle: 'main' },
    ],
    editor: { viewport: { x: 0, y: 0, zoom: 1 } },
  },
}

const INVALID: ValidationResult = {
  valid: false, errorCount: 3, warningCount: 1, executionOrder: [],
  issues: [
    { code: 'UNSUPPORTED_MERGE', severity: 'ERROR', message: "'Check' already has an incoming connection.", nodeId: 'c', edgeId: 'e3', path: 'edges[2]' },
    { code: 'BRANCH_NOT_CONNECTED', severity: 'WARNING', message: "The 'false' branch of 'Check' is not connected.", nodeId: 'c', path: 'nodes[1]' },
    { code: 'CYCLE_DETECTED', severity: 'ERROR', message: "Loop detected: 'Check' → 'Log' → 'Check'.", nodeId: 'c', edgeId: 'e3', path: 'edges[2]' },
    { code: 'CONFIGURATION_INVALID', severity: 'ERROR', message: "'Log': message: must not be empty", nodeId: 'l', path: 'nodes[2].configuration.message' },
  ],
}
const VALID: ValidationResult = { valid: true, errorCount: 0, warningCount: 0, issues: [], executionOrder: ['t', 'c', 'l'] }

async function openEditor(route = '/workflows/wf-1') {
  const utils = renderApp(route)
  await screen.findByRole('toolbar', { name: 'Editor toolbar' })
  return utils
}

describe('validation in the editor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    store().reset()
    api.getWorkflow.mockResolvedValue(structuredClone(WORKFLOW))
    api.listWorkflows.mockResolvedValue({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })
  api.listExecutions.mockResolvedValue({ items: [], page: 0, size: 1, totalElements: 0, totalPages: 0 })
    api.validateWorkflow.mockResolvedValue(INVALID)
    vi.spyOn(healthApi, 'getHealth').mockResolvedValue({ status: 'UP', version: '1', timestamp: 't', components: {} })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  it('validates the loaded workflow automatically and lists the problems', async () => {
    await openEditor()

    await waitFor(() => expect(screen.getByTestId('problems-summary')).toHaveTextContent('3 errors, 1 warning'))
    const [id, definition] = api.validateWorkflow.mock.calls[0] as [string, WorkflowDefinition]
    expect(id).toBe('wf-1')
    expect(definition.nodes.map((n) => n.id)).toEqual(['t', 'c', 'l'])
    expect(definition.edges).toEqual(WORKFLOW.definition.edges)
    const panel = screen.getByRole('region', { name: 'Problems' })
    expect(panel).toHaveTextContent('Loop detected')
    expect(panel).toHaveTextContent("'Log': message: must not be empty")
  })

  it('highlights affected nodes and connections on the canvas', async () => {
    await openEditor()
    await waitFor(() => expect(screen.getByTestId('problems-summary')).toHaveTextContent('3 errors'))

    const nodes = await screen.findAllByTestId('workflow-node')
    const byName = (name: string) => nodes.find((n) => n.textContent?.includes(name))!
    expect(byName('Check')).toHaveAttribute('data-validation', 'error')
    expect(byName('Log')).toHaveAttribute('data-validation', 'error')
    expect(byName('Start')).not.toHaveAttribute('data-validation')
    expect(within(byName('Check')).getByLabelText('2 errors, 1 warning')).toBeInTheDocument()

    // React Flow does not lay out edges in jsdom; check the decoration it is given (see edgeDecoration.test.ts).
    expect(decorateEdges(store().edges, store().validation!.byEdge).map((e) => e.className)).toEqual([
      undefined, undefined, 'ff-edge-error',
    ])
  })

  it('clicking a problem selects the node and shows its problems in the properties panel', async () => {
    await openEditor()
    await waitFor(() => expect(screen.getByTestId('problems-summary')).toHaveTextContent('3 errors'))

    await userEvent.click(screen.getByText("'Log': message: must not be empty"))

    expect(store().nodes.find((n) => n.id === 'l')!.selected).toBe(true)
    const list = screen.getByRole('list', { name: 'Node problems' })
    expect(list).toHaveTextContent('message: must not be empty')
    expect(screen.getByRole('form', { name: 'Node settings' })).toBeInTheDocument()
  })

  it('re-validates the unsaved canvas after edits and clears the highlights once fixed', async () => {
    const { container } = await openEditor()
    await waitFor(() => expect(api.validateWorkflow).toHaveBeenCalledTimes(1))
    api.validateWorkflow.mockResolvedValue(VALID)

    act(() => {
      store().edges.find((e) => e.id === 'e3')!
      store().onEdgesChange([{ type: 'select', id: 'e3', selected: true }])
      store().deleteSelected()
    })

    await waitFor(() => expect(api.validateWorkflow).toHaveBeenCalledTimes(2))
    const sent = api.validateWorkflow.mock.calls[1][1] as WorkflowDefinition
    expect(sent.edges.map((e) => e.id)).toEqual(['e1', 'e2'])
    expect(api.updateWorkflow).not.toHaveBeenCalled() // validation never saves
    await waitFor(() => expect(screen.getByTestId('problems-summary')).toHaveTextContent('No problems'))
    expect(decorateEdges(store().edges, store().validation!.byEdge).some((e) => e.className)).toBe(false)
    expect(container.querySelector('[data-validation]')).toBeNull()
  })

  it('ignores a slow response that was superseded by a newer validation', async () => {
    let resolveSlow!: (r: ValidationResult) => void
    api.validateWorkflow
      .mockImplementationOnce(() => new Promise<ValidationResult>((resolve) => (resolveSlow = resolve)))
      .mockResolvedValueOnce(VALID)
    await openEditor()
    await waitFor(() => expect(api.validateWorkflow).toHaveBeenCalledTimes(1))

    await userEvent.click(screen.getByRole('button', { name: 'Validate' }))
    await waitFor(() => expect(screen.getByTestId('problems-summary')).toHaveTextContent('No problems'))

    await act(async () => resolveSlow(INVALID))
    expect(screen.getByTestId('problems-summary')).toHaveTextContent('No problems')
  })

  it('the Validate button checks immediately', async () => {
    await openEditor()
    await waitFor(() => expect(api.validateWorkflow).toHaveBeenCalledTimes(1))

    await userEvent.click(screen.getByRole('button', { name: 'Validate' }))

    expect(api.validateWorkflow).toHaveBeenCalledTimes(2)
  })

  it('does not validate unsaved new workflows, and starts once the first save created it', async () => {
    api.createWorkflow.mockImplementation(async (r: { name: string; definition: WorkflowDefinition }) => ({
      ...WORKFLOW, id: 'created', name: r.name, lockVersion: 0, definition: r.definition,
    }))
    api.validateWorkflow.mockResolvedValue(VALID)
    await openEditor('/workflows/new')

    expect(screen.getByRole('button', { name: 'Validate' })).toBeDisabled()
    expect(screen.getByTestId('problems-summary')).toHaveTextContent('Save the workflow to validate it')
    await new Promise((r) => setTimeout(r, 700))
    expect(api.validateWorkflow).not.toHaveBeenCalled()

    await userEvent.type(screen.getByRole('textbox', { name: 'Workflow name' }), 'Fresh')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(api.validateWorkflow).toHaveBeenCalledWith('created', expect.anything()))
    await waitFor(() => expect(screen.getByTestId('problems-summary')).toHaveTextContent('No problems'))
  })

  it('reports when validation could not run', async () => {
    api.validateWorkflow.mockRejectedValue(new Error('down'))
    await openEditor()
    await waitFor(() => expect(screen.getByTestId('problems-summary')).toHaveTextContent('Validation failed to run'))
  })
})
