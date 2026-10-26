import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, type AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as healthApi from '@/lib/api/health'
import type { TriggersInfo } from '@/lib/api/triggers'
import type { WorkflowResponse } from '@/lib/api/workflows'
import type { NodeDefinition } from '@/lib/workflow/definition'
import { renderApp } from '@/test/renderWithProviders'

import { useEditorStore } from './editorStore'
import { curlExample } from './triggers'

const api = vi.hoisted(() => ({
  getWorkflow: vi.fn(), createWorkflow: vi.fn(), updateWorkflow: vi.fn(), listWorkflows: vi.fn(), validateWorkflow: vi.fn(),
  executeWorkflow: vi.fn(), listExecutions: vi.fn(), getExecution: vi.fn(),
}))
vi.mock('@/lib/api/workflows', () => api)
const triggersApi = vi.hoisted(() => ({
  activateWorkflow: vi.fn(), deactivateWorkflow: vi.fn(), getTriggers: vi.fn(), getWebhookSecret: vi.fn(),
  rotateWebhookSecret: vi.fn(),
}))
vi.mock('@/lib/api/triggers', () => triggersApi)

const store = () => useEditorStore.getState()

function workflow(trigger: NodeDefinition, status: WorkflowResponse['status'] = 'DRAFT'): WorkflowResponse {
  return {
    id: 'wf-1', name: 'Hooked', status, version: 2, lockVersion: 4, createdAt: 't', updatedAt: 't',
    definition: {
      schemaVersion: 1,
      nodes: [
        trigger,
        { id: 'log', type: 'core.logger', typeVersion: 1, name: 'Log', position: { x: 250, y: 0 }, configuration: { message: 'x' } },
      ],
      edges: [{ id: 'e1', source: 't', sourceHandle: 'main', target: 'log', targetHandle: 'main' }],
      editor: { viewport: { x: 0, y: 0, zoom: 1 } },
    },
  }
}

const WEBHOOK_NODE: NodeDefinition = {
  id: 't', type: 'core.webhookTrigger', typeVersion: 1, name: 'Incoming order', position: { x: 0, y: 0 },
  configuration: { method: 'POST', authentication: 'signature', inputMode: 'body' },
}
const SCHEDULE_NODE: NodeDefinition = {
  id: 't', type: 'core.scheduleTrigger', typeVersion: 1, name: 'Nightly', position: { x: 0, y: 0 },
  configuration: { cron: '0 2 * * *', timezone: 'Europe/Berlin', missedRunPolicy: 'runOnce' },
}

const webhookInfo = (active: boolean): TriggersInfo => ({
  workflowId: 'wf-1', status: active ? 'ACTIVE' : 'DRAFT', triggerType: 'core.webhookTrigger',
  webhook: {
    webhookId: 'abcDEF123_-abcDEF123_-abcDEF1234', path: '/api/v1/hooks/abcDEF123_-abcDEF123_-abcDEF1234', nodeId: 't',
    method: 'POST', authentication: 'signature', inputMode: 'body', active, secretHint: '…wxyz',
  },
})

function problem(status: number, detail: string) {
  return new AxiosError('fail', String(status), undefined, undefined, {
    status, data: { status, detail, title: 'x', type: 'about:blank' },
  } as AxiosResponse)
}

async function openEditor(definition: WorkflowResponse) {
  api.getWorkflow.mockResolvedValue(structuredClone(definition))
  renderApp('/workflows/wf-1')
  await screen.findByRole('toolbar', { name: 'Editor toolbar' })
  act(() => store().selectOnly('t'))
}

const triggerSection = () => screen.getByRole('region', { name: 'Trigger status' })

