import { useEffect } from 'react'
import { useBlocker } from 'react-router-dom'

import { selectIsDirty, useEditorStore } from './editorStore'

export const UNSAVED_MESSAGE = 'You have unsaved changes. Leave this page and discard them?'

/** Router state flag set by the editor itself when it navigates right after a successful first save. */
export const SKIP_UNSAVED_GUARD = 'skipUnsavedGuard'

/**
 * Warns before losing unsaved work: the browser's own prompt on reload/close, and a confirm dialog for in-app
 * navigation (requires a data router). Reads the store directly so decisions never use a stale render.
 */
export function useUnsavedChangesGuard() {
  const dirty = useEditorStore(selectIsDirty)

  useEffect(() => {
    if (!dirty) return
    const handler = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [dirty])

  const blocker = useBlocker(({ currentLocation, nextLocation }) => {
    if ((nextLocation.state as Record<string, unknown> | null)?.[SKIP_UNSAVED_GUARD]) return false
    return selectIsDirty(useEditorStore.getState()) && currentLocation.pathname !== nextLocation.pathname
  })

  useEffect(() => {
    if (blocker.state !== 'blocked') return
    if (window.confirm(UNSAVED_MESSAGE)) blocker.proceed()
    else blocker.reset()
  }, [blocker])
}
