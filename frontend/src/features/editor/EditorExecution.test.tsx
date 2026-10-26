import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, type AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as healthApi from '@/lib/api/health'
import type { WorkflowResponse } from '@/lib/api/workflows'
import { renderApp } from '@/test/renderWithProviders'

import { failedExecution, succeededExecution, summaryOf } from '../executions/fixtures'
import { useEditorStore } from './editorStore'

const api = vi.hoisted(() => ({
  getWorkflow: vi.fn(), createWorkflow: vi.fn(), updateWorkflow: vi.fn(), listWorkflows: vi.fn(), validateWorkflow: vi.fn(),
  executeWorkflow: vi.fn(), listExecutions: vi.fn(), getExecution: vi.fn(),
}))
vi.mock('@/lib/api/workflows', () => api)

const store = () => useEditorStore.getState()

const WORKFLOW: WorkflowResponse = {
  id: 'wf-1', name: 'Orders', status: 'DRAFT', version: 3, lockVersion: 0, createdAt: 't', updatedAt: 't',
  definition: {
    schemaVersion: 1,
    nodes: [
      { id: 't', type: 'core.manualTrigger', typeVersion: 1, name: 'Start', position: { x: 0, y: 0 }, configuration: {} },
      { id: 'call', type: 'core.httpRequest', typeVersion: 1, name: 'Call API', position: { x: 250, y: 0 }, configuration: { method: 'GET', url: 'http://api/x', headers: [], query: [], timeoutSeconds: 30 } },
      { id: 'log', type: 'core.logger', typeVersion: 1, name: 'Log result', position: { x: 500, y: 0 }, configuration: { message: 'x' } },
    ],
    edges: [
      { id: 'e1', source: 't', sourceHandle: 'main', target: 'call', targetHandle: 'main' },
      { id: 'e2', source: 'call', sourceHandle: 'main', target: 'log', targetHandle: 'main' },
    ],
    editor: { viewport: { x: 0, y: 0, zoom: 1 } },
  },
}

async function openEditor() {
  const utils = renderApp('/workflows/wf-1')
  await screen.findByRole('toolbar', { name: 'Editor toolbar' })
  return utils
}

const panel = () => screen.getByRole('region', { name: 'Execution' })