describe('webhook and schedule triggers in the editor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    store().reset()
    api.listWorkflows.mockResolvedValue({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })
    api.validateWorkflow.mockResolvedValue({ valid: true, errorCount: 0, warningCount: 0, issues: [], executionOrder: [] })
    api.listExecutions.mockResolvedValue({ items: [], page: 0, size: 1, totalElements: 0, totalPages: 0 })
    vi.spyOn(healthApi, 'getHealth').mockResolvedValue({ status: 'UP', version: '1', timestamp: 't', components: {} })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  it('shows the webhook URL, reveals and rotates the secret, and offers a signed example request', async () => {
    triggersApi.getTriggers.mockResolvedValue(webhookInfo(false))
    triggersApi.getWebhookSecret.mockResolvedValue('the-secret-value')
    triggersApi.rotateWebhookSecret.mockResolvedValue('a-brand-new-secret')
    await openEditor(workflow(WEBHOOK_NODE))

    const section = triggerSection()
    expect(await within(section).findByTestId('webhook-url')).toHaveTextContent(
      `${window.location.origin}/api/v1/hooks/abcDEF123_-abcDEF123_-abcDEF1234`,
    )
    expect(section).toHaveTextContent('Inactive. Activate the workflow to accept requests')
    expect(within(section).getByTestId('webhook-secret')).toHaveTextContent('••••••••wxyz')
    expect(within(section).getByTestId('webhook-example').textContent).toContain('X-FlowForge-Signature: sha256=$SIG')

    await userEvent.click(within(section).getByRole('button', { name: 'Reveal secret' }))
    expect(await within(section).findByText('the-secret-value')).toBeInTheDocument()
    expect(triggersApi.getWebhookSecret).toHaveBeenCalledWith('wf-1')

    await userEvent.click(within(section).getByRole('button', { name: 'Rotate secret' }))
    expect(await within(section).findByText('a-brand-new-secret')).toBeInTheDocument()
    expect(window.confirm).toHaveBeenCalled()
  })

  it('activates the saved workflow and then offers to deactivate it', async () => {
    triggersApi.getTriggers.mockResolvedValueOnce(webhookInfo(false)).mockResolvedValue(webhookInfo(true))
    triggersApi.activateWorkflow.mockResolvedValue({ ...workflow(WEBHOOK_NODE, 'ACTIVE'), lockVersion: 5 })
    triggersApi.deactivateWorkflow.mockResolvedValue({ ...workflow(WEBHOOK_NODE, 'INACTIVE'), lockVersion: 6 })
    await openEditor(workflow(WEBHOOK_NODE))
    await within(triggerSection()).findByTestId('webhook-url')

    await userEvent.click(screen.getByRole('button', { name: 'Activate' }))

    expect(triggersApi.activateWorkflow).toHaveBeenCalledWith('wf-1')
    await waitFor(() => expect(store().status).toBe('ACTIVE'))
    expect(store().lockVersion).toBe(5)
    expect(await within(triggerSection()).findByText('Active')).toBeInTheDocument()
    expect(screen.getByRole('toolbar')).toHaveTextContent('ACTIVE')

    await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }))
    await waitFor(() => expect(store().status).toBe('INACTIVE'))
    expect(store().lockVersion).toBe(6)
    expect(screen.getByRole('button', { name: 'Activate' })).toBeEnabled()
  })

  it('requires saving before activation and explains validation failures', async () => {
    triggersApi.getTriggers.mockResolvedValue(webhookInfo(false))
    triggersApi.activateWorkflow.mockRejectedValue(problem(422, 'The workflow cannot be activated: it has 1 validation error(s).'))
    await openEditor(workflow(WEBHOOK_NODE))

    act(() => store().setName('Renamed'))
    expect(screen.getByRole('button', { name: 'Activate' })).toBeDisabled()
    act(() => store().setName('Hooked'))

    await userEvent.click(screen.getByRole('button', { name: 'Activate' }))
    expect(await screen.findByText(/cannot be activated because it has validation errors/)).toBeInTheDocument()
    await waitFor(() => expect(api.validateWorkflow).toHaveBeenCalled())
    expect(store().status).toBe('DRAFT')
  })

  it('shows the next and last scheduled runs', async () => {
    triggersApi.getTriggers.mockResolvedValue({
      workflowId: 'wf-1', status: 'ACTIVE', triggerType: 'core.scheduleTrigger',
      schedule: {
        nodeId: 't', cron: '0 2 * * *', timezone: 'Europe/Berlin', missedRunPolicy: 'runOnce', active: true,
        nextFireAt: '2030-01-02T01:00:00Z', lastFireAt: '2030-01-01T01:00:00Z', lastExecutionId: 'exec-9',
      },
    } satisfies TriggersInfo)
    await openEditor(workflow(SCHEDULE_NODE, 'ACTIVE'))

    const section = triggerSection()
    expect(await within(section).findByText('Active')).toBeInTheDocument()
    expect(section).toHaveTextContent('0 2 * * * (Europe/Berlin)')
    // Shown in the browser's zone and in the schedule's zone (02:00 in Berlin); format depends on the locale.
    expect(within(section).getByTestId('schedule-next')).toHaveTextContent(/\b0?2:00(:00)?\s?(AM)?\s?(GMT\+1|CET)/)
    expect(within(section).getByRole('link')).toHaveAttribute('href', '/executions/exec-9')
  })

  it('asks to save first for a workflow that has never been saved', async () => {
    renderApp('/workflows/new')
    await screen.findByRole('toolbar', { name: 'Editor toolbar' })
    act(() => {
      store().addNode('core.webhookTrigger', { x: 0, y: 0 })
      store().selectOnly(store().nodes[0].id)
    })
    expect(triggerSection()).toHaveTextContent('Save the workflow to register this trigger.')
    expect(screen.getByRole('button', { name: 'Activate' })).toBeDisabled()
    expect(triggersApi.getTriggers).not.toHaveBeenCalled()
  })
})

describe('curl examples', () => {
  it('uses a bearer token for token webhooks and no body for GET', () => {
    expect(curlExample('http://x/api/v1/hooks/id', { method: 'GET', authentication: 'token' })).toBe(
      `SECRET='<webhook secret>'\ncurl -X GET 'http://x/api/v1/hooks/id' \\\n  -H "Authorization: Bearer $SECRET"`,
    )
  })
})
