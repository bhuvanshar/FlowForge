import { Badge } from '@/components/ui/badge'

import { STATUS_STYLE, type AnyStatus } from './format'

/** Status of an execution or node run as a badge: colour from the design tokens, meaning from the label. */
export function StatusBadge({ status, className }: { status: AnyStatus; className?: string }) {
  const style = STATUS_STYLE[status]
  return (
    <Badge data-status={status} tone={style.tone} className={className}>
      {style.label}
    </Badge>
  )
}
