import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Copy, Eye, RefreshCw } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { toProblemDetail } from '@/lib/api/client'
import {
  getTriggers,
  getWebhookSecret,
  rotateWebhookSecret,
  type ScheduleTriggerInfo,
  type WebhookTriggerInfo,
} from '@/lib/api/triggers'

import { formatDateTime } from '../executions/format'
import { NODE_TYPE_IDS } from './nodeTypes'
import { curlExample, triggersQueryKey } from './triggers'

/**
 * Runtime state of the selected webhook or schedule trigger as the server sees it: the saved registration, not the
 * unsaved editor content.
 */
export function TriggerPanel({ workflowId, nodeType }: { workflowId: string | null; nodeType: string }) {
  return (
    <section aria-label="Trigger status" className="flex flex-col gap-2 rounded-md border bg-muted/30 p-3 text-sm">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
        {nodeType === NODE_TYPE_IDS.webhookTrigger ? 'Webhook' : 'Schedule'}
      </h3>
      {workflowId === null ? (
        <p className="text-xs text-muted-foreground">Save the workflow to register this trigger.</p>
      ) : (
        <SavedTrigger workflowId={workflowId} nodeType={nodeType} />
      )}
    </section>
  )
}

function SavedTrigger({ workflowId, nodeType }: { workflowId: string; nodeType: string }) {
  const query = useQuery({
    queryKey: triggersQueryKey(workflowId),
    queryFn: () => getTriggers(workflowId),
    // Next/last run times move on their own while a schedule is active.
    refetchInterval: (q) => (q.state.data?.schedule?.active ? 30_000 : false),
  })
  if (query.isPending) return <p className="text-xs text-muted-foreground">Loading trigger status…</p>
  if (query.isError) return <p role="alert" className="text-xs text-destructive">Could not load the trigger status.</p>

  const info = query.data
  if (nodeType === NODE_TYPE_IDS.webhookTrigger) {
    return info.webhook ? (
      <WebhookDetails workflowId={workflowId} webhook={info.webhook} />
    ) : (
      <p className="text-xs text-muted-foreground">Save the workflow to get its webhook URL.</p>
    )
  }
  return info.schedule ? (
    <ScheduleDetails schedule={info.schedule} />
  ) : (
    <p className="text-xs text-muted-foreground">Save the workflow to register the schedule.</p>
  )
}

function ActiveBadge({ active, inactiveHint }: { active: boolean; inactiveHint: string }) {
  return active ? (
    <p className="text-xs"><span className="mr-1 inline-block size-2 rounded-full bg-green-600" aria-hidden />Active</p>
  ) : (
    <p className="text-xs text-muted-foreground">
      <span className="mr-1 inline-block size-2 rounded-full bg-muted-foreground" aria-hidden />Inactive. {inactiveHint}
    </p>
  )
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    // Clipboard access can be denied; the text stays visible for manual copying.
  }
}