describe('running workflows from the editor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    store().reset()
    api.getWorkflow.mockResolvedValue(structuredClone(WORKFLOW))
    api.listWorkflows.mockResolvedValue({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })
    api.validateWorkflow.mockResolvedValue({ valid: true, errorCount: 0, warningCount: 0, issues: [], executionOrder: [] })
    api.listExecutions.mockResolvedValue({ items: [], page: 0, size: 1, totalElements: 0, totalPages: 0 })
    vi.spyOn(healthApi, 'getHealth').mockResolvedValue({ status: 'UP', version: '1', timestamp: 't', components: {} })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  it('runs the saved workflow and shows the execution in the bottom inspector, with statuses on the canvas', async () => {
    api.executeWorkflow.mockResolvedValue({ executionId: 'exec-1' })
    api.getExecution.mockResolvedValue(failedExecution())
    await openEditor()

    await userEvent.click(screen.getByRole('button', { name: 'Run' }))

    expect(api.executeWorkflow).toHaveBeenCalledWith('wf-1')
    await waitFor(() => expect(within(panel()).getByRole('list', { name: 'Execution order' })).toBeInTheDocument())
    expect(within(panel()).getAllByText('Failed').length).toBeGreaterThanOrEqual(2) // header badge and node row
    expect(panel()).toHaveTextContent('1.25 s')
    expect(panel()).toHaveTextContent('HTTP_STATUS_ERROR')
    expect(within(panel()).getByRole('link', { name: 'Open details' })).toHaveAttribute('href', '/executions/exec-1')
    // Failed node is open: input visible.
    expect(within(panel()).getByLabelText('Input').textContent).toBe(JSON.stringify({ order: 7 }, null, 2))
    expect(store().runStatuses).toEqual({ t: 'SUCCEEDED', call: 'FAILED', log: 'SKIPPED' })
    const nodes = await screen.findAllByTestId('workflow-node')
    expect(nodes.find((n) => n.textContent?.includes('Call API'))!.querySelector('[data-status="FAILED"]')).not.toBeNull()
  })

  it('selecting a node in the inspector selects it on the canvas', async () => {
    api.executeWorkflow.mockResolvedValue({ executionId: 'exec-2' })
    api.getExecution.mockResolvedValue(succeededExecution({ id: 'exec-2', nodes: succeededExecution().nodes.map((n) => ({ ...n })) }))
    await openEditor()
    await userEvent.click(screen.getByRole('button', { name: 'Run' }))
    await within(panel()).findByRole('list', { name: 'Execution order' })

    await userEvent.click(within(panel()).getByRole('button', { name: /Log result/ }))

    expect(store().nodes.find((n) => n.id === 'log')!.selected).toBe(true)
    expect(within(panel()).getByLabelText('Output').textContent).toBe(JSON.stringify({ a: [1, 2] }, null, 2))
  })

  it('does not show a delayed run response in a different workflow editor', async () => {
    let resolve!: (value: { executionId: string }) => void
    api.executeWorkflow.mockReturnValue(new Promise((done) => { resolve = done }))
    const { router } = await openEditor()
    await userEvent.click(screen.getByRole('button', { name: 'Run' }))
    await waitFor(() => expect(api.executeWorkflow).toHaveBeenCalledOnce())
    api.getWorkflow.mockResolvedValue({ ...WORKFLOW, id: 'wf-2', name: 'Other' })
    await act(async () => { await router.navigate('/workflows/wf-2') })
    await waitFor(() => expect(store().workflowId).toBe('wf-2'))

    await act(async () => resolve({ executionId: 'old-run' }))

    expect(store().activeExecutionId).toBeNull()
    expect(store().inspectorRequest).toBe(0)
  })

  it('polls while the run is in progress and updates the shown results', async () => {
    api.executeWorkflow.mockResolvedValue({ executionId: 'exec-2' })
    api.getExecution
      .mockResolvedValueOnce(succeededExecution({ id: 'exec-2', status: 'RUNNING', durationMs: undefined, nodes: [{ ...succeededExecution().nodes[0], status: 'RUNNING' }] }))
      .mockResolvedValue(succeededExecution({ id: 'exec-2' }))
    await openEditor()

    await userEvent.click(screen.getByRole('button', { name: 'Run' }))

    await waitFor(() => expect(within(panel()).getByText('Running…')).toBeInTheDocument())
    await waitFor(() => expect(within(panel()).queryByText('Running…')).not.toBeInTheDocument(), { timeout: 4000 })
    expect(store().runStatuses).toEqual({ t: 'SUCCEEDED', log: 'SUCCEEDED' })
  })

  it('opens a saved workflow showing its latest execution (collapsed) so it can be inspected after a refresh', async () => {
    api.listExecutions.mockResolvedValue({ items: [summaryOf(failedExecution())], page: 0, size: 1, totalElements: 1, totalPages: 1 })
    api.getExecution.mockResolvedValue(failedExecution())
    await openEditor()

    await waitFor(() => expect(store().activeExecutionId).toBe('exec-1'))
    expect(api.listExecutions).toHaveBeenCalledWith('wf-1', { size: 1 })
    await waitFor(() => expect(panel()).toHaveTextContent('Failed'))
    // The panel header shows the status; opening it shows the exact saved node results.
    await userEvent.click(within(panel()).getAllByRole('button')[0])
    expect(await within(panel()).findByRole('list', { name: 'Execution order' })).toBeInTheDocument()
  })

  it('Run is disabled for new, unsaved and archived workflows', async () => {
    const { unmount } = renderApp('/workflows/new')
    await screen.findByRole('toolbar', { name: 'Editor toolbar' })
    expect(screen.getByRole('button', { name: 'Run' })).toBeDisabled()
    expect(screen.queryByRole('link', { name: /History/ })).not.toBeInTheDocument()
    unmount()
    store().reset()

    await openEditor()
    expect(screen.getByRole('button', { name: 'Run' })).toBeEnabled()
    act(() => store().setName('Edited'))
    expect(screen.getByRole('button', { name: 'Run' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Run' })).toHaveAttribute('title', expect.stringContaining('Save your changes first'))
  })

  it('links to the execution history', async () => {
    await openEditor()
    expect(screen.getByRole('link', { name: /History/ })).toHaveAttribute('href', '/workflows/wf-1/executions')
  })

  it.each([
    [422, undefined, 'validation errors'],
    [501, 'These node types cannot be executed yet: x.', 'cannot be executed yet'],
    [503, undefined, 'Too many executions'],
  ])('explains a failed start (HTTP %i)', async (status, detail, expected) => {
    api.executeWorkflow.mockRejectedValue(new AxiosError('x', String(status), undefined, undefined,
      { status, data: { status, detail } } as AxiosResponse))
    await openEditor()

    await userEvent.click(screen.getByRole('button', { name: 'Run' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(expected)
    expect(store().activeExecutionId).toBeNull()
  })
})
