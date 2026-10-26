import { get, set, type FieldErrors, type Resolver } from 'react-hook-form'
import type { ZodType } from 'zod'

import type { FormValues } from './fieldSpec'

/**
 * Minimal React Hook Form resolver for Zod. The first issue per path wins; issue paths map directly to form field
 * names (`variables.0.name`). Written here instead of using @hookform/resolvers to avoid its large optional peer tree.
 */
export function zodResolver(schema: ZodType<FormValues>): Resolver<FormValues> {
  return (values) => {
    const result = schema.safeParse(values)
    if (result.success) return { values: result.data, errors: {} }
    const errors: FieldErrors<FormValues> = {}
    for (const issue of result.error.issues) {
      const path = issue.path.join('.')
      if (!get(errors, path)) set(errors, path, { type: issue.code, message: issue.message })
    }
    return { values: {}, errors }
  }
}
