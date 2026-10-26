import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { failedExecution, succeededExecution } from './fixtures'
import { ExecutionInspector } from './ExecutionInspector'

describe('ExecutionInspector', () => {
  it('lists nodes in execution order with number, name, status and duration', () => {
    render(<ExecutionInspector execution={failedExecution()} />)

    const rows = within(screen.getByRole('list', { name: 'Execution order' })).getAllByRole('button')
    expect(rows.map((r) => r.getAttribute('data-node-id'))).toEqual(['t', 'call', 'log'])
    expect(rows[0]).toHaveTextContent('1')
    expect(rows[0]).toHaveTextContent('Start')
    expect(rows[0]).toHaveTextContent('Succeeded')
    expect(rows[0]).toHaveTextContent('4 ms')
    expect(rows[1]).toHaveTextContent('Call API')
    expect(rows[1]).toHaveTextContent('Failed')
    expect(rows[1]).toHaveTextContent('1.24 s')
    expect(rows[2]).toHaveTextContent('Skipped')
    expect(rows[2]).toHaveTextContent('–')
  })

  it('opens on the failed node and shows its input, error and affected node', () => {
    render(<ExecutionInspector execution={failedExecution()} />)

    const details = screen.getByLabelText('Node details')
    expect(within(details).getByRole('heading', { name: 'Call API' })).toBeInTheDocument()
    expect(within(details).getByRole('alert')).toHaveTextContent('HTTP_STATUS_ERROR')
    expect(within(details).getByRole('alert')).toHaveTextContent('answered 503')
    expect(within(details).getByLabelText('Input').textContent).toBe(JSON.stringify({ order: 7 }, null, 2))
    expect(within(details).getByText('No output')).toBeInTheDocument()
    expect(within(details).getByText('Log').closest('section')).not.toBeNull()
    expect(screen.getByRole('list', { name: 'Node log' })).toHaveTextContent('[INFO] calling api')
  })

  it('opens on the first node when nothing failed', () => {
    render(<ExecutionInspector execution={succeededExecution()} />)
    expect(within(screen.getByLabelText('Node details')).getByRole('heading', { name: 'Start' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('clicking a node shows its exact saved input and output, and reports the selection', async () => {
    const onSelect = vi.fn()
    render(<ExecutionInspector execution={succeededExecution()} onSelectNode={onSelect} />)

    await userEvent.click(screen.getByRole('button', { name: /Log result/ }))

    const details = screen.getByLabelText('Node details')
    expect(within(details).getByRole('heading', { name: 'Log result' })).toBeInTheDocument()
    expect(within(details).getByLabelText('Output').textContent).toBe(JSON.stringify({ a: [1, 2] }, null, 2))
    expect(screen.getByRole('button', { name: /Log result/ })).toHaveAttribute('aria-current', 'true')
    expect(screen.getByRole('list', { name: 'Node log' })).toHaveTextContent('hello')
    expect(onSelect).toHaveBeenCalledWith('log')
  })

  it('explains skipped nodes and shows redacted values as stored', async () => {
    render(<ExecutionInspector execution={failedExecution()} />)

    await userEvent.click(screen.getByRole('button', { name: /Log result/ }))
    expect(screen.getByText(/This node was skipped/)).toBeInTheDocument()
    expect(screen.getByText('No input (the node did not run)')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /Start/ }))
    expect(screen.getByLabelText('Output').textContent).toContain('"password": "[REDACTED]"')
  })

  it('keeps the selection while polling updates the same execution, and resets for another execution', async () => {
    const { rerender } = render(<ExecutionInspector execution={succeededExecution({ status: 'RUNNING' })} />)
    await userEvent.click(screen.getByRole('button', { name: /Log result/ }))

    rerender(<ExecutionInspector execution={succeededExecution({ status: 'SUCCEEDED', durationMs: 90 })} />)
    expect(screen.getByRole('button', { name: /Log result/ })).toHaveAttribute('aria-current', 'true')

    rerender(<ExecutionInspector execution={failedExecution()} />)
    expect(screen.getByRole('button', { name: /Call API/ })).toHaveAttribute('aria-current', 'true')
  })

  it('handles an execution without nodes', () => {
    render(<ExecutionInspector execution={succeededExecution({ nodes: [], logs: [] })} />)
    expect(screen.getByText('Select a node to inspect its input and output.')).toBeInTheDocument()
  })
})
