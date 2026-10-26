import { apiClient } from './client'
import type { WorkflowResponse } from './workflows'

export interface Sample {
  id: string
  name: string
  description: string
}

export async function listSamples(): Promise<Sample[]> {
  return (await apiClient.get<Sample[]>('/samples')).data
}

/** Creates a DRAFT workflow from a sample; `credentialId` (optional) is stored on its AI Model node. */
export async function createSample(id: string, credentialId?: string): Promise<WorkflowResponse> {
  return (await apiClient.post<WorkflowResponse>(`/samples/${id}/create`, credentialId ? { credentialId } : undefined)).data
}
