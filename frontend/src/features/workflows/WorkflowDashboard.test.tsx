import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, type AxiosResponse } from 'axios'
import { Link, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as samples from '@/lib/api/samples'
import * as triggers from '@/lib/api/triggers'
import * as api from '@/lib/api/workflows'
import { usePreferences } from '@/features/settings/preferences'
import { renderWithProviders } from '@/test/renderWithProviders'
import { WorkflowListPage } from './WorkflowListPage'

const row: api.WorkflowSummary = {
  id: 'orders',
  name: 'Orders',
  status: 'DRAFT',
  description: 'Process incoming orders',
  version: 1,
  lockVersion: 2,
  nodeCount: 2,
  edgeCount: 1,
  createdAt: '2026-10-26T00:00:00Z',
  updatedAt: '2026-10-26T00:00:00Z',
}
function Location() {
  const location = useLocation()
  return (
    <output data-testid="location">
      {location.pathname}
      {location.search}
    </output>
  )
}
function open(route = '/') {
  return renderWithProviders(
    <>
      <WorkflowListPage />
      <Location />
    </>,
    { route },
  )
}
function response(items = [row], page = 0, total = items.length): api.PageResponse<api.WorkflowSummary> {
  return { items, page, size: 12, totalElements: total, totalPages: Math.ceil(total / 12) }
}

function problem(status: number, data: Record<string, unknown> = {}) {
  return new AxiosError('failed', String(status), undefined, undefined, { status, data: { status, ...data } } as AxiosResponse)
}

/** Opens the "More actions" menu of a workflow card and returns the menu. */
async function openMenu(name: string) {
  await userEvent.click(await screen.findByRole('button', { name: `More actions for ${name}` }))
  return screen.getByRole('menu', { name: `More actions for ${name}` })
}
const choose = (menu: HTMLElement, item: string) => userEvent.click(within(menu).getByRole('menuitem', { name: item }))

describe('workflow dashboard controls', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    usePreferences.getState().reset()
    vi.spyOn(api, 'listWorkflows').mockImplementation(async (params) =>
      response(params?.status?.includes('ARCHIVED') ? [] : [row]),
    )
  })

  it('searches on the server, resets pagination, and preserves the search in the URL', async () => {
    open()
    await screen.findByRole('link', { name: /^Orders,/ })
    await userEvent.type(screen.getByRole('textbox', { name: 'Search workflows' }), 'invoice')
    await waitFor(() =>
      expect(api.listWorkflows).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'invoice', page: 0, size: 12 }),
      ),
    )
    expect(screen.getByTestId('location')).toHaveTextContent('q=invoice')
    await userEvent.click(screen.getByRole('button', { name: 'Clear search' }))
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/))
  })

  it('restores filters and sorting from the URL', async () => {
    open('/?q=Orders&status=ACTIVE&sort=name%2Casc&view=list')
    await waitFor(() =>
      expect(api.listWorkflows).toHaveBeenCalledWith({
        name: 'Orders',
        status: ['ACTIVE'],
        page: 0,
        size: 12,
        sort: 'name,asc',
      }),
    )
    expect(screen.getByRole('button', { name: 'Active' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'List view' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('combobox', { name: 'Sort workflows' })).toHaveValue('name,asc')
  })

  it('changes status and sort without fetching every workflow', async () => {
    open()
    await userEvent.click(screen.getByRole('button', { name: 'Drafts' }))
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Sort workflows' }), 'name,desc')
    await waitFor(() =>
      expect(api.listWorkflows).toHaveBeenCalledWith(
        expect.objectContaining({ status: ['DRAFT'], sort: 'name,desc', size: 12 }),
      ),
    )
    expect(screen.getByTestId('location')).toHaveTextContent('status=DRAFT')
  })

  it('paginates server results and can return to the previous page', async () => {
    vi.mocked(api.listWorkflows).mockImplementation(async (params) =>
      response([{ ...row, name: params?.page ? 'Second page' : 'Orders' }], params?.page ?? 0, 13),
    )
    open()
    await screen.findByRole('link', { name: /^Orders,/ })
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }))
    await screen.findByRole('link', { name: /^Second page,/ })
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Previous page' }))
    await screen.findByRole('link', { name: /^Orders,/ })
  })

  it('recovers an out-of-range page after the last item is removed', async () => {
    vi.mocked(api.listWorkflows).mockImplementation(async (params) => response(params?.page ? [] : [row]))
    open('/?page=5')
    await screen.findByRole('link', { name: /^Orders,/ })
    expect(screen.getByTestId('location')).toHaveTextContent('page=0')
  })

  it('duplicates a workflow from its menu and opens the new draft', async () => {
    vi.spyOn(api, 'duplicateWorkflow').mockResolvedValue({ id: 'copy' } as api.WorkflowResponse)
    open()
    await choose(await openMenu('Orders'), 'Duplicate')
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/workflows/copy'))
    expect(api.duplicateWorkflow).toHaveBeenCalledWith('orders')
  })

  it('runs the saved workflow and opens execution details', async () => {
    vi.spyOn(api, 'executeWorkflow').mockResolvedValue({ executionId: 'run-1' })
    open()
    await userEvent.click(await screen.findByRole('button', { name: 'Run Orders' }))
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/executions/run-1'))
    expect(api.executeWorkflow).toHaveBeenCalledWith('orders')
  })

  it.each(['run', 'sample'] as const)('does not navigate back after leaving a pending %s action', async (kind) => {
    let finish!: (result: api.WorkflowResponse & { executionId: string }) => void
    const pending = new Promise<api.WorkflowResponse & { executionId: string }>((resolve) => { finish = resolve })
    vi.spyOn(api, 'executeWorkflow').mockReturnValue(pending)
    vi.spyOn(samples, 'createSample').mockReturnValue(pending)
    renderWithProviders(<>
      <Link to="/elsewhere">Leave dashboard</Link>
      <Routes>
        <Route path="/" element={<WorkflowListPage />} />
        <Route path="*" element={<p>Another page</p>} />
      </Routes>
      <Location />
    </>)
    await userEvent.click(await screen.findByRole('button', { name: kind === 'run' ? 'Run Orders' : 'Try the AI sample' }))
    await userEvent.click(screen.getByRole('link', { name: 'Leave dashboard' }))
    await screen.findByText('Another page')
    await act(async () => finish({ id: 'sample', executionId: 'late-run' } as api.WorkflowResponse & { executionId: string }))
    expect(screen.getByTestId('location')).toHaveTextContent('/elsewhere')
  })

  it('shows actionable validation errors when a run cannot start', async () => {
    vi.spyOn(api, 'executeWorkflow').mockRejectedValue(problem(422))
    open()
    await userEvent.click(await screen.findByRole('button', { name: 'Run Orders' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Open the editor and validate')
  })

  it('explains a busy server when too many executions are queued', async () => {
    vi.spyOn(api, 'executeWorkflow').mockRejectedValue(problem(503))
    open()
    await userEvent.click(await screen.findByRole('button', { name: 'Run Orders' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('server is busy')
  })

  it('distinguishes an empty search from an empty workspace and clears filters', async () => {
    vi.mocked(api.listWorkflows).mockResolvedValue(response([]))
    open('/?q=missing&status=ACTIVE')
    await screen.findByText('No matching workflows')
    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    await screen.findByText(/No workflows yet/)
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/)
    expect(screen.getByRole('button', { name: 'Create your first workflow' })).toBeInTheDocument()
  })

  it('switches between grid and list without changing the data query', async () => {
    open()
    await screen.findByRole('link', { name: /^Orders,/ })
    const calls = vi.mocked(api.listWorkflows).mock.calls.length
    await userEvent.click(screen.getByRole('button', { name: 'List view' }))
    expect(screen.getByTestId('location')).toHaveTextContent('view=list')
    expect(within(screen.getByRole('list', { name: 'Workflows' })).getAllByRole('listitem')).toHaveLength(1)
    expect(api.listWorkflows).toHaveBeenCalledTimes(calls)
  })

  it('creates the AI sample workflow and opens it in the editor', async () => {
    vi.spyOn(samples, 'createSample').mockResolvedValue({ id: 'sample-1' } as api.WorkflowResponse)
    open()
    await userEvent.click(await screen.findByRole('button', { name: 'Try the AI sample' }))

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/workflows/sample-1'))
    expect(samples.createSample).toHaveBeenCalledWith('ai-review-triage')
  })

  it('reports a failed sample creation', async () => {
    vi.spyOn(samples, 'createSample').mockRejectedValue(new Error('offline'))
    open()
    await userEvent.click(await screen.findByRole('button', { name: 'Try the AI sample' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not create the sample')
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/)
  })
})

describe('workflow dashboard: status and last run', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    usePreferences.getState().reset()
  })

  it('shows lifecycle status, last execution result and a link to it', async () => {
    const recent = new Date(Date.now() - 5 * 60_000).toISOString()
    vi.spyOn(api, 'listWorkflows').mockResolvedValue(response([
      { ...row, id: 'a', name: 'Active one', status: 'ACTIVE', lastExecution: { id: 'run-a', status: 'SUCCEEDED', triggerType: 'WEBHOOK', createdAt: recent, finishedAt: recent } },
      { ...row, id: 'b', name: 'Broken one', status: 'INACTIVE', lastExecution: { id: 'run-b', status: 'FAILED', triggerType: 'MANUAL', createdAt: recent, finishedAt: recent } },
      { ...row, id: 'c', name: 'Draft one', status: 'DRAFT' },
      { ...row, id: 'd', name: 'Busy one', status: 'ACTIVE', lastExecution: { id: 'run-d', status: 'RUNNING', triggerType: 'SCHEDULE', createdAt: recent } },
    ]))
    open()
    const cards = await screen.findAllByRole('listitem')

    expect(within(cards[0]).getByTestId('workflow-status')).toHaveTextContent('Active')
    expect(within(cards[1]).getByTestId('workflow-status')).toHaveTextContent('Inactive')
    expect(within(cards[2]).getByTestId('workflow-status')).toHaveTextContent('Draft')
    const lastRun = within(cards[0]).getByRole('link', { name: 'Last run of Active one' })
    expect(lastRun).toHaveAttribute('href', '/executions/run-a')
    expect(lastRun).toHaveTextContent('Succeeded')
    expect(lastRun).toHaveTextContent('5 min ago')
    expect(within(cards[1]).getByRole('link', { name: 'Last run of Broken one' })).toHaveTextContent('Failed')
    expect(within(cards[3]).getByRole('link', { name: 'Last run of Busy one' })).toHaveTextContent('Running')
    expect(within(cards[2]).queryByRole('link', { name: /Last run/ })).not.toBeInTheDocument()
    expect(within(cards[2]).getByText('Not run yet')).toBeInTheDocument()
  })

  it('offers each status only the actions that make sense', async () => {
    vi.spyOn(api, 'listWorkflows').mockResolvedValue(response([
      { ...row, id: 'a', name: 'Active one', status: 'ACTIVE' },
      { ...row, id: 'c', name: 'Draft one', status: 'DRAFT' },
      { ...row, id: 'z', name: 'Archived one', status: 'ARCHIVED' },
    ]))
    open()
    const items = async (name: string) => {
      const menu = await openMenu(name)
      const labels = within(menu).getAllByRole('menuitem').map((i) => i.textContent)
      await userEvent.keyboard('{Escape}')
      return labels
    }

    expect(await items('Active one')).toEqual(['Rename…', 'Duplicate', 'Deactivate…', 'Archive…'])
    expect(await items('Draft one')).toEqual(['Rename…', 'Duplicate', 'Activate', 'Archive…'])
    expect(await items('Archived one')).toEqual(['Restore as draft', 'Duplicate', 'Delete permanently…'])
    expect(screen.queryByRole('button', { name: 'Run Archived one' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Run Draft one' })).toBeInTheDocument()
  })
})

describe('workflow dashboard: creating and renaming', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    usePreferences.getState().reset()
    vi.spyOn(api, 'listWorkflows').mockImplementation(async () => response())
  })

  it('creates a workflow from a dialog and opens it in the editor', async () => {
    const create = vi.spyOn(api, 'createWorkflow').mockResolvedValue({ id: 'new-1' } as api.WorkflowResponse)
    open()
    await userEvent.click(await screen.findByRole('button', { name: 'New workflow' }))
    const dialog = screen.getByRole('dialog', { name: 'New workflow' })
    await userEvent.type(within(dialog).getByLabelText('Name'), '  Weekly report ')
    await userEvent.type(within(dialog).getByLabelText('Description (optional)'), 'Send it on Mondays')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create workflow' }))

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/workflows/new-1'))
    expect(create).toHaveBeenCalledWith({ name: 'Weekly report', description: 'Send it on Mondays' })
  })

  it('refuses an empty name and keeps the dialog open with the reason', async () => {
    const create = vi.spyOn(api, 'createWorkflow')
    open()
    await userEvent.click(await screen.findByRole('button', { name: 'New workflow' }))
    const dialog = screen.getByRole('dialog', { name: 'New workflow' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create workflow' }))

    expect(within(dialog).getByRole('alert')).toHaveTextContent('Give the workflow a name.')
    expect(within(dialog).getByLabelText('Name')).toHaveAttribute('aria-invalid', 'true')
    expect(within(dialog).getByLabelText('Name')).toHaveFocus()
    expect(create).not.toHaveBeenCalled()
  })

  it('keeps what was typed and shows the server error when creating fails', async () => {
    vi.spyOn(api, 'createWorkflow').mockRejectedValue(problem(400, { detail: 'Name must be 1 to 200 characters.' }))
    open()
    await userEvent.click(await screen.findByRole('button', { name: 'New workflow' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText('Name'), 'X')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create workflow' }))

    expect(await within(dialog).findByText('Action failed: Name must be 1 to 200 characters.')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Name')).toHaveValue('X')
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/)
  })

  it('renames through the latest server version and confirms', async () => {
    const rename = vi.spyOn(api, 'renameWorkflow').mockResolvedValue({ id: 'orders' } as api.WorkflowResponse)
    open()
    await choose(await openMenu('Orders'), 'Rename…')
    const dialog = screen.getByRole('dialog', { name: 'Rename workflow' })
    expect(within(dialog).getByLabelText('Name')).toHaveValue('Orders')
    expect(within(dialog).getByLabelText('Description (optional)')).toHaveValue('Process incoming orders')
    await userEvent.clear(within(dialog).getByLabelText('Name'))
    await userEvent.type(within(dialog).getByLabelText('Name'), 'Order intake')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save name' }))

    expect(await screen.findByText('Renamed to “Order intake”.')).toBeInTheDocument()
    expect(rename).toHaveBeenCalledWith('orders', 'Order intake', 'Process incoming orders')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('reports a rename that lost a race with another change', async () => {
    vi.spyOn(api, 'renameWorkflow').mockRejectedValue(problem(409, { detail: 'Workflow orders was modified by someone else.' }))
    open()
    await choose(await openMenu('Orders'), 'Rename…')
    await userEvent.type(screen.getByLabelText('Name'), ' 2')
    await userEvent.click(screen.getByRole('button', { name: 'Save name' }))
    expect(await screen.findByText('Workflow orders was modified by someone else.')).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('closes dialogs with Escape and with a click on the backdrop, without doing anything', async () => {
    const create = vi.spyOn(api, 'createWorkflow')
    open()
    await userEvent.click(await screen.findByRole('button', { name: 'New workflow' }))
    fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true })) // what the browser sends for Escape
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'New workflow' }))
    await userEvent.click(screen.getByRole('dialog')) // the <dialog> itself is the backdrop area
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(create).not.toHaveBeenCalled()
  })
})

describe('workflow dashboard: activation, archiving and deletion', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    usePreferences.getState().reset()
  })

  it('activates a draft with one click and refreshes', async () => {
    vi.spyOn(api, 'listWorkflows').mockImplementation(async () => response([{ ...row, status: 'DRAFT' }]))
    const activate = vi.spyOn(triggers, 'activateWorkflow').mockResolvedValue({ id: 'orders' } as api.WorkflowResponse)
    open()
    await choose(await openMenu('Orders'), 'Activate')

    expect(await screen.findByText(/“Orders” is active/)).toBeInTheDocument()
    expect(activate).toHaveBeenCalledWith('orders')
    await waitFor(() => expect(api.listWorkflows).toHaveBeenCalledTimes(4 * 2 + 2 - 2 + 2)) // list + counts refetched
  })

  it('tells the user what to do when a workflow cannot be activated', async () => {
    vi.spyOn(api, 'listWorkflows').mockImplementation(async () => response([{ ...row, status: 'DRAFT' }]))
    vi.spyOn(triggers, 'activateWorkflow').mockRejectedValue(problem(422))
    open()
    await choose(await openMenu('Orders'), 'Activate')
    expect(await screen.findByRole('alert')).toHaveTextContent('cannot be activated yet')
  })

  it('asks before deactivating and explains the consequence', async () => {
    vi.spyOn(api, 'listWorkflows').mockImplementation(async () => response([{ ...row, status: 'ACTIVE' }]))
    const deactivate = vi.spyOn(triggers, 'deactivateWorkflow').mockResolvedValue({ id: 'orders' } as api.WorkflowResponse)
    open()
    await choose(await openMenu('Orders'), 'Deactivate…')
    const dialog = screen.getByRole('dialog', { name: 'Deactivate “Orders”?' })
    expect(dialog).toHaveTextContent('webhook will stop accepting requests')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Keep active' }))
    expect(deactivate).not.toHaveBeenCalled()

    await choose(await openMenu('Orders'), 'Deactivate…')
    await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }))
    expect(await screen.findByText(/“Orders” is inactive/)).toBeInTheDocument()
    expect(deactivate).toHaveBeenCalledWith('orders')
  })

  it('does not archive when confirmation is declined', async () => {
    vi.spyOn(api, 'listWorkflows').mockImplementation(async () => response())
    const archive = vi.spyOn(api, 'archiveWorkflow').mockResolvedValue()
    open()
    await choose(await openMenu('Orders'), 'Archive…')
    expect(screen.getByRole('dialog')).toHaveTextContent('Automatic triggers will stop')
    await userEvent.click(screen.getByRole('button', { name: 'Keep workflow' }))
    expect(archive).not.toHaveBeenCalled()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('archives and refreshes the list while explaining how to restore', async () => {
    vi.spyOn(api, 'listWorkflows').mockImplementation(async () => response())
    vi.spyOn(api, 'archiveWorkflow').mockImplementation(async () => {
      vi.mocked(api.listWorkflows).mockImplementation(async () => response([]))
    })
    open()
    await choose(await openMenu('Orders'), 'Archive…')
    await userEvent.click(screen.getByRole('button', { name: 'Archive workflow' }))
    await screen.findByText(/Restore it from the Archived tab/)
    await waitFor(() => expect(screen.queryByRole('link', { name: /^Orders,/ })).not.toBeInTheDocument())
    expect(api.archiveWorkflow).toHaveBeenCalledWith('orders')
  })

  it('restores archived workflows and never offers to run or rename them', async () => {
    vi.spyOn(api, 'listWorkflows').mockImplementation(async () => response([{ ...row, status: 'ARCHIVED' }]))
    vi.spyOn(api, 'restoreWorkflow').mockResolvedValue({ id: 'orders', status: 'DRAFT' } as api.WorkflowResponse)
    open('/?status=ARCHIVED')
    expect(screen.queryByRole('button', { name: 'Run Orders' })).not.toBeInTheDocument()
    const menu = await openMenu('Orders')
    expect(within(menu).queryByRole('menuitem', { name: /Rename/ })).not.toBeInTheDocument()
    await choose(menu, 'Restore as draft')
    expect(await screen.findByText(/restored as a draft/)).toBeInTheDocument()
    expect(api.restoreWorkflow).toHaveBeenCalledWith('orders')
  })

  it('deletes permanently only after a destructive confirmation, keeping focus on the safe choice', async () => {
    vi.spyOn(api, 'listWorkflows').mockImplementation(async () => response([{ ...row, status: 'ARCHIVED' }]))
    const remove = vi.spyOn(api, 'deleteWorkflowPermanently').mockResolvedValue()
    open('/?status=ARCHIVED')
    await choose(await openMenu('Orders'), 'Delete permanently…')
    const dialog = screen.getByRole('dialog', { name: 'Delete “Orders” permanently?' })
    expect(dialog).toHaveTextContent('cannot be undone')
    expect(within(dialog).getByRole('button', { name: 'Keep it' })).toHaveFocus()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Keep it' }))
    expect(remove).not.toHaveBeenCalled()

    await choose(await openMenu('Orders'), 'Delete permanently…')
    await userEvent.click(screen.getByRole('button', { name: 'Delete permanently' }))
    expect(await screen.findByText(/execution history were deleted/)).toBeInTheDocument()
    expect(remove).toHaveBeenCalledWith('orders')
  })

  it('keeps the confirmation open with the reason when deleting fails', async () => {
    vi.spyOn(api, 'listWorkflows').mockImplementation(async () => response([{ ...row, status: 'ARCHIVED' }]))
    vi.spyOn(api, 'deleteWorkflowPermanently').mockRejectedValue(problem(404))
    open('/?status=ARCHIVED')
    await choose(await openMenu('Orders'), 'Delete permanently…')
    await userEvent.click(screen.getByRole('button', { name: 'Delete permanently' }))
    expect(await within(screen.getByRole('dialog')).findByRole('alert')).toHaveTextContent('no longer exists')
  })
})

