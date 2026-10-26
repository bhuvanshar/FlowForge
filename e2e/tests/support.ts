import { createHmac } from 'node:crypto'
import { execSync } from 'node:child_process'

import { expect, type APIRequestContext } from '@playwright/test'

/** Shared helpers: workflow JSON builders, API calls through the frontend's /api proxy, and the mock's request log. */

export const MOCK_URL = process.env.E2E_MOCK_URL ?? 'http://127.0.0.1:28090'
/** How the backend reaches the mock on the compose network (the only allowlisted outbound host). */
export const MOCK_INTERNAL = 'http://mock-services'

export type Json = Record<string, unknown>

export interface WorkflowNode {
  id: string
  type: string
  typeVersion: number
  name: string
  position: { x: number; y: number }
  configuration: Json
}

let column = 0
export function node(id: string, type: string, configuration: Json = {}, name = id): WorkflowNode {
  return { id, type, typeVersion: 1, name, position: { x: 260 * column++, y: 0 }, configuration }
}

export function edge(source: string, target: string, sourceHandle = 'main') {
  return { id: `${source}-${sourceHandle}-${target}`, source, sourceHandle, target, targetHandle: 'main' }
}

export function definition(nodes: WorkflowNode[], edges: ReturnType<typeof edge>[]) {
  return { schemaVersion: 1, nodes, edges }
}

export function httpNode(id: string, url: string, extra: Json = {}) {
  return node(id, 'core.httpRequest', { method: 'GET', url, headers: [], query: [], timeoutSeconds: 10, ...extra })
}

export async function ok<T = Json>(response: Promise<import('@playwright/test').APIResponse>, status?: number): Promise<T> {
  const res = await response
  const text = await res.text()
  if (status !== undefined) expect(res.status(), text).toBe(status)
  else expect(res.ok(), `${res.status()} ${text}`).toBeTruthy()
  return (text ? JSON.parse(text) : undefined) as T
}

export class Api {
  private readonly created: string[] = []
  private readonly credentials: string[] = []

  constructor(private readonly request: APIRequestContext) {}

  async createWorkflow(name: string, def: ReturnType<typeof definition>): Promise<Json & { id: string }> {
    const workflow = await ok<Json & { id: string }>(this.request.post('/api/v1/workflows', { data: { name, definition: def } }), 201)
    this.created.push(workflow.id)
    return workflow
  }

  get<T = Json>(path: string) {
    return ok<T>(this.request.get(`/api/v1${path}`))
  }

  post<T = Json>(path: string, data?: unknown, status?: number) {
    return ok<T>(this.request.post(`/api/v1${path}`, data === undefined ? {} : { data }), status)
  }

  put<T = Json>(path: string, data: unknown, status?: number) {
    return ok<T>(this.request.put(`/api/v1${path}`, { data }), status)
  }

  /** Replaces the saved definition (optimistic locking: uses the current lockVersion). */
  async updateDefinition(id: string, change: (def: ReturnType<typeof definition>) => void) {
    const current = await this.get<{ name: string; lockVersion: number; definition: ReturnType<typeof definition> }>(`/workflows/${id}`)
    change(current.definition)
    return this.put(`/workflows/${id}`, { name: current.name, definition: current.definition, lockVersion: current.lockVersion })
  }

  /** Registers a workflow created elsewhere (e.g. through the UI) for cleanup. */
  track(workflowId: string) {
    this.created.push(workflowId)
  }

  async createCredential(body: Json): Promise<string> {
    const credential = await this.post<{ id: string }>('/credentials', body, 201)
    this.credentials.push(credential.id)
    return credential.id
  }

  async execute(workflowId: string, input?: Json): Promise<string> {
    const started = await this.post<{ executionId: string }>(`/workflows/${workflowId}/execute`, input === undefined ? undefined : { input }, 202)
    return started.executionId
  }

  async waitForExecution(executionId: string, timeoutMs = 30_000): Promise<Execution> {
    let execution: Execution | undefined
    await expect.poll(async () => {
      execution = await this.get<Execution>(`/executions/${executionId}`)
      return ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(execution.status)
    }, { timeout: timeoutMs, intervals: [100, 250, 500] }).toBe(true)
    return execution!
  }

  async run(workflowId: string, input?: Json) {
    return this.waitForExecution(await this.execute(workflowId, input))
  }

  async executions(workflowId: string) {
    return (await this.get<{ items: ExecutionSummary[] }>(`/workflows/${workflowId}/executions?size=100`)).items
  }

  /** Archive then permanently delete everything this test created, so scenarios do not see each other's data. */
  async cleanup() {
    for (const id of this.created.splice(0)) {
      await this.request.post(`/api/v1/workflows/${id}/deactivate`)
      await this.request.delete(`/api/v1/workflows/${id}`)
      await this.request.delete(`/api/v1/workflows/${id}?permanent=true`)
    }
    for (const id of this.credentials.splice(0)) await this.request.delete(`/api/v1/credentials/${id}`)
  }
}

export interface NodeRun {
  nodeId: string
  status: string
  output?: Json
  error?: { code: string; message: string }
}

export interface Execution {
  id: string
  status: string
  triggerType: string
  scheduledFor?: string
  error?: { code: string; message: string }
  nodes: NodeRun[]
  logs: { nodeId?: string; level: string; message: string }[]
}

export interface ExecutionSummary {
  id: string
  status: string
  triggerType: string
  scheduledFor?: string
}

export function nodeRun(execution: Execution, nodeId: string): NodeRun {
  const run = execution.nodes.find((n) => n.nodeId === nodeId)
  expect(run, `node ${nodeId} in ${JSON.stringify(execution.nodes.map((n) => n.nodeId))}`).toBeDefined()
  return run!
}

export interface MockRequest {
  method: string
  path: string
  query: Record<string, string>
  headers: Record<string, string>
  body: unknown
}

export async function mockRequests(request: APIRequestContext, pathPrefix: string): Promise<MockRequest[]> {
  return ok<MockRequest[]>(request.get(`${MOCK_URL}/_requests?path=${encodeURIComponent(pathPrefix)}`))
}

export async function resetMock(request: APIRequestContext) {
  await ok(request.delete(`${MOCK_URL}/_requests`), 204)
}

export function sign(secret: string, timestamp: number, body: string) {
  return `sha256=${createHmac('sha256', secret).update(`${timestamp}.${body}`).digest('hex')}`
}

/** Runs a docker compose subcommand against the e2e project (scripts/e2e.sh passes the full command). */
export function compose(args: string) {
  const base = process.env.E2E_COMPOSE
  if (!base) throw new Error('E2E_COMPOSE is not set; run the suite through scripts/e2e.sh')
  execSync(`${base} ${args}`, { stdio: 'inherit', timeout: 240_000 })
}

/** A unique, recognisable suffix for names created by one test. */
export function unique(label: string) {
  return `${label} ${Date.now().toString(36)}`
}
