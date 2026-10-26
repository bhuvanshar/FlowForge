import { useQuery } from '@tanstack/react-query'
import { Keyboard, KeyRound, RotateCcw, Server, ShieldCheck, SlidersHorizontal } from 'lucide-react'
import type * as React from 'react'
import { Link } from 'react-router-dom'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { SelectField } from '@/components/ui/form-field'
import { Notice } from '@/components/ui/notice'
import { PageHeader } from '@/components/ui/page-header'
import { Skeleton } from '@/components/ui/skeleton'
import { getServerSettings, type ServerSettings } from '@/lib/api/settings'

import { PAGE_SIZES, SORT_OPTIONS, usePreferences } from './preferences'

const SHORTCUTS = [
  { keys: ['/'], what: 'Jump to the workflow search (Workflows page)' },
  { keys: ['n'], what: 'Create a new workflow (Workflows page)' },
  { keys: ['Esc'], what: 'Close a dialog or menu' },
  { keys: ['↑', '↓'], what: 'Move through a menu; Home and End jump to the first and last item' },
  { keys: ['Ctrl/⌘', 'S'], what: 'Save the workflow (editor)' },
  { keys: ['Ctrl/⌘', 'Z'], what: 'Undo in the editor (Shift added: redo)' },
  { keys: ['Delete'], what: 'Remove the selected nodes or connections (editor)' },
  { keys: ['Shift', '1'], what: 'Fit the workflow into view (editor)' },
]

/** Preferences for this browser, the server's effective configuration (read-only) and the keyboard shortcuts. */
export function SettingsPage() {
  return (
    <div className="mx-auto max-w-4xl px-5 py-7 sm:px-8">
      <PageHeader
        eyebrow="Workspace"
        title="Settings"
        description="How this browser shows your workflows, how the server is configured, and the shortcuts you can use."
      />
      <div className="flex flex-col gap-6">
        <PreferencesSection />
        <ServerSection />
        <ShortcutsSection />
      </div>
    </div>
  )
}

function Section({ icon: Icon, title, description, children, id }: {
  icon: typeof Server
  title: string
  description: string
  children: React.ReactNode
  id: string
}) {
  return (
    <section aria-labelledby={id} className="rounded-2xl border bg-card p-6">
      <div className="mb-5 flex items-start gap-3">
        <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary-soft text-primary-soft-foreground">
          <Icon className="size-[18px]" aria-hidden />
        </span>
        <div>
          <h2 id={id} className="text-base font-semibold tracking-tight">{title}</h2>
          <p className="mt-0.5 text-sm text-muted-foreground">{description}</p>
        </div>
      </div>
      {children}
    </section>
  )
}