describe('workflow dashboard: loading, errors, shortcuts and preferences', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    usePreferences.getState().reset()
  })

  it('shows a loading skeleton with a status message, then the list', async () => {
    const waiting: ((value: api.PageResponse<api.WorkflowSummary>) => void)[] = []
    vi.spyOn(api, 'listWorkflows').mockImplementation(() => new Promise((resolve) => { waiting.push(resolve) }))
    open()
    expect((await screen.findByText('Loading workflows…')).closest('[role="status"]')).not.toBeNull()
    waiting.forEach((resolve) => resolve(response()))
    await screen.findByRole('link', { name: /^Orders,/ })
    expect(screen.queryByText('Loading workflows…')).not.toBeInTheDocument()
  })

  it('offers a retry when the list cannot be loaded', async () => {
    const list = vi.spyOn(api, 'listWorkflows').mockRejectedValue(new Error('down'))
    open()
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load workflows.')
    list.mockImplementation(async () => response())
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await screen.findByRole('link', { name: /^Orders,/ })
  })

  it('"/" focuses the search and "n" opens the new workflow dialog, but not while typing', async () => {
    vi.spyOn(api, 'listWorkflows').mockImplementation(async () => response())
    open()
    await screen.findByRole('link', { name: /^Orders,/ })

    await userEvent.keyboard('/')
    expect(screen.getByRole('textbox', { name: 'Search workflows' })).toHaveFocus()
    await userEvent.keyboard('n') // typed into the search field, not a shortcut
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Search workflows' })).toHaveValue('n')

    await userEvent.tab({ shift: true })
    ;(document.activeElement as HTMLElement).blur()
    await userEvent.keyboard('n')
    expect(screen.getByRole('dialog', { name: 'New workflow' })).toBeInTheDocument()
    expect(screen.getByLabelText('Name')).toHaveFocus()
  })

  it('uses the saved defaults for view, sort and page size, and the URL overrides them', async () => {
    usePreferences.getState().update({ defaultView: 'list', defaultSort: 'name,asc', pageSize: 24 })
    vi.spyOn(api, 'listWorkflows').mockImplementation(async () => response())
    open()
    await screen.findByRole('link', { name: /^Orders,/ })

    expect(api.listWorkflows).toHaveBeenCalledWith(expect.objectContaining({ size: 24, sort: 'name,asc' }))
    expect(screen.getByRole('button', { name: 'List view' })).toHaveAttribute('aria-pressed', 'true')
    await userEvent.click(screen.getByRole('button', { name: 'Grid view' }))
    expect(screen.getByRole('button', { name: 'Grid view' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTestId('location')).toHaveTextContent('view=grid')
  })

  it('navigates from a card to the editor, the execution history and the last run', async () => {
    vi.spyOn(api, 'listWorkflows').mockImplementation(async () => response([
      { ...row, lastExecution: { id: 'run-9', status: 'SUCCEEDED', triggerType: 'MANUAL', createdAt: new Date().toISOString() } },
    ]))
    open()
    expect(await screen.findByRole('link', { name: /^Orders,/ })).toHaveAttribute('href', '/workflows/orders')
    expect(screen.getByRole('link', { name: 'Execution history of Orders' })).toHaveAttribute('href', '/workflows/orders/executions')
    expect(screen.getByRole('link', { name: 'Last run of Orders' })).toHaveAttribute('href', '/executions/run-9')
  })
})
