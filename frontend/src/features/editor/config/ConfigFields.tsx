import { Plus, Trash2 } from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { get, useController, useFieldArray, type Control, type FieldErrors, type UseFormRegister } from 'react-hook-form'

import { Button } from '@/components/ui/button'
import { CREDENTIALS_QUERY_KEY, listCredentials } from '@/lib/api/credentials'
import { cn } from '@/lib/utils'

import { getCredentialType } from '../../credentials/credentialTypes'
import type { ColumnSpec, CredentialFieldSpec, FieldSpec, FormValues, ListFieldSpec } from './fieldSpec'

const INPUT =
  'w-full rounded-md border bg-background px-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring aria-[invalid=true]:border-destructive'

// Reference roots resolved by the backend ExpressionResolver (Module 8).
export const EXPRESSION_HINT =
  'References: {{ input.x }}, {{ vars.x }}, {{ trigger.body.x }}, {{ nodes.<nodeId>.output.x }}.'

interface FieldProps {
  spec: FieldSpec
  idPrefix: string
  register: UseFormRegister<FormValues>
  control: Control<FormValues>
  errors: FieldErrors<FormValues>
}

function errorMessage(errors: FieldErrors<FormValues>, path: string): string | undefined {
  const error = get(errors, path) as { message?: string; root?: { message?: string } } | undefined
  return error?.message ?? error?.root?.message
}

function Shell({ id, label, hint, error, children }: {
  id: string
  label: string
  hint?: string
  error?: string
  children: React.ReactNode
}) {
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-xs font-medium">
        {label}
      </label>
      {children}
      {hint && (
        <p id={`${id}-hint`} className="text-xs text-muted-foreground">
          {hint}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} role="alert" className="text-xs text-destructive">
          {error}
        </p>
      )}
    </div>
  )
}

const describedBy = (id: string, hint: boolean, error: boolean) =>
  [hint ? `${id}-hint` : '', error ? `${id}-error` : ''].filter(Boolean).join(' ') || undefined

function joinHint(hint: string | undefined, expression: boolean | undefined) {
  return [hint, expression ? EXPRESSION_HINT : undefined].filter(Boolean).join(' ') || undefined
}

/** Renders one declarative field. Adding a node type never requires new rendering code. */
export function ConfigField({ spec, idPrefix, register, control, errors }: FieldProps) {
  const id = `${idPrefix}-${spec.name}`
  const error = errorMessage(errors, spec.name)

  switch (spec.kind) {
    case 'text': {
      const hint = joinHint(spec.hint, spec.expression)
      return (
        <Shell id={id} label={spec.label} hint={hint} error={error}>
          <input
            id={id}
            type="text"
            autoComplete="off"
            spellCheck={false}
            placeholder={spec.placeholder}
            aria-invalid={error ? true : undefined}
            aria-describedby={describedBy(id, !!hint, !!error)}
            className={cn(INPUT, 'h-8', spec.expression && 'font-mono')}
            list={spec.suggestions ? `${id}-suggestions` : undefined}
            {...register(spec.name)}
          />
          {spec.suggestions && (
            <datalist id={`${id}-suggestions`}>
              {spec.suggestions.map((s) => <option key={s} value={s} />)}
            </datalist>
          )}
        </Shell>
      )
    }
    case 'select':
      return (
        <Shell id={id} label={spec.label} hint={spec.hint} error={error}>
          <select
            id={id}
            aria-invalid={error ? true : undefined}
            aria-describedby={describedBy(id, !!spec.hint, !!error)}
            className={cn(INPUT, 'h-8')}
            {...register(spec.name)}
          >
            {spec.options.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </Shell>
      )
    case 'textarea':
    case 'json': {
      const hint = joinHint(spec.hint, spec.kind === 'textarea' ? spec.expression : spec.expression)
      return (
        <Shell id={id} label={spec.label} hint={hint} error={error}>
          <textarea
            id={id}
            rows={spec.rows ?? 4}
            spellCheck={false}
            placeholder={spec.placeholder}
            aria-invalid={error ? true : undefined}
            aria-describedby={describedBy(id, !!hint, !!error)}
            className={cn(INPUT, 'py-1.5 font-mono text-xs')}
            {...register(spec.name)}
          />
        </Shell>
      )
    }
    case 'list':
      return <ListField spec={spec} id={id} register={register} control={control} errors={errors} />
    case 'credential':
      return <CredentialField spec={spec} id={id} control={control} error={error} />
  }
}

/**
 * Stored credentials usable by the node. Controlled, so a saved id survives while the list loads (or if the credential
 * was deleted: it is then shown as unknown and validation reports it).
 */
function CredentialField({ spec, id, control, error }: {
  spec: CredentialFieldSpec
  id: string
  control: Control<FormValues>
  error?: string
}) {
  const { field } = useController({ name: spec.name, control })
  const credentials = useQuery({ queryKey: CREDENTIALS_QUERY_KEY, queryFn: listCredentials, staleTime: 10_000 })
  const usable = (credentials.data ?? []).filter((c) => getCredentialType(c.type)?.usableBy.includes(spec.nodeType))
  const value = typeof field.value === 'string' ? field.value : ''
  const known = value === '' || usable.some((c) => c.id === value)
  const hint = [spec.hint, credentials.isError ? 'Could not load credentials.' : undefined].filter(Boolean).join(' ')
  return (
    <Shell id={id} label={spec.label} hint={hint || undefined} error={error}>
      <select
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(id, !!hint, !!error)}
        className={cn(INPUT, 'h-8')}
        name={field.name}
        value={value}
        onBlur={field.onBlur}
        onChange={(event) => field.onChange(event.target.value)}
      >
        <option value="">None</option>
        {usable.map((c) => (
          <option key={c.id} value={c.id}>
            {c.name} ({getCredentialType(c.type)?.label ?? c.type})
          </option>
        ))}
        {!known && <option value={value}>{credentials.isPending ? 'Loading…' : `Unknown credential ${value.slice(0, 8)}…`}</option>}
      </select>
      <a href="/credentials" target="_blank" rel="noopener noreferrer" className="text-xs underline">
        Manage credentials
      </a>
    </Shell>
  )
}

