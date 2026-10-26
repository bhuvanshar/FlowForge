/** Which part of the application a path belongs to: the breadcrumb label and the browser tab title. */
export function sectionOf(pathname: string): string {
  if (pathname.startsWith('/credentials')) return 'Credentials'
  if (pathname.startsWith('/settings')) return 'Settings'
  if (pathname.includes('/executions') || pathname.startsWith('/executions/')) return 'Executions'
  if (pathname.startsWith('/workflows/')) return 'Workflow editor'
  return 'Workflows'
}
