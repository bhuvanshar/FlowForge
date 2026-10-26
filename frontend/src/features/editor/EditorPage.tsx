import { ReactFlowProvider, useReactFlow } from '@xyflow/react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { Layers3, Settings2, X } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { activateWorkflow, deactivateWorkflow } from '@/lib/api/triggers'
import { executeWorkflow, getWorkflow } from '@/lib/api/workflows'
import { toProblemDetail } from '@/lib/api/client'
import axios from 'axios'
import { cn } from '@/lib/utils'

import { NEW_WORKFLOW_KEY, selectIsDirty, useEditorStore } from './editorStore'
import { EditorToolbar } from './EditorToolbar'
import { ExecutionPanel } from './ExecutionPanel'
import { NodePalette } from './NodePalette'
import { useAddNodeAtCenter } from './paletteDrag'
import { ProblemsPanel } from './ProblemsPanel'
import { PropertiesPanel } from './PropertiesPanel'
import { triggersQueryKey } from './triggers'
import { useEditorShortcuts } from './useEditorShortcuts'
import { useSaveWorkflow } from './useSaveWorkflow'
import { useUnsavedChangesGuard } from './useUnsavedChangesGuard'
import { useWorkflowValidation } from './useWorkflowValidation'
import { WorkflowCanvas } from './WorkflowCanvas'

export function EditorPage() {
  const { workflowId = NEW_WORKFLOW_KEY } = useParams()
  const isNew = workflowId === NEW_WORKFLOW_KEY
  const loadedFor = useEditorStore((s) => s.loadedFor)
  const load = useEditorStore((s) => s.load)

  // A brand-new workflow starts empty. After its first save the URL changes to the new id, and the store already
  // holds that workflow (loadedFor === id), so nothing is refetched and undo history is kept.
  useEffect(() => {
    if (isNew && useEditorStore.getState().loadedFor !== NEW_WORKFLOW_KEY)
      useEditorStore.getState().newWorkflow()
  }, [isNew])

  // Leaving the editor discards its in-memory state, so reopening always shows the server version.
  useEffect(() => () => useEditorStore.getState().reset(), [])

  const query = useQuery({
    queryKey: ['workflow', workflowId],
    queryFn: () => getWorkflow(workflowId),
    enabled: !isNew && loadedFor !== workflowId,
    staleTime: 0,
    gcTime: 0,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })

  useEffect(() => {
    if (query.data && useEditorStore.getState().loadedFor !== workflowId) load(query.data)
  }, [query.data, workflowId, load])

  if (loadedFor !== workflowId) {
    if (query.isError) {
      const notFound = axios.isAxiosError(query.error) && query.error.response?.status === 404
      return (
        <p role="alert" className="p-6 text-sm text-destructive">
          {notFound ? 'Workflow not found.' : 'Could not load the workflow.'}
        </p>
      )
    }
    return <p className="p-6 text-sm text-muted-foreground">Loading workflow…</p>
  }

  return (
    <ReactFlowProvider>
      <EditorLayout workflowId={workflowId} />
    </ReactFlowProvider>
  )
}

