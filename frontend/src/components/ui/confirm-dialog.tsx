import type { LucideIcon } from 'lucide-react'
import { useRef } from 'react'
import type * as React from 'react'

import { Button } from './button'
import { Dialog } from './dialog'

export interface ConfirmDialogProps {
  title: string
  description: string
  confirmLabel: string
  cancelLabel?: string
  /** `danger` styles the confirm button as destructive; the safe choice (cancel) always gets the initial focus. */
  tone?: 'default' | 'danger'
  icon?: LucideIcon
  busy?: boolean
  /** Failure of the confirmed action, shown in the dialog so it can be retried. */
  error?: string
  onConfirm: () => void
  onCancel: () => void
  children?: React.ReactNode
}

/** "Are you sure?" for actions with consequences. Spells out what happens and defaults to the safe answer. */
export function ConfirmDialog({
  title, description, confirmLabel, cancelLabel = 'Cancel', tone = 'default', icon, busy = false, error, onConfirm,
  onCancel, children,
}: ConfirmDialogProps) {
  const cancel = useRef<HTMLButtonElement>(null)
  return (
    <Dialog title={title} description={description} icon={icon} onClose={onCancel} initialFocus={cancel}>
      {children}
      {error && (
        <p role="alert" className="mt-4 rounded-lg bg-destructive-soft px-3 py-2 text-sm text-destructive-foreground">
          {error}
        </p>
      )}
      <div className="mt-6 flex justify-end gap-2">
        <Button ref={cancel} variant="outline" onClick={onCancel} disabled={busy}>
          {cancelLabel}
        </Button>
        <Button variant={tone === 'danger' ? 'destructive' : 'default'} onClick={onConfirm} disabled={busy}>
          {busy ? 'Working…' : confirmLabel}
        </Button>
      </div>
    </Dialog>
  )
}
