import { useEffect } from 'react'

/** True when a key press is meant for a text field (or a menu/dialog), not for page shortcuts. */
function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable) return true
  return ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName) || target.closest('[role="menu"], dialog[open]') !== null
}

/**
 * Single-key page shortcuts (e.g. {@code "/"} to search). They never fire with Ctrl/Cmd/Alt, while typing, or while a dialog
 * or menu is open, and `preventDefault` is only called for keys that have a handler.
 */
export function useHotkeys(handlers: Record<string, () => void>) {
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.ctrlKey || event.metaKey || event.altKey || event.defaultPrevented || isTypingTarget(event.target)) return
      const handler = handlers[event.key]
      if (handler) {
        event.preventDefault()
        handler()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [handlers])
}
