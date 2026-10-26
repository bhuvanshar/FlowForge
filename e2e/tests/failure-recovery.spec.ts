import { expect, test } from '@playwright/test'

import { Api, compose, definition, edge, httpNode, MOCK_INTERNAL, node, nodeRun, resetMock, unique } from './support'

let api: Api
test.beforeEach(async ({ request }) => {
  api = new Api(request)
  await resetMock(request)
})
test.afterEach(async () => api.cleanup())

test.describe('execution failure and recovery', () => {
  test('a failed run is fixed by editing the workflow, and history keeps both runs', async () => {
    const workflow = await api.createWorkflow(unique('E2E fix and rerun'), definition([
      node('start', 'core.manualTrigger'),
      httpNode('call', `${MOCK_INTERNAL}/status/500`),
      node('log', 'core.logger', { message: 'done with {{ input.status }}' }),
    ], [edge('start', 'call'), edge('call', 'log')]))

    const failed = await api.run(workflow.id)
    expect(failed.status).toBe('FAILED')
    expect(nodeRun(failed, 'call').error?.code).toBe('HTTP_STATUS_ERROR')

    const saved = await api.updateDefinition(workflow.id, (def) => {
      def.nodes.find((n) => n.id === 'call')!.configuration.url = `${MOCK_INTERNAL}/echo/fixed`
    })
    expect(saved.version).toBe(2)

    const fixed = await api.run(workflow.id)
    expect(fixed.status).toBe('SUCCEEDED')
    expect(fixed.logs.map((l) => l.message)).toContain('done with 200')

    const history = await api.executions(workflow.id)
    expect(history.map((e) => e.status)).toEqual(['SUCCEEDED', 'FAILED'])
    const detail = await api.get<{ workflowVersion: number }>(`/executions/${failed.id}`)
    expect(detail.workflowVersion).toBe(1)
  })

  test('a backend restart drains short runs, records long ones as interrupted, and the next run works', async () => {
    test.setTimeout(240_000)
    const slowRun = (ms: number) => definition([
      node('start', 'core.manualTrigger'),
      httpNode('slow', `${MOCK_INTERNAL}/delay/${ms}`, { timeoutSeconds: 60 }),
    ], [edge('start', 'slow')])
    const short = await api.createWorkflow(unique('E2E restart short'), slowRun(3_000))
    const workflow = await api.createWorkflow(unique('E2E restart long'), slowRun(28_000))

    const shortId = await api.execute(short.id)
    const executionId = await api.execute(workflow.id)
    for (const id of [shortId, executionId]) {
      await expect.poll(async () => (await api.get<{ status: string }>(`/executions/${id}`)).status).toBe('RUNNING')
    }

    // Graceful stop (SIGTERM, 30 s grace): running executions get 12 s to finish, then are interrupted and recorded.
    const stoppedAt = Date.now()
    compose('restart -t 30 backend')
    expect(Date.now() - stoppedAt).toBeLessThan(30_000)
    compose('up -d --wait --wait-timeout 180 backend')

    expect((await api.waitForExecution(shortId, 60_000)).status).toBe('SUCCEEDED')
    const interrupted = await api.waitForExecution(executionId, 60_000)
    expect(interrupted.status).toBe('FAILED')
    expect(interrupted.error?.code).toBe('EXECUTION_INTERRUPTED')

    await api.updateDefinition(workflow.id, (def) => {
      def.nodes.find((n) => n.id === 'slow')!.configuration.url = `${MOCK_INTERNAL}/delay/200`
    })
    expect((await api.run(workflow.id)).status).toBe('SUCCEEDED')
  })
})

test.describe('real-time updates', () => {
  test('the event stream delivers a snapshot, node transitions and the terminal event in order', async ({ baseURL }) => {
    const workflow = await api.createWorkflow(unique('E2E live events'), definition([
      node('start', 'core.manualTrigger'),
      httpNode('wait', `${MOCK_INTERNAL}/delay/1500`),
      node('log', 'core.logger', { message: 'finished' }),
    ], [edge('start', 'wait'), edge('wait', 'log')]))

    const executionId = await api.execute(workflow.id)
    const response = await fetch(`${baseURL}/api/v1/executions/${executionId}/events`, {
      headers: { accept: 'text/event-stream' },
    })
    expect(response.status).toBe(200)
    expect(response.headers.get('content-type')).toContain('text/event-stream')

    // The server ends the stream after the terminal event. (Spring writes "event:x"; the space is optional in SSE.)
    const text = await response.text()
    const events = [...text.matchAll(/^event: ?(.+)$/gm)].map((m) => m[1])
    expect(events[0]).toBe('snapshot')
    expect(events.at(-1)).toBe('execution.completed')
    expect(events).toContain('node.succeeded')
    // The engine never streams node payloads, only statuses and timing.
    expect(text).not.toContain('delayedMs')

    const after = await fetch(`${baseURL}/api/v1/executions/${executionId}/events`).then((r) => r.text())
    expect([...after.matchAll(/^event: ?(.+)$/gm)].map((m) => m[1])).toEqual(['snapshot'])
    expect(after).toContain('"status":"SUCCEEDED"')
  })
})
