import type { WorkflowStatus } from '@/lib/workflow/definition'

import { apiClient } from './client'
import type { WorkflowResponse } from './workflows'

/** Webhook registration; `path` is relative to the origin the app is served from (it proxies /api). */
export interface WebhookTriggerInfo {
  webhookId: string
  path: string
  nodeId: string
  method: string
  authentication: 'signature' | 'token' | 'none'
  inputMode: 'body' | 'request'
  active: boolean
  secretHint: string
}

export interface ScheduleTriggerInfo {
  nodeId: string
  cron: string
  timezone: string
  missedRunPolicy: 'skip' | 'runOnce'
  active: boolean
  nextFireAt?: string
  lastFireAt?: string
  lastExecutionId?: string
  lastMissedAt?: string
  lastError?: string
}

export interface TriggersInfo {
  workflowId: string
  status: WorkflowStatus
  triggerType?: string
  webhook?: WebhookTriggerInfo
  schedule?: ScheduleTriggerInfo
}

export async function activateWorkflow(id: string): Promise<WorkflowResponse> {
  return (await apiClient.post<WorkflowResponse>(`/workflows/${id}/activate`)).data
}

export async function deactivateWorkflow(id: string): Promise<WorkflowResponse> {
  return (await apiClient.post<WorkflowResponse>(`/workflows/${id}/deactivate`)).data
}

export async function getTriggers(id: string): Promise<TriggersInfo> {
  return (await apiClient.get<TriggersInfo>(`/workflows/${id}/triggers`)).data
}

export async function getWebhookSecret(id: string): Promise<string> {
  return (await apiClient.get<{ secret: string }>(`/workflows/${id}/triggers/webhook/secret`)).data.secret
}

export async function rotateWebhookSecret(id: string): Promise<string> {
  return (await apiClient.post<{ secret: string }>(`/workflows/${id}/triggers/webhook/secret/rotate`)).data.secret
}
