import { cn } from '@/lib/utils'

import { useBackendHealth, type ConnectivityState } from './useBackendHealth'

const LABELS: Record<ConnectivityState, string> = {
  checking: 'Checking backend…',
  connected: 'Backend connected',
  degraded: 'Backend degraded',
  unreachable: 'Backend unreachable',
}

const DOT_CLASSES: Record<ConnectivityState, string> = {
  checking: 'bg-muted-foreground animate-pulse',
  connected: 'bg-success',
  degraded: 'bg-amber-500',
  unreachable: 'bg-destructive',
}

export function ConnectivityIndicator() {
  const { state, health } = useBackendHealth()
  return (
    <div
      role="status"
      aria-live="polite"
      data-state={state}
      title={health ? `Version ${health.version}` : undefined}
      className="flex items-center gap-2 text-[11px] text-muted-foreground"
    >
      <span aria-hidden className={cn('size-2.5 rounded-full', DOT_CLASSES[state])} />
      <span>{LABELS[state]}</span>
    </div>
  )
}
