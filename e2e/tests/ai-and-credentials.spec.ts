import { expect, test } from '@playwright/test'

import { Api, definition, edge, httpNode, MOCK_INTERNAL, mockRequests, node, nodeRun, resetMock, unique } from './support'

// Secrets are generated per run; they must reach the mock server and appear nowhere the user (or a log reader) can see.
const AI_KEY = `sk-e2e-${Date.now().toString(36)}-provider-key`
const API_TOKEN = `tok-e2e-${Date.now().toString(36)}-echoed-back`

let api: Api
test.beforeEach(async ({ request }) => {
  api = new Api(request)
  await resetMock(request)
})
test.afterEach(async () => api.cleanup())

async function aiWorkflow(credentialId: string, review: string) {
  // The bundled sample (Manual Trigger → Set Variables → AI Model → Condition → Logger), pointed at the mock provider.
  const sample = await api.post<{ id: string }>('/samples/ai-review-triage/create', { credentialId }, 201)
  await api.updateDefinition(sample.id, (def) => {
    const ai = def.nodes.find((n) => n.type === 'core.aiModel')!
    ai.configuration = { ...ai.configuration, baseUrl: `${MOCK_INTERNAL}/v1`, model: 'e2e-model' }
    const start = def.nodes.find((n) => n.type === 'core.manualTrigger')!
    start.configuration = { testInput: { review } }
  })
  return sample.id
}

test.describe('AI Model node', () => {
  test('a provider response is parsed, validated and drives the branch', async ({ request }) => {
    const credentialId = await api.createCredential({
      type: 'httpBearer', name: unique('E2E AI key'), allowedHosts: ['mock-services'], secret: { token: AI_KEY },
    })
    const workflowId = await aiWorkflow(credentialId, 'The parcel was two weeks late and arrived crushed.')

    const execution = await api.run(workflowId)

    expect(execution.status).toBe('SUCCEEDED')
    const ai = nodeRun(execution, 'ai')
    expect(ai.output).toMatchObject({
      json: { sentiment: 'negative', urgency: 4 },
      model: 'e2e-model',
      usage: { prompt: 42, completion: 17, total: 59 },
      attempts: 1,
    })
    expect(nodeRun(execution, 'escalate').status).toBe('SUCCEEDED')
    expect(nodeRun(execution, 'archive').status).toBe('SKIPPED')
    expect(execution.logs.map((l) => l.message).join('\n')).toContain('Escalate to support: Late delivery')

    const [call] = await mockRequests(request, '/v1/chat/completions')
    expect(call.headers.authorization).toBe(`Bearer ${AI_KEY}`)
    expect(call.body).toMatchObject({ model: 'e2e-model', response_format: { type: 'json_object' } })
    expect(JSON.stringify(execution)).not.toContain(AI_KEY)
    expect(JSON.stringify(await api.get(`/workflows/${workflowId}`))).not.toContain(AI_KEY)
  })

  test('malformed output and rate limits are retried within the bounded policy', async ({ request }) => {
    const credentialId = await api.createCredential({
      type: 'httpBearer', name: unique('E2E AI retry'), allowedHosts: ['mock-services'], secret: { token: AI_KEY },
    })
    const malformed = await api.run(await aiWorkflow(credentialId, 'Item was late. [[malformed]]'))
    expect(malformed.status).toBe('SUCCEEDED')
    expect(nodeRun(malformed, 'ai').output).toMatchObject({ attempts: 2, json: { sentiment: 'negative' } })

    const limited = await api.run(await aiWorkflow(credentialId, 'Item was late. [[rate-limit]]'))
    expect(limited.status).toBe('SUCCEEDED')
    expect(nodeRun(limited, 'ai').output).toMatchObject({ attempts: 2 })

    const failing = await api.waitForExecution(
      await api.execute(await aiWorkflow(credentialId, 'Provider is down [[fail]]')), 60_000)
    expect(failing.status).toBe('FAILED')
    expect(nodeRun(failing, 'ai').error?.code).toBe('AI_PROVIDER_ERROR')
    // maxRetries 2 in the sample: one request plus at most two retries.
    const attempts = (await mockRequests(request, '/v1/chat/completions'))
      .filter((r) => JSON.stringify(r.body).includes('[[fail]]'))
    expect(attempts.length).toBeGreaterThanOrEqual(1)
    expect(attempts.length).toBeLessThanOrEqual(3)
  })
})

test.describe('credentials', () => {
  test('a stored credential authenticates the request and is never exposed', async ({ request, page }) => {
    const name = unique('E2E echo token')
    const credentialId = await api.createCredential({
      type: 'httpBearer', name, allowedHosts: ['mock-services'], secret: { token: API_TOKEN },
    })
    const created = await api.get<Record<string, unknown>>(`/credentials/${credentialId}`)
    expect(JSON.stringify(created)).not.toContain(API_TOKEN)
    expect(created.secretFields).toEqual(['token'])

    const workflow = await api.createWorkflow(unique('E2E uses credential'), definition([
      node('start', 'core.manualTrigger'),
      // The mock echoes the request headers back, Authorization included.
      httpNode('call', `${MOCK_INTERNAL}/echo/secure`, { credentialId }),
      node('log', 'core.logger', { message: 'server saw {{ input.body.headers }}' }),
    ], [edge('start', 'call'), edge('call', 'log')]))

    const execution = await api.run(workflow.id)

    expect(execution.status).toBe('SUCCEEDED')
    const [received] = await mockRequests(request, '/echo/secure')
    expect(received.headers.authorization).toBe(`Bearer ${API_TOKEN}`)
    expect(JSON.stringify(execution)).not.toContain(API_TOKEN)
    expect(JSON.stringify(execution)).toContain('[REDACTED]')
    expect(JSON.stringify(await api.executions(workflow.id))).not.toContain(API_TOKEN)
    expect(JSON.stringify(await api.get(`/workflows/${workflow.id}`))).not.toContain(API_TOKEN)
    const usage = await api.get<{ workflows: { id: string }[] }>(`/credentials/${credentialId}/usage`)
    expect(usage.workflows.map((w) => w.id)).toContain(workflow.id)

    // Deleting a credential that is still referenced is refused.
    expect((await request.delete(`/api/v1/credentials/${credentialId}`)).status()).toBe(409)

    // The Credentials page lists it without the secret.
    await page.goto('/credentials')
    await expect(page.getByText(name)).toBeVisible()
    expect(await page.content()).not.toContain(API_TOKEN)

    // A credential is only sent to its allowed hosts.
    const elsewhere = await api.createCredential({
      type: 'httpBearer', name: unique('E2E other host'), allowedHosts: ['api.example.com'], secret: { token: API_TOKEN },
    })
    const misuse = await api.createWorkflow(unique('E2E credential misuse'), definition([
      node('start', 'core.manualTrigger'), httpNode('call', `${MOCK_INTERNAL}/echo/misuse`, { credentialId: elsewhere }),
    ], [edge('start', 'call')]))
    const refused = await api.run(misuse.id)
    expect(refused.error?.code).toBe('CREDENTIAL_HOST_NOT_ALLOWED')
    expect(await mockRequests(request, '/echo/misuse')).toEqual([])
  })
})
