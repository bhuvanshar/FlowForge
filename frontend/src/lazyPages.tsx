import { lazy } from 'react'

// Keep the graph editor and configuration libraries out of the initial dashboard download.
export const EditorPage = lazy(() => import('@/features/editor/EditorPage').then((m) => ({ default: m.EditorPage })))
export const SettingsPage = lazy(() => import('@/features/settings/SettingsPage').then((m) => ({ default: m.SettingsPage })))
export const CredentialsPage = lazy(() => import('@/features/credentials/CredentialsPage').then((m) => ({ default: m.CredentialsPage })))
export const ExecutionDetailPage = lazy(() => import('@/features/executions/ExecutionDetailPage').then((m) => ({ default: m.ExecutionDetailPage })))
export const ExecutionHistoryPage = lazy(() => import('@/features/executions/ExecutionHistoryPage').then((m) => ({ default: m.ExecutionHistoryPage })))
