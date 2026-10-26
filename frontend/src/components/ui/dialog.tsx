import type { LucideIcon } from 'lucide-react'
import { useEffect, useId, useRef } from 'react'
import type * as React from 'react'

import { cn } from '@/lib/utils'

export interface DialogProps {
  title: string
  description?: string
  icon?: LucideIcon
  /** Called for Escape and for a click on the backdrop. */
  onClose: () => void
  children: React.ReactNode
  /** Element to focus when the dialog opens; default: the first focusable element. */
  initialFocus?: React.RefObject<HTMLElement | null>
  className?: string
}

/**
 * Modal dialog on the native `<dialog>` element: focus stays inside, the page behind is inert, Escape closes it and focus
 * returns to the element that opened it. Render it only while it should be open.
 */
export function Dialog({ title, description, icon: Icon, onClose, children, initialFocus, className }: DialogProps) {
  const dialog = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  const descriptionId = useId()

  useEffect(() => {
    const element = dialog.current
    element?.showModal()
    initialFocus?.current?.focus()
    return () => element?.close()
    // Opens once per mount.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <dialog
      ref={dialog}
      aria-labelledby={titleId}
      aria-describedby={description ? descriptionId : undefined}
      onCancel={(event) => {
        event.preventDefault() // let the parent decide (it unmounts the dialog)
        onClose()
      }}
      onClick={(event) => {
        if (event.target === dialog.current) onClose() // the backdrop, not the content
      }}
      className={cn(
        'fixed inset-0 m-auto w-[calc(100%_-_2rem)] max-w-md rounded-2xl border bg-card p-0 text-foreground shadow-pop backdrop:bg-slate-950/40',
        className,
      )}
    >
      <div className="p-6">
        {Icon && (
          <span className="mb-4 flex size-11 items-center justify-center rounded-xl bg-primary-soft text-primary-soft-foreground">
            <Icon className="size-5" aria-hidden />
          </span>
        )}
        <h2 id={titleId} className="break-words text-lg font-semibold tracking-tight">
          {title}
        </h2>
        {description && (
          <p id={descriptionId} className="mt-2 text-sm leading-6 text-muted-foreground">
            {description}
          </p>
        )}
        {children}
      </div>
    </dialog>
  )
}
