import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { createMemoryRouter, MemoryRouter, RouterProvider } from 'react-router-dom'

import { routes } from '@/routes'

function newClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

/** Renders a component that needs a router context but not the data router (links, hooks). */
export function renderWithProviders(ui: ReactElement, { route = '/' } = {}) {
  return render(
    <QueryClientProvider client={newClient()}>
      <MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Renders the whole application routes with a data router (needed by the editor's unsaved-changes blocker). */
export function renderApp(route = '/') {
  const router = createMemoryRouter(routes, { initialEntries: [route] })
  const utils = render(
    <QueryClientProvider client={newClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, ...utils }
}
