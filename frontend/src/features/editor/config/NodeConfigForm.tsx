import { useEffect, useMemo, useRef } from 'react'
import { useForm, useWatch } from 'react-hook-form'

import { Button } from '@/components/ui/button'

import { useEditorStore } from '../editorStore'
import type { WorkflowFlowNode } from '../types'
import { ConfigField } from './ConfigFields'
import type { FormValues, NodeConfigDefinition } from './fieldSpec'
import { jsonEqual, prettyJson } from './json'
import { getConfigDefinition } from './registry'
import { zodResolver } from './zodResolver'

/**
 * Settings form for the selected node. Pass `key={node.id}` so switching nodes remounts it with that node's own
 * values (a saved draft if the user left unfinished edits, otherwise the committed configuration).
 */
export function NodeConfigForm({ node }: { node: WorkflowFlowNode }) {
  const definition = getConfigDefinition(node.data.nodeType)
  if (!definition) return <UnknownTypeConfig node={node} />
  return <ConfigFormBody key={node.id} node={node} definition={definition} />
}

function UnknownTypeConfig({ node }: { node: WorkflowFlowNode }) {
  return (
    <div className="flex flex-col gap-2">
      <p className="rounded-md border border-dashed p-3 text-xs text-muted-foreground">
        This node type has no configuration form in this editor. Its configuration is kept unchanged when you save.
      </p>
      <pre className="max-h-48 overflow-auto rounded-md bg-muted p-2 text-xs">{prettyJson(node.data.configuration)}</pre>
    </div>
  )
}

function ConfigFormBody({ node, definition }: { node: WorkflowFlowNode; definition: NodeConfigDefinition }) {
  const nodeId = node.id
  const configuration = node.data.configuration
  const baseline = useMemo(() => definition.toFormValues(configuration), [definition, configuration])
  const initial = () => useEditorStore.getState().drafts[nodeId] ?? baseline

  const form = useForm<FormValues>({
    resolver: zodResolver(definition.schema),
    defaultValues: initial(),
    mode: 'onChange',
  })
  const { register, control, handleSubmit, reset, trigger, formState } = form
  const values = useWatch({ control }) as FormValues
  const dirty = !jsonEqual(values, baseline)

  // Restoring an unfinished draft: show its problems right away instead of waiting for the user to touch fields.
  useEffect(() => {
    if (useEditorStore.getState().drafts[nodeId]) void trigger()
  }, [nodeId, trigger])

  // Mirror unapplied edits into the store, so they survive switching nodes and count as unsaved changes.
  useEffect(() => {
    const { setDraft, clearDraft } = useEditorStore.getState()
    if (dirty) setDraft(nodeId, values)
    else clearDraft(nodeId)
  }, [dirty, values, nodeId])

  // The committed configuration changed from outside this form (Apply, undo/redo, Save): re-sync the form.
  const firstRun = useRef(true)
  useEffect(() => {
    if (firstRun.current) {
      firstRun.current = false
      return
    }
    reset(initial())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [configuration])

  const apply = handleSubmit((submitted) => {
    useEditorStore.getState().applyConfiguration(nodeId, definition.toConfiguration(submitted))
  })

  const revert = () => {
    useEditorStore.getState().clearDraft(nodeId)
    reset(baseline)
  }

  return (
    <form onSubmit={apply} noValidate aria-label="Node settings" className="flex flex-col gap-3">
      {definition.fields.map((spec) =>
        spec.visibleWhen && !spec.visibleWhen(values) ? null : (
          <ConfigField
            key={spec.name}
            spec={spec}
            idPrefix={`${nodeId}-cfg`}
            register={register}
            control={control}
            errors={formState.errors}
          />
        ),
      )}
      <div className="flex items-center gap-2 pt-1">
        <Button type="submit" size="sm" disabled={!dirty}>
          Apply
        </Button>
        <Button type="button" size="sm" variant="outline" disabled={!dirty} onClick={revert}>
          Revert
        </Button>
        <span data-testid="config-status" className="text-xs text-muted-foreground">
          {dirty ? 'Edited, not applied' : 'Applied'}
        </span>
      </div>
    </form>
  )
}
