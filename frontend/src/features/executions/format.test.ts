import { describe, expect, it } from 'vitest'

import { formatDateTime, formatDuration, isTerminalExecution } from './format'

describe('format', () => {
  it.each([
    [undefined, '–'], [null, '–'], [0, '0 ms'], [999, '999 ms'], [1000, '1.00 s'], [1250, '1.25 s'], [12_340, '12.3 s'],
    [60_000, '1 min 0 s'], [125_000, '2 min 5 s'],
  ])('formats %s as %s', (ms, expected) => expect(formatDuration(ms as number | undefined)).toBe(expected))

  it('formats dates and tolerates bad input', () => {
    expect(formatDateTime(undefined)).toBe('–')
    expect(formatDateTime('not a date')).toBe('not a date')
    expect(formatDateTime('2026-01-01T10:00:00Z')).not.toBe('–')
  })

  it('knows terminal statuses', () => {
    expect(['SUCCEEDED', 'FAILED', 'CANCELLED'].every((s) => isTerminalExecution(s as never))).toBe(true)
    expect(['QUEUED', 'RUNNING'].some((s) => isTerminalExecution(s as never))).toBe(false)
  })
})
