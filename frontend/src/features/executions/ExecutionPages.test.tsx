import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, type AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as healthApi from '@/lib/api/health'
import { renderApp } from '@/test/renderWithProviders'

import { failedExecution, succeededExecution, summaryOf } from './fixtures'

const api = vi.hoisted(() => ({
  listWorkflows: vi.fn(), getWorkflow: vi.fn(), listExecutions: vi.fn(), getExecution: vi.fn(), cancelExecution: vi.fn(),
}))
vi.mock('@/lib/api/workflows', () => api)

const page = (items: ReturnType<typeof summaryOf>[], totalElements = items.length, number = 0, totalPages = 1) => ({
  items, page: number, size: 20, totalElements, totalPages,
})

describe('execution history page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.spyOn(healthApi, 'getHealth').mockResolvedValue({ status: 'UP', version: '1', timestamp: 't', components: {} })
    api.getWorkflow.mockResolvedValue({ id: 'wf-1', name: 'Orders' })
    api.listExecutions.mockResolvedValue(page([
      summaryOf(failedExecution()),
      summaryOf(succeededExecution(), { triggerType: 'SCHEDULE', scheduledFor: '2026-01-01T09:00:00Z' }),
    ]))
  })

  it('lists executions with status, trigger, duration, version, node counts and the failure with its node', async () => {
    renderApp('/workflows/wf-1/executions')

    const rows = await screen.findAllByRole('row')
    expect(rows).toHaveLength(3) // header + 2
    const failed = rows[1]
    expect(failed).toHaveTextContent('Failed')
    expect(failed).toHaveTextContent('1.25 s')
    expect(failed).toHaveTextContent('v3')
    expect(failed).toHaveTextContent('1/3 ok, 1 skipped')
    expect(failed).toHaveTextContent('Call API: Node')
    expect(rows[2]).toHaveTextContent('Succeeded')
    expect(rows[2]).toHaveTextContent('80 ms')
    // Trigger column: summaries without a trigger type predate Module 11 and were manual.
    expect(within(failed).getAllByRole('cell')[2]).toHaveTextContent('Manual')
    expect(within(rows[2]).getAllByRole('cell')[2]).toHaveTextContent('Schedule')
    expect(within(rows[2]).getAllByRole('cell')[2]).toHaveAttribute('title', expect.stringContaining('Scheduled for'))
    expect(screen.getByRole('link', { name: 'Orders' })).toHaveAttribute('href', '/workflows/wf-1')
    expect(api.listExecutions).toHaveBeenCalledWith('wf-1', { status: undefined, page: 0, size: 20 })
  })

  it('filters by status and resets to the first page', async () => {
    renderApp('/workflows/wf-1/executions')
    await screen.findAllByRole('row')

    await userEvent.click(screen.getByRole('button', { name: 'Failed' }))

    await waitFor(() => expect(api.listExecutions).toHaveBeenLastCalledWith('wf-1', { status: ['FAILED'], page: 0, size: 20 }))
    expect(screen.getByRole('button', { name: 'Failed' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('pages through results', async () => {
    api.listExecutions.mockResolvedValue(page([summaryOf(failedExecution())], 41, 0, 3))
    renderApp('/workflows/wf-1/executions')
    await screen.findByText(/Page 1 of 3 · 41 executions/)
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()

    await userEvent.click(screen.getByRole('button', { name: 'Next' }))

    await waitFor(() => expect(api.listExecutions).toHaveBeenLastCalledWith('wf-1', { status: undefined, page: 1, size: 20 }))
  })

  it('recovers when a page of running results disappears', async () => {
    api.listExecutions.mockImplementation(async (_id: string, params: { page: number }) =>
      params.page === 0 ? page([summaryOf(failedExecution())], 21, 0, 2) : page([], 1, 1, 1))
    renderApp('/workflows/wf-1/executions')
    await screen.findAllByRole('row')
    await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(api.listExecutions).toHaveBeenLastCalledWith('wf-1',
      { status: undefined, page: 0, size: 20 }))
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
  })

  it('starts on the first unfiltered page when switching workflows', async () => {
    api.listExecutions.mockResolvedValue(page([summaryOf(failedExecution())], 41, 0, 3))
    const { router } = renderApp('/workflows/wf-1/executions')
    await screen.findAllByRole('row')
    await userEvent.click(screen.getByRole('button', { name: 'Failed' }))
    await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    await act(async () => { await router.navigate('/workflows/wf-2/executions') })
    await waitFor(() => expect(api.listExecutions).toHaveBeenLastCalledWith('wf-2',
      { status: undefined, page: 0, size: 20 }))
  })

  it('shows empty and error states', async () => {
    api.listExecutions.mockResolvedValueOnce(page([]))
    const { unmount } = renderApp('/workflows/wf-1/executions')
    expect(await screen.findByText('This workflow has not been executed yet.')).toBeInTheDocument()
    unmount()

    api.listExecutions.mockRejectedValueOnce(new Error('down'))
    renderApp('/workflows/wf-1/executions')
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the execution history.')
  })

  it('opens an execution from the list', async () => {
    api.getExecution.mockResolvedValue(failedExecution())
    const { router } = renderApp('/workflows/wf-1/executions')
    const rows = await screen.findAllByRole('row')

    await userEvent.click(within(rows[1]).getByRole('link'))

    await waitFor(() => expect(router.state.location.pathname).toBe('/executions/exec-1'))
    expect(await screen.findByRole('heading', { name: 'Execution' })).toBeInTheDocument()
  })
})

describe('execution detail page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.spyOn(healthApi, 'getHealth').mockResolvedValue({ status: 'UP', version: '1', timestamp: 't', components: {} })
  })

  it('shows summary, failure with the affected node, execution order and node results', async () => {
    api.getExecution.mockResolvedValue(failedExecution({ triggerType: 'WEBHOOK' }))
    renderApp('/executions/exec-1')

    expect(await screen.findByRole('heading', { name: 'Execution' })).toBeInTheDocument()
    expect(screen.getByText('Trigger').nextElementSibling).toHaveTextContent('Webhook')
    expect(screen.getByText('v3')).toBeInTheDocument()
    expect(screen.getByText('1.25 s')).toBeInTheDocument()
    const failure = screen.getAllByRole('alert')[0]
    expect(failure).toHaveTextContent('Failed at node “Call API”')
    expect(failure).toHaveTextContent('HTTP_STATUS_ERROR')
    expect(screen.getByRole('list', { name: 'Execution order' }).querySelectorAll('li')).toHaveLength(3)
    expect(screen.getByLabelText('Node details')).toHaveTextContent('Call API')
    expect(screen.getByLabelText('Execution log')).toHaveTextContent('[INFO] (call) calling api')
    expect(screen.getByRole('link', { name: 'Execution history' })).toHaveAttribute('href', '/workflows/wf-1/executions')
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument()
  })

  it('inspects the exact saved result of any node', async () => {
    api.getExecution.mockResolvedValue(succeededExecution())
    renderApp('/executions/exec-2')
    await screen.findByRole('heading', { name: 'Execution' })

    await userEvent.click(screen.getByRole('button', { name: /Start/ }))

    expect(screen.getByLabelText('Output').textContent).toBe(JSON.stringify({ a: [1, 2] }, null, 2))
  })

  it('polls a running execution until it finishes, and allows cancelling it', async () => {
    api.getExecution
      .mockResolvedValueOnce(succeededExecution({ status: 'RUNNING', finishedAt: undefined, durationMs: undefined }))
      .mockResolvedValue(succeededExecution())
    api.cancelExecution.mockResolvedValue(succeededExecution({ status: 'RUNNING' }))
    renderApp('/executions/exec-2')

    const cancel = await screen.findByRole('button', { name: 'Cancel' })
    await userEvent.click(cancel)
    expect(api.cancelExecution).toHaveBeenCalledWith('exec-2')

    await waitFor(() => expect(api.getExecution.mock.calls.length).toBeGreaterThan(1), { timeout: 4000 })
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument(), { timeout: 4000 })
  })

  it('keeps a delayed cancellation response attached to its original execution', async () => {
    const first = succeededExecution({ id: 'exec-2', status: 'RUNNING', finishedAt: undefined })
    const second = succeededExecution({ id: 'exec-3' })
    api.getExecution.mockImplementation(async (id: string) => id === 'exec-2' ? first : second)
    let finish!: (value: typeof first) => void
    api.cancelExecution.mockReturnValue(new Promise<typeof first>((resolve) => { finish = resolve }))
    const { router } = renderApp('/executions/exec-2')
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }))
    await act(async () => { await router.navigate('/executions/exec-3') })
    await screen.findByText('exec-3')
    await act(async () => finish({ ...first, status: 'CANCELLED' }))
    expect(screen.getByText('exec-3')).toBeInTheDocument()
    expect(screen.queryByText('exec-2')).not.toBeInTheDocument()
  })

  it('reports cancellation errors and allows another attempt', async () => {
    api.getExecution.mockResolvedValue(succeededExecution({ status: 'RUNNING', finishedAt: undefined }))
    api.cancelExecution.mockRejectedValue(new Error('offline'))
    renderApp('/executions/exec-2')
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not cancel')
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeEnabled()
  })

  it('reports missing executions', async () => {
    api.getExecution.mockRejectedValue(new AxiosError('x', '404', undefined, undefined, { status: 404 } as AxiosResponse))
    renderApp('/executions/nope')
    expect(await screen.findByRole('alert')).toHaveTextContent('Execution not found.')
  })
})
