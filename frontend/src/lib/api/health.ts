import { apiClient } from './client'

export type ComponentStatus = 'UP' | 'DOWN'

export interface HealthResponse {
  status: ComponentStatus
  version: string
  timestamp: string
  components: Record<string, ComponentStatus>
}

export async function getHealth(): Promise<HealthResponse> {
  // The backend answers 503 with the same body shape when a component is down; surface that body.
  const response = await apiClient.get<HealthResponse>('/health', {
    validateStatus: (status) => status === 200 || status === 503,
  })
  return response.data
}
