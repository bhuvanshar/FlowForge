import type { ZodType } from 'zod'

/** Form state of a node configuration form. Strings for inputs and JSON text, arrays of row objects for lists. */
export type FormValues = Record<string, unknown>

export interface Option {
  value: string
  label: string
}

interface BaseField {
  name: string
  label: string
  hint?: string
  /** Hide the field (and skip it visually) when false. Validation lives in the Zod schema, not here. */
  visibleWhen?: (values: FormValues) => boolean
}

export interface TextFieldSpec extends BaseField {
  kind: 'text'
  placeholder?: string
  /** Accepts `{{ path.to.value }}` references (a hint is shown). */
  expression?: boolean
  /** Example values offered by the browser while typing (any value is still accepted). */
  suggestions?: string[]
}

export interface TextareaFieldSpec extends BaseField {
  kind: 'textarea'
  placeholder?: string
  rows?: number
  expression?: boolean
}

export interface SelectFieldSpec extends BaseField {
  kind: 'select'
  options: Option[]
}

export interface JsonFieldSpec extends BaseField {
  kind: 'json'
  placeholder?: string
  rows?: number
  /** Strings inside must be safe expression text. */
  expression?: boolean
}

export interface ColumnSpec {
  name: string
  label: string
  kind: 'text' | 'select'
  placeholder?: string
  options?: Option[]
  expression?: boolean
}

export interface ListFieldSpec extends BaseField {
  kind: 'list'
  addLabel: string
  columns: ColumnSpec[]
  /** Values of a freshly added row. */
  emptyRow: Record<string, string>
}

/** Picks a stored credential (by id) usable by `nodeType`; empty means none. */
export interface CredentialFieldSpec extends BaseField {
  kind: 'credential'
  nodeType: string
}

export type FieldSpec = TextFieldSpec | TextareaFieldSpec | SelectFieldSpec | JsonFieldSpec | ListFieldSpec | CredentialFieldSpec

/**
 * Everything the editor needs to configure one node type: the form schema and fields (what the user sees) plus the
 * converters between form state and the persisted `configuration` object (what the backend validates).
 */
export interface NodeConfigDefinition {
  type: string
  schema: ZodType<FormValues>
  fields: FieldSpec[]
  /** Persisted configuration of a freshly created node. Mirrors `defaultConfiguration` in node-types.v1.json. */
  defaultConfiguration: () => Record<string, unknown>
  toFormValues: (configuration: Record<string, unknown>) => FormValues
  toConfiguration: (values: FormValues) => Record<string, unknown>
}
