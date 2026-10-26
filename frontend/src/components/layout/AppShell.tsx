import { Suspense, useEffect, useRef } from 'react'
import { Outlet, useLocation } from 'react-router-dom'

import { Header } from './Header'
import { sectionOf } from './sections'
import { Sidebar } from './Sidebar'

export function AppShell() {
  const { pathname } = useLocation()
  const main = useRef<HTMLElement>(null)
  const shownPath = useRef(pathname)

  // Page title for the browser tab and screen readers; after navigating, keyboard focus moves to the page content
  // (not on the first load, and never while something inside the page already has focus).
  useEffect(() => {
    document.title = `${sectionOf(pathname)} · FlowForge`
    if (shownPath.current === pathname) return // first load (or a repeated effect run): nothing was navigated to
    shownPath.current = pathname
    if (!main.current?.contains(document.activeElement) || document.activeElement === document.body) {
      main.current?.focus({ preventScroll: true })
    }
  }, [pathname])

  return (
    <div className="flex h-dvh min-h-0 overflow-hidden">
      <a href="#main" className="skip-link">
        Skip to main content
      </a>
      <Sidebar />
      <div className="flex min-w-0 flex-1 flex-col">
        <Header />
        <main id="main" ref={main} tabIndex={-1} className="min-h-0 flex-1 overflow-auto outline-none focus:outline-none focus-visible:ring-0 focus-visible:ring-offset-0">
          <Suspense fallback={<p role="status" className="p-6 text-sm text-muted-foreground">Loading page…</p>}>
            <Outlet />
          </Suspense>
        </main>
      </div>
    </div>
  )
}
