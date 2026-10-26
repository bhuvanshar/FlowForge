import { describe, expect, it, vi } from 'vitest'

import { apiClient } from './client'
import { getHealth } from './health'

describe('getHealth', () => {
  it('returns the response body and accepts 503 as a valid health answer', async () => {
    const body = { status: 'DOWN', version: '1', timestamp: 't', components: { database: 'DOWN' } }
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: body })
    await expect(getHealth()).resolves.toEqual(body)
    const config = get.mock.calls[0][1]
    expect(config?.validateStatus?.(503)).toBe(true)
    expect(config?.validateStatus?.(500)).toBe(false)
  })
})
