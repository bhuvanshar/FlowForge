import { ChevronRight, PanelLeft } from 'lucide-react'
import { Link, useLocation } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { ConnectivityIndicator } from '@/features/health/ConnectivityIndicator'
import { useUiStore } from '@/stores/uiStore'

import { sectionOf } from './sections'

export function Header() {
  const toggleSidebar = useUiStore((s) => s.toggleSidebar)
  const { pathname } = useLocation()
  const section = sectionOf(pathname)
  return (
    <header className="flex h-16 shrink-0 items-center justify-between gap-3 border-b bg-card px-4 sm:px-7">
      <div className="flex items-center gap-3">
        <Button variant="ghost" size="icon" aria-label="Toggle sidebar" onClick={toggleSidebar}>
          <PanelLeft className="size-4 text-muted-foreground" aria-hidden />
        </Button>
        <span className="hidden h-4 w-px bg-border sm:block" />
        <nav aria-label="Breadcrumb" className="flex items-center gap-2 text-xs">
          <Link to="/" className="hidden text-muted-foreground hover:text-foreground sm:block">
            Workspace
          </Link>
          <ChevronRight className="hidden size-3 text-muted-foreground sm:block" aria-hidden />
          <span className="font-medium" aria-current="page">{section}</span>
        </nav>
      </div>
      <div className="rounded-full border bg-background px-3 py-1.5">
        <ConnectivityIndicator />
      </div>
    </header>
  )
}