function Column({ column, name, rowLabel, register, error }: {
  column: ColumnSpec
  name: string
  rowLabel: string
  register: UseFormRegister<FormValues>
  error?: string
}) {
  const label = `${rowLabel} ${column.label.toLowerCase()}`
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-[11px] text-muted-foreground">{column.label}</span>
      {column.kind === 'select' ? (
        <select aria-label={label} className={cn(INPUT, 'h-8')} {...register(name)}>
          {column.options?.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      ) : (
        <input
          type="text"
          autoComplete="off"
          spellCheck={false}
          aria-label={label}
          aria-invalid={error ? true : undefined}
          placeholder={column.placeholder}
          className={cn(INPUT, 'h-8', column.expression && 'font-mono')}
          {...register(name)}
        />
      )}
      {error && (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      )}
    </div>
  )
}

function ListField({ spec, id, register, control, errors }: Omit<FieldProps, 'spec' | 'idPrefix'> & { spec: ListFieldSpec; id: string }) {
  const { fields, append, remove } = useFieldArray({ control, name: spec.name as never })
  const listError = errorMessage(errors, spec.name)
  const singular = spec.addLabel.replace(/^Add /, '')

  return (
    <fieldset className="flex flex-col gap-2" aria-describedby={listError ? `${id}-error` : undefined}>
      <legend className="mb-1 text-xs font-medium">{spec.label}</legend>
      {spec.hint && <p className="text-xs text-muted-foreground">{spec.hint}</p>}
      {fields.map((field, index) => (
        <div key={field.id} className="flex flex-col gap-2 rounded-md border p-2" data-testid={`${spec.name}-row`}>
          {spec.columns.map((column) => {
            const path = `${spec.name}.${index}.${column.name}`
            return (
              <Column
                key={column.name}
                column={column}
                name={path}
                rowLabel={`${singular} ${index + 1}`}
                register={register}
                error={errorMessage(errors, path)}
              />
            )
          })}
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="self-end"
            aria-label={`Remove ${singular} ${index + 1}`}
            onClick={() => remove(index)}
          >
            <Trash2 className="size-3.5" aria-hidden />
            Remove
          </Button>
        </div>
      ))}
      <Button type="button" variant="outline" size="sm" onClick={() => append({ ...spec.emptyRow } as never)}>
        <Plus className="size-3.5" aria-hidden />
        {spec.addLabel}
      </Button>
      {listError && (
        <p id={`${id}-error`} role="alert" className="text-xs text-destructive">
          {listError}
        </p>
      )}
    </fieldset>
  )
}
