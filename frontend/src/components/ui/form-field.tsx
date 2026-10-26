import { useId } from 'react'
import type * as React from 'react'

import { cn } from '@/lib/utils'

const CONTROL =
  'h-10 w-full rounded-lg border bg-card px-3 text-sm placeholder:text-muted-foreground aria-[invalid=true]:border-destructive disabled:opacity-60'

interface FieldChrome {
  label: string
  hint?: string
  error?: string
}

function describedBy(id: string, hint?: string, error?: string) {
  return [hint ? `${id}-hint` : '', error ? `${id}-error` : ''].filter(Boolean).join(' ') || undefined
}

function Chrome({ id, label, hint, error, children }: FieldChrome & { id: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-xs font-semibold">
        {label}
      </label>
      {children}
      {hint && (
        <p id={`${id}-hint`} className="text-xs text-muted-foreground">
          {hint}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} role="alert" className="text-xs text-destructive-foreground">
          {error}
        </p>
      )}
    </div>
  )
}

/** Labelled text input with hint and error text wired up for assistive technology. */
export function TextField({ label, hint, error, className, ref, ...props }: FieldChrome & React.ComponentProps<'input'>) {
  const id = useId()
  return (
    <Chrome id={id} label={label} hint={hint} error={error}>
      <input
        id={id}
        ref={ref}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(id, hint, error)}
        className={cn(CONTROL, className)}
        {...props}
      />
    </Chrome>
  )
}

export function TextAreaField({ label, hint, error, className, ref, ...props }: FieldChrome & React.ComponentProps<'textarea'>) {
  const id = useId()
  return (
    <Chrome id={id} label={label} hint={hint} error={error}>
      <textarea
        id={id}
        ref={ref}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(id, hint, error)}
        className={cn(CONTROL, 'h-auto min-h-20 py-2', className)}
        {...props}
      />
    </Chrome>
  )
}

export function SelectField({
  label, hint, error, className, children, ref, ...props
}: FieldChrome & React.ComponentProps<'select'>) {
  const id = useId()
  return (
    <Chrome id={id} label={label} hint={hint} error={error}>
      <select
        id={id}
        ref={ref}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(id, hint, error)}
        className={cn(CONTROL, className)}
        {...props}
      >
        {children}
      </select>
    </Chrome>
  )
}
