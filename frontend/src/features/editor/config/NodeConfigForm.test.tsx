import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { renderWithProviders } from '@/test/renderWithProviders'

import { selectHasDrafts, selectIsDirty, useEditorStore } from '../editorStore'
import { PropertiesPanel } from '../PropertiesPanel'

const credentialsApi = vi.hoisted(() => ({
  CREDENTIALS_QUERY_KEY: ['credentials'],
  listCredentials: vi.fn(async () => [
    { id: '3f2b8c1e-5d4a-4b6f-9e7d-1a2b3c4d5e6f', name: 'Users API', type: 'httpBearer', allowedHosts: ['api.example.com'],
      fields: {}, secretFields: ['token'], secretVersion: 1, createdAt: 't', updatedAt: 't', lockVersion: 0 },
  ]),
}))
vi.mock('@/lib/api/credentials', () => credentialsApi)

const store = () => useEditorStore.getState()
const nodeConfig = (id: string) => store().nodes.find((n) => n.id === id)!.data.configuration

/** Adds a node of `type` (selected), optionally with a committed configuration, and renders the properties panel. */
function setup(type: string, configuration?: Record<string, unknown>) {
  const id = store().addNode(type, { x: 0, y: 0 })!
  if (configuration) act(() => store().applyConfiguration(id, configuration))
  renderWithProviders(<PropertiesPanel />)
  return id
}

async function paste(label: string, text: string, user = userEvent.setup()) {
  const field = screen.getByLabelText(label)
  await user.clear(field)
  await user.click(field)
  await user.paste(text)
}

const apply = (user = userEvent.setup()) => user.click(screen.getByRole('button', { name: 'Apply' }))

