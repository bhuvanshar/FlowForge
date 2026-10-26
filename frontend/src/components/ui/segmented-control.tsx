import type * as React from 'react'

import { cn } from '@/lib/utils'

export interface SegmentOption<T extends string> {
  value: T
  label: string
  /** Accessible name when the visible content is only an icon. */
  ariaLabel?: string
  icon?: React.ReactNode
}

/** Pick one of a few options. Plain toggle buttons (aria-pressed) in a labelled group: every key works as expected. */
export function SegmentedControl<T extends string>({
  label, value, options, onChange, variant = 'pill', className,
}: {
  label: string
  value: T
  options: SegmentOption<T>[]
  onChange: (value: T) => void
  /** `pill`: filled active segment (tabs, filters). `icon`: compact boxed icon switch. */
  variant?: 'pill' | 'icon'
  className?: string
}) {
  return (
    <div
      role="group"
      aria-label={label}
      className={cn(variant === 'icon' ? 'flex rounded-lg border bg-card p-1' : 'flex max-w-full gap-1 overflow-x-auto', className)}
    >
      {options.map((o) => {
        const active = o.value === value
        return (
          <button
            key={o.value}
            type="button"
            aria-pressed={active}
            aria-label={o.ariaLabel}
            onClick={() => onChange(o.value)}
            className={cn(
              'whitespace-nowrap transition-colors',
              variant === 'icon'
                ? cn('rounded p-1.5', active ? 'bg-muted text-foreground' : 'text-muted-foreground hover:bg-muted')
                : cn('rounded-lg px-3 py-2 text-xs font-medium', active
                    ? 'bg-foreground text-background'
                    : 'text-muted-foreground hover:bg-muted hover:text-foreground'),
            )}
          >
            {o.icon}
            {variant === 'pill' && o.label}
          </button>
        )
      })}
    </div>
  )
}
