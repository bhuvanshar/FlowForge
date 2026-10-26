import { ArrowUpRight, KeyRound, Layers3, LockKeyhole, Settings, Workflow, Zap } from 'lucide-react'
import { NavLink, useLocation } from 'react-router-dom'

import { cn } from '@/lib/utils'
import { useUiStore } from '@/stores/uiStore'

const NAV_ITEMS = [
  { to: '/', label: 'Workflows', icon: Workflow },
  { to: '/credentials', label: 'Credentials', icon: KeyRound },
  { to: '/settings', label: 'Settings', icon: Settings },
]

export function Sidebar() {
  const collapsed = useUiStore((s) => s.sidebarCollapsed)
  const { pathname } = useLocation()
  return (
    <aside
      aria-label="Sidebar"
      className={cn(
        'forge-sidebar flex shrink-0 flex-col transition-[width] motion-reduce:transition-none',
        collapsed ? 'w-16' : 'w-16 md:w-60',
      )}
    >
      <div className="flex h-[76px] shrink-0 items-center gap-2.5 px-4 md:px-5">
        <span className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary text-white">
          <Zap className="size-5" fill="currentColor" aria-hidden />
        </span>
        {!collapsed && (
          <span className="hidden text-lg font-semibold tracking-tight md:block">
            FlowForge<span className="text-primary">.</span>
          </span>
        )}
      </div>
      {!collapsed && (
        <div className="mx-4 mb-7 hidden items-center gap-3 rounded-xl border border-white/10 bg-white/5 px-3 py-3 md:flex">
          <span className="flex size-8 items-center justify-center rounded-lg bg-white/10">
            <Layers3 className="size-4 text-stone-300" aria-hidden />
          </span>
          <div>
            <p className="text-xs font-medium">Personal workspace</p>
            <p className="mt-0.5 text-[10px] text-stone-400">Local environment</p>
          </div>
        </div>
      )}
      {!collapsed && (
        <p className="mb-2 hidden px-6 text-[9px] font-semibold uppercase tracking-[0.2em] text-stone-500 md:block">
          Workspace
        </p>
      )}
      <nav aria-label="Main" className="flex flex-col gap-1 px-2 md:px-3">
        {NAV_ITEMS.map(({ to, label, icon: Icon }) => {
          const active =
            to === '/'
              ? pathname === '/' || pathname.startsWith('/workflows/') || pathname.startsWith('/executions/')
              : pathname.startsWith(to)
          return (
            <NavLink
              key={to}
              to={to}
              end
              title={label}
              aria-label={label}
              aria-current={active ? 'page' : undefined}
              className={cn(
                'flex items-center gap-3 rounded-lg px-3 py-3 text-xs transition-colors',
                active
                  ? 'bg-white/10 font-medium text-white'
                  : 'text-stone-400 hover:bg-white/5 hover:text-white',
              )}
            >
              <Icon className={cn('size-4 shrink-0', active && 'text-orange-400')} aria-hidden />
              {!collapsed && (
                <>
                  <span className="hidden md:block">{label}</span>
                  {active && <span className="ml-auto hidden size-1.5 rounded-full bg-orange-400 md:block" />}
                </>
              )}
            </NavLink>
          )
        })}
      </nav>
      <div className="mt-auto p-3 md:p-4">
        {!collapsed && (
          <div className="mb-5 hidden rounded-xl border border-white/10 p-4 md:block">
            <LockKeyhole className="mb-3 size-4 text-stone-400" aria-hidden />
            <p className="text-xs font-medium">Your workflows. Your machine.</p>
            <p className="mt-2 text-[11px] leading-5 text-stone-400">
              Built to run locally. Your credentials stay encrypted.
            </p>
            <NavLink
              to="/credentials"
              className="mt-3 inline-flex items-center gap-1 text-[11px] text-orange-300 hover:text-white"
            >
              Manage credentials
              <ArrowUpRight className="size-3" aria-hidden />
            </NavLink>
          </div>
        )}
        <div className="flex items-center gap-3 border-t border-white/10 pt-4">
          <span
            className="flex size-8 shrink-0 items-center justify-center rounded-full bg-stone-700 text-[10px] font-semibold text-stone-200"
            aria-hidden
          >
            PW
          </span>
          {!collapsed && (
            <div className="hidden md:block">
              <p className="text-xs">Personal workspace</p>
              <p className="mt-0.5 text-[10px] text-stone-500">Self-hosted edition</p>
            </div>
          )}
        </div>
      </div>
    </aside>
  )
}
