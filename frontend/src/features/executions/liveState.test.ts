import { describe, expect, it } from 'vitest'

import { failedExecution, succeededExecution } from './fixtures'
import {
  applyEvent,
  applySnapshot,
  emptyLiveState,
  isTerminalEvent,
  laterNodeStatus,
  mergeLive,
  type LiveEvent,
  type SnapshotEvent,
} from './liveState'

const ID = 'exec-2'

const event = (type: string, extra: Partial<LiveEvent> = {}): LiveEvent => ({
  seq: 1, type, executionId: ID, status: 'RUNNING', at: '2026-01-01T00:00:00Z', ...extra,
})

const snapshot = (status: SnapshotEvent['status'], nodes: [string, SnapshotEvent['nodes'][number]['status']][]): SnapshotEvent => ({
  executionId: ID,
  status,
  nodes: nodes.map(([nodeId, s], i) => ({ nodeId, nodeName: nodeId, sequence: i, status: s })),
})

describe('live state', () => {
  it('follows a run: started, node started, node succeeded, completed', () => {
    let s = emptyLiveState(ID)
    s = applyEvent(s, 'execution.started', event('execution.started'))
    s = applyEvent(s, 'node.started', event('node.started', { nodeId: 't', status: 'RUNNING' }))
    expect(s.status).toBe('RUNNING')
    expect(s.nodes.t.status).toBe('RUNNING')

    s = applyEvent(s, 'node.succeeded', event('node.succeeded', { nodeId: 't', status: 'SUCCEEDED', durationMs: 5 }))
    s = applyEvent(s, 'execution.completed', event('execution.completed', { status: 'SUCCEEDED', durationMs: 80 }))

    expect(s.nodes.t).toEqual({ status: 'SUCCEEDED', durationMs: 5, error: undefined })
    expect(s.status).toBe('SUCCEEDED')
    expect(s.durationMs).toBe(80)
  })

  it('records failures with their error, skipped nodes, and the failed or cancelled execution', () => {
    let s = emptyLiveState(ID)
    const error = { code: 'HTTP_STATUS_ERROR', message: 'answered 500' }
    s = applyEvent(s, 'node.failed', event('node.failed', { nodeId: 'a', status: 'FAILED', error }))
    s = applyEvent(s, 'node.skipped', event('node.skipped', { nodeId: 'b', status: 'SKIPPED' }))
    s = applyEvent(s, 'execution.failed', event('execution.failed', { status: 'FAILED', error }))

    expect(s.nodes.a).toMatchObject({ status: 'FAILED', error })
    expect(s.nodes.b.status).toBe('SKIPPED')
    expect(s.status).toBe('FAILED')
    expect(s.error).toEqual(error)
    expect(applyEvent(emptyLiveState(ID), 'execution.cancelled', event('execution.cancelled', { status: 'CANCELLED' })).status)
      .toBe('CANCELLED')
  })

  it('never moves a status backwards: duplicated or reordered events are harmless', () => {
    let s = emptyLiveState(ID)
    s = applyEvent(s, 'node.succeeded', event('node.succeeded', { nodeId: 't', status: 'SUCCEEDED' }))
    const after = applyEvent(s, 'node.started', event('node.started', { nodeId: 't', status: 'RUNNING' }))
    expect(after.nodes.t.status).toBe('SUCCEEDED')
    expect(after).toBe(s)

    s = applyEvent(s, 'execution.completed', event('execution.completed', { status: 'SUCCEEDED' }))
    expect(applyEvent(s, 'execution.started', event('execution.started')).status).toBe('SUCCEEDED')
    expect(laterNodeStatus('FAILED', 'RUNNING')).toBe('FAILED')
    expect(laterNodeStatus('PENDING', undefined)).toBe('PENDING')
  })

  it('applies the same event twice without change in outcome', () => {
    const e = event('node.started', { nodeId: 'x', status: 'RUNNING' })
    const once = applyEvent(emptyLiveState(ID), 'node.started', e)
    expect(applyEvent(once, 'node.started', e).nodes).toEqual(once.nodes)
  })

  it('ignores events of other executions, events without a node, and unknown event names', () => {
    const s = emptyLiveState(ID)
    expect(applyEvent(s, 'node.started', event('node.started', { executionId: 'other', nodeId: 'x' }))).toBe(s)
    expect(applyEvent(s, 'node.started', event('node.started'))).toBe(s)
    expect(applyEvent(s, 'something.else', event('something.else'))).toBe(s)
  })

  it('a snapshot restores everything the client missed', () => {
    const missed = emptyLiveState(ID)

    const s = applySnapshot(missed, {
      ...snapshot('RUNNING', [['t', 'SUCCEEDED'], ['a', 'RUNNING'], ['b', 'PENDING']]),
      durationMs: undefined,
    })

    expect(s.status).toBe('RUNNING')
    expect(Object.fromEntries(Object.entries(s.nodes).map(([k, v]) => [k, v.status]))).toEqual({
      t: 'SUCCEEDED', a: 'RUNNING', b: 'PENDING',
    })
  })

  it('a snapshot never regresses what was already seen, and carries error and duration of a finished run', () => {
    let s = applyEvent(emptyLiveState(ID), 'node.succeeded', event('node.succeeded', { nodeId: 'a', status: 'SUCCEEDED' }))
    s = applySnapshot(s, snapshot('RUNNING', [['a', 'RUNNING']]))
    expect(s.nodes.a.status).toBe('SUCCEEDED')

    const finished = applySnapshot(emptyLiveState(ID), {
      ...snapshot('FAILED', [['a', 'FAILED']]),
      error: { code: 'X', message: 'boom' },
      durationMs: 1200,
    })
    expect(finished).toMatchObject({ status: 'FAILED', error: { code: 'X' }, durationMs: 1200 })
  })

  it('knows the terminal event names', () => {
    expect(['execution.completed', 'execution.failed', 'execution.cancelled'].every(isTerminalEvent)).toBe(true)
    expect(['node.started', 'node.failed', 'execution.started', 'snapshot'].some(isTerminalEvent)).toBe(false)
  })
})

