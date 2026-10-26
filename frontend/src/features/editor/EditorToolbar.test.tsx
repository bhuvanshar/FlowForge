import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import example from '../../../../backend/src/test/resources/workflow/example-definition.v1.json'
import type { WorkflowDefinition } from '@/lib/workflow/definition'

import { useEditorStore } from './editorStore'
import { EditorToolbar } from './EditorToolbar'

const store = () => useEditorStore.getState()

function renderToolbar(props: Partial<React.ComponentProps<typeof EditorToolbar>> = {}) {
  const onSave = vi.fn()
  const onFitView = vi.fn()
  render(<EditorToolbar onSave={onSave} onFitView={onFitView} saving={false} {...props} />)
  return { onSave, onFitView }
}

function loadSaved() {
  store().load({
    id: 'w', name: 'Orders', status: 'ACTIVE', version: 2, lockVersion: 1,
    definition: example as WorkflowDefinition, createdAt: 't', updatedAt: 't',
  })
}

describe('EditorToolbar', () => {
  beforeEach(() => {
    store().reset()
    store().newWorkflow()
  })

  it('reflects the saved workflow: name, status, version, clean state, save disabled', () => {
    loadSaved()
    renderToolbar()

    expect(screen.getByRole('textbox', { name: 'Workflow name' })).toHaveValue('Orders')
    expect(screen.getByText('ACTIVE')).toBeInTheDocument()
    expect(screen.getByText('v2')).toBeInTheDocument()
    expect(screen.getByTestId('dirty-indicator')).toHaveTextContent('All changes saved')
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Undo' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Redo' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Delete selected' })).toBeDisabled()
  })

  it('shows unsaved changes and enables save and undo after an edit', async () => {
    loadSaved()
    const { onSave } = renderToolbar()
    store().addNode('core.logger', { x: 0, y: 0 })

    expect(await screen.findByText('Unsaved changes')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Undo' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Delete selected' })).toBeEnabled()
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(onSave).toHaveBeenCalledTimes(1)
  })

  it('undo and redo buttons drive the store', async () => {
    renderToolbar()
    store().addNode('core.logger', { x: 0, y: 0 })

    await userEvent.click(await screen.findByRole('button', { name: 'Undo' }))
    expect(store().nodes).toHaveLength(0)
    await userEvent.click(screen.getByRole('button', { name: 'Redo' }))
    expect(store().nodes).toHaveLength(1)
  })

  it('a new workflow needs a name before it can be saved', async () => {
    renderToolbar()
    expect(screen.getByTestId('dirty-indicator')).toHaveTextContent('Not saved yet')
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()

    await userEvent.type(screen.getByRole('textbox', { name: 'Workflow name' }), 'Fresh')

    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
  })

  it('disables saving while a save is running and for archived workflows', () => {
    loadSaved()
    store().setName('Edited')
    const { rerender } = render(<EditorToolbar onSave={vi.fn()} onFitView={vi.fn()} saving />)
    expect(screen.getAllByRole('button', { name: 'Save' }).at(-1)).toBeDisabled()
    rerender(<EditorToolbar onSave={vi.fn()} onFitView={vi.fn()} saving={false} />)
    expect(screen.getAllByRole('button', { name: 'Save' }).at(-1)).toBeEnabled()

    store().markSaved({ ...store(), id: 'w', status: 'ARCHIVED', version: 2, lockVersion: 1 } as never, 'x')
    rerender(<EditorToolbar onSave={vi.fn()} onFitView={vi.fn()} saving={false} />)
    expect(screen.getAllByRole('button', { name: 'Save' }).at(-1)).toBeDisabled()
  })

  it('fit view button calls the handler', async () => {
    const { onFitView } = renderToolbar()
    await userEvent.click(screen.getByRole('button', { name: 'Fit view' }))
    expect(onFitView).toHaveBeenCalled()
  })
})
