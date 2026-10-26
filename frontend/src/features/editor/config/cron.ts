/**
 * Editor-side checks for schedule triggers. They catch typos early; the backend (Spring CronExpression, java.time
 * zones) has the final word and reports anything else through workflow validation.
 */

const FIELDS_5 = ['minute', 'hour', 'day of month', 'month', 'day of week']
const FIELDS_6 = ['second', ...FIELDS_5]
const RANGES: Record<string, [number, number]> = {
  second: [0, 59], minute: [0, 59], hour: [0, 23], 'day of month': [1, 31], month: [1, 12], 'day of week': [0, 7],
}

/** Returns a problem description, or undefined when the expression looks valid. */
export function cronProblem(expression: string): string | undefined {
  const fields = expression.trim().split(/\s+/)
  const names = fields.length === 5 ? FIELDS_5 : fields.length === 6 ? FIELDS_6 : undefined
  if (!names) return 'Use 5 fields (minute hour day month weekday) or 6 with leading seconds'
  for (let i = 0; i < fields.length; i++) {
    const [min, max] = RANGES[names[i]]
    for (const part of fields[i].split(',')) {
      // Plain numbers, ranges and steps are range-checked; names (JAN, MON) and specials (?, L, W, #) are left
      // to the backend.
      const match = /^(\*|\d+)(?:-(\d+))?(?:\/(\d+))?$/.exec(part)
      if (!match) continue
      const numbers = [match[1], match[2]].filter((n): n is string => n !== undefined && n !== '*').map(Number)
      if (numbers.some((n) => n < min || n > max)) return `The ${names[i]} field must be between ${min} and ${max}`
      if (match[3] !== undefined && Number(match[3]) === 0) return `The ${names[i]} step must be greater than 0`
    }
  }
  return undefined
}

export function isKnownTimeZone(zone: string): boolean {
  try {
    new Intl.DateTimeFormat('en', { timeZone: zone })
    return true
  } catch {
    return false
  }
}
