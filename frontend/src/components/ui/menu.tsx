import type { LucideIcon } from 'lucide-react'
import { MoreHorizontal } from 'lucide-react'
import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'

import { cn } from '@/lib/utils'

import { Button } from './button'

export interface MenuItem {
  key: string
  label: string
  icon?: LucideIcon
  tone?: 'default' | 'danger'
  disabled?: boolean
  onSelect: () => void
}

const MENU_WIDTH = 224

/**
 * "More actions" menu following the WAI-ARIA menu button pattern: Enter, Space or Arrow Down open it (Arrow Up opens at the
 * last item), arrows/Home/End move, Escape or Tab close it, and focus returns to the button. It renders in a portal with
 * fixed positioning so cards with `overflow: hidden` cannot clip it, and flips upward near the bottom of the screen.
 */
export function ActionMenu({ label, items, disabled = false }: { label: string; items: MenuItem[]; disabled?: boolean }) {
  const [open, setOpen] = useState(false)
  const [position, setPosition] = useState<{ left: number; top?: number; bottom?: number }>({ left: 0 })
  const button = useRef<HTMLButtonElement>(null)
  const menu = useRef<HTMLUListElement>(null)
  const menuId = useId()
  const pendingFocus = useRef<'first' | 'last'>('first')

  const close = useCallback((returnFocus: boolean) => {
    setOpen(false)
    if (returnFocus) button.current?.focus()
  }, [])

  const enabledItems = () => Array.from(menu.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]:not(:disabled)') ?? [])

  useLayoutEffect(() => {
    if (!open || !button.current) return
    const rect = button.current.getBoundingClientRect()
    const left = Math.max(8, Math.min(rect.right - MENU_WIDTH, window.innerWidth - MENU_WIDTH - 8))
    const below = window.innerHeight - rect.bottom
    // Flip above the button when there is not enough room below (items are ~40px each).
    const needed = items.length * 40 + 16
    setPosition(below >= needed || rect.top < needed ? { left, top: rect.bottom + 4 } : { left, bottom: window.innerHeight - rect.top + 4 })
  }, [open, items.length])

  useEffect(() => {
    if (!open) return
    const list = enabledItems()
    ;(pendingFocus.current === 'last' ? list[list.length - 1] : list[0])?.focus()
    const away = (event: MouseEvent) => {
      const target = event.target as Node
      if (!menu.current?.contains(target) && !button.current?.contains(target)) close(false)
    }
    const dismiss = () => close(false)
    document.addEventListener('mousedown', away)
    window.addEventListener('resize', dismiss)
    window.addEventListener('scroll', dismiss, true)
    return () => {
      document.removeEventListener('mousedown', away)
      window.removeEventListener('resize', dismiss)
      window.removeEventListener('scroll', dismiss, true)
    }
  }, [open, close])

  function onButtonKeyDown(event: React.KeyboardEvent) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      pendingFocus.current = event.key === 'ArrowUp' ? 'last' : 'first'
      setOpen(true)
    }
  }

  function onMenuKeyDown(event: React.KeyboardEvent) {
    const list = enabledItems()
    const index = list.indexOf(document.activeElement as HTMLButtonElement)
    const move = (to: number) => {
      event.preventDefault()
      list[(to + list.length) % list.length]?.focus()
    }
    switch (event.key) {
      case 'ArrowDown': return move(index + 1)
      case 'ArrowUp': return move(index - 1)
      case 'Home': return move(0)
      case 'End': return move(list.length - 1)
      case 'Escape':
        event.preventDefault()
        return close(true)
      case 'Tab':
        return close(false)
    }
  }

  return (
    <>
      <Button
        ref={button}
        variant="ghost"
        size="icon"
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        disabled={disabled}
        onClick={() => {
          pendingFocus.current = 'first'
          setOpen((o) => !o)
        }}
        onKeyDown={onButtonKeyDown}
      >
        <MoreHorizontal className="size-4" aria-hidden />
      </Button>
      {open &&
        createPortal(
          <ul
            ref={menu}
            id={menuId}
            role="menu"
            aria-label={label}
            onKeyDown={onMenuKeyDown}
            style={{ position: 'fixed', width: MENU_WIDTH, ...position }}
            className="z-50 rounded-xl border bg-card p-1.5 shadow-pop"
          >
            {items.map(({ key, label: text, icon: Icon, tone = 'default', disabled: off, onSelect }) => (
              <li key={key} role="none">
                <button
                  type="button"
                  role="menuitem"
                  tabIndex={-1}
                  disabled={off}
                  onClick={() => {
                    // Close first and give focus back to the button, so a dialog opened by the item returns focus to it.
                    close(true)
                    onSelect()
                  }}
                  className={cn(
                    'flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-left text-sm focus-visible:ring-offset-0 disabled:opacity-50',
                    tone === 'danger' ? 'text-destructive-foreground hover:bg-destructive-soft' : 'hover:bg-muted',
                  )}
                >
                  {Icon && <Icon className="size-4 shrink-0" aria-hidden />}
                  {text}
                </button>
              </li>
            ))}
          </ul>,
          document.body,
        )}
    </>
  )
}
