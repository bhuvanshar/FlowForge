import { FilePlus2, Pencil } from 'lucide-react'
import { useRef, useState } from 'react'

import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { TextAreaField, TextField } from '@/components/ui/form-field'

export const MAX_NAME = 200
export const MAX_DESCRIPTION = 2000

/** Name and description of a workflow: used to create one and to rename one. */
export function WorkflowDetailsDialog({
  mode, initialName = '', initialDescription = '', busy, error, onSubmit, onCancel,
}: {
  mode: 'create' | 'rename'
  initialName?: string
  initialDescription?: string
  busy: boolean
  /** Failure of the last submit (from the server), shown above the buttons. */
  error?: string
  onSubmit: (name: string, description: string) => void
  onCancel: () => void
}) {
  const [name, setName] = useState(initialName)
  const [description, setDescription] = useState(initialDescription)
  const [touched, setTouched] = useState(false)
  const nameInput = useRef<HTMLInputElement>(null)
  const trimmed = name.trim()
  const nameError = !touched ? undefined : trimmed === '' ? 'Give the workflow a name.' : trimmed.length > MAX_NAME
    ? `Use at most ${MAX_NAME} characters.` : undefined
  const descriptionError = description.length > MAX_DESCRIPTION ? `Use at most ${MAX_DESCRIPTION} characters.` : undefined
  const create = mode === 'create'

  function submit(event: React.FormEvent) {
    event.preventDefault()
    setTouched(true)
    if (trimmed === '' || trimmed.length > MAX_NAME || descriptionError) {
      nameInput.current?.focus()
      return
    }
    onSubmit(trimmed, description.trim())
  }

  return (
    <Dialog
      title={create ? 'New workflow' : 'Rename workflow'}
      description={create ? 'Name it now; you build the steps in the editor next.' : 'The workflow itself is not changed, only how it is labelled.'}
      icon={create ? FilePlus2 : Pencil}
      onClose={onCancel}
      initialFocus={nameInput}
    >
      <form onSubmit={submit} noValidate className="mt-5 flex flex-col gap-4">
        <TextField
          ref={nameInput}
          label="Name"
          value={name}
          maxLength={MAX_NAME + 50}
          placeholder="e.g. Notify support about negative reviews"
          autoComplete="off"
          required
          error={nameError}
          onChange={(e) => {
            setName(e.target.value)
            setTouched(true)
          }}
        />
        <TextAreaField
          label="Description (optional)"
          value={description}
          rows={3}
          placeholder="What does this workflow do?"
          error={descriptionError}
          onChange={(e) => setDescription(e.target.value)}
        />
        {error && (
          <p role="alert" className="rounded-lg bg-destructive-soft px-3 py-2 text-sm text-destructive-foreground">
            {error}
          </p>
        )}
        <div className="mt-1 flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" disabled={busy}>
            {busy ? 'Saving…' : create ? 'Create workflow' : 'Save name'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
