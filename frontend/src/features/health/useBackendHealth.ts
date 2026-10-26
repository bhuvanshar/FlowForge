import { useQuery } from '@tanstack/react-query'

import { getHealth } from '@/lib/api/health'

export const HEALTH_POLL_INTERVAL_MS = 15_000

export type ConnectivityState = 'checking' | 'connected' | 'degraded' | 'unreachable'

export function useBackendHealth() {
  const query = useQuery({
    queryKey: ['health'],
    queryFn: getHealth,
    refetchInterval: HEALTH_POLL_INTERVAL_MS,
    retry: false,
  })

  let state: ConnectivityState = 'checking'
  if (query.isError) state = 'unreachable'
  else if (query.data) state = query.data.status === 'UP' ? 'connected' : 'degraded'

  return { state, health: query.data }
}
