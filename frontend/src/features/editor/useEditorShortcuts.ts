import { useEffect } from 'react'

import { useEditorStore } from './editorStore'

function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  return target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)
}

interface Options {
  onSave: () => void
  onFitView: () => void
}

/**
 * Editor keyboard shortcuts: Ctrl/Cmd+Z undo, Ctrl/Cmd+Shift+Z or Ctrl+Y redo, Ctrl/Cmd+S save,
 * Delete/Backspace delete selection, Shift+1 fit view. Ignored while typing in a form field (except save).
 */
export function useEditorShortcuts({ onSave, onFitView }: Options) {
  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      const mod = event.ctrlKey || event.metaKey
      const key = event.key.toLowerCase()
      const { undo, redo, deleteSelected } = useEditorStore.getState()

      if (mod && key === 's') {
        event.preventDefault()
        onSave()
        return
      }
      if (isTypingTarget(event.target)) return

      if (mod && key === 'z') {
        event.preventDefault()
        if (event.shiftKey) redo()
        else undo()
      } else if (mod && key === 'y') {
        event.preventDefault()
        redo()
      } else if (!mod && (event.key === 'Delete' || event.key === 'Backspace')) {
        event.preventDefault()
        deleteSelected()
      } else if (!mod && event.shiftKey && (event.key === '1' || event.key === '!')) {
        event.preventDefault()
        onFitView()
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [onSave, onFitView])
}
