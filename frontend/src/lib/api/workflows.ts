import type {
  ExecutionStatus,
  NodeExecutionStatus,
  WorkflowDefinition,
  WorkflowStatus,
} from '@/lib/workflow/definition'

import { apiClient } from './client'

export interface WorkflowResponse {
  id: string
  name: string
  description?: string
  status: WorkflowStatus
  version: number
  lockVersion: number
  definition: WorkflowDefinition
  createdAt: string
  updatedAt: string
}

/** The newest execution of a workflow, of any kind. `finishedAt` is absent while it is queued or running. */
export interface LastExecution {
  id: string
  status: ExecutionStatus
  triggerType: TriggerType
  createdAt: string
  finishedAt?: string
}

export interface WorkflowSummary {
  id: string
  name: string
  description?: string
  status: WorkflowStatus
  version: number
  lockVersion: number
  nodeCount: number
  edgeCount: number
  createdAt: string
  updatedAt: string
  /** Absent for workflows that never ran (and in responses from servers before Module 14). */
  lastExecution?: LastExecution
}

export interface PageResponse<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface ListWorkflowsParams {
  name?: string
  status?: WorkflowStatus[]
  page?: number
  size?: number
  sort?: string
}

export interface CreateWorkflowRequest {
  name: string
  description?: string
  definition?: WorkflowDefinition
}

/** `lockVersion` must be the value from the last read; a stale value yields 409. */
export interface UpdateWorkflowRequest {
  name: string
  description?: string
  status?: WorkflowStatus
  definition: WorkflowDefinition
  lockVersion: number
}

export type ValidationSeverity = 'ERROR' | 'WARNING'

/** One finding from the backend graph validator. `code` values are stable (see docs/API_CONTRACTS.md). */
export interface ValidationIssue {
  code: string
  severity: ValidationSeverity
  message: string
  nodeId?: string
  edgeId?: string
  /** Location in the definition JSON, e.g. `nodes[2].configuration.url`. */
  path?: string
}

export interface ValidationResult {
  valid: boolean
  errorCount: number
  warningCount: number
  issues: ValidationIssue[]
  executionOrder: string[]
}

export async function listWorkflows(params: ListWorkflowsParams = {}): Promise<PageResponse<WorkflowSummary>> {
  const response = await apiClient.get<PageResponse<WorkflowSummary>>('/workflows', {
    params,
    paramsSerializer: { indexes: null }, // status=A&status=B
  })
  return response.data
}

export async function getWorkflow(id: string): Promise<WorkflowResponse> {
  return (await apiClient.get<WorkflowResponse>(`/workflows/${id}`)).data
}

export async function createWorkflow(request: CreateWorkflowRequest): Promise<WorkflowResponse> {
  return (await apiClient.post<WorkflowResponse>('/workflows', request)).data
}

export async function duplicateWorkflow(id: string): Promise<WorkflowResponse> {
  return (await apiClient.post<WorkflowResponse>(`/workflows/${id}/duplicate`)).data
}

/** Soft deletion preserves execution history. */
export async function archiveWorkflow(id: string): Promise<void> {
  await apiClient.delete(`/workflows/${id}`)
}

/** Hard delete of an already archived workflow, including its execution history. Cannot be undone. */
export async function deleteWorkflowPermanently(id: string): Promise<void> {
  await apiClient.delete(`/workflows/${id}`, { params: { permanent: true } })
}

/** Renames with the latest definition and lock version, so only the name and description change. */
export async function renameWorkflow(id: string, name: string, description?: string): Promise<WorkflowResponse> {
  const current = await getWorkflow(id)
  return updateWorkflow(id, { name, description, definition: current.definition, lockVersion: current.lockVersion })
}

/** Restore with the latest definition and optimistic lock; automatic triggers stay stopped. */
export async function restoreWorkflow(id: string): Promise<WorkflowResponse> {
  const current = await getWorkflow(id)
  if (current.status !== 'ARCHIVED') return current
  return updateWorkflow(id, {
    name: current.name, description: current.description, definition: current.definition,
    lockVersion: current.lockVersion, status: 'DRAFT',
  })
}

export async function updateWorkflow(id: string, request: UpdateWorkflowRequest): Promise<WorkflowResponse> {
  return (await apiClient.put<WorkflowResponse>(`/workflows/${id}`, request)).data
}

/** Validates `definition` (e.g. the unsaved canvas) in the context of workflow `id`; the stored one when omitted. */
export async function validateWorkflow(id: string, definition?: WorkflowDefinition): Promise<ValidationResult> {
  return (await apiClient.post<ValidationResult>(`/workflows/${id}/validate`, definition ? { definition } : undefined)).data
}

// ---------------------------------------------------------------------------------------------------------------------
// Executions
// ---------------------------------------------------------------------------------------------------------------------

export interface ExecutionError {
  code: string
  message: string
}

export interface NodeRun {
  nodeId: string
  /** Name in the snapshot this execution ran, unaffected by later edits. */
  nodeName: string
  nodeType: string
  sequence: number
  status: NodeExecutionStatus
  /** May be `{ _truncated: true, originalSizeBytes, preview }` for large payloads; credentials are redacted. */
  input?: unknown
  output?: unknown
  error?: ExecutionError
  startedAt?: string
  finishedAt?: string
  durationMs?: number
}

export interface LogLine {
  nodeId?: string
  level: string
  message: string
  at: string
}

/** How an execution was started (Module 11). */
export type TriggerType = 'MANUAL' | 'WEBHOOK' | 'SCHEDULE'

export interface ExecutionDetail {
  id: string
  workflowId: string
  workflowVersion: number
  status: ExecutionStatus
  /** Absent in responses from servers before Module 11; treat as MANUAL. */
  triggerType?: TriggerType
  /** Schedule occurrence of SCHEDULE runs. */
  scheduledFor?: string
  error?: ExecutionError
  createdAt: string
  startedAt?: string
  finishedAt?: string
  durationMs?: number
  nodes: NodeRun[]
  logs: LogLine[]
}

export interface ExecutionSummary {
  id: string
  workflowId: string
  workflowVersion: number
  status: ExecutionStatus
  triggerType?: TriggerType
  scheduledFor?: string
  error?: ExecutionError
  createdAt: string
  startedAt?: string
  finishedAt?: string
  durationMs?: number
  nodeCount: number
  succeededNodes: number
  failedNodes: number
  skippedNodes: number
  failedNodeId?: string
  failedNodeName?: string
}

export interface ListExecutionsParams {
  status?: ExecutionStatus[]
  page?: number
  size?: number
}

export async function executeWorkflow(id: string, input?: unknown): Promise<{ executionId: string }> {
  return (await apiClient.post<{ executionId: string }>(`/workflows/${id}/execute`, input === undefined ? undefined : { input })).data
}

export async function listExecutions(workflowId: string, params: ListExecutionsParams = {}): Promise<PageResponse<ExecutionSummary>> {
  return (
    await apiClient.get<PageResponse<ExecutionSummary>>(`/workflows/${workflowId}/executions`, {
      params,
      paramsSerializer: { indexes: null },
    })
  ).data
}

export async function getExecution(executionId: string): Promise<ExecutionDetail> {
  return (await apiClient.get<ExecutionDetail>(`/executions/${executionId}`)).data
}

export async function cancelExecution(executionId: string): Promise<ExecutionDetail> {
  return (await apiClient.post<ExecutionDetail>(`/executions/${executionId}/cancel`)).data
}