function WebhookDetails({ workflowId, webhook }: { workflowId: string; webhook: WebhookTriggerInfo }) {
  const queryClient = useQueryClient()
  const [secret, setSecret] = useState<string | null>(null)
  const url = `${window.location.origin}${webhook.path}`
  const reveal = useMutation({ mutationFn: () => getWebhookSecret(workflowId), onSuccess: setSecret })
  const rotate = useMutation({
    mutationFn: () => rotateWebhookSecret(workflowId),
    onSuccess: (value) => {
      setSecret(value)
      void queryClient.invalidateQueries({ queryKey: triggersQueryKey(workflowId) })
    },
  })
  const error = reveal.error ?? rotate.error

  return (
    <>
      <ActiveBadge active={webhook.active} inactiveHint="Activate the workflow to accept requests (until then: 404)." />
      <div className="flex flex-col gap-1">
        <span className="text-xs text-muted-foreground">URL ({webhook.method})</span>
        <div className="flex items-center gap-1">
          <code data-testid="webhook-url" className="min-w-0 flex-1 break-all rounded bg-background px-1.5 py-1 font-mono text-[11px]">{url}</code>
          <Button variant="ghost" size="icon" aria-label="Copy webhook URL" onClick={() => void copy(url)}>
            <Copy className="size-3.5" aria-hidden />
          </Button>
        </div>
      </div>
      {webhook.authentication === 'none' ? (
        <p className="text-xs text-amber-700 dark:text-amber-400">No authentication: anyone who can reach this server can start the workflow.</p>
      ) : (
        <div className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">
            Secret ({webhook.authentication === 'signature' ? 'HMAC-SHA256 signing key' : 'bearer token'})
          </span>
          <div className="flex items-center gap-1">
            <code data-testid="webhook-secret" className="min-w-0 flex-1 break-all rounded bg-background px-1.5 py-1 font-mono text-[11px]">
              {secret ?? `••••••••${webhook.secretHint.replace('…', '')}`}
            </code>
            {secret ? (
              <Button variant="ghost" size="icon" aria-label="Copy secret" onClick={() => void copy(secret)}>
                <Copy className="size-3.5" aria-hidden />
              </Button>
            ) : (
              <Button variant="ghost" size="icon" aria-label="Reveal secret" disabled={reveal.isPending} onClick={() => reveal.mutate()}>
                <Eye className="size-3.5" aria-hidden />
              </Button>
            )}
            <Button
              variant="ghost"
              size="icon"
              aria-label="Rotate secret"
              title="Generate a new secret; the old one stops working immediately"
              disabled={rotate.isPending}
              onClick={() => {
                if (window.confirm('Generate a new secret? Requests using the current secret will be rejected.')) rotate.mutate()
              }}
            >
              <RefreshCw className="size-3.5" aria-hidden />
            </Button>
          </div>
        </div>
      )}
      {error && <p role="alert" className="text-xs text-destructive">{toProblemDetail(error)?.detail ?? 'Request failed.'}</p>}
      <details className="text-xs">
        <summary className="cursor-pointer text-muted-foreground">Example request</summary>
        <pre data-testid="webhook-example" className="mt-1 overflow-x-auto whitespace-pre rounded bg-background p-2 font-mono text-[10px]">
          {curlExample(url, webhook)}
        </pre>
        {webhook.authentication === 'signature' && (
          <p className="mt-1 text-muted-foreground">
            Signature: hex HMAC-SHA256 of <code>timestamp.body</code>; the timestamp must be within 5 minutes and each
            signed request is accepted once.
          </p>
        )}
      </details>
    </>
  )
}

function ScheduleDetails({ schedule }: { schedule: ScheduleTriggerInfo }) {
  const inZone = (iso: string) => {
    try {
      return new Date(iso).toLocaleString(undefined, { timeZone: schedule.timezone, timeZoneName: 'short' })
    } catch {
      return iso
    }
  }
  return (
    <>
      <ActiveBadge active={schedule.active} inactiveHint="Activate the workflow to start the schedule." />
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs">
        <dt className="text-muted-foreground">Cron</dt>
        <dd className="font-mono">{schedule.cron} ({schedule.timezone})</dd>
        {schedule.nextFireAt && (
          <>
            <dt className="text-muted-foreground">Next run</dt>
            <dd data-testid="schedule-next">
              {formatDateTime(schedule.nextFireAt)}
              <span className="block text-muted-foreground">{inZone(schedule.nextFireAt)}</span>
            </dd>
          </>
        )}
        {schedule.lastFireAt && (
          <>
            <dt className="text-muted-foreground">Last run</dt>
            <dd>
              {schedule.lastExecutionId ? (
                <Link to={`/executions/${schedule.lastExecutionId}`} className="underline">{formatDateTime(schedule.lastFireAt)}</Link>
              ) : (
                formatDateTime(schedule.lastFireAt)
              )}
            </dd>
          </>
        )}
        {schedule.lastMissedAt && (
          <>
            <dt className="text-muted-foreground">Last missed</dt>
            <dd>{formatDateTime(schedule.lastMissedAt)}</dd>
          </>
        )}
      </dl>
      {schedule.lastError && <p role="alert" className="text-xs text-destructive">Last occurrence not started: {schedule.lastError}</p>}
    </>
  )
}
