import { beforeEach, describe, expect, it, vi } from 'vitest'

import { DEFAULT_PREFERENCES, sanitizePreferences, usePreferences } from './preferences'

describe('dashboard preferences', () => {
  beforeEach(() => {
    window.localStorage.clear()
    usePreferences.getState().reset()
  })

  it('starts with sensible defaults', () => {
    expect(DEFAULT_PREFERENCES).toEqual({ defaultView: 'grid', defaultSort: 'updatedAt,desc', pageSize: 12 })
    expect(usePreferences.getState()).toMatchObject(DEFAULT_PREFERENCES)
  })

  it('stores changes in this browser and resets them', () => {
    usePreferences.getState().update({ defaultView: 'list', pageSize: 48 })
    expect(usePreferences.getState()).toMatchObject({ defaultView: 'list', pageSize: 48, defaultSort: 'updatedAt,desc' })
    expect(JSON.parse(window.localStorage.getItem('flowforge.preferences.v1')!)).toEqual({
      defaultView: 'list', defaultSort: 'updatedAt,desc', pageSize: 48,
    })
    usePreferences.getState().reset()
    expect(usePreferences.getState()).toMatchObject(DEFAULT_PREFERENCES)
  })

  it('accepts only known values, field by field', () => {
    expect(sanitizePreferences({ defaultView: 'carousel', defaultSort: 'name,asc', pageSize: 7 })).toEqual({
      defaultView: 'grid', defaultSort: 'name,asc', pageSize: 12,
    })
    expect(sanitizePreferences(null)).toEqual(DEFAULT_PREFERENCES)
    expect(sanitizePreferences('nonsense')).toEqual(DEFAULT_PREFERENCES)
    usePreferences.getState().update({ pageSize: 1000 as never })
    expect(usePreferences.getState().pageSize).toBe(12)
  })

  it('keeps working when browser storage is blocked', () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    usePreferences.getState().update({ defaultView: 'list' })
    expect(usePreferences.getState().defaultView).toBe('list')
    spy.mockRestore()
  })
})
