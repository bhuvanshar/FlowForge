import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { FakeEventSource } from '@/test/fakeEventSource'

import { failedExecution, succeededExecution } from './fixtures'
import { useLiveExecution } from './useLiveExecution'

const api = vi.hoisted(() => ({ getExecution: vi.fn() }))
vi.mock('@/lib/api/workflows', () => api)

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

const pending = (overrides = {}) => succeededExecution({
  id: 'exec-2', status: 'QUEUED', finishedAt: undefined, durationMs: undefined,
  nodes: [
    { nodeId: 't', nodeName: 'Start', nodeType: 'core.manualTrigger', sequence: 0, status: 'PENDING' },
    { nodeId: 'log', nodeName: 'Log result', nodeType: 'core.logger', sequence: 1, status: 'PENDING' },
  ],
  logs: [],
  ...overrides,
})

const ev = (extra: object) => ({ seq: 1, executionId: 'exec-2', at: 't', ...extra })
const statuses = (hook: { current: ReturnType<typeof useLiveExecution> }) =>
  hook.current.execution?.nodes.map((n) => `${n.nodeId}=${n.status}`)

describe('useLiveExecution', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    FakeEventSource.reset()
    vi.stubGlobal('EventSource', FakeEventSource)
  })
  afterEach(() => vi.unstubAllGlobals())

  it('loads the persisted execution and opens the event stream only while it is unfinished', async () => {
    api.getExecution.mockResolvedValue(pending())
    const { result } = renderHook(() => useLiveExecution('exec-2'), { wrapper })

    await waitFor(() => expect(result.current.execution?.status).toBe('QUEUED'))
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    expect(FakeEventSource.last.url).toBe('/api/v1/executions/exec-2/events')
    expect(result.current.connection).toBe('connecting')

    act(() => FakeEventSource.last.open())
    expect(result.current.connection).toBe('open')
  })

  it('does not stream finished executions', async () => {
    api.getExecution.mockResolvedValue(failedExecution())
    const { result } = renderHook(() => useLiveExecution('exec-1'), { wrapper })

    await waitFor(() => expect(result.current.execution?.status).toBe('FAILED'))
    expect(FakeEventSource.instances).toHaveLength(0)
    expect(result.current.connection).toBe('idle')
  })

  it('shows transitions as soon as events arrive, then replaces them with persisted results', async () => {
    api.getExecution.mockResolvedValue(pending())
    const { result } = renderHook(() => useLiveExecution('exec-2'), { wrapper })
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    const source = FakeEventSource.last

    act(() => source.emit('snapshot', { executionId: 'exec-2', status: 'QUEUED', nodes: [
      { nodeId: 't', nodeName: 'Start', sequence: 0, status: 'PENDING' }, { nodeId: 'log', nodeName: 'Log result', sequence: 1, status: 'PENDING' }] }))
    act(() => source.emit('execution.started', ev({ type: 'execution.started', status: 'RUNNING' })))
    act(() => source.emit('node.started', ev({ type: 'node.started', nodeId: 't', status: 'RUNNING' })))

    expect(result.current.execution?.status).toBe('RUNNING')
    expect(statuses(result)).toEqual(['t=RUNNING', 'log=PENDING'])

    // Persisted data catches up (what the refetch after the event returns), including payloads.
    api.getExecution.mockResolvedValue(succeededExecution({ id: 'exec-2', status: 'RUNNING' }))
    act(() => source.emit('node.succeeded', ev({ type: 'node.succeeded', nodeId: 't', status: 'SUCCEEDED', durationMs: 4 })))
    act(() => source.emit('node.started', ev({ type: 'node.started', nodeId: 'log', status: 'RUNNING' })))
    await waitFor(() => expect(result.current.execution?.nodes[0].output).toEqual({ a: [1, 2] }))
    expect(statuses(result)).toEqual(['t=SUCCEEDED', 'log=SUCCEEDED']) // persisted is further along: it wins
  })

  it('a burst of events causes one refetch, not one per event', async () => {
    api.getExecution.mockResolvedValue(pending())
    renderHook(() => useLiveExecution('exec-2'), { wrapper })
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    const initialCalls = api.getExecution.mock.calls.length

    act(() => {
      for (let i = 0; i < 20; i++) FakeEventSource.last.emit('node.started', ev({ type: 'node.started', nodeId: 't', status: 'RUNNING' }))
    })

    await waitFor(() => expect(api.getExecution.mock.calls.length).toBeGreaterThan(initialCalls))
    await new Promise((r) => setTimeout(r, 400))
    expect(api.getExecution.mock.calls.length - initialCalls).toBe(1)
  })

  it('a terminal event closes the stream, ends the connection and loads the final persisted result', async () => {
    api.getExecution.mockResolvedValue(pending())
    const { result } = renderHook(() => useLiveExecution('exec-2'), { wrapper })
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    const source = FakeEventSource.last
    api.getExecution.mockResolvedValue(succeededExecution({ id: 'exec-2' }))

    act(() => source.emit('execution.completed', ev({ type: 'execution.completed', status: 'SUCCEEDED', durationMs: 80 })))

    expect(source.closeCalls).toBeGreaterThan(0)
    expect(result.current.connection).toBe('ended')
    expect(result.current.execution?.status).toBe('SUCCEEDED')
    await waitFor(() => expect(result.current.execution?.nodes[1].output).toEqual({ a: [1, 2] }))
    expect(FakeEventSource.instances).toHaveLength(1) // no second connection for a finished run
  })

  it('shows failures and the affected node live', async () => {
    api.getExecution.mockResolvedValue(pending())
    const { result } = renderHook(() => useLiveExecution('exec-2'), { wrapper })
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    const error = { code: 'HTTP_STATUS_ERROR', message: 'answered 503' }

    act(() => FakeEventSource.last.emit('node.failed', ev({ type: 'node.failed', nodeId: 't', status: 'FAILED', error })))
    act(() => FakeEventSource.last.emit('node.skipped', ev({ type: 'node.skipped', nodeId: 'log', status: 'SKIPPED' })))
    act(() => FakeEventSource.last.emit('execution.failed', ev({ type: 'execution.failed', status: 'FAILED', error })))

    expect(statuses(result)).toEqual(['t=FAILED', 'log=SKIPPED'])
    expect(result.current.execution?.status).toBe('FAILED')
    expect(result.current.execution?.error).toEqual(error)
    expect(result.current.execution?.nodes[0].error).toEqual(error)
  })

  it('connection loss: shows reconnecting, then a snapshot restores everything missed meanwhile', async () => {
    api.getExecution.mockResolvedValue(pending())
    const { result } = renderHook(() => useLiveExecution('exec-2'), { wrapper })
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    const source = FakeEventSource.last
    act(() => source.open())
    act(() => source.emit('node.started', ev({ type: 'node.started', nodeId: 't', status: 'RUNNING' })))

    act(() => source.failTemporarily())
    expect(result.current.connection).toBe('reconnecting')
    expect(statuses(result)).toEqual(['t=RUNNING', 'log=PENDING']) // last known state stays visible

    // While disconnected the run advanced; the browser reconnects and the server sends the persisted state first.
    api.getExecution.mockResolvedValue(succeededExecution({ id: 'exec-2', status: 'RUNNING' }))
    act(() => source.emit('snapshot', { executionId: 'exec-2', status: 'RUNNING', nodes: [
      { nodeId: 't', nodeName: 'Start', sequence: 0, status: 'SUCCEEDED' }, { nodeId: 'log', nodeName: 'Log result', sequence: 1, status: 'RUNNING' }] }))

    expect(result.current.connection).toBe('open')
    expect(statuses(result)).toEqual(['t=SUCCEEDED', 'log=RUNNING'])
    expect(FakeEventSource.instances).toHaveLength(1) // the browser reconnects itself; the hook does not open another
    await waitFor(() => expect(result.current.execution?.nodes[0].output).toEqual({ a: [1, 2] }))
  })

  it('a snapshot of an already finished run completes the view even if every event was missed', async () => {
    api.getExecution.mockResolvedValue(pending())
    const { result } = renderHook(() => useLiveExecution('exec-2'), { wrapper })
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    api.getExecution.mockResolvedValue(succeededExecution({ id: 'exec-2' }))

    act(() => FakeEventSource.last.emit('snapshot', { executionId: 'exec-2', status: 'SUCCEEDED', durationMs: 80, nodes: [
      { nodeId: 't', nodeName: 'Start', sequence: 0, status: 'SUCCEEDED' }, { nodeId: 'log', nodeName: 'Log result', sequence: 1, status: 'SUCCEEDED' }] }))

    expect(result.current.execution?.status).toBe('SUCCEEDED')
    await waitFor(() => expect(result.current.execution?.nodes[1].output).toEqual({ a: [1, 2] }))
  })

  it('falls back to polling the persisted state when the server closes the stream', async () => {
    api.getExecution.mockResolvedValueOnce(pending()).mockResolvedValue(succeededExecution({ id: 'exec-2' }))
    const { result } = renderHook(() => useLiveExecution('exec-2'), { wrapper })
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))

    act(() => FakeEventSource.last.failPermanently())

    expect(result.current.connection).toBe('polling')
    await waitFor(() => expect(result.current.execution?.status).toBe('SUCCEEDED'), { timeout: 4000 })
  })

  it('polls when the browser has no EventSource', async () => {
    vi.unstubAllGlobals()
    vi.stubGlobal('EventSource', undefined)
    api.getExecution.mockResolvedValueOnce(pending()).mockResolvedValue(succeededExecution({ id: 'exec-2' }))
    const { result } = renderHook(() => useLiveExecution('exec-2'), { wrapper })

    await waitFor(() => expect(result.current.connection).toBe('polling'))
    await waitFor(() => expect(result.current.execution?.status).toBe('SUCCEEDED'), { timeout: 4000 })
  })

  it('does not poll while the stream is open', async () => {
    api.getExecution.mockResolvedValue(pending())
    renderHook(() => useLiveExecution('exec-2'), { wrapper })
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    act(() => FakeEventSource.last.open())
    const calls = api.getExecution.mock.calls.length

    await new Promise((r) => setTimeout(r, 1500))

    expect(api.getExecution.mock.calls.length).toBe(calls)
  })

  it('closes the stream on unmount and when another execution is shown, starting from a clean overlay', async () => {
    api.getExecution.mockImplementation(async (id: string) => pending({ id }))
    const { result, rerender, unmount } = renderHook(({ id }) => useLiveExecution(id), {
      wrapper, initialProps: { id: 'exec-2' },
    })
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    act(() => FakeEventSource.last.emit('node.started', ev({ type: 'node.started', nodeId: 't', status: 'RUNNING' })))
    expect(statuses(result)).toEqual(['t=RUNNING', 'log=PENDING'])
    const first = FakeEventSource.last

    rerender({ id: 'exec-3' })

    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(2))
    expect(first.closeCalls).toBeGreaterThan(0)
    await waitFor(() => expect(result.current.execution?.id).toBe('exec-3'))
    expect(statuses(result)).toEqual(['t=PENDING', 'log=PENDING'])
    expect(FakeEventSource.last.url).toContain('exec-3')

    const second = FakeEventSource.last
    unmount()
    expect(second.closeCalls).toBeGreaterThan(0)
  })

  it('does nothing without an execution id', () => {
    const { result } = renderHook(() => useLiveExecution(undefined), { wrapper })
    expect(result.current.execution).toBeUndefined()
    expect(result.current.connection).toBe('idle')
    expect(FakeEventSource.instances).toHaveLength(0)
  })
})
