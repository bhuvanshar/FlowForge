import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import * as healthApi from '@/lib/api/health'
import { FakeEventSource } from '@/test/fakeEventSource'
import { renderApp } from '@/test/renderWithProviders'

import { succeededExecution } from './fixtures'

const api = vi.hoisted(() => ({ listWorkflows: vi.fn(), getExecution: vi.fn(), cancelExecution: vi.fn() }))
vi.mock('@/lib/api/workflows', () => api)

const running = () => succeededExecution({
  status: 'RUNNING', finishedAt: undefined, durationMs: undefined,
  nodes: [
    { nodeId: 't', nodeName: 'Start', nodeType: 'core.manualTrigger', sequence: 0, status: 'PENDING' },
    { nodeId: 'log', nodeName: 'Log result', nodeType: 'core.logger', sequence: 1, status: 'PENDING' },
  ],
  logs: [],
})
const ev = (extra: object) => ({ seq: 1, executionId: 'exec-2', at: 't', ...extra })

describe('execution detail page, live', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    FakeEventSource.reset()
    vi.stubGlobal('EventSource', FakeEventSource)
    vi.spyOn(healthApi, 'getHealth').mockResolvedValue({ status: 'UP', version: '1', timestamp: 't', components: {} })
    api.getExecution.mockImplementation(async () => running())
  })
  afterEach(() => vi.unstubAllGlobals())

  it('updates without a refresh: running node, completion, final persisted results, and the Cancel button disappears', async () => {
    renderApp('/executions/exec-2')
    expect(await screen.findByRole('button', { name: 'Cancel' })).toBeInTheDocument()
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    const source = FakeEventSource.last
    act(() => source.open())
    expect(screen.getByTestId('connection-badge')).toHaveTextContent('Live')

    act(() => source.emit('node.started', ev({ type: 'node.started', nodeId: 't', status: 'RUNNING' })))
    await waitFor(() => expect(screen.getByRole('button', { name: /Start/ })).toHaveTextContent('Running'))
    expect(screen.getByLabelText('Node details')).toHaveTextContent('Start') // follows the running node

    api.getExecution.mockImplementation(async () => succeededExecution())
    act(() => source.emit('execution.completed', ev({ type: 'execution.completed', status: 'SUCCEEDED', durationMs: 80 })))

    await waitFor(() => expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument())
    expect(screen.getAllByText('Succeeded').length).toBeGreaterThan(0)
    await waitFor(() => expect(screen.getByLabelText('Output').textContent).toBe(JSON.stringify({ a: [1, 2] }, null, 2)))
    expect(screen.queryByTestId('connection-badge')).not.toBeInTheDocument()
  })

  it('does not replace a completed result with an older cancellation acknowledgement', async () => {
    let finish!: (value: ReturnType<typeof running>) => void
    api.cancelExecution.mockReturnValue(new Promise<ReturnType<typeof running>>((resolve) => { finish = resolve }))
    renderApp('/executions/exec-2')
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    api.getExecution.mockResolvedValue(succeededExecution())
    act(() => FakeEventSource.last.emit('execution.completed', ev({ type: 'execution.completed', status: 'SUCCEEDED' })))
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument())
    await waitFor(() => expect(screen.queryByTestId('connection-badge')).not.toBeInTheDocument())
    await act(async () => finish(running()))
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument()
    expect(screen.queryByTestId('connection-badge')).not.toBeInTheDocument()
  })

  it('shows reconnecting while the connection is down', async () => {
    renderApp('/executions/exec-2')
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    act(() => FakeEventSource.last.open())

    act(() => FakeEventSource.last.failTemporarily())

    expect(await screen.findByTestId('connection-badge')).toHaveTextContent('Reconnecting…')
  })
})
