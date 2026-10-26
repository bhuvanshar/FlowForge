import { History, Maximize, Play, Power, PowerOff, Redo2, Save, ShieldCheck, Trash2, Undo2 } from 'lucide-react'
import { Link } from 'react-router-dom'

import { Button } from '@/components/ui/button'

import { selectCanRedo, selectCanUndo, selectIsDirty, useEditorStore } from './editorStore'

interface Props {
  onSave: () => void
  onFitView: () => void
  saving: boolean
  /** Validate the current canvas now. Omitted means validation is unavailable. */
  onValidate?: () => void
  /** Run the saved workflow. Omitted means running is unavailable. */
  onRun?: () => void
  running?: boolean
  /** Link to the execution history; shown for saved workflows when provided (requires a router). */
  historyHref?: string
  /** Activate or deactivate the saved workflow (automatic triggers). Omitted means unavailable. */
  onToggleActive?: () => void
  toggling?: boolean
}

export function EditorToolbar({
  onSave, onFitView, saving, onValidate, onRun, running = false, historyHref, onToggleActive, toggling = false,
}: Props) {
  const name = useEditorStore((s) => s.name)
  const setName = useEditorStore((s) => s.setName)
  const status = useEditorStore((s) => s.status)
  const version = useEditorStore((s) => s.version)
  const isNew = useEditorStore((s) => s.workflowId === null)
  const dirty = useEditorStore(selectIsDirty)
  const canUndo = useEditorStore(selectCanUndo)
  const canRedo = useEditorStore(selectCanRedo)
  const hasSelection = useEditorStore((s) => s.nodes.some((n) => n.selected) || s.edges.some((e) => e.selected))
  const undo = useEditorStore((s) => s.undo)
  const redo = useEditorStore((s) => s.redo)
  const deleteSelected = useEditorStore((s) => s.deleteSelected)

  const archived = status === 'ARCHIVED'
  const canSave = !saving && !archived && name.trim().length > 0 && (dirty || isNew)

  return (
    <div role="toolbar" aria-label="Editor toolbar" className="flex min-h-16 shrink-0 flex-wrap items-center gap-2 border-b bg-card px-4 py-3">
      <input
        aria-label="Workflow name"
        value={name}
        maxLength={200}
        placeholder="Untitled workflow"
        onChange={(event) => setName(event.target.value)}
        className="h-9 w-52 rounded-lg border border-transparent bg-muted/50 px-3 text-sm font-semibold hover:border-border focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      />
      <span className="rounded-full border px-2 py-0.5 text-xs text-muted-foreground">{status}</span>
      {!isNew && <span className="text-xs text-muted-foreground">v{version}</span>}
      <span
        data-testid="dirty-indicator"
        aria-live="polite"
        className="text-xs text-muted-foreground"
      >
        {dirty ? 'Unsaved changes' : isNew ? 'Not saved yet' : 'All changes saved'}
      </span>

      <div className="ml-auto flex flex-wrap items-center gap-1">
        <Button variant="ghost" size="icon" aria-label="Undo" title="Undo (Ctrl/Cmd+Z)" disabled={!canUndo} onClick={undo}>
          <Undo2 className="size-4" aria-hidden />
        </Button>
        <Button variant="ghost" size="icon" aria-label="Redo" title="Redo (Ctrl/Cmd+Shift+Z)" disabled={!canRedo} onClick={redo}>
          <Redo2 className="size-4" aria-hidden />
        </Button>
        <Button
          variant="ghost"
          size="icon"
          aria-label="Delete selected"
          title="Delete selected (Delete)"
          disabled={!hasSelection}
          onClick={deleteSelected}
        >
          <Trash2 className="size-4" aria-hidden />
        </Button>
        <Button variant="ghost" size="icon" aria-label="Fit view" title="Fit view (Shift+1)" onClick={onFitView}>
          <Maximize className="size-4" aria-hidden />
        </Button>
        {historyHref && !isNew && (
          <Link
            to={historyHref}
            className="inline-flex h-8 items-center gap-1 rounded-md px-3 text-sm hover:bg-accent"
            title="Execution history"
          >
            <History className="size-4" aria-hidden />
            History
          </Link>
        )}
        {onRun && (
          <Button
            variant="outline"
            size="sm"
            aria-label="Run"
            title={isNew ? 'Save the workflow first' : dirty ? 'Save your changes first: Run executes the saved version' : 'Run the saved workflow'}
            disabled={isNew || dirty || running || status === 'ARCHIVED'}
            onClick={onRun}
          >
            <Play className="size-4" aria-hidden />
            {running ? 'Starting…' : 'Run'}
          </Button>
        )}
        {onToggleActive && (
          status === 'ACTIVE' ? (
            <Button
              variant="outline"
              size="sm"
              aria-label="Deactivate"
              title="Stop the webhook and schedule triggers"
              disabled={toggling}
              onClick={onToggleActive}
            >
              <PowerOff className="size-4" aria-hidden />
              Deactivate
            </Button>
          ) : (
            <Button
              variant="outline"
              size="sm"
              aria-label="Activate"
              title={
                isNew ? 'Save the workflow first'
                  : dirty ? 'Save your changes first: activation uses the saved version'
                    : 'Start the webhook or schedule trigger of the saved workflow'
              }
              disabled={isNew || dirty || toggling || archived}
              onClick={onToggleActive}
            >
              <Power className="size-4" aria-hidden />
              Activate
            </Button>
          )
        )}
        {onValidate && (
          <Button
            variant="outline"
            size="sm"
            aria-label="Validate"
            title={isNew ? 'Save the workflow first' : 'Check the workflow for problems'}
            disabled={isNew}
            onClick={onValidate}
          >
            <ShieldCheck className="size-4" aria-hidden />
            Validate
          </Button>
        )}
        <Button size="sm" aria-label="Save" title="Save (Ctrl/Cmd+S)" disabled={!canSave} onClick={onSave}>
          <Save className="size-4" aria-hidden />
          {saving ? 'Saving…' : 'Save'}
        </Button>
      </div>
    </div>
  )
}
