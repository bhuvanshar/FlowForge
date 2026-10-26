import type { RouteObject } from 'react-router-dom'

import { AppShell } from '@/components/layout/AppShell'
import { WorkflowListPage } from '@/features/workflows/WorkflowListPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { CredentialsPage, EditorPage, ExecutionDetailPage, ExecutionHistoryPage, SettingsPage } from './lazyPages'

export const routes: RouteObject[] = [
  {
    element: <AppShell />,
    children: [
      { index: true, element: <WorkflowListPage /> },
      // `:workflowId` is a workflow UUID, or the literal `new` for an unsaved workflow.
      { path: 'workflows/:workflowId', element: <EditorPage /> },
      { path: 'workflows/:workflowId/executions', element: <ExecutionHistoryPage /> },
      { path: 'executions/:executionId', element: <ExecutionDetailPage /> },
      { path: 'credentials', element: <CredentialsPage /> },
      { path: 'settings', element: <SettingsPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]
