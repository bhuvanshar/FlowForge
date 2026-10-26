import { describe, expect, it } from 'vitest'

import { cronProblem, isKnownTimeZone } from './cron'
import { validateFormValues } from './registry'

describe('schedule trigger checks', () => {
  it.each(['0 * * * *', '*/15 9-17 * * MON-FRI', '0 0 12 * * ?', '0 0 1 1 *', '30 2 * * 0,7', '*/10 * * * * *'])(
    'accepts %s',
    (cron) => expect(cronProblem(cron)).toBeUndefined(),
  )

  it.each([
    ['* * *', 'Use 5 fields'],
    ['0 25 * * *', 'hour field must be between 0 and 23'],
    ['60 * * * *', 'minute field must be between 0 and 59'],
    ['0 0 0 * *', 'day of month field must be between 1 and 31'],
    ['0 0 1 13 *', 'month field must be between 1 and 12'],
    ['*/0 * * * *', 'minute step must be greater than 0'],
    ['61 * * * * *', 'second field must be between 0 and 59'],
  ])('rejects %s', (cron, message) => expect(cronProblem(cron)).toContain(message))

  it('checks time zones against the browser database', () => {
    expect(isKnownTimeZone('Europe/Berlin')).toBe(true)
    expect(isKnownTimeZone('Mars/Olympus')).toBe(false)
  })

  it('reports cron and time zone problems on their fields', () => {
    const result = validateFormValues('core.scheduleTrigger', {
      cron: '0 25 * * *', timezone: 'Mars/Olympus', missedRunPolicy: 'skip', testInput: '',
    })
    expect(result).toEqual({
      valid: false,
      issues: [
        { path: 'cron', message: 'The hour field must be between 0 and 23' },
        { path: 'timezone', message: 'Unknown time zone' },
      ],
    })
  })

  it('accepts webhook settings and rejects a non-object test input', () => {
    expect(validateFormValues('core.webhookTrigger', { method: 'POST', authentication: 'token', inputMode: 'request', testInput: '' }))
      .toEqual({ valid: true })
    expect(validateFormValues('core.webhookTrigger', { method: 'POST', authentication: 'token', inputMode: 'body', testInput: '"x"' }))
      .toMatchObject({ valid: false, issues: [{ path: 'testInput' }] })
  })
})
