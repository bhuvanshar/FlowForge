import { expect, test } from '@playwright/test'

import { Api, definition, edge, httpNode, MOCK_INTERNAL, mockRequests, node, nodeRun, resetMock, unique } from './support'

let api: Api
test.beforeEach(async ({ request }) => {
  api = new Api(request)
  await resetMock(request)
})
test.afterEach(async () => api.cleanup())

test.describe('condition branching', () => {
  test('routes each run down exactly one branch and skips the other', async () => {
    const workflow = await api.createWorkflow(unique('E2E branching'), definition([
      node('start', 'core.manualTrigger'),
      node('check', 'core.condition', { field: 'amount', operator: 'greaterThan', value: '100' }, 'Large order?'),
      node('large', 'core.logger', { message: 'large order of {{ input.amount }}' }),
      node('small', 'core.logger', { message: 'small order of {{ input.amount }}' }),
    ], [edge('start', 'check'), edge('check', 'large', 'true'), edge('check', 'small', 'false')]))

    const big = await api.run(workflow.id, { amount: 150 })
    expect(big.status).toBe('SUCCEEDED')
    expect(nodeRun(big, 'large').status).toBe('SUCCEEDED')
    expect(nodeRun(big, 'small').status).toBe('SKIPPED')
    expect(big.logs.map((l) => l.message)).toContain('large order of 150')

    const little = await api.run(workflow.id, { amount: 50 })
    expect(little.status).toBe('SUCCEEDED')
    expect(nodeRun(little, 'large').status).toBe('SKIPPED')
    expect(nodeRun(little, 'small').status).toBe('SUCCEEDED')
    expect(little.logs.map((l) => l.message)).toContain('small order of 50')
    expect(little.logs.map((l) => l.message)).not.toContain('large order of 50')
  })
})

test.describe('HTTP Request node', () => {
  test('success: the request reaches the server and its response feeds the next node', async ({ request }) => {
    const workflow = await api.createWorkflow(unique('E2E http ok'), definition([
      node('start', 'core.manualTrigger'),
      node('vars', 'core.setVariables', { variables: [{ name: 'orderId', value: '{{ input.orderId }}' }] }),
      httpNode('call', `${MOCK_INTERNAL}/echo/orders`, {
        method: 'POST',
        query: [{ name: 'id', value: '{{ vars.orderId }}' }],
        headers: [{ name: 'X-Trace', value: 'e2e' }],
        body: { orderId: '{{ vars.orderId }}', source: 'flowforge' },
      }),
      node('log', 'core.logger', { message: 'echoed {{ input.body.query.id }} with {{ input.status }}' }),
    ], [edge('start', 'vars'), edge('vars', 'call'), edge('call', 'log')]))

    const execution = await api.run(workflow.id, { orderId: 'A-17' })

    expect(execution.status).toBe('SUCCEEDED')
    expect(nodeRun(execution, 'call').output).toMatchObject({ status: 200 })
    expect(execution.logs.map((l) => l.message)).toContain('echoed A-17 with 200')
    const [received] = await mockRequests(request, '/echo/orders')
    expect(received).toMatchObject({ method: 'POST', query: { id: 'A-17' }, body: { orderId: 'A-17', source: 'flowforge' } })
    expect(received.headers['x-trace']).toBe('e2e')
  })

  test('failure: an error status fails the node and the run, and later nodes do not run', async () => {
    const workflow = await api.createWorkflow(unique('E2E http 503'), definition([
      node('start', 'core.manualTrigger'),
      httpNode('call', `${MOCK_INTERNAL}/status/503`),
      node('log', 'core.logger', { message: 'should not run' }),
    ], [edge('start', 'call'), edge('call', 'log')]))

    const execution = await api.run(workflow.id)

    expect(execution.status).toBe('FAILED')
    expect(execution.error?.code).toBe('HTTP_STATUS_ERROR')
    expect(nodeRun(execution, 'call')).toMatchObject({ status: 'FAILED', error: { code: 'HTTP_STATUS_ERROR' } })
    expect(execution.error?.message).toContain('503')
    expect(execution.logs.map((l) => l.message)).not.toContain('should not run')
  })

  test('outbound control: hosts outside the allowlist are refused without any connection', async ({ request }) => {
    const workflow = await api.createWorkflow(unique('E2E http blocked'), definition([
      node('start', 'core.manualTrigger'),
      httpNode('call', 'http://example.com/'),
    ], [edge('start', 'call')]))

    const execution = await api.run(workflow.id)

    expect(execution.status).toBe('FAILED')
    expect(execution.error?.code).toBe('HTTP_HOST_NOT_ALLOWED')
    expect(await mockRequests(request, '/')).toEqual([])
  })
})
