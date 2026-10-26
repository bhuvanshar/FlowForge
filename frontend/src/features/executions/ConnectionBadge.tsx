import { cn } from '@/lib/utils'

import type { Connection } from './useLiveExecution'

const LABELS: Partial<Record<Connection, { text: string; dot: string; title: string }>> = {
  connecting: { text: 'Connecting…', dot: 'bg-muted-foreground animate-pulse', title: 'Opening the live connection' },
  open: { text: 'Live', dot: 'bg-success', title: 'Updates arrive in real time' },
  reconnecting: {
    text: 'Reconnecting…',
    dot: 'bg-amber-500 animate-pulse',
    title: 'Connection lost. Showing saved results while reconnecting; the current state is restored on reconnect',
  },
  polling: { text: 'Polling', dot: 'bg-amber-500', title: 'Live connection unavailable; refreshing every second' },
}

/** Small indicator of how the displayed execution is kept current. Nothing is shown when no stream is needed. */
export function ConnectionBadge({ connection }: { connection: Connection }) {
  const label = LABELS[connection]
  if (!label) return null
  return (
    <span
      data-testid="connection-badge"
      data-connection={connection}
      title={label.title}
      className="inline-flex items-center gap-1 text-xs font-normal normal-case tracking-normal text-muted-foreground"
    >
      <span aria-hidden className={cn('size-2 rounded-full', label.dot)} />
      {label.text}
    </span>
  )
}
