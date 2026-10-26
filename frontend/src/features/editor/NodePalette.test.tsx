import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { NodePalette } from './NodePalette'
import { DRAG_MIME } from './paletteDrag'

describe('NodePalette', () => {
  it('lists all built-in node types, triggers first', () => {
    render(<NodePalette />)
    const items = screen.getAllByRole('button')

    expect(items.map((i) => i.textContent)).toEqual([
      'Manual Trigger', 'Webhook Trigger', 'Schedule Trigger', 'Set Variables', 'Condition', 'Transform', 'Logger',
      'HTTP Request', 'AI Model',
    ])
    expect(items.map((i) => i.getAttribute('data-node-type'))).toEqual([
      'core.manualTrigger', 'core.webhookTrigger', 'core.scheduleTrigger', 'core.setVariables', 'core.condition', 'core.transform', 'core.logger', 'core.httpRequest', 'core.aiModel',
    ])
    expect(items.every((i) => i.getAttribute('draggable') === 'true')).toBe(true)
  })

  it('puts the backend type identifier on the drag payload', () => {
    render(<NodePalette />)
    const data: Record<string, string> = {}
    const dataTransfer = { setData: (k: string, v: string) => (data[k] = v), effectAllowed: '' }

    fireEvent.dragStart(screen.getByRole('button', { name: 'HTTP Request' }), { dataTransfer })

    expect(data[DRAG_MIME]).toBe('core.httpRequest')
  })

  it('filters node types and reports when nothing matches', async () => {
    render(<NodePalette />)
    const search = screen.getByRole('textbox', { name: 'Search nodes' })
    await userEvent.type(search, 'HTTP')
    expect(screen.getByRole('button', { name: 'HTTP Request' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Logger' })).not.toBeInTheDocument()
    await userEvent.clear(search)
    await userEvent.type(search, 'no-such-node')
    expect(screen.getByRole('status')).toHaveTextContent('No nodes match')
    await userEvent.clear(search)
    expect(screen.getAllByRole('button')).toHaveLength(9)
  })

  it('adds at the centre on double-click and Enter', async () => {
    const onAdd = vi.fn()
    render(<NodePalette onAddAtCenter={onAdd} />)

    await userEvent.dblClick(screen.getByRole('button', { name: 'Logger' }))
    screen.getByRole('button', { name: 'Condition' }).focus()
    await userEvent.keyboard('{Enter}')

    expect(onAdd).toHaveBeenNthCalledWith(1, 'core.logger')
    expect(onAdd).toHaveBeenNthCalledWith(2, 'core.condition')
  })
})
