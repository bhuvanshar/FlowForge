import { act, cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as healthApi from '@/lib/api/health'
import type { CreateWorkflowRequest, UpdateWorkflowRequest, WorkflowResponse } from '@/lib/api/workflows'
import type { WorkflowDefinition } from '@/lib/workflow/definition'
import { renderApp } from '@/test/renderWithProviders'

import { selectIsDirty, useEditorStore } from './editorStore'

const server = vi.hoisted(() => ({ rows: new Map<string, unknown>() }))
const api = vi.hoisted(() => ({ getWorkflow: vi.fn(), createWorkflow: vi.fn(), updateWorkflow: vi.fn(), listWorkflows: vi.fn(), validateWorkflow: vi.fn(), executeWorkflow: vi.fn(), listExecutions: vi.fn(), getExecution: vi.fn() }))
vi.mock('@/lib/api/workflows', () => api)

const store = () => useEditorStore.getState()

function installFakeServer() {
  server.rows.clear()
  let counter = 0
  api.getWorkflow.mockImplementation(async (id: string) => structuredClone(server.rows.get(id)))
  api.createWorkflow.mockImplementation(async (r: CreateWorkflowRequest) => {
    const row: WorkflowResponse = {
      id: `wf-${++counter}`, name: r.name, description: r.description, status: 'DRAFT', version: 1, lockVersion: 0,
      definition: r.definition as WorkflowDefinition, createdAt: 't', updatedAt: 't',
    }
    server.rows.set(row.id, structuredClone(row))
    return structuredClone(row)
  })
  api.updateWorkflow.mockImplementation(async (id: string, r: UpdateWorkflowRequest) => {
    const row = server.rows.get(id) as WorkflowResponse
    const next = { ...row, name: r.name, definition: r.definition, lockVersion: row.lockVersion + 1, version: row.version + 1 }
    server.rows.set(id, structuredClone(next))
    return structuredClone(next)
  })
  api.validateWorkflow.mockResolvedValue({ valid: true, errorCount: 0, warningCount: 0, issues: [], executionOrder: [] })
  api.listWorkflows.mockResolvedValue({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })
  api.listExecutions.mockResolvedValue({ items: [], page: 0, size: 1, totalElements: 0, totalPages: 0 })
}

const palette = () => within(screen.getByRole('complementary', { name: 'Node palette' }))
const settings = () => within(screen.getByRole('form', { name: 'Node settings' }))
const addNode = (label: string) => userEvent.dblClick(palette().getByRole('button', { name: label }))
async function paste(label: string, text: string) {
  const user = userEvent.setup()
  const field = settings().getByLabelText(label)
  await user.click(field)
  await user.paste(text)
}

async function openNewEditor() {
  const utils = renderApp('/workflows/new')
  await screen.findByRole('toolbar', { name: 'Editor toolbar' })
  await userEvent.type(screen.getByRole('textbox', { name: 'Workflow name' }), 'Configured flow')
  return utils
}

describe('configuring nodes in the editor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    store().reset()
    installFakeServer()
    vi.spyOn(healthApi, 'getHealth').mockResolvedValue({ status: 'UP', version: '1', timestamp: 't', components: {} })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  it('shows the settings form of the selected node in the right panel and its default configuration', async () => {
    await openNewEditor()
    expect(screen.getByText('Select a node to configure it.')).toBeInTheDocument()

    await addNode('HTTP Request')

    expect(screen.getByRole('form', { name: 'Node settings' })).toBeInTheDocument()
    expect(settings().getByLabelText('Method')).toHaveValue('GET')
    expect(settings().getByLabelText('Timeout (seconds)')).toHaveValue('30')
    expect(store().nodes[0].data.configuration).toEqual({ method: 'GET', url: '', headers: [], query: [], timeoutSeconds: 30 })
    expect(await screen.findByLabelText('Configuration incomplete')).toBeInTheDocument()
  })

  it('configures every node type, saves, and reopens them all with their data intact', async () => {
    const user = userEvent.setup()
    await openNewEditor()

    await addNode('Manual Trigger')
    await paste('Test input (JSON, optional)', '{"order":{"id":7,"total":120}}')
    await user.click(settings().getByRole('button', { name: 'Apply' }))

    await addNode('Set Variables')
    await user.click(settings().getByRole('button', { name: 'Add variable' }))
    await user.type(settings().getByLabelText('variable 1 name'), 'order_id')
    await paste('variable 1 value', '{{ input.order.id }}')
    await user.click(settings().getByRole('button', { name: 'Apply' }))

    await addNode('Condition')
    await user.type(settings().getByLabelText('Field'), 'order.total')
    await user.selectOptions(settings().getByLabelText('Operator'), 'greaterThan')
    await user.type(settings().getByLabelText('Comparison value'), '100')
    await user.click(settings().getByRole('button', { name: 'Apply' }))

    await addNode('Transform')
    await user.click(settings().getByRole('button', { name: 'Add operation' }))
    await user.type(settings().getByLabelText('operation 1 output field'), 'label')
    await paste('operation 1 source', '{{ input.order.id }}')
    await user.selectOptions(settings().getByLabelText('operation 1 operation'), 'toString')
    await user.click(settings().getByRole('button', { name: 'Apply' }))

    await addNode('Logger')
    await paste('Message', 'Order {{ vars.order_id }} is large')
    // Deliberately not applied: saving applies pending valid edits.

    await addNode('HTTP Request')
    await user.selectOptions(settings().getByLabelText('Method'), 'POST')
    await user.type(settings().getByLabelText('URL'), 'https://api.example.com/orders')
    await user.click(settings().getByRole('button', { name: 'Add header' }))
    await user.type(settings().getByLabelText('header 1 name'), 'X-Source')
    await user.type(settings().getByLabelText('header 1 value'), 'flowforge')
    await paste('JSON body (optional)', '{"id":"{{ vars.order_id }}"}')
    await user.click(settings().getByRole('button', { name: 'Apply' }))

    // Only the Logger still has its default (incomplete) configuration: its edit is not applied yet.
    expect(screen.getAllByLabelText('Configuration incomplete')).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(api.createWorkflow).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(screen.queryByLabelText('Configuration incomplete')).not.toBeInTheDocument())

    const saved = (api.createWorkflow.mock.calls[0][0] as CreateWorkflowRequest).definition!
    expect(saved.nodes.map((n) => [n.type, n.configuration])).toEqual([
      ['core.manualTrigger', { testInput: { order: { id: 7, total: 120 } } }],
      ['core.setVariables', { variables: [{ name: 'order_id', value: '{{ input.order.id }}' }] }],
      ['core.condition', { field: 'order.total', operator: 'greaterThan', value: '100' }],
      ['core.transform', { operations: [{ field: 'label', source: '{{ input.order.id }}', operation: 'toString' }] }],
      ['core.logger', { message: 'Order {{ vars.order_id }} is large' }],
      ['core.httpRequest', {
        method: 'POST', url: 'https://api.example.com/orders', headers: [{ name: 'X-Source', value: 'flowforge' }], query: [],
        body: { id: '{{ vars.order_id }}' }, timeoutSeconds: 30,
      }],
    ])
    await waitFor(() => expect(screen.getByTestId('dirty-indicator')).toHaveTextContent('All changes saved'))

    // Browser refresh: fresh store, URL opened from scratch.
    cleanup()
    store().reset()
    renderApp('/workflows/wf-1')
    await screen.findByRole('toolbar', { name: 'Editor toolbar' })
    expect(store().nodes.map((n) => n.data.configuration)).toEqual(saved.nodes.map((n) => n.configuration))

    const expectations: [string, () => void][] = [
      ['Manual Trigger', () => expect(settings().getByLabelText('Test input (JSON, optional)')).toHaveValue('{\n  "order": {\n    "id": 7,\n    "total": 120\n  }\n}')],
      ['Set Variables', () => expect(settings().getByLabelText('variable 1 value')).toHaveValue('{{ input.order.id }}')],
      ['Condition', () => expect(settings().getByLabelText('Operator')).toHaveValue('greaterThan')],
      ['Transform', () => expect(settings().getByLabelText('operation 1 operation')).toHaveValue('toString')],
      ['Logger', () => expect(settings().getByLabelText('Message')).toHaveValue('Order {{ vars.order_id }} is large')],
      ['HTTP Request', () => expect(settings().getByLabelText('URL')).toHaveValue('https://api.example.com/orders')],
    ]
    for (const [index, [, check]] of expectations.entries()) {
      act(() => store().selectOnly(store().nodes[index].id))
      check()
      expect(screen.getByTestId('config-status')).toHaveTextContent('Applied')
    }
    expect(screen.queryByLabelText('Configuration incomplete')).not.toBeInTheDocument()
    expect(screen.getByTestId('dirty-indicator')).toHaveTextContent('All changes saved')
  })

  it('refuses to save while a node has an invalid unapplied edit, selects that node, then saves once fixed', async () => {
    const user = userEvent.setup()
    await openNewEditor()
    await addNode('Logger')
    await paste('Message', 'bad {{ run() }}')

    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText(/Not saved: fix the settings of "Logger"/)).toBeInTheDocument()
    expect(api.createWorkflow).not.toHaveBeenCalled()
    expect(settings().getByLabelText('Message')).toHaveValue('bad {{ run() }}') // nothing lost

    await user.clear(settings().getByLabelText('Message'))
    await paste('Message', 'fixed {{ input.a }}')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(api.createWorkflow).toHaveBeenCalledTimes(1))
    expect((api.createWorkflow.mock.calls[0][0] as CreateWorkflowRequest).definition!.nodes[0].configuration).toEqual({
      message: 'fixed {{ input.a }}',
    })
    await waitFor(() => expect(screen.queryByText(/Not saved: fix the settings/)).not.toBeInTheDocument())
  })

  it('treats unapplied settings edits as unsaved changes (indicator and tab-close warning)', async () => {
    const user = userEvent.setup()
    await openNewEditor()
    await addNode('Logger')
    await paste('Message', 'one')
    await user.click(settings().getByRole('button', { name: 'Apply' }))
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.getByTestId('dirty-indicator')).toHaveTextContent('All changes saved'))
    expect(selectIsDirty(store())).toBe(false)

    await user.type(settings().getByLabelText('Message'), ' two')

    expect(screen.getByTestId('dirty-indicator')).toHaveTextContent('Unsaved changes')
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
    const event = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)

    await user.click(settings().getByRole('button', { name: 'Revert' }))
    expect(screen.getByTestId('dirty-indicator')).toHaveTextContent('All changes saved')
  })

  it('saves configuration edits to an existing workflow with the lockVersion', async () => {
    server.rows.set('wf-9', {
      id: 'wf-9', name: 'Existing', status: 'DRAFT', version: 1, lockVersion: 3, createdAt: 't', updatedAt: 't',
      definition: {
        schemaVersion: 1,
        nodes: [{ id: 'log1', type: 'core.logger', typeVersion: 1, name: 'Logger', position: { x: 0, y: 0 }, configuration: { message: 'old' } }],
        edges: [],
      },
    })
    renderApp('/workflows/wf-9')
    await screen.findByRole('toolbar', { name: 'Editor toolbar' })
    act(() => store().selectOnly('log1'))
    await paste('Message', ' + new')

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(api.updateWorkflow).toHaveBeenCalledTimes(1))
    const [, request] = api.updateWorkflow.mock.calls[0] as [string, UpdateWorkflowRequest]
    expect(request.lockVersion).toBe(3)
    expect(request.definition.nodes[0].configuration).toEqual({ message: 'old + new' })
  })
})
