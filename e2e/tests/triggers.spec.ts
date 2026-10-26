import { expect, test } from '@playwright/test'

import { Api, definition, edge, node, sign, unique } from './support'

let api: Api
test.beforeEach(async ({ request }) => {
  api = new Api(request)
})
test.afterEach(async () => api.cleanup())

interface Triggers {
  status: string
  webhook?: { webhookId: string; path: string; active: boolean }
  schedule?: { active: boolean; nextFireAt?: string; lastExecutionId?: string }
}

test.describe('webhook trigger', () => {
  test('a signed delivery starts a run; unsigned, replayed and inactive deliveries are refused', async ({ request }) => {
    const workflow = await api.createWorkflow(unique('E2E webhook'), definition([
      node('hook', 'core.webhookTrigger', { method: 'POST', authentication: 'signature', inputMode: 'body' }),
      node('log', 'core.logger', { message: 'order {{ input.order }} for {{ input.customer.name }}' }),
    ], [edge('hook', 'log')]))

    const registered = await api.get<Triggers>(`/workflows/${workflow.id}/triggers`)
    const path = registered.webhook!.path
    expect(path).toBe(`/api/v1/hooks/${registered.webhook!.webhookId}`)
    // Saved but not active yet: the endpoint does not exist for callers.
    const body = JSON.stringify({ order: 'W-42', customer: { name: 'Ada' } })
    const deliver = (headers: Record<string, string>, payload = body) =>
      request.post(path, { headers: { 'content-type': 'application/json', ...headers }, data: payload })
    expect((await deliver({})).status()).toBe(404)

    await api.post(`/workflows/${workflow.id}/activate`)
    const { secret } = await api.get<{ secret: string }>(`/workflows/${workflow.id}/triggers/webhook/secret`)

    expect((await deliver({})).status()).toBe(401)
    const now = Math.floor(Date.now() / 1000)
    expect((await deliver({ 'X-FlowForge-Timestamp': `${now}`, 'X-FlowForge-Signature': sign('wrong-secret', now, body) })).status()).toBe(401)
    expect((await deliver({ 'X-FlowForge-Timestamp': `${now - 3600}`, 'X-FlowForge-Signature': sign(secret, now - 3600, body) })).status()).toBe(401)

    const signed = { 'X-FlowForge-Timestamp': `${now}`, 'X-FlowForge-Signature': sign(secret, now, body) }
    const accepted = await deliver(signed)
    expect(accepted.status()).toBe(202)
    const { executionId } = await accepted.json()
    expect(accepted.headers().location).toBe(`/api/v1/executions/${executionId}`)

    const execution = await api.waitForExecution(executionId)
    expect(execution).toMatchObject({ status: 'SUCCEEDED', triggerType: 'WEBHOOK' })
    expect(execution.logs.map((l) => l.message)).toContain('order W-42 for Ada')

    // The same signed delivery again is a replay; a tampered body no longer matches the signature.
    expect((await deliver(signed)).status()).toBe(409)
    expect((await deliver(signed, body.replace('W-42', 'W-43'))).status()).toBe(401)

    await api.post(`/workflows/${workflow.id}/deactivate`)
    const later = Math.floor(Date.now() / 1000) + 1
    expect((await deliver({ 'X-FlowForge-Timestamp': `${later}`, 'X-FlowForge-Signature': sign(secret, later, body) })).status()).toBe(404)
    expect((await api.executions(workflow.id)).filter((e) => e.triggerType === 'WEBHOOK')).toHaveLength(1)
  })
})

test.describe('schedule trigger', () => {
  test('an active schedule fires on time and stops when deactivated', async () => {
    test.setTimeout(120_000)
    const workflow = await api.createWorkflow(unique('E2E schedule'), definition([
      // Every 10 seconds (6-field cron, seconds first).
      node('tick', 'core.scheduleTrigger', { cron: '*/10 * * * * *', timezone: 'UTC', missedRunPolicy: 'skip' }),
      node('log', 'core.logger', { message: 'tick at {{ input.scheduledFor }}' }),
    ], [edge('tick', 'log')]))

    await api.post(`/workflows/${workflow.id}/activate`)
    const active = await api.get<Triggers>(`/workflows/${workflow.id}/triggers`)
    expect(active.schedule).toMatchObject({ active: true })
    expect(Date.parse(active.schedule!.nextFireAt!)).toBeGreaterThan(Date.now() - 5_000)

    // Poll interval 5 s + 10 s cadence: the first run appears within ~20 s.
    await expect.poll(async () => (await api.executions(workflow.id)).filter((e) => e.triggerType === 'SCHEDULE').length,
      { timeout: 45_000, intervals: [1_000] }).toBeGreaterThanOrEqual(1)
    const [run] = (await api.executions(workflow.id)).filter((e) => e.triggerType === 'SCHEDULE')
    const execution = await api.waitForExecution(run.id)
    expect(execution.status).toBe('SUCCEEDED')
    expect(execution.scheduledFor).toBeTruthy()
    expect(new Date(execution.scheduledFor!).getUTCSeconds() % 10).toBe(0)
    expect(execution.logs[0].message).toContain('tick at ')

    await api.post(`/workflows/${workflow.id}/deactivate`)
    const inactive = await api.get<Triggers>(`/workflows/${workflow.id}/triggers`)
    expect(inactive.schedule?.active).toBe(false)
    expect(inactive.schedule?.nextFireAt).toBeUndefined()
    // A run already claimed when deactivating may still finish; afterwards nothing new is scheduled.
    await new Promise((resolve) => setTimeout(resolve, 6_000))
    const settled = (await api.executions(workflow.id)).length
    await new Promise((resolve) => setTimeout(resolve, 16_000))
    expect((await api.executions(workflow.id)).length).toBe(settled)
  })
})