function PreferencesSection() {
  const { defaultView, defaultSort, pageSize, update, reset } = usePreferences()
  return (
    <Section
      id="preferences"
      icon={SlidersHorizontal}
      title="Dashboard preferences"
      description="Saved in this browser only. A view or sort chosen on the Workflows page itself still wins for that visit."
    >
      <div className="grid gap-4 sm:grid-cols-3">
        <SelectField
          label="Default view"
          value={defaultView}
          onChange={(e) => update({ defaultView: e.target.value as 'grid' | 'list' })}
        >
          <option value="grid">Grid</option>
          <option value="list">List</option>
        </SelectField>
        <SelectField
          label="Default sort"
          value={defaultSort}
          onChange={(e) => update({ defaultSort: e.target.value as typeof defaultSort })}
        >
          {SORT_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </SelectField>
        <SelectField
          label="Workflows per page"
          value={String(pageSize)}
          onChange={(e) => update({ pageSize: Number(e.target.value) as typeof pageSize })}
        >
          {PAGE_SIZES.map((size) => (
            <option key={size} value={size}>{size}</option>
          ))}
        </SelectField>
      </div>
      <Button variant="ghost" size="sm" className="mt-4" onClick={reset}>
        <RotateCcw className="size-3.5" aria-hidden />
        Reset to defaults
      </Button>
    </Section>
  )
}

function Row({ label, env, children }: { label: string; env?: string; children: React.ReactNode }) {
  return (
    <div className="grid gap-1 border-b py-3 last:border-b-0 sm:grid-cols-[14rem_1fr] sm:gap-4">
      <dt className="text-sm font-medium">{label}</dt>
      <dd className="min-w-0 text-sm">
        {children}
        {env && (
          <span className="mt-1 block text-xs text-muted-foreground">
            Set with <code className="rounded bg-muted px-1 py-0.5 font-mono text-[11px]">{env}</code>
          </span>
        )}
      </dd>
    </div>
  )
}

const bytes = (n: number) => (n >= 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(n % (1024 * 1024) ? 1 : 0)} MiB` : `${Math.round(n / 1024)} KiB`)
const seconds = (n: number) => (n >= 120 ? `${Math.round(n / 60)} min` : `${n} s`)

function ServerSection() {
  const query = useQuery({ queryKey: ['server-settings'], queryFn: getServerSettings, staleTime: 30_000 })
  return (
    <Section
      id="server"
      icon={Server}
      title="Server configuration"
      description="What the backend is running with. Read-only: it is changed with environment variables and a restart."
    >
      {query.isPending && (
        <div role="status">
          <span className="sr-only">Loading server settings…</span>
          <Skeleton className="h-4 w-1/3" />
          <Skeleton className="mt-4 h-4 w-full" />
          <Skeleton className="mt-4 h-4 w-2/3" />
        </div>
      )}
      {query.isError && (
        <Notice
          tone="danger"
          action={<Button size="sm" variant="outline" onClick={() => void query.refetch()}>Try again</Button>}
        >
          Could not load the server settings. Check that the backend is running.
        </Notice>
      )}
      {query.data && <ServerRows settings={query.data} />}
    </Section>
  )
}

function ServerRows({ settings: s }: { settings: ServerSettings }) {
  return (
    <>
      <dl>
        <Row label="Version">FlowForge {s.application.version}</Row>
        <Row label="Concurrent executions" env="FLOWFORGE_EXECUTION_WORKERS">
          {s.execution.workerThreads} at a time, {s.execution.queueCapacity} waiting, at most {s.execution.maxActivePerWorkflow} per workflow
        </Row>
        <Row label="Time limits">
          {seconds(s.execution.nodeTimeoutSeconds)} per node, {seconds(s.execution.maxDurationSeconds)} per execution
        </Row>
        <Row label="Outbound HTTP hosts" env="FLOWFORGE_HTTP_ALLOWED_HOSTS">
          {s.outbound.allowedHosts.length === 0 ? (
            <span>
              <Badge tone="warning">None allowed</Badge>
              <span className="ml-2 text-muted-foreground">HTTP Request and AI Model nodes cannot call anything yet.</span>
            </span>
          ) : (
            <ul aria-label="Allowed hosts" className="flex flex-wrap gap-1.5">
              {s.outbound.allowedHosts.map((host) => (
                <li key={host}><code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">{host}</code></li>
              ))}
            </ul>
          )}
        </Row>
        <Row label="Private network addresses" env="FLOWFORGE_HTTP_ALLOW_PRIVATE_ADDRESSES">
          {s.outbound.allowPrivateAddresses
            ? <Badge tone="warning">Allowed (development only)</Badge>
            : <Badge tone="success">Blocked</Badge>}
        </Row>
        <Row label="Response and request size">
          {bytes(s.outbound.maxResponseBytes)} response, {bytes(s.outbound.maxRequestBytes)} request, {s.outbound.maxRedirects} redirects
        </Row>
        <Row label="Scheduled workflows" env="FLOWFORGE_SCHEDULER_ENABLED">
          {s.triggers.schedulerEnabled
            ? <Badge tone="success">Running</Badge>
            : <Badge tone="neutral">Off</Badge>}
          {s.triggers.schedulerEnabled && <span className="ml-2 text-muted-foreground">checked every {s.triggers.schedulerPollSeconds} s</span>}
        </Row>
        <Row label="Unauthenticated webhooks" env="FLOWFORGE_WEBHOOKS_ALLOW_UNAUTHENTICATED">
          {s.triggers.allowUnauthenticatedWebhooks
            ? <Badge tone="warning">Allowed</Badge>
            : <Badge tone="success">Not allowed</Badge>}
          <span className="ml-2 text-muted-foreground">bodies up to {bytes(s.triggers.webhookMaxBodyBytes)}</span>
        </Row>
        <Row label="Credential encryption" env="FLOWFORGE_CREDENTIALS_KEYS">
          {s.credentials.encryptionAvailable ? (
            <span>
              <Badge tone="success">Enabled</Badge>
              <span className="ml-2 text-muted-foreground">
                active key “{s.credentials.activeKeyId}”, {s.credentials.keyCount} key{s.credentials.keyCount === 1 ? '' : 's'} loaded
              </span>
            </span>
          ) : (
            <span>
              <Badge tone="danger">No key configured</Badge>
              <span className="ml-2 text-muted-foreground">Credentials cannot be stored.</span>
            </span>
          )}
        </Row>
        <Row label="AI prompts">up to {s.ai.maxPromptChars.toLocaleString()} characters, answers up to {bytes(s.ai.maxResponseBytes)}</Row>
        <Row label="Accepted host names" env="FLOWFORGE_ALLOWED_HOST_NAMES">
          {s.security.allowedHostNames.join(', ')}
        </Row>
        <Row label="Allowed browser origins" env="FLOWFORGE_ALLOWED_ORIGINS">
          {s.security.allowedOrigins.join(', ') || 'none'}
        </Row>
      </dl>
      <div className="mt-4 flex flex-wrap items-center gap-3 rounded-xl bg-muted/50 px-4 py-3 text-sm">
        <ShieldCheck className="size-4 shrink-0 text-success" aria-hidden />
        <p className="flex-1 text-muted-foreground">
          FlowForge is a single-user tool for this machine: it listens on localhost only and has no login. Keep its ports off the network.
        </p>
        <Button asChild variant="outline" size="sm">
          <Link to="/credentials">
            <KeyRound className="size-3.5" aria-hidden />
            Manage credentials
          </Link>
        </Button>
      </div>
    </>
  )
}

function ShortcutsSection() {
  return (
    <Section id="shortcuts" icon={Keyboard} title="Keyboard shortcuts" description="Everything in FlowForge can also be done with the keyboard. Tab moves between controls; Enter or Space activates.">
      <dl className="grid gap-x-8 sm:grid-cols-2">
        {SHORTCUTS.map(({ keys, what }) => (
          <div key={what} className="flex items-start justify-between gap-4 border-b py-2.5 text-sm">
            <dt className="text-muted-foreground">{what}</dt>
            <dd className="flex shrink-0 gap-1">
              {keys.map((k) => (
                <kbd key={k} className="rounded border bg-muted px-1.5 py-0.5 text-[11px] font-medium">{k}</kbd>
              ))}
            </dd>
          </div>
        ))}
      </dl>
    </Section>
  )
}
