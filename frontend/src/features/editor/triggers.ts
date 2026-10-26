import type { WebhookTriggerInfo } from '@/lib/api/triggers'

import { NODE_TYPE_IDS } from './nodeTypes'

export const triggersQueryKey = (workflowId: string) => ['triggers', workflowId] as const

/** Triggers with a server-side registration (shown in the trigger status panel). */
export function isAutomaticTrigger(nodeType: string): boolean {
  return nodeType === NODE_TYPE_IDS.webhookTrigger || nodeType === NODE_TYPE_IDS.scheduleTrigger
}

/** Shell example for the configured authentication. The secret stays a variable unless the user pastes it. */
export function curlExample(url: string, webhook: Pick<WebhookTriggerInfo, 'method' | 'authentication'>): string {
  const body = webhook.method === 'GET' ? '' : `BODY='{"hello":"world"}'\n`
  const data = webhook.method === 'GET' ? '' : ` \\\n  -H 'Content-Type: application/json' -d "$BODY"`
  if (webhook.authentication === 'signature') {
    return (
      `SECRET='<webhook secret>'\n${body}TS=$(date +%s)\n` +
      `SIG=$(printf '%s.%s' "$TS" "${webhook.method === 'GET' ? '' : '$BODY'}" | openssl dgst -sha256 -hmac "$SECRET" | sed 's/^.* //')\n` +
      `curl -X ${webhook.method} '${url}' \\\n  -H "X-FlowForge-Timestamp: $TS" -H "X-FlowForge-Signature: sha256=$SIG"${data}`
    )
  }
  if (webhook.authentication === 'token') {
    return `SECRET='<webhook secret>'\n${body}curl -X ${webhook.method} '${url}' \\\n  -H "Authorization: Bearer $SECRET"${data}`
  }
  return `${body}curl -X ${webhook.method} '${url}'${data}`
}
