import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { PropsWithChildren } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { ValidationResult, WorkflowResponse } from '@/lib/api/workflows'

import { DEFAULT_VIEWPORT, selectIsDirty, useEditorStore } from './editorStore'
import { useSaveWorkflow } from './useSaveWorkflow'
import { useWorkflowValidation } from './useWorkflowValidation'

const api = vi.hoisted(() => ({ createWorkflow: vi.fn(), updateWorkflow: vi.fn(), validateWorkflow: vi.fn() }))
vi.mock('@/lib/api/workflows', () => api)

const workflow: WorkflowResponse = {
  id: 'first', name: 'First', status: 'DRAFT', version: 1, lockVersion: 0,
  createdAt: 't', updatedAt: 't', definition: { schemaVersion: 1, nodes: [], edges: [] },
}
const valid: ValidationResult = { valid: true, errorCount: 0, warningCount: 0, issues: [], executionOrder: [] }
const store = () => useEditorStore.getState()

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return ({ children }: PropsWithChildren) => (
    <QueryClientProvider client={client}><MemoryRouter>{children}</MemoryRouter></QueryClientProvider>
  )
}

describe('editor asynchronous responses', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    store().load(workflow)
  })

  it.each(['another workflow', 'the same workflow reopened', 'a new draft'])(
    'does not apply a pending save to %s', async (destination) => {
      const pending = deferred<WorkflowResponse>()
      api.updateWorkflow.mockReturnValue(pending.promise)
      const { result } = renderHook(() => useSaveWorkflow(), { wrapper: wrapper() })
      act(() => result.current.mutate(DEFAULT_VIEWPORT))
      await waitFor(() => expect(api.updateWorkflow).toHaveBeenCalledOnce())

      act(() => {
        if (destination === 'a new draft') store().newWorkflow()
        else store().load({ ...workflow, id: destination === 'another workflow' ? 'second' : workflow.id })
        store().setName('Current edits')
      })
      const before = store()
      await act(async () => pending.resolve({ ...workflow, lockVersion: 1 }))
      await waitFor(() => expect(result.current.isSuccess).toBe(true))
      expect(store()).toBe(before)
    },
  )

  it('keeps edits made during a save dirty while accepting its new lock version', async () => {
    const pending = deferred<WorkflowResponse>()
    api.updateWorkflow.mockReturnValue(pending.promise)
    const { result } = renderHook(() => useSaveWorkflow(), { wrapper: wrapper() })
    act(() => result.current.mutate(DEFAULT_VIEWPORT))
    await waitFor(() => expect(api.updateWorkflow).toHaveBeenCalledOnce())
    act(() => store().setName('Edited while saving'))
    await act(async () => pending.resolve({ ...workflow, lockVersion: 1 }))
    await waitFor(() => expect(store().lockVersion).toBe(1))
    expect(store().name).toBe('Edited while saving')
    expect(selectIsDirty(store())).toBe(true)
  })

  it.each(['success', 'failure'])(
    'ignores a validation %s after loading another workflow', async (outcome) => {
      const pending = deferred<ValidationResult>()
      api.validateWorkflow.mockReturnValue(pending.promise)
      const { result } = renderHook(() => useWorkflowValidation(60_000))
      let validation!: Promise<void>
      act(() => { validation = result.current() })
      act(() => store().load({ ...workflow, id: 'second' }))
      await act(async () => {
        if (outcome === 'success') pending.resolve(valid)
        else pending.reject(new Error('offline'))
        await validation
      })
      expect(store().validation).toBeNull()
      expect(store().validationStatus).toBe('idle')
    },
  )

  it('ignores validation after its editor unmounts', async () => {
    const pending = deferred<ValidationResult>()
    api.validateWorkflow.mockReturnValue(pending.promise)
    const { result, unmount } = renderHook(() => useWorkflowValidation(60_000))
    let validation!: Promise<void>
    act(() => { validation = result.current() })
    unmount()
    await act(async () => { pending.resolve(valid); await validation })
    expect(store().validation).toBeNull()
    expect(store().validationStatus).toBe('idle')
  })
})
