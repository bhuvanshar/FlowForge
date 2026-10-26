import openApi from '../../../../docs/api/openapi.json'

import type {
  EdgeDefinition,
  EditorMetadata,
  ExecutionStatus,
  NodeDefinition,
  NodeExecutionStatus,
  NodePosition,
  Viewport,
  WorkflowDefinition,
  WorkflowStatus,
} from '@/lib/workflow/definition'

import type { CreateCredentialRequest, Credential, CredentialUsage, EncryptionStatus, UpdateCredentialRequest } from './credentials'
import type { HealthResponse } from './health'
import type { Sample } from './samples'
import type { ServerSettings } from './settings'
import type { ScheduleTriggerInfo, TriggersInfo, WebhookTriggerInfo } from './triggers'
import type {
  CreateWorkflowRequest,
  ExecutionDetail,
  ExecutionError,
  ExecutionSummary,
  LastExecution,
  LogLine,
  NodeRun,
  PageResponse,
  TriggerType,
  UpdateWorkflowRequest,
  ValidationIssue,
  ValidationResult,
  WorkflowResponse,
  WorkflowSummary,
} from './workflows'

/**
 * The frontend half of the API contract. docs/api/openapi.json is generated from the running backend and checked by
 * OpenApiContractIT; this test checks the TypeScript client against the same file:
 *  - every request the client sends goes to an operation the backend publishes;
 *  - every field a TypeScript response type declares exists in the backend schema (the `satisfies` lists below fail
 *    type checking when a type gains or loses a field, so they cannot drift from the types);
 *  - request types send only known fields and include every field the backend requires;
 *  - status enums match exactly.
 */

interface Schema {
  type?: string
  properties?: Record<string, Schema>
  required?: string[]
  enum?: string[]
  items?: Schema
  $ref?: string
}
interface OpenApi {
  paths: Record<string, Record<string, { requestBody?: { content: Record<string, { schema: Schema }> } }>>
  components: { schemas: Record<string, Schema> }
}

const spec = openApi as unknown as OpenApi
const sources = import.meta.glob(['./*.ts', '../../features/executions/eventSource.ts', '!./*.test.ts'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

function schema(name: string): Schema {
  const found = spec.components.schemas[name]
  if (!found) throw new Error(`schema ${name} is not in docs/api/openapi.json`)
  return found
}

function deref(s: Schema): Schema {
  return s.$ref ? schema(s.$ref.replace('#/components/schemas/', '')) : s
}

function property(owner: string, name: string): Schema {
  return deref(schema(owner).properties?.[name] ?? {})
}

type Keys<T> = Record<keyof T, true>
const keys = (fields: object) => Object.keys(fields).sort()

function expectFieldsKnown(schemaName: string, fields: object) {
  const published = Object.keys(schema(schemaName).properties ?? {})
  const unknown = keys(fields).filter((field) => !published.includes(field))
  expect(unknown, `${schemaName}: fields the frontend reads but the backend does not publish`).toEqual([])
}

function expectRequestMatches(schemaName: string, fields: object) {
  expectFieldsKnown(schemaName, fields)
  const missing = (schema(schemaName).required ?? []).filter((field) => !keys(fields).includes(field))
  expect(missing, `${schemaName}: required fields the frontend type cannot send`).toEqual([])
}

function requestBodySchema(method: string, path: string): string {
  const content = spec.paths[path]?.[method]?.requestBody?.content ?? {}
  const ref = Object.values(content)[0]?.schema.$ref
  if (!ref) throw new Error(`${method.toUpperCase()} ${path} has no request body schema`)
  return ref.replace('#/components/schemas/', '')
}

/** `/workflows/${id}/execute` and `/api/v1/workflows/{id}/execute` both become `/workflows/{}/execute`. */
const normalize = (path: string) => path.replace(/^\/api\/v1/, '').replace(/\$\{[^}]+\}|\{[^}]+\}/g, '{}')