describe('mergeLive', () => {
  it('returns the persisted execution unchanged without a live overlay or for another execution', () => {
    const data = succeededExecution()
    expect(mergeLive(data, undefined)).toBe(data)
    expect(mergeLive(data, emptyLiveState('other'))).toBe(data)
  })

  it('shows live progress ahead of the persisted data, while keeping persisted payloads', () => {
    const data = succeededExecution({
      status: 'QUEUED',
      nodes: [
        { nodeId: 't', nodeName: 'Start', nodeType: 'core.manualTrigger', sequence: 0, status: 'PENDING' },
        { nodeId: 'log', nodeName: 'Log result', nodeType: 'core.logger', sequence: 1, status: 'PENDING' },
      ],
    })
    let live = applyEvent(emptyLiveState(data.id), 'execution.started', event('execution.started'))
    live = applyEvent(live, 'node.succeeded', event('node.succeeded', { nodeId: 't', status: 'SUCCEEDED', durationMs: 3 }))
    live = applyEvent(live, 'node.started', event('node.started', { nodeId: 'log', status: 'RUNNING' }))

    const merged = mergeLive(data, live)

    expect(merged.status).toBe('RUNNING')
    expect(merged.nodes.map((n) => n.status)).toEqual(['SUCCEEDED', 'RUNNING'])
    expect(merged.nodes[0].durationMs).toBe(3)
  })

  it('persisted state wins when it is further along, and its payloads are kept', () => {
    const data = succeededExecution()
    const live = applyEvent(emptyLiveState(data.id), 'node.started', event('node.started', { nodeId: 'log', status: 'RUNNING' }))

    const merged = mergeLive(data, live)

    expect(merged.nodes[1].status).toBe('SUCCEEDED')
    expect(merged.nodes[1].output).toEqual({ a: [1, 2] })
    expect(merged.status).toBe('SUCCEEDED')
  })

  it('uses the streamed error until the persisted one arrives', () => {
    const data = failedExecution({ error: undefined, status: 'RUNNING', nodes: failedExecution().nodes.map((n) => (
      n.nodeId === 'call' ? { nodeId: n.nodeId, nodeName: n.nodeName, nodeType: n.nodeType, sequence: n.sequence, status: 'RUNNING' as const } : n)) })
    const error = { code: 'HTTP_STATUS_ERROR', message: 'answered 503' }
    let live = applyEvent(emptyLiveState(data.id), 'node.failed', event('node.failed', { executionId: data.id, nodeId: 'call', status: 'FAILED', error }))
    live = applyEvent(live, 'execution.failed', event('execution.failed', { executionId: data.id, status: 'FAILED', error }))

    const merged = mergeLive(data, live)

    expect(merged.status).toBe('FAILED')
    expect(merged.error).toEqual(error)
    expect(merged.nodes.find((n) => n.nodeId === 'call')).toMatchObject({ status: 'FAILED', error })
  })
})
