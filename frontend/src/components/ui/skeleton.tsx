import type * as React from 'react'

import { cn } from '@/lib/utils'

/** Decorative placeholder for content that is loading. Pair it with a visible or screen-reader-only status message. */
export function Skeleton({ className, ...props }: React.ComponentProps<'div'>) {
  return <div aria-hidden className={cn('skeleton rounded-md', className)} {...props} />
}
