import axios from 'axios'

/**
 * Shared Axios instance for the FlowForge REST API (all endpoints live under /api/v1).
 * Feature modules add their own typed functions on top of this client.
 */
export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api/v1',
  timeout: 5000,
  headers: { Accept: 'application/json' },
})

/** RFC 9457 Problem Details as returned by the backend for every error. */
export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
  errors?: { field: string; message: string }[]
}

export function toProblemDetail(error: unknown): ProblemDetail | undefined {
  if (axios.isAxiosError(error) && error.response?.data && typeof error.response.data === 'object') {
    return error.response.data as ProblemDetail
  }
  return undefined
}
