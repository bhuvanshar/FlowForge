import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import * as healthApi from '@/lib/api/health'
import type { ExecutionDetail, WorkflowResponse } from '@/lib/api/workflows'
import { FakeEventSource } from '@/test/fakeEventSource'
import { renderApp } from '@/test/renderWithProviders'

import { useEditorStore } from './editorStore'

const api = vi.hoisted(() => ({
  getWorkflow: vi.fn(), createWorkflow: vi.fn(), updateWorkflow: vi.fn(), listWorkflows: vi.fn(), validateWorkflow: vi.fn(),
  executeWorkflow: vi.fn(), listExecutions: vi.fn(), getExecution: vi.fn(),
}))
vi.mock('@/lib/api/workflows', () => api)

const store = () => useEditorStore.getState()

const WORKFLOW: WorkflowResponse = {
  id: 'wf-1', name: 'Orders', status: 'DRAFT', version: 1, lockVersion: 0, createdAt: 't', updatedAt: 't',
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

const queued = (): ExecutionDetail => ({
  id: 'exec-9', workflowId: 'wf-1', workflowVersion: 1, status: 'QUEUED', createdAt: 't',
  nodes: [
    { nodeId: 't', nodeName: 'Start', nodeType: 'core.manualTrigger', sequence: 0, status: 'PENDING' },
    { nodeId: 'call', nodeName: 'Call API', nodeType: 'core.httpRequest', sequence: 1, status: 'PENDING' },
    { nodeId: 'log', nodeName: 'Log result', nodeType: 'core.logger', sequence: 2, status: 'PENDING' },
  ],
  logs: [],
})

const ev = (extra: object) => ({ seq: 1, executionId: 'exec-9', at: 't', ...extra })
const panel = () => screen.getByRole('region', { name: 'Execution' })
const canvasNode = async (name: string) => (await screen.findAllByTestId('workflow-node')).find((n) => n.textContent?.includes(name))!

async function runWorkflow() {
  renderApp('/workflows/wf-1')
  await screen.findByRole('toolbar', { name: 'Editor toolbar' })
  await userEvent.click(screen.getByRole('button', { name: 'Run' }))
  await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
  return FakeEventSource.last
}

describe('live execution in the editor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    store().reset()
    FakeEventSource.reset()
    vi.stubGlobal('EventSource', FakeEventSource)
    api.getWorkflow.mockResolvedValue(structuredClone(WORKFLOW))
    api.listWorkflows.mockResolvedValue({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })
    api.validateWorkflow.mockResolvedValue({ valid: true, errorCount: 0, warningCount: 0, issues: [], executionOrder: [] })
    api.listExecutions.mockResolvedValue({ items: [], page: 0, size: 1, totalElements: 0, totalPages: 0 })
    api.executeWorkflow.mockResolvedValue({ executionId: 'exec-9' })
    api.getExecution.mockImplementation(async () => queued())
    vi.spyOn(healthApi, 'getHealth').mockResolvedValue({ status: 'UP', version: '1', timestamp: 't', components: {} })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })
  afterEach(() => vi.unstubAllGlobals())

  it('highlights the running node on the canvas and moves on as nodes finish, without refreshing', async () => {
    const source = await runWorkflow()
    act(() => source.open())
    expect(within(panel()).getByTestId('connection-badge')).toHaveTextContent('Live')

    act(() => source.emit('execution.started', ev({ type: 'execution.started', status: 'RUNNING' })))
    act(() => source.emit('node.started', ev({ type: 'node.started', nodeId: 't', status: 'RUNNING' })))
    await waitFor(() => expect(store().runStatuses).toEqual({ t: 'RUNNING', call: 'PENDING', log: 'PENDING' }))
    expect((await canvasNode('Start')).getAttribute('data-running')).toBe('true')

    act(() => source.emit('node.succeeded', ev({ type: 'node.succeeded', nodeId: 't', status: 'SUCCEEDED', durationMs: 5 })))
    act(() => source.emit('node.started', ev({ type: 'node.started', nodeId: 'call', status: 'RUNNING' })))
    await waitFor(() => expect(store().runStatuses).toEqual({ t: 'SUCCEEDED', call: 'RUNNING', log: 'PENDING' }))
    expect((await canvasNode('Start')).hasAttribute('data-running')).toBe(false)
    expect((await canvasNode('Call API')).getAttribute('data-running')).toBe('true')
    expect((await canvasNode('Start')).querySelector('[data-status="SUCCEEDED"]')).not.toBeNull()
  })

  it('updates the inspector live: header status, node rows, and it follows the running node', async () => {
    const source = await runWorkflow()
    act(() => source.emit('node.succeeded', ev({ type: 'node.succeeded', nodeId: 't', status: 'SUCCEEDED', durationMs: 5 })))
    act(() => source.emit('node.started', ev({ type: 'node.started', nodeId: 'call', status: 'RUNNING' })))
    act(() => source.emit('execution.started', ev({ type: 'execution.started', status: 'RUNNING' })))

    const list = await within(panel()).findByRole('list', { name: 'Execution order' })
    await waitFor(() => expect(list.querySelector('[data-node-id="call"]')).toHaveTextContent('Running'))
    expect(list.querySelector('[data-node-id="t"]')).toHaveTextContent('Succeeded')
    expect(list.querySelector('[data-node-id="log"]')).toHaveTextContent('Pending')
    expect(panel()).toHaveTextContent('Running')
    // The detail pane follows the node that is running now.
    expect(within(panel()).getByLabelText('Node details')).toHaveTextContent('Call API')

    act(() => source.emit('node.succeeded', ev({ type: 'node.succeeded', nodeId: 'call', status: 'SUCCEEDED' })))
    act(() => source.emit('node.started', ev({ type: 'node.started', nodeId: 'log', status: 'RUNNING' })))
    await waitFor(() => expect(within(panel()).getByLabelText('Node details')).toHaveTextContent('Log result'))
  })

  it('a node the user picked stays selected while others progress', async () => {
    const source = await runWorkflow()
    act(() => source.emit('node.started', ev({ type: 'node.started', nodeId: 't', status: 'RUNNING' })))
    await within(panel()).findByRole('list', { name: 'Execution order' })
    await userEvent.click(within(panel()).getByRole('button', { name: /Log result/ }))

    act(() => source.emit('node.started', ev({ type: 'node.started', nodeId: 'call', status: 'RUNNING' })))

    expect(within(panel()).getByLabelText('Node details')).toHaveTextContent('Log result')
  })

  it('shows failure and skipped states, closes the stream and loads the persisted results at the end', async () => {
    const source = await runWorkflow()
    const error = { code: 'HTTP_STATUS_ERROR', message: 'GET http://api/x answered 503.' }
    const final: ExecutionDetail = {
      ...queued(), status: 'FAILED', durationMs: 1250, error: { code: error.code, message: `Node 'Call API' failed: ${error.message}` },
      nodes: [
        { ...queued().nodes[0], status: 'SUCCEEDED', input: { order: 7 }, output: { order: 7 }, durationMs: 5 },
        { ...queued().nodes[1], status: 'FAILED', input: { order: 7 }, error, durationMs: 1240 },
        { ...queued().nodes[2], status: 'SKIPPED' },
      ],
    }
    api.getExecution.mockImplementation(async () => final)

    act(() => source.emit('node.started', ev({ type: 'node.started', nodeId: 'call', status: 'RUNNING' })))
    act(() => source.emit('node.failed', ev({ type: 'node.failed', nodeId: 'call', status: 'FAILED', error })))
    act(() => source.emit('node.skipped', ev({ type: 'node.skipped', nodeId: 'log', status: 'SKIPPED' })))
    act(() => source.emit('execution.failed', ev({ type: 'execution.failed', status: 'FAILED', error })))

    await waitFor(() => expect(store().runStatuses).toEqual({ t: 'SUCCEEDED', call: 'FAILED', log: 'SKIPPED' }))
    expect(source.closeCalls).toBeGreaterThan(0)
    expect((await canvasNode('Call API')).querySelector('[data-status="FAILED"]')).not.toBeNull()
    await waitFor(() => expect(within(panel()).getByLabelText('Input').textContent).toBe(JSON.stringify({ order: 7 }, null, 2)))
    expect(within(panel()).getByLabelText('Node details')).toHaveTextContent('HTTP_STATUS_ERROR')
    expect(panel()).toHaveTextContent('1.25 s')
    expect(within(panel()).queryByTestId('connection-badge')).not.toBeInTheDocument()
  })

  it('shows reconnecting on connection loss and recovers the missed progress from the snapshot', async () => {
    const source = await runWorkflow()
    act(() => source.open())
    act(() => source.emit('node.started', ev({ type: 'node.started', nodeId: 't', status: 'RUNNING' })))

    act(() => source.failTemporarily())
    await waitFor(() => expect(within(panel()).getByTestId('connection-badge')).toHaveTextContent('Reconnecting…'))
    expect(store().runStatuses).toMatchObject({ t: 'RUNNING' }) // last known state stays on the canvas

    act(() => source.emit('snapshot', { executionId: 'exec-9', status: 'RUNNING', nodes: [
      { nodeId: 't', nodeName: 'Start', sequence: 0, status: 'SUCCEEDED' },
      { nodeId: 'call', nodeName: 'Call API', sequence: 1, status: 'SUCCEEDED' },
      { nodeId: 'log', nodeName: 'Log result', sequence: 2, status: 'RUNNING' }] }))

    await waitFor(() => expect(store().runStatuses).toEqual({ t: 'SUCCEEDED', call: 'SUCCEEDED', log: 'RUNNING' }))
    expect(within(panel()).getByTestId('connection-badge')).toHaveTextContent('Live')
  })

  it('falls back to polling and says so when streaming is not possible', async () => {
    const source = await runWorkflow()

    act(() => source.failPermanently())

    await waitFor(() => expect(within(panel()).getByTestId('connection-badge')).toHaveTextContent('Polling'))
  })

  it('after a page refresh the latest persisted run of a saved workflow is shown, live if it is still running', async () => {
    api.listExecutions.mockResolvedValue({
      items: [{ id: 'exec-9', workflowId: 'wf-1', workflowVersion: 1, status: 'RUNNING', createdAt: 't', nodeCount: 3,
        succeededNodes: 1, failedNodes: 0, skippedNodes: 0 }],
      page: 0, size: 1, totalElements: 1, totalPages: 1,
    })
    api.getExecution.mockImplementation(async () => ({
      ...queued(), status: 'RUNNING',
      nodes: [{ ...queued().nodes[0], status: 'SUCCEEDED' }, { ...queued().nodes[1], status: 'RUNNING' }, queued().nodes[2]],
    }))
    renderApp('/workflows/wf-1')
    await screen.findByRole('toolbar', { name: 'Editor toolbar' })

    await waitFor(() => expect(store().runStatuses).toEqual({ t: 'SUCCEEDED', call: 'RUNNING', log: 'PENDING' }))
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    expect((await canvasNode('Call API')).getAttribute('data-running')).toBe('true')

    act(() => FakeEventSource.last.emit('node.succeeded', ev({ type: 'node.succeeded', nodeId: 'call', status: 'SUCCEEDED' })))
    await waitFor(() => expect(store().runStatuses).toMatchObject({ call: 'SUCCEEDED' }))
  })
})
