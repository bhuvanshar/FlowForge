import type { LucideIcon } from 'lucide-react'
import type * as React from 'react'

import { cn } from '@/lib/utils'

export interface EmptyStateProps {
  icon: LucideIcon
  title: string
  description?: string
  /** Primary action(s), usually a Button. */
  action?: React.ReactNode
  /** Heading level of the title, to fit the page outline. */
  headingLevel?: 2 | 3
  className?: string
}

/** What to show when a list has nothing to list: say why and offer the next step. */
export function EmptyState({ icon: Icon, title, description, action, headingLevel = 2, className }: EmptyStateProps) {
  const Heading = `h${headingLevel}` as 'h2' | 'h3'
  return (
    <div className={cn('rounded-2xl border border-dashed bg-card px-6 py-14 text-center', className)}>
      <span className="mx-auto mb-4 flex size-14 items-center justify-center rounded-2xl bg-primary-soft text-primary-soft-foreground">
        <Icon className="size-6" aria-hidden />
      </span>
      <Heading className="text-lg font-semibold tracking-tight">{title}</Heading>
      {description && <p className="mx-auto mt-2 max-w-sm text-sm leading-6 text-muted-foreground">{description}</p>}
      {action && <div className="mt-5 flex justify-center gap-2">{action}</div>}
    </div>
  )
}
