import { expect, test } from '@playwright/test'

import { Api, unique } from './support'

let api: Api
test.beforeEach(async ({ request }) => {
  api = new Api(request)
})
test.afterEach(async () => api.cleanup())

test.describe('editor (browser)', () => {
  test('create → save → reload → execute', async ({ page }) => {
    const name = unique('E2E UI workflow')

    // Create from the dashboard.
    await page.goto('/')
    await page.getByRole('button', { name: 'New workflow' }).first().click()
    const dialog = page.getByRole('dialog', { name: 'New workflow' })
    await dialog.getByLabel('Name').fill(name)
    await dialog.getByRole('button', { name: 'Create workflow' }).click()
    await expect(page).toHaveURL(/\/workflows\/[0-9a-f-]{36}$/)
    const workflowId = page.url().split('/').at(-1)!
    api.track(workflowId)

    // Build Manual Trigger → Logger with the palette and a drag between the handles.
    const palette = page.getByLabel('Node palette')
    await palette.getByText('Manual Trigger', { exact: true }).dblclick()
    await palette.getByText('Logger', { exact: true }).dblclick()
    const nodes = page.getByTestId('workflow-node')
    await expect(nodes).toHaveCount(2)
    const trigger = nodes.filter({ hasText: 'Manual Trigger' })
    const logger = nodes.filter({ hasText: 'Logger' })
    await trigger.locator('.react-flow__handle.source').dragTo(logger.locator('.react-flow__handle.target'))
    await expect(page.locator('.react-flow__edge')).toHaveCount(1)

    // Configure the logger in the properties panel.
    await logger.click()
    const properties = page.getByRole('complementary', { name: 'Properties' })
    await properties.getByLabel('Message').fill('Hello from the browser suite')

    await page.getByRole('button', { name: 'Save' }).click()
    await expect(page.getByTestId('dirty-indicator')).toHaveText('All changes saved')

    // Reload: everything comes back from the server.
    await page.reload()
    await expect(page.getByLabel('Workflow name')).toHaveValue(name)
    await expect(nodes).toHaveCount(2)
    await expect(page.locator('.react-flow__edge')).toHaveCount(1)
    await logger.click()
    await expect(properties.getByLabel('Message')).toHaveValue('Hello from the browser suite')

    const saved = await api.get<{ definition: { nodes: { type: string }[]; edges: unknown[] } }>(`/workflows/${workflowId}`)
    expect(saved.definition.nodes.map((n) => n.type).sort()).toEqual(['core.logger', 'core.manualTrigger'])
    expect(saved.definition.edges).toHaveLength(1)

    // Execute: live status reaches the canvas and the execution panel.
    await page.getByRole('button', { name: 'Run' }).click()
    const execution = page.getByRole('region', { name: 'Execution' })
    await expect(execution.getByText('Succeeded', { exact: false }).first()).toBeVisible()
    await expect(logger.getByRole('img', { name: 'Status: SUCCEEDED' })).toBeVisible()
    await expect(execution.getByRole('list', { name: 'Execution order' }).getByRole('button')).toHaveCount(2)
    await execution.getByRole('button', { name: /^2 Logger Succeeded/ }).click()
    await expect(execution.getByRole('heading', { name: 'Logger', level: 3 })).toBeVisible()
    await expect(execution.getByText('Hello from the browser suite').first()).toBeVisible()

    const [run] = await api.executions(workflowId)
    expect(run).toMatchObject({ status: 'SUCCEEDED', triggerType: 'MANUAL' })
  })
})
