import type { ExecutionDetail, ExecutionSummary } from '@/lib/api/workflows'

/** A failed run: trigger ok, HTTP node failed, logger skipped. Mirrors the backend response shape. */
export function failedExecution(overrides: Partial<ExecutionDetail> = {}): ExecutionDetail {
  return {
    id: 'exec-1',
    workflowId: 'wf-1',
    workflowVersion: 3,
    status: 'FAILED',
    error: { code: 'HTTP_STATUS_ERROR', message: "Node 'Call API' failed: GET http://api/x answered 503." },
    createdAt: '2026-01-01T10:00:00Z',
    startedAt: '2026-01-01T10:00:00Z',
    finishedAt: '2026-01-01T10:00:01.250Z',
    durationMs: 1250,
    nodes: [
      {
        nodeId: 't', nodeName: 'Start', nodeType: 'core.manualTrigger', sequence: 0, status: 'SUCCEEDED',
        input: { order: 7, password: '[REDACTED]' }, output: { order: 7, password: '[REDACTED]' }, durationMs: 4,
      },
      {
        nodeId: 'call', nodeName: 'Call API', nodeType: 'core.httpRequest', sequence: 1, status: 'FAILED',
        input: { order: 7 }, error: { code: 'HTTP_STATUS_ERROR', message: 'GET http://api/x answered 503.' }, durationMs: 1240,
      },
      { nodeId: 'log', nodeName: 'Log result', nodeType: 'core.logger', sequence: 2, status: 'SKIPPED' },
    ],
    logs: [{ nodeId: 'call', level: 'INFO', message: 'calling api', at: '2026-01-01T10:00:00.500Z' }],
    ...overrides,
  }
}

export function succeededExecution(overrides: Partial<ExecutionDetail> = {}): ExecutionDetail {
  return {
    id: 'exec-2',
    workflowId: 'wf-1',
    workflowVersion: 2,
    status: 'SUCCEEDED',
    createdAt: '2026-01-01T09:00:00Z',
    startedAt: '2026-01-01T09:00:00Z',
    finishedAt: '2026-01-01T09:00:00.080Z',
    durationMs: 80,
    nodes: [
      { nodeId: 't', nodeName: 'Start', nodeType: 'core.manualTrigger', sequence: 0, status: 'SUCCEEDED', input: {}, output: { a: [1, 2] }, durationMs: 3 },
      {
        nodeId: 'log', nodeName: 'Log result', nodeType: 'core.logger', sequence: 1, status: 'SUCCEEDED',
        input: { a: [1, 2] }, output: { a: [1, 2] }, durationMs: 70,
      },
    ],
    logs: [{ nodeId: 'log', level: 'INFO', message: 'hello', at: '2026-01-01T09:00:00.060Z' }],
    ...overrides,
  }
}

export function summaryOf(e: ExecutionDetail, extra: Partial<ExecutionSummary> = {}): ExecutionSummary {
  const failed = e.nodes.find((n) => n.status === 'FAILED')
  return {
    id: e.id, workflowId: e.workflowId, workflowVersion: e.workflowVersion, status: e.status, error: e.error,
    createdAt: e.createdAt, startedAt: e.startedAt, finishedAt: e.finishedAt, durationMs: e.durationMs,
    nodeCount: e.nodes.length, succeededNodes: e.nodes.filter((n) => n.status === 'SUCCEEDED').length,
    failedNodes: e.nodes.filter((n) => n.status === 'FAILED').length,
    skippedNodes: e.nodes.filter((n) => n.status === 'SKIPPED').length,
    failedNodeId: failed?.nodeId, failedNodeName: failed?.nodeName, ...extra,
  }
}
