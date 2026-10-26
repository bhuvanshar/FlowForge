import { fireEvent, render } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useEditorStore } from './editorStore'
import { useEditorShortcuts } from './useEditorShortcuts'

const store = () => useEditorStore.getState()

function Harness({ onSave, onFitView }: { onSave: () => void; onFitView: () => void }) {
  useEditorShortcuts({ onSave, onFitView })
  return <input aria-label="field" />
}

describe('editor keyboard shortcuts', () => {
  const onSave = vi.fn()
  const onFitView = vi.fn()

  beforeEach(() => {
    vi.clearAllMocks()
    store().reset()
    store().newWorkflow()
    render(<Harness onSave={onSave} onFitView={onFitView} />)
  })

  it('Ctrl/Cmd+S saves and prevents the browser dialog', () => {
    const ctrl = fireEvent.keyDown(window, { key: 's', ctrlKey: true })
    const meta = fireEvent.keyDown(window, { key: 's', metaKey: true })

    expect(onSave).toHaveBeenCalledTimes(2)
    expect(ctrl).toBe(false) // defaultPrevented
    expect(meta).toBe(false)
  })

  it('Ctrl/Cmd+Z undoes, Ctrl+Shift+Z and Ctrl+Y redo', () => {
    store().addNode('core.logger', { x: 0, y: 0 })

    fireEvent.keyDown(window, { key: 'z', ctrlKey: true })
    expect(store().nodes).toHaveLength(0)
    fireEvent.keyDown(window, { key: 'z', ctrlKey: true, shiftKey: true })
    expect(store().nodes).toHaveLength(1)
    fireEvent.keyDown(window, { key: 'z', metaKey: true })
    fireEvent.keyDown(window, { key: 'y', ctrlKey: true })
    expect(store().nodes).toHaveLength(1)
  })

  it('Delete and Backspace remove the selection', () => {
    store().addNode('core.logger', { x: 0, y: 0 })
    fireEvent.keyDown(window, { key: 'Delete' })
    expect(store().nodes).toHaveLength(0)

    store().addNode('core.logger', { x: 0, y: 0 })
    fireEvent.keyDown(window, { key: 'Backspace' })
    expect(store().nodes).toHaveLength(0)
  })

  it('Shift+1 fits the view', () => {
    fireEvent.keyDown(window, { key: '!', shiftKey: true })
    expect(onFitView).toHaveBeenCalledTimes(1)
  })

  it('does not delete or undo while typing in an input, but still saves', () => {
    store().addNode('core.logger', { x: 0, y: 0 })
    const input = document.querySelector('input')!

    fireEvent.keyDown(input, { key: 'Backspace' })
    fireEvent.keyDown(input, { key: 'z', ctrlKey: true })
    expect(store().nodes).toHaveLength(1)

    fireEvent.keyDown(input, { key: 's', ctrlKey: true })
    expect(onSave).toHaveBeenCalledTimes(1)
  })

  it('unregisters on unmount', () => {
    const { unmount } = render(<Harness onSave={onSave} onFitView={onFitView} />)
    unmount()
    onSave.mockClear()
    // The first Harness is still mounted; ensure exactly one listener fires after the second was removed.
    fireEvent.keyDown(window, { key: 's', ctrlKey: true })
    expect(onSave).toHaveBeenCalledTimes(1)
  })
})
