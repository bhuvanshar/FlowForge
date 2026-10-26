import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'

import { createWorkflow, updateWorkflow } from '@/lib/api/workflows'
import type { Viewport } from '@/lib/workflow/definition'

import { selectDefinition, selectSignature, useEditorStore } from './editorStore'
import { SKIP_UNSAVED_GUARD } from './useUnsavedChangesGuard'

/** Saves the current editor content through the Module 3 API (create on first save, update afterwards). */
export function useSaveWorkflow() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()

  return useMutation({
    mutationFn: async (viewport: Viewport) => {
      const state = useEditorStore.getState()
      state.setViewport(viewport)
      const current = useEditorStore.getState()
      const signature = selectSignature(current)
      const definition = selectDefinition(current, viewport)
      const name = current.name.trim()
      const saved =
        current.workflowId === null
          ? await createWorkflow({ name, description: current.description, definition })
          : await updateWorkflow(current.workflowId, {
              name,
              description: current.description,
              definition,
              lockVersion: current.lockVersion ?? 0,
            })
      return { saved, signature, wasNew: current.workflowId === null, sessionId: current.sessionId }
    },
    onSuccess: ({ saved, signature, wasNew, sessionId }) => {
      void queryClient.invalidateQueries({ queryKey: ['workflows'] })
      // Registrations follow the saved definition (webhook settings, schedule).
      void queryClient.invalidateQueries({ queryKey: ['triggers', saved.id] })
      // The request still saved on the server, but must not affect an editor opened since it began.
      if (useEditorStore.getState().sessionId !== sessionId) return
      // The signature is from when the request was built, so edits made while saving remain "unsaved".
      useEditorStore.getState().markSaved(saved, signature)
      if (wasNew) {
        navigate(`/workflows/${saved.id}`, { replace: true, state: { [SKIP_UNSAVED_GUARD]: true } })
      }
    },
  })
}
