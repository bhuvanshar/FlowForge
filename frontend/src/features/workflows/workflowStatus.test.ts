import { describe, expect, it } from 'vitest'

import { EXECUTION_TONE, menuActions, relativeTime, WORKFLOW_STATUS } from './workflowStatus'

describe('relativeTime', () => {
  const now = new Date('2026-10-26T12:00:00Z').getTime()
  const ago = (ms: number) => new Date(now - ms).toISOString()

  it.each([
    [10_000, 'just now'],
    [5 * 60_000, '5 min ago'],
    [3 * 3_600_000, '3 h ago'],
    [2 * 86_400_000, '2 d ago'],
  ])('%i ms ago reads "%s"', (age, text) => expect(relativeTime(ago(age), now)).toBe(text))

  it('falls back to a date for old timestamps and to nothing for missing or unreadable ones', () => {
    expect(relativeTime(ago(40 * 86_400_000), now)).toMatch(/2026|Sep/)
    expect(relativeTime(undefined, now)).toBe('')
    expect(relativeTime('not a date', now)).toBe('')
  })
})

describe('workflow status presentation', () => {
  it('labels every lifecycle status and maps every execution status to a tone', () => {
    expect(Object.values(WORKFLOW_STATUS).map((s) => s.label)).toEqual(['Active', 'Draft', 'Inactive', 'Archived'])
    expect(WORKFLOW_STATUS.ACTIVE.tone).toBe('success')
    expect(EXECUTION_TONE).toEqual({ QUEUED: 'neutral', RUNNING: 'info', SUCCEEDED: 'success', FAILED: 'danger', CANCELLED: 'warning' })
  })

  it('offers archived workflows only restore, duplicate and delete', () => {
    expect(menuActions('ARCHIVED')).toEqual(['restore', 'duplicate', 'delete'])
    expect(menuActions('ACTIVE')).toContain('deactivate')
    expect(menuActions('ACTIVE')).not.toContain('activate')
    for (const status of ['DRAFT', 'INACTIVE'] as const) {
      expect(menuActions(status)).toContain('activate')
      expect(menuActions(status)).not.toContain('delete')
    }
  })
})
