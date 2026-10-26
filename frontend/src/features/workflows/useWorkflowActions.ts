import { useMutation, useQueryClient } from '@tanstack/react-query'

import { toProblemDetail } from '@/lib/api/client'
import { activateWorkflow, deactivateWorkflow } from '@/lib/api/triggers'
import {
  archiveWorkflow,
  createWorkflow,
  deleteWorkflowPermanently,
  duplicateWorkflow,
  executeWorkflow,
  renameWorkflow,
  restoreWorkflow,
  type WorkflowSummary,
} from '@/lib/api/workflows'

export type ActionRequest =
  | { kind: 'create'; name: string; description: string }
  | { kind: 'rename'; workflow: WorkflowSummary; name: string; description: string }
  | { kind: 'run' | 'duplicate' | 'activate' | 'deactivate' | 'archive' | 'restore' | 'delete'; workflow: WorkflowSummary }

/** What happened: a page to open, or a message to show. */
export interface ActionResult {
  to?: string
  message?: string
}

async function perform(request: ActionRequest): Promise<ActionResult> {
  switch (request.kind) {
    case 'create':
      return { to: `/workflows/${(await createWorkflow({ name: request.name, description: request.description || undefined })).id}` }
    case 'rename':
      await renameWorkflow(request.workflow.id, request.name, request.description || undefined)
      return { message: `Renamed to “${request.name}”.` }
    case 'duplicate':
      return { to: `/workflows/${(await duplicateWorkflow(request.workflow.id)).id}` }
    case 'run':
      return { to: `/executions/${(await executeWorkflow(request.workflow.id)).executionId}` }
    case 'activate':
      await activateWorkflow(request.workflow.id)
      return { message: `“${request.workflow.name}” is active. Its trigger now starts it automatically.` }
    case 'deactivate':
      await deactivateWorkflow(request.workflow.id)
      return { message: `“${request.workflow.name}” is inactive. Automatic triggers are stopped.` }
    case 'archive':
      await archiveWorkflow(request.workflow.id)
      return { message: `“${request.workflow.name}” archived. Its history is preserved. Restore it from the Archived tab.` }
    case 'restore': {
      const restored = await restoreWorkflow(request.workflow.id)
      return {
        message: restored.status === 'DRAFT'
          ? `“${request.workflow.name}” restored as a draft. Automatic triggers remain off.`
          : `“${request.workflow.name}” is already ${restored.status.toLowerCase()}.`,
      }
    }
    case 'delete':
      await deleteWorkflowPermanently(request.workflow.id)
      return { message: `“${request.workflow.name}” and its execution history were deleted.` }
  }
}

/** A message a person can act on, from whatever the API answered. */
export function describeActionError(error: unknown, kind: ActionRequest['kind']): string {
  const problem = toProblemDetail(error)
  if (problem?.status === 422) {
    return kind === 'activate'
      ? 'The workflow cannot be activated yet: open it in the editor and fix the problems the validation reports.'
      : 'This workflow needs attention. Open the editor and validate it before running.'
  }
  if (problem?.status === 409) return problem.detail ?? 'The workflow was changed elsewhere. Refresh the list and try again.'
  if (problem?.status === 404) return 'This workflow no longer exists. Refresh the list.'
  if (problem?.status === 503) return 'The server is busy right now (too many executions queued). Try again in a moment.'
  return problem?.detail
    ? `Action failed: ${problem.detail}`
    : 'Could not reach the server. Check that the backend is running and try again.'
}

/** Every dashboard action through one mutation, so buttons are disabled while anything is in flight. */
export function useWorkflowActions() {
  const client = useQueryClient()
  const mutation = useMutation({
    mutationFn: perform,
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['workflows'] })
      void client.invalidateQueries({ queryKey: ['executions'] })
      void client.invalidateQueries({ queryKey: ['triggers'] })
    },
  })
  // Per-call UI callbacks are discarded on unmount; cache invalidation still runs for completed server writes.
  return { pending: mutation.isPending, perform: mutation.mutate }
}
