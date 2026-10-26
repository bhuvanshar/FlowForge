import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { KeyRound, Pencil, Plus, RefreshCw, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { toProblemDetail } from '@/lib/api/client'
import {
  createCredential,
  deleteCredential,
  getEncryptionStatus,
  listCredentials,
  rotateCredential,
  updateCredential,
  CREDENTIALS_QUERY_KEY as credentialsQueryKey,
  type Credential,
  type CredentialUsage,
} from '@/lib/api/credentials'

import { formatDateTime } from '../executions/format'
import { CREDENTIAL_TYPES, getCredentialType, parseHosts, validateDraft, type CredentialDraft } from './credentialTypes'

type Mode = { kind: 'create' } | { kind: 'edit'; credential: Credential } | { kind: 'rotate'; credential: Credential }

const INPUT =
  'h-8 w-full rounded-md border bg-background px-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring aria-[invalid=true]:border-destructive'

/**
 * Credentials page. Secret values only ever travel from this form to the server: they are never shown, never stored in
 * the browser beyond the open form, and the API never returns them.
 */
export function CredentialsPage() {
  const queryClient = useQueryClient()
  const list = useQuery({ queryKey: credentialsQueryKey, queryFn: listCredentials })
  const status = useQuery({ queryKey: ['credentials-status'], queryFn: getEncryptionStatus })
  const [mode, setMode] = useState<Mode | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [blocked, setBlocked] = useState<{ name: string; workflows: CredentialUsage[] } | null>(null)

  const remove = useMutation({
    mutationFn: (credential: Credential) => deleteCredential(credential.id),
    onSuccess: (_, credential) => {
      setNotice(`Deleted "${credential.name}".`)
      void queryClient.invalidateQueries({ queryKey: credentialsQueryKey })
    },
    onError: (error, credential) => {
      const problem = toProblemDetail(error) as { status?: number; workflows?: CredentialUsage[] } | undefined
      if (problem?.status === 409 && problem.workflows) setBlocked({ name: credential.name, workflows: problem.workflows })
    },
  })

  const done = (message: string) => {
    setMode(null)
    setNotice(message)
    void queryClient.invalidateQueries({ queryKey: credentialsQueryKey })
  }

  const disabled = status.data?.available === false

  return (
    <div className="flex flex-col gap-4 p-6">
      <div className="flex items-center gap-3">
        <h1 className="text-xl font-semibold">Credentials</h1>
        <Button
          size="sm"
          className="ml-auto"
          disabled={disabled}
          onClick={() => {
            setNotice(null)
            setMode({ kind: 'create' })
          }}
        >
          <Plus className="size-4" aria-hidden />
          New credential
        </Button>
      </div>
      <p className="text-sm text-muted-foreground">
        Secrets are encrypted on the server (AES-256-GCM) and can be replaced but never read back. Workflows refer to a
        credential by its id; each credential is only sent to the hosts you allow.
      </p>
      {disabled && (
        <p role="alert" className="rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-sm">
          Credentials are disabled: the server has no encryption key. Set <code>FLOWFORGE_CREDENTIALS_KEYS</code> (see the
          README) and restart the backend.
        </p>
      )}
      {status.data && (status.data.credentialsOnOldKeys > 0 || (status.data.webhookSecretsToEncrypt ?? 0) > 0) && (
        <p role="note" className="rounded-md border px-3 py-2 text-sm">
          {status.data.credentialsOnOldKeys} credential(s) and {status.data.webhookSecretsToEncrypt ?? 0} webhook secret(s)
          are not yet encrypted with the active key{status.data.available ? '' : ' (no key is configured)'}.
          {status.data.available && <> Run <code>POST /api/v1/credentials/re-encrypt</code> or restart the backend.</>}
        </p>
      )}
      {notice && <p role="status" className="text-sm text-green-700 dark:text-green-400">{notice}</p>}
      {blocked && (
        <div role="alert" className="rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm">
          "{blocked.name}" is used by {blocked.workflows.length} workflow(s) and cannot be deleted:
          <ul className="mt-1 list-disc pl-5">
            {blocked.workflows.map((w) => (
              <li key={w.id}><Link className="underline" to={`/workflows/${w.id}`}>{w.name}</Link> ({w.status})</li>
            ))}
          </ul>
          <Button size="sm" variant="ghost" onClick={() => setBlocked(null)}>Dismiss</Button>
        </div>
      )}
      {remove.isError && !blocked && (
        <p role="alert" className="text-sm text-destructive">
          Could not delete: {toProblemDetail(remove.error)?.detail ?? 'the server could not be reached.'}
        </p>
      )}

      {mode && <CredentialForm key={mode.kind + ('credential' in mode ? mode.credential.id : '')} mode={mode}
        onCancel={() => setMode(null)} onDone={done} />}

      {list.isPending && <p className="text-sm text-muted-foreground">Loading credentials…</p>}
      {list.isError && <p role="alert" className="text-sm text-destructive">Could not load credentials.</p>}
      {list.data && list.data.length === 0 && !mode && (
        <p className="text-sm text-muted-foreground">No credentials yet.</p>
      )}
      {list.data && list.data.length > 0 && (
        <table className="w-full text-left text-sm">
          <thead>
            <tr className="border-b text-xs text-muted-foreground">
              <th className="py-2 pr-3 font-medium">Name</th>
              <th className="pr-3 font-medium">Type</th>
              <th className="pr-3 font-medium">Allowed hosts</th>
              <th className="pr-3 font-medium">Secret</th>
              <th className="pr-3 font-medium">Last used</th>
              <th className="font-medium"><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {list.data.map((c) => (
              <tr key={c.id} className="border-b align-top">
                <td className="py-2 pr-3">
                  <div className="font-medium">{c.name}</div>
                  {c.description && <div className="text-xs text-muted-foreground">{c.description}</div>}
                  {Object.entries(c.fields).map(([k, v]) => (
                    <div key={k} className="text-xs text-muted-foreground">{getCredentialType(c.type)?.fields.find((f) => f.name === k)?.label ?? k}: {v}</div>
                  ))}
                </td>
                <td className="pr-3">{getCredentialType(c.type)?.label ?? c.type}</td>
                <td className="pr-3 font-mono text-xs">{c.allowedHosts.join(', ')}</td>
                <td className="pr-3 text-xs" title={c.rotatedAt ? `Rotated ${formatDateTime(c.rotatedAt)}` : undefined}>
                  <KeyRound className="mr-1 inline size-3.5" aria-hidden />
                  •••••••• <span className="text-muted-foreground">v{c.secretVersion}</span>
                </td>
                <td className="pr-3 text-xs">{c.lastUsedAt ? formatDateTime(c.lastUsedAt) : 'never'}</td>
                <td className="whitespace-nowrap text-right">
                  <Button variant="ghost" size="icon" aria-label={`Edit ${c.name}`} onClick={() => setMode({ kind: 'edit', credential: c })}>
                    <Pencil className="size-4" aria-hidden />
                  </Button>
                  <Button variant="ghost" size="icon" aria-label={`Rotate secret of ${c.name}`} disabled={disabled}
                    onClick={() => setMode({ kind: 'rotate', credential: c })}>
                    <RefreshCw className="size-4" aria-hidden />
                  </Button>
                  <Button variant="ghost" size="icon" aria-label={`Delete ${c.name}`} disabled={remove.isPending}
                    onClick={() => {
                      setBlocked(null)
                      if (window.confirm(`Delete credential "${c.name}"? This cannot be undone.`)) remove.mutate(c)
                    }}>
                    <Trash2 className="size-4" aria-hidden />
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

function initialDraft(mode: Mode): CredentialDraft {
  if (mode.kind === 'create') {
    return { type: CREDENTIAL_TYPES[0].type, name: '', description: '', hosts: '', fields: {}, secret: {} }
  }
  const c = mode.credential
  return { type: c.type, name: c.name, description: c.description ?? '', hosts: c.allowedHosts.join(', '),
    fields: { ...c.fields }, secret: {} }
}

function CredentialForm({ mode, onCancel, onDone }: { mode: Mode; onCancel: () => void; onDone: (message: string) => void }) {
  const [draft, setDraft] = useState<CredentialDraft>(() => initialDraft(mode))
  const [errors, setErrors] = useState<Record<string, string>>({})
  const spec = getCredentialType(draft.type)!
  const showDetails = mode.kind !== 'rotate'
  const showSecret = mode.kind !== 'edit'

  const save = useMutation({
    mutationFn: () => {
      const fields = Object.fromEntries(spec.fields.filter((f) => !f.secret).map((f) => [f.name, (draft.fields[f.name] ?? '').trim()]))
      const secret = Object.fromEntries(spec.fields.filter((f) => f.secret).map((f) => [f.name, draft.secret[f.name] ?? '']))
      const details = { name: draft.name.trim(), description: draft.description.trim() || undefined,
        allowedHosts: parseHosts(draft.hosts), fields }
      if (mode.kind === 'create') return createCredential({ type: draft.type, ...details, secret })
      if (mode.kind === 'edit') return updateCredential(mode.credential.id, { ...details, lockVersion: mode.credential.lockVersion })
      return rotateCredential(mode.credential.id, secret)
    },
    onSuccess: (saved) => {
      // Drop the typed secret from memory as soon as the server has it.
      setDraft((d) => ({ ...d, secret: {} }))
      onDone(mode.kind === 'rotate' ? `Rotated the secret of "${saved.name}" (version ${saved.secretVersion}).`
        : mode.kind === 'create' ? `Created "${saved.name}".` : `Saved "${saved.name}".`)
    },
    onError: (error) => {
      const problem = toProblemDetail(error)
      if (problem?.errors?.length) setErrors(Object.fromEntries(problem.errors.map((e) => [e.field, e.message])))
    },
  })

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    const found = validateDraft(draft, { withSecret: showSecret })
    const relevant = showDetails ? found : Object.fromEntries(Object.entries(found).filter(([k]) => k.startsWith('secret.')))
    setErrors(relevant)
    if (Object.keys(relevant).length === 0) save.mutate()
  }

  const problem = save.isError ? toProblemDetail(save.error) : undefined
  const title = mode.kind === 'create' ? 'New credential' : mode.kind === 'edit' ? `Edit "${mode.credential.name}"`
    : `Rotate secret of "${mode.credential.name}"`

  return (
    <form aria-label={title} onSubmit={submit} className="flex max-w-xl flex-col gap-3 rounded-md border bg-card p-4" autoComplete="off">
      <h2 className="text-sm font-semibold">{title}</h2>
      {mode.kind === 'create' && (
        <Field id="cred-type" label="Type" error={errors.type} hint={spec.description}>
          <select id="cred-type" className={INPUT} value={draft.type}
            onChange={(e) => setDraft({ ...draft, type: e.target.value, fields: {}, secret: {} })}>
            {CREDENTIAL_TYPES.map((t) => <option key={t.type} value={t.type}>{t.label}</option>)}
          </select>
        </Field>
      )}
      {showDetails && (
        <>
          <Field id="cred-name" label="Name" error={errors.name}>
            <input id="cred-name" className={INPUT} value={draft.name} maxLength={100} aria-invalid={!!errors.name || undefined}
              onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
          </Field>
          <Field id="cred-description" label="Description (optional)" error={errors.description}>
            <input id="cred-description" className={INPUT} value={draft.description} maxLength={500}
              onChange={(e) => setDraft({ ...draft, description: e.target.value })} />
          </Field>
          <Field id="cred-hosts" label="Allowed hosts" error={errors.allowedHosts}
            hint="Comma-separated, e.g. api.example.com, *.example.com. The credential is never sent anywhere else.">
            <input id="cred-hosts" className={`${INPUT} font-mono`} value={draft.hosts} spellCheck={false}
              aria-invalid={!!errors.allowedHosts || undefined} onChange={(e) => setDraft({ ...draft, hosts: e.target.value })} />
          </Field>
          {spec.fields.filter((f) => !f.secret).map((f) => (
            <Field key={f.name} id={`cred-field-${f.name}`} label={f.label} error={errors[`fields.${f.name}`]}>
              <input id={`cred-field-${f.name}`} className={INPUT} value={draft.fields[f.name] ?? ''} spellCheck={false}
                aria-invalid={!!errors[`fields.${f.name}`] || undefined}
                onChange={(e) => setDraft({ ...draft, fields: { ...draft.fields, [f.name]: e.target.value } })} />
            </Field>
          ))}
        </>
      )}
      {showSecret && spec.fields.filter((f) => f.secret).map((f) => (
        <Field key={f.name} id={`cred-secret-${f.name}`} label={mode.kind === 'rotate' ? `New ${f.label.toLowerCase()}` : f.label}
          error={errors[`secret.${f.name}`]} hint="Write-only: it is encrypted and cannot be displayed again.">
          <input id={`cred-secret-${f.name}`} type="password" autoComplete="new-password" spellCheck={false} className={INPUT}
            value={draft.secret[f.name] ?? ''} aria-invalid={!!errors[`secret.${f.name}`] || undefined}
            onChange={(e) => setDraft({ ...draft, secret: { ...draft.secret, [f.name]: e.target.value } })} />
        </Field>
      ))}
      {problem && !problem.errors?.length && (
        <p role="alert" className="text-sm text-destructive">
          {problem.status === 409 ? problem.detail : `Could not save: ${problem.detail ?? 'the server could not be reached.'}`}
        </p>
      )}
      {save.isError && !problem && <p role="alert" className="text-sm text-destructive">Could not save: the server could not be reached.</p>}
      <div className="flex gap-2">
        <Button type="submit" size="sm" disabled={save.isPending}>
          {save.isPending ? 'Saving…' : mode.kind === 'rotate' ? 'Rotate secret' : 'Save'}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onCancel}>Cancel</Button>
      </div>
    </form>
  )
}

function Field({ id, label, hint, error, children }: { id: string; label: string; hint?: string; error?: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-xs font-medium">{label}</label>
      {children}
      {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
      {error && <p role="alert" className="text-xs text-destructive">{error}</p>}
    </div>
  )
}
