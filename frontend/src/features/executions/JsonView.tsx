import { useMemo } from 'react'

interface Props {
  value: unknown
  /** Shown when the value is undefined (e.g. a skipped node has no input). */
  emptyLabel?: string
  label: string
}

function isTruncated(value: unknown): value is { _truncated: true; originalSizeBytes: number; preview: string } {
  return typeof value === 'object' && value !== null && (value as { _truncated?: unknown })._truncated === true
}

/** Readable, indented JSON. Large payloads stored truncated by the backend show a notice and the stored preview. */
export function JsonView({ value, label, emptyLabel = 'No data' }: Props) {
  const text = useMemo(() => (value === undefined ? '' : JSON.stringify(value, null, 2)), [value])
  if (value === undefined) {
    return <p className="text-xs text-muted-foreground">{emptyLabel}</p>
  }
  if (isTruncated(value)) {
    return (
      <div className="flex flex-col gap-1">
        <p role="note" className="text-xs text-amber-700 dark:text-amber-300">
          Large payload ({Math.round(value.originalSizeBytes / 1024)} KiB): only a preview is stored.
        </p>
        <pre aria-label={label} className="max-h-72 overflow-auto rounded-md bg-muted p-2 font-mono text-xs">
          {value.preview}…
        </pre>
      </div>
    )
  }
  return (
    <pre aria-label={label} className="max-h-72 overflow-auto rounded-md bg-muted p-2 font-mono text-xs">
      {text}
    </pre>
  )
}