function EditorLayout({ workflowId }: { workflowId: string }) {
  const [mobilePanel, setMobilePanel] = useState<'nodes' | 'properties' | null>(null)
  const { fitView, getViewport, getEdge } = useReactFlow()
  const save = useSaveWorkflow()
  const addAtCenter = useAddNodeAtCenter()
  const status = useEditorStore((s) => s.status)
  const load = useEditorStore((s) => s.load)

  useUnsavedChangesGuard()
  const queryClient = useQueryClient()
  const run = useMutation({
    onMutate: () => ({ sessionId: useEditorStore.getState().sessionId }),
    mutationFn: () => executeWorkflow(workflowId),
    onSuccess: ({ executionId }, _variables, context) => {
      if (useEditorStore.getState().sessionId === context?.sessionId) {
        useEditorStore.getState().showExecution(executionId)
      }
      void queryClient.invalidateQueries({ queryKey: ['executions'] })
    },
  })
  const validateNow = useWorkflowValidation()
  const toggleActive = useMutation({
    onMutate: () => ({ sessionId: useEditorStore.getState().sessionId }),
    mutationFn: (activate: boolean) =>
      activate ? activateWorkflow(workflowId) : deactivateWorkflow(workflowId),
    onSuccess: (workflow, _variables, context) => {
      if (useEditorStore.getState().sessionId === context?.sessionId) {
        useEditorStore.getState().applyServerStatus(workflow)
      }
      void queryClient.invalidateQueries({ queryKey: triggersQueryKey(workflowId) })
      void queryClient.invalidateQueries({ queryKey: ['workflows'] })
    },
    onError: (error, _variables, context) => {
      // Show the reasons on the canvas and in the Problems panel.
      if (
        useEditorStore.getState().sessionId === context?.sessionId &&
        toProblemDetail(error)?.status === 422
      ) {
        void validateNow()
      }
    },
  })
  const reload = useMutation({
    onMutate: () => ({ sessionId: useEditorStore.getState().sessionId }),
    mutationFn: () => getWorkflow(workflowId),
    onSuccess: (latest, _variables, context) => {
      if (useEditorStore.getState().sessionId !== context?.sessionId) return
      save.reset()
      load(latest)
    },
  })

  const onFitView = useCallback(() => void fitView({ maxZoom: 1, padding: 0.3, duration: 200 }), [fitView])
  const [invalidNodes, setInvalidNodes] = useState<{ id: string; name: string }[]>([])
  const onSave = useCallback(() => {
    const s = useEditorStore.getState()
    if (save.isPending || s.status === 'ARCHIVED' || s.name.trim() === '') return
    if (s.workflowId !== null && !selectIsDirty(s)) return
    // Unapplied settings edits are applied first. Saving never writes a half-finished configuration.
    const { invalidNodeIds } = s.applyDrafts()
    const names = useEditorStore.getState().nodes.filter((n) => invalidNodeIds.includes(n.id))
    setInvalidNodes(names.map((n) => ({ id: n.id, name: n.data.name })))
    if (invalidNodeIds.length > 0) {
      useEditorStore.getState().selectOnly(invalidNodeIds[0])
      return
    }
    save.mutate(getViewport())
  }, [save, getViewport])

  useEditorShortcuts({ onSave, onFitView })

  const reloadFromServer = () => {
    if (reload.isPending) return
    if (!window.confirm('Reload the latest saved version? Your unsaved changes will be lost.')) return
    reload.mutate()
  }

  return (
    <div className="flex h-full flex-col">
      <EditorToolbar
        onSave={onSave}
        onFitView={onFitView}
        saving={save.isPending}
        onValidate={() => void validateNow()}
        onRun={() => run.mutate()}
        running={run.isPending}
        historyHref={`/workflows/${workflowId}/executions`}
        onToggleActive={() => toggleActive.mutate(useEditorStore.getState().status !== 'ACTIVE')}
        toggling={toggleActive.isPending}
      />
      {status === 'ARCHIVED' && (
        <p role="status" className="border-b bg-muted px-4 py-2 text-sm">
          This workflow is archived and read-only.
        </p>
      )}
      {invalidNodes.length > 0 && (
        <div role="alert" className="border-b border-destructive/40 bg-destructive/10 px-4 py-2 text-sm">
          Not saved: fix the settings of {invalidNodes.map((n) => `"${n.name}"`).join(', ')} first.
        </div>
      )}
      {run.isError && <RunError error={run.error} />}
      {toggleActive.isError && <ActivationError error={toggleActive.error} />}
      {save.isError && (
        <SaveError error={save.error} onReload={reloadFromServer} reloading={reload.isPending} />
      )}
      {reload.isError && (
        <p role="alert" className="border-b bg-destructive/10 px-4 py-2 text-sm text-destructive">
          Could not reload the workflow. Your edits are still available. Try again when the server is
          reachable.
        </p>
      )}
      <div className="flex gap-2 border-b bg-card px-4 py-2 xl:hidden" aria-label="Editor panels">
        <Button
          variant="outline"
          size="sm"
          aria-expanded={mobilePanel === 'nodes'}
          onClick={() => setMobilePanel(mobilePanel === 'nodes' ? null : 'nodes')}
        >
          <Layers3 className="size-3.5" aria-hidden />
          Nodes
        </Button>
        <Button
          variant="outline"
          size="sm"
          aria-expanded={mobilePanel === 'properties'}
          onClick={() => setMobilePanel(mobilePanel === 'properties' ? null : 'properties')}
        >
          <Settings2 className="size-3.5" aria-hidden />
          Properties
        </Button>
        {mobilePanel && (
          <Button
            variant="ghost"
            size="sm"
            className="ml-auto"
            onClick={() => setMobilePanel(null)}
            aria-label="Close editor panel"
          >
            <X className="size-4" aria-hidden />
          </Button>
        )}
      </div>
      <div className="relative flex min-h-0 flex-1 overflow-hidden">
        <div
          className={cn(
            'h-full max-w-full shrink-0 [&>aside]:h-full [&>aside]:max-w-full',
            mobilePanel === 'nodes'
              ? 'absolute inset-y-0 left-0 z-20 shadow-xl xl:static xl:shadow-none'
              : 'hidden xl:block',
          )}
        >
          <NodePalette
            onAddAtCenter={(type) => {
              addAtCenter(type)
              setMobilePanel(null)
            }}
          />
        </div>
        <div className="relative min-w-0 flex-1" data-testid="canvas-area">
          <WorkflowCanvas />
        </div>
        <div
          className={cn(
            'h-full max-w-full shrink-0 [&>aside]:h-full [&>aside]:max-w-full',
            mobilePanel === 'properties'
              ? 'absolute inset-y-0 right-0 z-20 shadow-xl xl:static xl:shadow-none'
              : 'hidden xl:block',
          )}
        >
          <PropertiesPanel />
        </div>
      </div>
      <ProblemsPanel
        onFocusIssue={(issue) => {
          const edge = issue.edgeId ? getEdge(issue.edgeId) : undefined
          const ids = issue.nodeId ? [issue.nodeId] : edge ? [edge.source, edge.target] : []
          if (ids.length > 0)
            void fitView({ nodes: ids.map((id) => ({ id })), maxZoom: 1, padding: 0.5, duration: 200 })
        }}
      />
      <ExecutionPanel />
    </div>
  )
}

