import { Search } from 'lucide-react'
import { useState } from 'react'

import { DRAG_MIME } from './paletteDrag'
import { NODE_TYPES } from './nodeTypes'

/**
 * Left palette. Drag an item onto the canvas to create a node there; double-click (or press Enter) adds it
 * at the centre of the visible canvas.
 */
export function NodePalette({ onAddAtCenter }: { onAddAtCenter?: (type: string) => void }) {
  const [search, setSearch] = useState('')
  const visible = NODE_TYPES.filter((spec) =>
    `${spec.label} ${spec.description}`.toLowerCase().includes(search.trim().toLowerCase()),
  )
  return (
    <aside
      aria-label="Node palette"
      className="flex w-56 shrink-0 flex-col gap-3 overflow-auto border-r bg-card p-4"
    >
      <h2 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Nodes</h2>
      <div className="relative">
        <Search className="absolute left-2.5 top-2.5 size-3.5 text-muted-foreground" aria-hidden />
        <input
          aria-label="Search nodes"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Find a node…"
          className="h-9 w-full rounded-lg border bg-background pl-8 pr-2 text-xs"
        />
      </div>
      <p className="text-[10px] leading-4 text-muted-foreground">
        Drag onto the canvas or double-click to add.
      </p>
      <ul className="flex flex-col gap-2">
        {visible.map((spec) => {
          const Icon = spec.icon
          return (
            <li key={spec.type}>
              <button
                type="button"
                draggable
                data-node-type={spec.type}
                onDragStart={(event) => {
                  event.dataTransfer.setData(DRAG_MIME, spec.type)
                  event.dataTransfer.effectAllowed = 'move'
                }}
                onDoubleClick={() => onAddAtCenter?.(spec.type)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') onAddAtCenter?.(spec.type)
                }}
                title={`${spec.description}. Drag onto the canvas, or double-click to add.`}
                className="flex w-full cursor-grab items-center gap-3 rounded-xl border border-transparent bg-background px-3 py-3 text-left text-xs font-medium transition-colors hover:border-orange-200 hover:bg-orange-50 active:cursor-grabbing"
              >
                <Icon className="size-4 shrink-0 text-primary" aria-hidden />
                <span>{spec.label}</span>
              </button>
            </li>
          )
        })}
      </ul>
      {visible.length === 0 && (
        <p role="status" className="py-4 text-center text-xs text-muted-foreground">
          No nodes match your search.
        </p>
      )}
    </aside>
  )
}
