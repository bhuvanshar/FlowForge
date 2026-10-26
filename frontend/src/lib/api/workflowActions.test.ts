import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import { archiveWorkflow, deleteWorkflowPermanently, duplicateWorkflow, listWorkflows, renameWorkflow, restoreWorkflow } from './workflows'
import { getServerSettings } from './settings'

describe('workflow management API contracts', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('archives without requesting permanent deletion', async () => {
    const request = vi.spyOn(apiClient, 'delete').mockResolvedValue({ status: 204 })
    await archiveWorkflow('a')
    expect(request).toHaveBeenCalledWith('/workflows/a')
  })

  it('duplicates using the existing endpoint', async () => {
    const request = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { id: 'copy' } })
    expect(await duplicateWorkflow('a')).toEqual({ id: 'copy' })
    expect(request).toHaveBeenCalledWith('/workflows/a/duplicate')
  })

  it('restores the latest server definition and lock version as a draft', async () => {
    const current = {
      name: 'Orders',
      description: 'Latest description',
      status: 'ARCHIVED',
      lockVersion: 9,
      definition: { schemaVersion: 1, nodes: [], edges: [] },
    }
    vi.spyOn(apiClient, 'get').mockResolvedValue({ data: current })
    const update = vi.spyOn(apiClient, 'put').mockResolvedValue({ data: { ...current, status: 'DRAFT' } })
    await restoreWorkflow('a')
    expect(update).toHaveBeenCalledWith('/workflows/a', { ...current, status: 'DRAFT' })
  })

  it('does not overwrite a workflow another client already restored', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { id: 'a', status: 'ACTIVE' } })
    const update = vi.spyOn(apiClient, 'put')
    expect(await restoreWorkflow('a')).toEqual({ id: 'a', status: 'ACTIVE' })
    expect(update).not.toHaveBeenCalled()
  })

  it('deletes permanently only with the explicit permanent flag', async () => {
    const request = vi.spyOn(apiClient, 'delete').mockResolvedValue({ status: 204 })
    await deleteWorkflowPermanently('a')
    expect(request).toHaveBeenCalledWith('/workflows/a', { params: { permanent: true } })
  })

  it('renames with the latest definition and lock version so nothing else changes', async () => {
    const definition = { schemaVersion: 1, nodes: [{ id: 'n' }], edges: [] }
    vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { id: 'a', name: 'Old', status: 'ACTIVE', lockVersion: 4, definition } })
    const update = vi.spyOn(apiClient, 'put').mockResolvedValue({ data: { id: 'a' } })
    await renameWorkflow('a', 'New', 'About it')
    expect(update).toHaveBeenCalledWith('/workflows/a', { name: 'New', description: 'About it', definition, lockVersion: 4 })
  })

  it('lists with the existing paging, sorting and repeated status parameters', async () => {
    const request = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { items: [], page: 0, size: 12, totalElements: 0, totalPages: 0 } })
    await listWorkflows({ name: 'x', status: ['ACTIVE', 'DRAFT'], page: 2, size: 12, sort: 'name,asc' })
    expect(request).toHaveBeenCalledWith('/workflows', expect.objectContaining({
      params: { name: 'x', status: ['ACTIVE', 'DRAFT'], page: 2, size: 12, sort: 'name,asc' },
      paramsSerializer: { indexes: null },
    }))
  })

  it('reads the server settings from the read-only settings endpoint', async () => {
    const request = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { application: { version: '1' } } })
    expect(await getServerSettings()).toEqual({ application: { version: '1' } })
    expect(request).toHaveBeenCalledWith('/settings')
  })
})
