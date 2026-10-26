import { AlertCircle, CheckCircle2, Info, X } from 'lucide-react'
import type * as React from 'react'

import { cn } from '@/lib/utils'

import { TONE_CLASSES, type Tone } from './tones'

type NoticeTone = Extract<Tone, 'success' | 'danger' | 'info' | 'warning'>

const ICONS = { success: CheckCircle2, danger: AlertCircle, info: Info, warning: AlertCircle } as const

export interface NoticeProps {
  tone?: NoticeTone
  /** Rendered as role="alert" for danger (announced at once) and role="status" otherwise. */
  children: React.ReactNode
  onDismiss?: () => void
  action?: React.ReactNode
  className?: string
}

/** An inline message about what just happened. */
export function Notice({ tone = 'info', children, onDismiss, action, className }: NoticeProps) {
  const Icon = ICONS[tone]
  return (
    <div
      role={tone === 'danger' ? 'alert' : 'status'}
      className={cn('flex items-start gap-3 rounded-xl border px-4 py-3 text-sm ring-0', TONE_CLASSES[tone], className)}
    >
      <Icon className="mt-0.5 size-4 shrink-0" aria-hidden />
      <div className="min-w-0 flex-1">{children}</div>
      {action}
      {onDismiss && (
        <button type="button" aria-label="Dismiss message" onClick={onDismiss} className="rounded p-0.5 hover:bg-black/5">
          <X className="size-4" aria-hidden />
        </button>
      )}
    </div>
  )
}