function clientCalls(): string[] {
  const calls = new Set<string>()
  for (const [file, text] of Object.entries(sources)) {
    for (const match of text.matchAll(/apiClient\.(get|post|put|patch|delete)(?:<[^(]*>)?\(\s*['`]([^'`]+)['`]/g)) {
      calls.add(`${match[1]} ${normalize(match[2])}`)
    }
    for (const match of text.matchAll(/new EventSource\(`\$\{base\}([^`]+)`\)/g)) {
      calls.add(`get ${normalize(match[1])}`)
    }
    if (file.endsWith('eventSource.ts')) expect(text).toContain('new EventSource(')
  }
  return [...calls].sort()
}

describe('API contract (docs/api/openapi.json)', () => {
  it('every request the client sends is a published operation', () => {
    const published = new Set(
      Object.entries(spec.paths).flatMap(([path, operations]) =>
        Object.keys(operations).map((method) => `${method} ${normalize(path)}`),
      ),
    )
    const calls = clientCalls()
    expect(calls.length).toBeGreaterThanOrEqual(28)
    expect(calls.filter((call) => !published.has(call))).toEqual([])
  })

  it('workflow and definition types match the backend schemas', () => {
    expectFieldsKnown('WorkflowResponse', {
      id: true, name: true, description: true, status: true, version: true, lockVersion: true, definition: true,
      createdAt: true, updatedAt: true,
    } satisfies Keys<WorkflowResponse>)
    expectFieldsKnown('WorkflowSummaryResponse', {
      id: true, name: true, description: true, status: true, version: true, lockVersion: true, nodeCount: true,
      edgeCount: true, createdAt: true, updatedAt: true, lastExecution: true,
    } satisfies Keys<WorkflowSummary>)
    expectFieldsKnown('LastExecution', {
      id: true, status: true, triggerType: true, createdAt: true, finishedAt: true,
    } satisfies Keys<LastExecution>)
    expectFieldsKnown('PageResponseWorkflowSummaryResponse', {
      items: true, page: true, size: true, totalElements: true, totalPages: true,
    } satisfies Keys<PageResponse<unknown>>)
    expectFieldsKnown('WorkflowDefinition', {
      schemaVersion: true, nodes: true, edges: true, editor: true,
    } satisfies Keys<WorkflowDefinition>)
    expectRequestMatches('NodeDefinition', {
      id: true, type: true, typeVersion: true, name: true, position: true, configuration: true,
    } satisfies Keys<NodeDefinition>)
    expectRequestMatches('EdgeDefinition', {
      id: true, source: true, sourceHandle: true, target: true, targetHandle: true,
    } satisfies Keys<EdgeDefinition>)
    expectFieldsKnown('NodePosition', { x: true, y: true } satisfies Keys<NodePosition>)
    expectFieldsKnown('EditorMetadata', { viewport: true } satisfies Keys<EditorMetadata>)
    expectFieldsKnown('Viewport', { x: true, y: true, zoom: true } satisfies Keys<Viewport>)
    expectFieldsKnown('ValidationResultResponse', {
      valid: true, errorCount: true, warningCount: true, issues: true, executionOrder: true,
    } satisfies Keys<ValidationResult>)
    expectFieldsKnown('Issue', {
      code: true, severity: true, message: true, nodeId: true, edgeId: true, path: true,
    } satisfies Keys<ValidationIssue>)
  })

  it('workflow request types send what the backend accepts and requires', () => {
    const create = { name: true, description: true, definition: true } satisfies Keys<CreateWorkflowRequest>
    expect(requestBodySchema('post', '/api/v1/workflows')).toBe('CreateWorkflowRequest')
    expectRequestMatches('CreateWorkflowRequest', create)
    const update = {
      name: true, description: true, status: true, definition: true, lockVersion: true,
    } satisfies Keys<UpdateWorkflowRequest>
    expect(requestBodySchema('put', '/api/v1/workflows/{id}')).toBe('UpdateWorkflowRequest')
    expectRequestMatches('UpdateWorkflowRequest', update)
    // validate({ definition }), execute({ input }) and samples create({ credentialId }) bodies
    expectRequestMatches(requestBodySchema('post', '/api/v1/workflows/{id}/validate'), { definition: true })
    expectRequestMatches(requestBodySchema('post', '/api/v1/workflows/{id}/execute'), { input: true })
    expectRequestMatches(requestBodySchema('post', '/api/v1/samples/{id}/create'), { credentialId: true })
  })

  it('execution types match the backend schemas', () => {
    expectFieldsKnown('ExecutionResponse', {
      id: true, workflowId: true, workflowVersion: true, status: true, triggerType: true, scheduledFor: true,
      error: true, createdAt: true, startedAt: true, finishedAt: true, durationMs: true, nodes: true, logs: true,
    } satisfies Keys<ExecutionDetail>)
    expectFieldsKnown('ExecutionSummaryResponse', {
      id: true, workflowId: true, workflowVersion: true, status: true, triggerType: true, scheduledFor: true,
      error: true, createdAt: true, startedAt: true, finishedAt: true, durationMs: true, nodeCount: true,
      succeededNodes: true, failedNodes: true, skippedNodes: true, failedNodeId: true, failedNodeName: true,
    } satisfies Keys<ExecutionSummary>)
    expectFieldsKnown('PageResponseExecutionSummaryResponse', {
      items: true, page: true, size: true, totalElements: true, totalPages: true,
    } satisfies Keys<PageResponse<unknown>>)
    expectFieldsKnown('NodeRun', {
      nodeId: true, nodeName: true, nodeType: true, sequence: true, status: true, input: true, output: true,
      error: true, startedAt: true, finishedAt: true, durationMs: true,
    } satisfies Keys<NodeRun>)
    expectFieldsKnown('LogLine', { nodeId: true, level: true, message: true, at: true } satisfies Keys<LogLine>)
    expectFieldsKnown('Error', { code: true, message: true } satisfies Keys<ExecutionError>)
    expect(Object.keys(schema('ExecutionStartedResponse').properties ?? {})).toContain('executionId')
  })

  it('status enums are identical on both sides', () => {
    const workflowStatus = { DRAFT: true, ACTIVE: true, INACTIVE: true, ARCHIVED: true } satisfies Record<WorkflowStatus, true>
    const executionStatus = {
      QUEUED: true, RUNNING: true, SUCCEEDED: true, FAILED: true, CANCELLED: true,
    } satisfies Record<ExecutionStatus, true>
    const nodeStatus = {
      PENDING: true, RUNNING: true, SUCCEEDED: true, FAILED: true, SKIPPED: true,
    } satisfies Record<NodeExecutionStatus, true>
    const triggerType = { MANUAL: true, WEBHOOK: true, SCHEDULE: true } satisfies Record<TriggerType, true>

    expect(property('WorkflowResponse', 'status').enum?.slice().sort()).toEqual(keys(workflowStatus))
    expect(property('WorkflowSummaryResponse', 'status').enum?.slice().sort()).toEqual(keys(workflowStatus))
    expect(property('ExecutionResponse', 'status').enum?.slice().sort()).toEqual(keys(executionStatus))
    expect(property('LastExecution', 'status').enum?.slice().sort()).toEqual(keys(executionStatus))
    expect(property('NodeRun', 'status').enum?.slice().sort()).toEqual(keys(nodeStatus))
    expect(property('ExecutionResponse', 'triggerType').enum?.slice().sort()).toEqual(keys(triggerType))
    expect(property('Issue', 'severity').enum?.slice().sort()).toEqual(['ERROR', 'WARNING'])
  })

  it('credential types match and never declare a secret in responses', () => {
    const credential = {
      id: true, name: true, type: true, description: true, allowedHosts: true, fields: true, secretFields: true,
      secretVersion: true, createdAt: true, updatedAt: true, rotatedAt: true, lastUsedAt: true, lockVersion: true,
    } satisfies Keys<Credential>
    expectFieldsKnown('CredentialResponse', credential)
    expect(Object.keys(schema('CredentialResponse').properties ?? {})).not.toContain('secret')

    const create = {
      type: true, name: true, description: true, allowedHosts: true, fields: true, secret: true,
    } satisfies Keys<CreateCredentialRequest>
    expectRequestMatches(requestBodySchema('post', '/api/v1/credentials'), create)
    const update = {
      name: true, description: true, allowedHosts: true, fields: true, lockVersion: true,
    } satisfies Keys<UpdateCredentialRequest>
    expectRequestMatches(requestBodySchema('put', '/api/v1/credentials/{id}'), update)
    expectRequestMatches(requestBodySchema('post', '/api/v1/credentials/{id}/rotate'), { secret: true })

    expectFieldsKnown('Usage', { id: true, name: true, status: true } satisfies Keys<CredentialUsage>)
    expectFieldsKnown('UsageResponse', { workflows: true })
    expectFieldsKnown('EncryptionStatus', {
      available: true, activeKeyId: true, credentialsOnOldKeys: true, webhookSecretsToEncrypt: true,
    } satisfies Keys<EncryptionStatus>)
  })

  it('trigger, sample, settings and health types match', () => {
    expectFieldsKnown('TriggersResponse', {
      workflowId: true, status: true, triggerType: true, webhook: true, schedule: true,
    } satisfies Keys<TriggersInfo>)
    expectFieldsKnown('Webhook', {
      webhookId: true, path: true, nodeId: true, method: true, authentication: true, inputMode: true, active: true,
      secretHint: true,
    } satisfies Keys<WebhookTriggerInfo>)
    expectFieldsKnown('Schedule', {
      nodeId: true, cron: true, timezone: true, missedRunPolicy: true, active: true, nextFireAt: true, lastFireAt: true,
      lastExecutionId: true, lastMissedAt: true, lastError: true,
    } satisfies Keys<ScheduleTriggerInfo>)
    expectFieldsKnown('WebhookSecretResponse', { secret: true })
    expectFieldsKnown('Sample', { id: true, name: true, description: true } satisfies Keys<Sample>)
    expectFieldsKnown('HealthResponse', {
      status: true, version: true, timestamp: true, components: true,
    } satisfies Keys<HealthResponse>)

    const settings = {
      application: true, execution: true, outbound: true, triggers: true, credentials: true, ai: true, security: true,
    } satisfies Keys<ServerSettings>
    expectFieldsKnown('Settings', settings)
    for (const section of Object.keys(settings) as (keyof ServerSettings)[]) {
      const sectionSchema = schema('Settings').properties?.[section]
      expect(sectionSchema?.$ref, `Settings.${section}`).toBeDefined()
    }
    expectFieldsKnown('Execution', {
      workerThreads: true, queueCapacity: true, nodeTimeoutSeconds: true, maxDurationSeconds: true,
      maxActivePerWorkflow: true, maxStoredPayloadBytes: true,
    } satisfies Keys<ServerSettings['execution']>)
    expectFieldsKnown('Outbound', {
      allowedHosts: true, allowPrivateAddresses: true, allowedPorts: true, maxResponseBytes: true, maxRequestBytes: true,
      maxRedirects: true,
    } satisfies Keys<ServerSettings['outbound']>)
    expectFieldsKnown('Triggers', {
      schedulerEnabled: true, schedulerPollSeconds: true, allowUnauthenticatedWebhooks: true, webhookMaxBodyBytes: true,
    } satisfies Keys<ServerSettings['triggers']>)
    expectFieldsKnown('Credentials', {
      encryptionAvailable: true, activeKeyId: true, keyCount: true,
    } satisfies Keys<ServerSettings['credentials']>)
    expectFieldsKnown('Ai', { maxPromptChars: true, maxResponseBytes: true } satisfies Keys<ServerSettings['ai']>)
    expectFieldsKnown('Security', {
      allowedHostNames: true, allowedOrigins: true, maxRequestBytes: true,
    } satisfies Keys<ServerSettings['security']>)
    expectFieldsKnown('Application', { version: true } satisfies Keys<ServerSettings['application']>)
  })
})