describe('NodeConfigForm', () => {
  beforeEach(() => {
    store().reset()
    store().newWorkflow()
  })

  describe('Manual Trigger', () => {
    it('saves optional test input JSON and shows it pretty-printed when reopened', async () => {
      const user = userEvent.setup()
      const id = setup('core.manualTrigger')
      await paste('Test input (JSON, optional)', '{"user":{"id":42}}', user)
      await apply(user)

      expect(nodeConfig(id)).toEqual({ testInput: { user: { id: 42 } } })
      expect(screen.getByLabelText('Test input (JSON, optional)')).toHaveValue('{\n  "user": {\n    "id": 42\n  }\n}')
      expect(screen.getByTestId('config-status')).toHaveTextContent('Applied')
    })

    it('reports invalid JSON and non-container JSON as field errors and does not apply', async () => {
      const user = userEvent.setup()
      const id = setup('core.manualTrigger')

      await paste('Test input (JSON, optional)', '{"a":', user)
      expect(await screen.findByText(/^Invalid JSON/)).toBeInTheDocument()
      await apply(user)
      expect(nodeConfig(id)).toEqual({})

      await paste('Test input (JSON, optional)', '42', user)
      expect(await screen.findByText('Test input must be a JSON object or array')).toBeInTheDocument()
    })

    it('clearing the input removes testInput from the configuration', async () => {
      const user = userEvent.setup()
      const id = setup('core.manualTrigger', { testInput: { a: 1 } })
      await user.clear(screen.getByLabelText('Test input (JSON, optional)'))
      await apply(user)
      expect(nodeConfig(id)).toEqual({})
    })
  })

  describe('Set Variables', () => {
    it('adds, edits and removes key/value rows and saves them in order', async () => {
      const user = userEvent.setup()
      const id = setup('core.setVariables')

      await user.click(screen.getByRole('button', { name: 'Add variable' }))
      await user.type(screen.getByLabelText('variable 1 name'), 'user_id')
      await paste('variable 1 value', '{{ input.user.id }}', user)
      await user.click(screen.getByRole('button', { name: 'Add variable' }))
      await user.type(screen.getByLabelText('variable 2 name'), 'greeting')
      await user.type(screen.getByLabelText('variable 2 value'), 'hello')
      await apply(user)

      expect(nodeConfig(id)).toEqual({
        variables: [{ name: 'user_id', value: '{{ input.user.id }}' }, { name: 'greeting', value: 'hello' }],
      })

      await user.click(screen.getByRole('button', { name: 'Remove variable 1' }))
      await apply(user)
      expect(nodeConfig(id)).toEqual({ variables: [{ name: 'greeting', value: 'hello' }] })
    })

    it('shows field-level errors: required name, invalid name, unsafe expression, duplicates, and an empty list', async () => {
      const user = userEvent.setup()
      const id = setup('core.setVariables')

      await user.click(screen.getByRole('button', { name: 'Add variable' }))
      await user.type(screen.getByLabelText('variable 1 value'), 'x')
      await user.click(screen.getByRole('button', { name: 'Apply' }))
      expect(await screen.findByText('Name is required')).toBeInTheDocument()
      expect(screen.getByLabelText('variable 1 name')).toHaveAttribute('aria-invalid', 'true')
      expect(nodeConfig(id)).toEqual({ variables: [] })

      await user.type(screen.getByLabelText('variable 1 name'), '1bad')
      expect(await screen.findByText(/Start with a letter or _/)).toBeInTheDocument()

      await user.clear(screen.getByLabelText('variable 1 name'))
      await user.type(screen.getByLabelText('variable 1 name'), 'ok')
      await paste('variable 1 value', '{{ run(); }}', user)
      expect(await screen.findByText(/Only \{\{ path\.to\.value \}\} references/)).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: 'Remove variable 1' }))
      expect(await screen.findByText('Add at least one variable')).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: 'Add variable' }))
      await user.click(screen.getByRole('button', { name: 'Add variable' }))
      await user.type(screen.getByLabelText('variable 1 name'), 'same')
      await user.type(screen.getByLabelText('variable 2 name'), 'same')
      expect(await screen.findByText('Variable names must be unique')).toBeInTheDocument()
    })
  })

  describe('Condition', () => {
    it('configures field, operator and comparison value', async () => {
      const user = userEvent.setup()
      const id = setup('core.condition')
      await user.type(screen.getByLabelText('Field'), 'order.total')
      await user.selectOptions(screen.getByLabelText('Operator'), 'greaterThan')
      await user.type(screen.getByLabelText('Comparison value'), '100')
      await apply(user)

      expect(nodeConfig(id)).toEqual({ field: 'order.total', operator: 'greaterThan', value: '100' })
    })

    it('hides the comparison value for unary operators and drops it from the configuration', async () => {
      const user = userEvent.setup()
      const id = setup('core.condition', { field: 'a', operator: 'equals', value: 'x' })
      await user.selectOptions(screen.getByLabelText('Operator'), 'isEmpty')

      expect(screen.queryByLabelText('Comparison value')).not.toBeInTheDocument()
      await apply(user)
      expect(nodeConfig(id)).toEqual({ field: 'a', operator: 'isEmpty' })

      await user.selectOptions(screen.getByLabelText('Operator'), 'equals')
      await user.click(screen.getByRole('button', { name: 'Apply' }))
      expect(await screen.findByText('Comparison value is required')).toBeInTheDocument()
      expect(nodeConfig(id)).toEqual({ field: 'a', operator: 'isEmpty' })
    })

    it('requires the field', async () => {
      const user = userEvent.setup()
      setup('core.condition')
      await user.type(screen.getByLabelText('Field'), 'x')
      await user.clear(screen.getByLabelText('Field'))
      expect(await screen.findByText('Field is required')).toBeInTheDocument()
    })
  })

  describe('Transform', () => {
    it('offers only the supported operations and saves rows', async () => {
      const user = userEvent.setup()
      const id = setup('core.transform')
      await user.click(screen.getByRole('button', { name: 'Add operation' }))
      const select = screen.getByLabelText('operation 1 operation')
      expect(within(select).getAllByRole('option').map((o) => o.getAttribute('value'))).toEqual([
        'copy', 'toUpperCase', 'toLowerCase', 'trim', 'toNumber', 'toString', 'parseJson', 'stringifyJson', 'length',
      ])
      await user.type(screen.getByLabelText('operation 1 output field'), 'name_upper')
      await paste('operation 1 source', '{{ input.name }}', user)
      await user.selectOptions(select, 'toUpperCase')
      await apply(user)

      expect(nodeConfig(id)).toEqual({ operations: [{ field: 'name_upper', source: '{{ input.name }}', operation: 'toUpperCase' }] })
    })

    it('requires a source', async () => {
      const user = userEvent.setup()
      setup('core.transform')
      await user.click(screen.getByRole('button', { name: 'Add operation' }))
      await user.type(screen.getByLabelText('operation 1 output field'), 'x')
      await user.click(screen.getByRole('button', { name: 'Apply' }))
      expect(await screen.findByText('Source is required')).toBeInTheDocument()
    })
  })

  describe('Logger', () => {
    it('requires a message and saves a template', async () => {
      const user = userEvent.setup()
      const id = setup('core.logger')
      await user.type(screen.getByLabelText('Message'), 'x')
      await user.clear(screen.getByLabelText('Message'))
      expect(await screen.findByText('Message is required')).toBeInTheDocument()

      await paste('Message', 'Processed {{ input.order.id }}', user)
      await apply(user)
      expect(nodeConfig(id)).toEqual({ message: 'Processed {{ input.order.id }}' })
    })

    it('rejects code in expressions', async () => {
      const user = userEvent.setup()
      setup('core.logger')
      await paste('Message', 'x {{ process.exit() }}', user)
      expect(await screen.findByText(/Only \{\{ path\.to\.value \}\} references/)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Apply' })).toBeEnabled() // clicking shows errors, never applies
    })
  })

  describe('HTTP Request', () => {
    it('authenticates with a stored credential instead of a secret in the workflow', async () => {
      const user = userEvent.setup()
      const id = setup('core.httpRequest')
      await user.type(screen.getByLabelText('URL'), 'https://api.example.com/me')
      const picker = screen.getByLabelText('Authentication')
      await screen.findByRole('option', { name: 'Users API (Bearer token)' })
      await user.selectOptions(picker, 'Users API (Bearer token)')
      await apply(user)

      expect(nodeConfig(id)).toEqual({
        method: 'GET', url: 'https://api.example.com/me', headers: [], query: [], timeoutSeconds: 30,
        credentialId: '3f2b8c1e-5d4a-4b6f-9e7d-1a2b3c4d5e6f',
      })
      expect(screen.getByRole('link', { name: 'Manage credentials' })).toHaveAttribute('href', '/credentials')
    })

    it('keeps a saved credential reference while the list loads and refuses literal secrets in headers', async () => {
      const user = userEvent.setup()
      setup('core.httpRequest', {
        method: 'GET', url: 'https://api.example.com', headers: [], query: [], timeoutSeconds: 30,
        credentialId: '00000000-0000-4000-8000-000000000000',
      })
      expect(await screen.findByRole('option', { name: /Unknown credential 00000000/ })).toBeInTheDocument()
      expect(screen.getByLabelText('Authentication')).toHaveValue('00000000-0000-4000-8000-000000000000')

      await user.click(screen.getByRole('button', { name: 'Add header' }))
      await user.type(screen.getByLabelText('header 1 name'), 'Authorization')
      await paste('header 1 value', 'Bearer sk_live_123', user)
      expect(await screen.findByText('Do not store secrets in workflows: use a credential for Authorization')).toBeInTheDocument()
      await paste('header 1 value', 'Bearer {{ input.token }}', user)
      await waitFor(() => expect(screen.queryByText(/Do not store secrets/)).not.toBeInTheDocument())
      await user.clear(screen.getByLabelText('header 1 name'))
      await user.type(screen.getByLabelText('header 1 name'), 'Host')
      expect(await screen.findByText('Managed by the HTTP client; cannot be set')).toBeInTheDocument()
    })

    it('configures method, URL, headers, query parameters, JSON body and timeout', async () => {
      const user = userEvent.setup()
      const id = setup('core.httpRequest')
      await user.selectOptions(screen.getByLabelText('Method'), 'POST')
      await user.type(screen.getByLabelText('URL'), 'https://api.example.com/items')
      await user.click(screen.getByRole('button', { name: 'Add header' }))
      await user.type(screen.getByLabelText('header 1 name'), 'Content-Type')
      await user.type(screen.getByLabelText('header 1 value'), 'application/json')
      await user.click(screen.getByRole('button', { name: 'Add parameter' }))
      await user.type(screen.getByLabelText('parameter 1 name'), 'page')
      await paste('parameter 1 value', '{{ input.page }}', user)
      await paste('JSON body (optional)', '{"name":"{{ input.name }}","tags":["a"]}', user)
      await user.clear(screen.getByLabelText('Timeout (seconds)'))
      await user.type(screen.getByLabelText('Timeout (seconds)'), '60')
      await apply(user)

      expect(nodeConfig(id)).toEqual({
        method: 'POST',
        url: 'https://api.example.com/items',
        headers: [{ name: 'Content-Type', value: 'application/json' }],
        query: [{ name: 'page', value: '{{ input.page }}' }],
        body: { name: '{{ input.name }}', tags: ['a'] },
        timeoutSeconds: 60,
      })
    })

    it('shows field errors for URL, headers, timeout and body JSON', async () => {
      const user = userEvent.setup()
      setup('core.httpRequest')
      await user.selectOptions(screen.getByLabelText('Method'), 'PUT')

      await user.type(screen.getByLabelText('URL'), 'ftp://nope')
      expect(await screen.findByText('Must start with http://, https:// or a {{ }} reference')).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: 'Add header' }))
      await user.type(screen.getByLabelText('header 1 name'), 'Bad Name')
      expect(await screen.findByText('Invalid header name')).toBeInTheDocument()

      await user.clear(screen.getByLabelText('Timeout (seconds)'))
      await user.type(screen.getByLabelText('Timeout (seconds)'), '999')
      expect(await screen.findByText('Timeout must be between 1 and 300 seconds')).toBeInTheDocument()

      await paste('JSON body (optional)', '{"a":', user)
      expect(await screen.findByText(/^Invalid JSON/)).toBeInTheDocument()
      await paste('JSON body (optional)', '{"a":"{{ eval() }}"}', user)
      expect(await screen.findByText(/Only \{\{ path\.to\.value \}\} references/)).toBeInTheDocument()
    })

    it('hides the body for GET and HEAD and omits it from the saved configuration', async () => {
      const user = userEvent.setup()
      const id = setup('core.httpRequest', {
        method: 'POST', url: 'https://x.io', headers: [], query: [], body: { a: 1 }, timeoutSeconds: 30,
      })
      expect(screen.getByLabelText('JSON body (optional)')).toBeInTheDocument()

      await user.selectOptions(screen.getByLabelText('Method'), 'GET')
      expect(screen.queryByLabelText('JSON body (optional)')).not.toBeInTheDocument()
      await apply(user)

      expect(nodeConfig(id)).toEqual({ method: 'GET', url: 'https://x.io', headers: [], query: [], timeoutSeconds: 30 })
    })
  })

  describe('AI Model', () => {
    const CREDENTIAL = '3f2b8c1e-5d4a-4b6f-9e7d-1a2b3c4d5e6f'

    it('configures prompts, model, credential and limits for a text reply', async () => {
      const user = userEvent.setup()
      const id = setup('core.aiModel')
      await user.clear(screen.getByLabelText('Model'))
      await user.type(screen.getByLabelText('Model'), 'gpt-4o')
      await screen.findByRole('option', { name: 'Users API (Bearer token)' })
      await user.selectOptions(screen.getByLabelText('API key'), 'Users API (Bearer token)')
      await paste('System prompt (optional)', 'Be brief about {{ vars.topic }}.', user)
      await paste('User prompt', 'Summarize: {{ input.text }}', user)
      await user.type(screen.getByLabelText('Temperature (optional)'), '0.3')
      await user.type(screen.getByLabelText('Max output tokens (optional)'), '400')
      await user.selectOptions(screen.getByLabelText('Retries'), '1')
      await apply(user)

      expect(nodeConfig(id)).toEqual({
        provider: 'openaiCompatible', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o', credentialId: CREDENTIAL,
        systemPrompt: 'Be brief about {{ vars.topic }}.', userPrompt: 'Summarize: {{ input.text }}', temperature: 0.3,
        maxTokens: 400, responseFormat: 'text', timeoutSeconds: 60, maxRetries: 1,
      })
      expect(screen.queryByLabelText('JSON mode')).not.toBeInTheDocument()
      expect(document.querySelector('datalist#' + screen.getByLabelText('Model').getAttribute('list'))).not.toBeNull()
    })

    it('JSON format reveals the JSON mode and response schema and saves the parsed schema', async () => {
      const user = userEvent.setup()
      const id = setup('core.aiModel')
      await paste('User prompt', 'Classify {{ input.text }}', user)
      await user.selectOptions(screen.getByLabelText('Response format'), 'json')
      await paste('Response schema (optional)',
        '{"type":"object","required":["label"],"properties":{"label":{"enum":["a","b"]}}}', user)
      await user.selectOptions(screen.getByLabelText('JSON mode'), 'prompt')
      await apply(user)

      expect(nodeConfig(id)).toMatchObject({
        responseFormat: 'json', jsonMode: 'prompt',
        responseSchema: { type: 'object', required: ['label'], properties: { label: { enum: ['a', 'b'] } } },
      })
    })

    it('shows field errors for the prompt, base URL, limits and unsafe response schemas', async () => {
      const user = userEvent.setup()
      setup('core.aiModel')
      await user.type(screen.getByLabelText('User prompt'), 'x')
      await user.clear(screen.getByLabelText('User prompt'))
      expect(await screen.findByText('The prompt is required')).toBeInTheDocument()

      await paste('User prompt', '{{ eval(x) }}', user)
      expect(await screen.findByText(/Only \{\{ path\.to\.value \}\} references/)).toBeInTheDocument()
      await paste('User prompt', 'ok', user)

      await user.clear(screen.getByLabelText('Base URL'))
      await user.type(screen.getByLabelText('Base URL'), 'https://api.example.com/v1?key=abc')
      expect(await screen.findByText(/without query, fragment/)).toBeInTheDocument()

      await user.type(screen.getByLabelText('Temperature (optional)'), '3')
      expect(await screen.findByText(/Temperature must be a number from 0 to 2/)).toBeInTheDocument()
      await user.type(screen.getByLabelText('Max output tokens (optional)'), '1.5')
      expect(await screen.findByText(/Max output tokens must be a whole number/)).toBeInTheDocument()

      await user.selectOptions(screen.getByLabelText('Response format'), 'json')
      await paste('Response schema (optional)', '{"$ref":"http://169.254.169.254/"}', user)
      expect(await screen.findByText(/At \/\$ref: keyword '\$ref' is not supported/)).toBeInTheDocument()
      await paste('Response schema (optional)', '{"type":', user)
      expect(await screen.findByText(/^Invalid JSON/)).toBeInTheDocument()
    })

    it('a text response ignores a leftover schema when saving', async () => {
      const user = userEvent.setup()
      const id = setup('core.aiModel')
      await paste('User prompt', 'Hi', user)
      await user.selectOptions(screen.getByLabelText('Response format'), 'json')
      await paste('Response schema (optional)', '{"type":"object"}', user)
      await user.selectOptions(screen.getByLabelText('Response format'), 'text')
      await apply(user)

      expect(nodeConfig(id)).not.toHaveProperty('responseSchema')
      expect(nodeConfig(id)).not.toHaveProperty('jsonMode')
    })
  })

  describe('reopening without losing data', () => {
    const CONFIGS: [string, Record<string, unknown>, string][] = [
      ['core.manualTrigger', { testInput: { a: [1, null] } }, 'Test input (JSON, optional)'],
      ['core.setVariables', { variables: [{ name: 'a', value: '{{ x.y }}' }] }, 'variable 1 value'],
      ['core.condition', { field: 'a.b', operator: 'contains', value: 'z' }, 'Comparison value'],
      ['core.transform', { operations: [{ field: 'o', source: '{{ s }}', operation: 'trim' }] }, 'operation 1 source'],
      ['core.logger', { message: 'hi {{ a }}' }, 'Message'],
      ['core.httpRequest', {
        method: 'DELETE', url: 'https://x.io/{{ id }}', headers: [{ name: 'X-A', value: '1' }], query: [{ name: 'q', value: 'v' }],
        body: { k: 'v' }, timeoutSeconds: 90,
      }, 'URL'],
      ['core.aiModel', {
        provider: 'openaiCompatible', baseUrl: 'http://localhost:11434/v1', model: 'llama3.1:8b', userPrompt: 'Q: {{ input.q }}',
        systemPrompt: 'Be exact.', temperature: 0, maxTokens: 200, responseFormat: 'json', jsonMode: 'jsonObject',
        responseSchema: { type: 'object', properties: { a: { type: 'string' } } }, timeoutSeconds: 45, maxRetries: 0,
        credentialId: '3f2b8c1e-5d4a-4b6f-9e7d-1a2b3c4d5e6f',
      }, 'User prompt'],
    ]

    it.each(CONFIGS)('%s: form shows the saved values, and applying again changes nothing', (type, config, label) => {
      const id = setup(type, config)

      expect(screen.getByLabelText(label)).toBeInTheDocument()
      expect(screen.getByTestId('config-status')).toHaveTextContent('Applied')
      expect(screen.getByRole('button', { name: 'Apply' })).toBeDisabled()
      expect(nodeConfig(id)).toEqual(config)
    })
  })
})