function SaveError({
  error,
  onReload,
  reloading,
}: {
  error: unknown
  onReload: () => void
  reloading: boolean
}) {
  const problem = toProblemDetail(error)
  const conflict = problem?.status === 409
  return (
    <div
      role="alert"
      className="flex items-center gap-3 border-b border-destructive/40 bg-destructive/10 px-4 py-2 text-sm"
    >
      <span className="flex-1">
        {conflict
          ? 'This workflow was changed elsewhere, so your save was rejected. Nothing was overwritten.'
          : problem?.errors?.length
            ? `Save failed: ${problem.errors.map((e) => `${e.field}: ${e.message}`).join('; ')}`
            : `Save failed: ${problem?.detail ?? 'the server could not be reached.'}`}
      </span>
      {conflict && (
        <Button size="sm" variant="outline" onClick={onReload} disabled={reloading}>
          {reloading ? 'Reloading…' : 'Reload latest'}
        </Button>
      )}
    </div>
  )
}

function ActivationError({ error }: { error: unknown }) {
  const problem = toProblemDetail(error)
  const message =
    problem?.status === 422
      ? 'The workflow cannot be activated because it has validation errors. See the Problems panel.'
      : `Could not change the activation: ${problem?.detail ?? 'the server could not be reached.'}`
  return (
    <div role="alert" className="border-b border-destructive/40 bg-destructive/10 px-4 py-2 text-sm">
      {message}
    </div>
  )
}

function RunError({ error }: { error: unknown }) {
  const problem = toProblemDetail(error)
  const message =
    problem?.status === 422
      ? 'The workflow has validation errors and cannot run. See the Problems panel.'
      : problem?.status === 501
        ? (problem.detail ?? 'The workflow uses a node type that cannot be executed yet.')
        : problem?.status === 503
          ? 'Too many executions are queued. Try again in a moment.'
          : `Could not start the execution: ${problem?.detail ?? 'the server could not be reached.'}`
  return (
    <div role="alert" className="border-b border-destructive/40 bg-destructive/10 px-4 py-2 text-sm">
      {message}
    </div>
  )
}
