import { useCallback, useEffect, useRef } from 'react'

import { validateWorkflow } from '@/lib/api/workflows'

import { selectDefinition, selectSignature, useEditorStore } from './editorStore'

export const VALIDATION_DEBOUNCE_MS = 500

/**
 * Keeps backend validation results current: validates the canvas (including unsaved changes) shortly after every
 * content change, as long as the workflow exists on the server. Responses to superseded requests are ignored, so a
 * slow old response never overwrites a newer result. Returns a function that validates immediately.
 */
export function useWorkflowValidation(delay: number = VALIDATION_DEBOUNCE_MS) {
  const workflowId = useEditorStore((s) => s.workflowId)
  const sessionId = useEditorStore((s) => s.sessionId)
  const signature = useEditorStore(selectSignature)
  const sequence = useRef(0)
  const invalidatePending = useCallback(() => { ++sequence.current }, [])

  const validateNow = useCallback(async () => {
    const state = useEditorStore.getState()
    if (!state.workflowId) return
    const request = ++sequence.current
    const validatedSignature = selectSignature(state)
    const isCurrent = () => request === sequence.current
      && useEditorStore.getState().sessionId === state.sessionId
      && selectSignature(useEditorStore.getState()) === validatedSignature
    state.setValidationStatus('running')
    try {
      const result = await validateWorkflow(state.workflowId, selectDefinition(state))
      if (isCurrent()) useEditorStore.getState().setValidation(result, validatedSignature)
    } catch {
      if (isCurrent()) useEditorStore.getState().setValidationStatus('failed')
    }
  }, [])

  useEffect(() => {
    if (!workflowId) return
    const timer = setTimeout(() => void validateNow(), delay)
    return () => {
      clearTimeout(timer)
      invalidatePending()
      const current = useEditorStore.getState()
      if (current.sessionId === sessionId && current.validationStatus === 'running') {
        current.setValidationStatus('idle')
      }
    }
  }, [workflowId, sessionId, signature, delay, validateNow, invalidatePending])

  return validateNow
}