describe('node switching, drafts and unsaved changes', () => {
  beforeEach(() => {
    store().reset()
    store().newWorkflow()
  })

  function twoNodes() {
    const logger = store().addNode('core.logger', { x: 0, y: 0 })!
    act(() => store().applyConfiguration(logger, { message: 'saved message' }))
    const condition = store().addNode('core.condition', { x: 200, y: 0 })!
    act(() => store().applyConfiguration(condition, { field: 'saved.field', operator: 'equals', value: '1' }))
    act(() => store().selectOnly(logger))
    renderWithProviders(<PropertiesPanel />)
    return { logger, condition }
  }

  it('resets the form to the newly selected node, showing that node and none of the previous one', () => {
    const { condition } = twoNodes()
    expect(screen.getByLabelText('Message')).toHaveValue('saved message')

    act(() => store().selectOnly(condition))

    expect(screen.queryByLabelText('Message')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Field')).toHaveValue('saved.field')
    expect(screen.getByLabelText('Comparison value')).toHaveValue('1')
  })

  it('keeps unapplied edits as a draft, restores them when the node is selected again, and flags them as unsaved', async () => {
    const user = userEvent.setup()
    const { logger, condition } = twoNodes()
    expect(selectIsDirty(store())).toBe(true) // newly created workflow
    store().markSaved({ id: 'w', name: 'n', status: 'DRAFT', version: 1, lockVersion: 0 } as never, JSON.stringify([store().name, null, { schemaVersion: 1, nodes: store().nodes.map((n) => ({ id: n.id, type: n.data.nodeType, typeVersion: 1, name: n.data.name, position: { x: n.position.x, y: n.position.y }, configuration: n.data.configuration })), edges: [] }]))
    expect(selectIsDirty(store())).toBe(false)

    await user.type(screen.getByLabelText('Message'), ' + edit')
    expect(screen.getByTestId('config-status')).toHaveTextContent('Edited, not applied')
    expect(selectHasDrafts(store())).toBe(true)
    expect(selectIsDirty(store())).toBe(true)
    expect(nodeConfig(logger)).toEqual({ message: 'saved message' }) // not applied

    act(() => store().selectOnly(condition))
    expect(screen.getByLabelText('Field')).toHaveValue('saved.field')
    expect(selectHasDrafts(store())).toBe(true) // draft survives the switch

    act(() => store().selectOnly(logger))
    expect(screen.getByLabelText('Message')).toHaveValue('saved message + edit')
    expect(screen.getByTestId('config-status')).toHaveTextContent('Edited, not applied')
  })

  it('keeps an invalid draft too and shows its errors when the node is reopened', async () => {
    const user = userEvent.setup()
    const { logger, condition } = twoNodes()
    await user.clear(screen.getByLabelText('Message'))
    act(() => store().selectOnly(condition))
    act(() => store().selectOnly(logger))

    expect(screen.getByLabelText('Message')).toHaveValue('')
    expect(await screen.findByText('Message is required')).toBeInTheDocument()
  })

  it('Revert discards the draft and clears the unsaved state', async () => {
    const user = userEvent.setup()
    const { logger } = twoNodes()
    await user.type(screen.getByLabelText('Message'), 'xyz')
    await user.click(screen.getByRole('button', { name: 'Revert' }))

    expect(screen.getByLabelText('Message')).toHaveValue('saved message')
    expect(selectHasDrafts(store())).toBe(false)
    expect(nodeConfig(logger)).toEqual({ message: 'saved message' })
  })

  it('Apply commits as one undo step', async () => {
    const user = userEvent.setup()
    const { logger } = twoNodes()
    await user.type(screen.getByLabelText('Message'), '!')
    await user.click(screen.getByRole('button', { name: 'Apply' }))
    expect(nodeConfig(logger)).toEqual({ message: 'saved message!' })
    expect(selectHasDrafts(store())).toBe(false)

    act(() => store().undo())
    expect(nodeConfig(logger)).toEqual({ message: 'saved message' })
    act(() => store().selectOnly(logger))
    expect(screen.getByLabelText('Message')).toHaveValue('saved message')
  })

  it('re-syncs the open form when its draft is applied from outside (Save)', async () => {
    const user = userEvent.setup()
    const { logger } = twoNodes()
    await user.type(screen.getByLabelText('Message'), '!')
    expect(screen.getByTestId('config-status')).toHaveTextContent('Edited, not applied')

    act(() => {
      store().applyDrafts()
    })

    expect(nodeConfig(logger)).toEqual({ message: 'saved message!' })
    expect(screen.getByLabelText('Message')).toHaveValue('saved message!')
    expect(screen.getByTestId('config-status')).toHaveTextContent('Applied')
    expect(selectHasDrafts(store())).toBe(false)
  })

  it('applyDrafts commits valid drafts and reports invalid ones without losing them', async () => {
    const user = userEvent.setup()
    const { logger, condition } = twoNodes()
    await user.type(screen.getByLabelText('Message'), '!')
    act(() => store().selectOnly(condition))
    await user.clear(screen.getByLabelText('Field'))

    let result!: { ok: boolean; invalidNodeIds: string[] }
    act(() => {
      result = store().applyDrafts()
    })

    expect(result).toEqual({ ok: false, invalidNodeIds: [condition] })
    expect(nodeConfig(logger)).toEqual({ message: 'saved message!' })
    expect(nodeConfig(condition)).toEqual({ field: 'saved.field', operator: 'equals', value: '1' })
    expect(Object.keys(store().drafts)).toEqual([condition])
  })

  it('drops the draft of a deleted node', async () => {
    const user = userEvent.setup()
    const { logger } = twoNodes()
    await user.type(screen.getByLabelText('Message'), 'x')
    expect(Object.keys(store().drafts)).toEqual([logger])

    act(() => store().deleteSelected())

    expect(store().drafts).toEqual({})
  })

  it('unknown node types show their configuration read-only and keep it unchanged', () => {
    store().load({
      id: 'w', name: 'n', status: 'DRAFT', version: 1, lockVersion: 0, createdAt: 't', updatedAt: 't',
      definition: {
        schemaVersion: 1,
        nodes: [{ id: 'x', type: 'vendor.custom', typeVersion: 3, name: 'X', position: { x: 0, y: 0 }, configuration: { keep: [1] } }],
        edges: [],
      },
    })
    act(() => store().selectOnly('x'))
    renderWithProviders(<PropertiesPanel />)

    expect(screen.getByText(/no configuration form/)).toBeInTheDocument()
    expect(screen.queryByRole('form')).not.toBeInTheDocument()
    expect(nodeConfig('x')).toEqual({ keep: [1] })
  })
})
