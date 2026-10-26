import type * as React from 'react'

import { cn } from '@/lib/utils'

import { TONE_CLASSES, type Tone } from './tones'

export interface BadgeProps extends React.ComponentProps<'span'> {
  tone?: Tone
  /** Show a coloured dot before the label (a second, non-colour cue is always the label text). */
  dot?: boolean
}

export function Badge({ tone = 'neutral', dot = false, className, children, ...props }: BadgeProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2.5 py-0.5 text-[11px] font-semibold ring-1 ring-inset',
        TONE_CLASSES[tone],
        className,
      )}
      {...props}
    >
      {dot && <span aria-hidden className="size-1.5 rounded-full bg-current" />}
      {children}
    </span>
  )
}
