import { apiClient } from './client'

export const CREDENTIALS_QUERY_KEY = ['credentials'] as const

/** A stored credential as the API returns it: never with secret values (they are write-only). */
export interface Credential {
  id: string
  name: string
  type: string
  description?: string
  allowedHosts: string[]
  /** Non-secret fields, e.g. `{ username }`. */
  fields: Record<string, string>
  /** Names of the stored secret fields; their values are never returned. */
  secretFields: string[]
  secretVersion: number
  createdAt: string
  updatedAt: string
  rotatedAt?: string
  lastUsedAt?: string
  lockVersion: number
}

export interface CreateCredentialRequest {
  type: string
  name: string
  description?: string
  allowedHosts: string[]
  fields: Record<string, string>
  secret: Record<string, string>
}

export interface UpdateCredentialRequest {
  name: string
  description?: string
  allowedHosts: string[]
  fields: Record<string, string>
  lockVersion: number
}

export interface CredentialUsage {
  id: string
  name: string
  status: string
}

export interface EncryptionStatus {
  available: boolean
  activeKeyId?: string
  credentialsOnOldKeys: number
  /** Webhook secrets still in plaintext (from before encryption) or under an older key. */
  webhookSecretsToEncrypt?: number
}

export async function listCredentials(): Promise<Credential[]> {
  return (await apiClient.get<Credential[]>('/credentials')).data
}

export async function getEncryptionStatus(): Promise<EncryptionStatus> {
  return (await apiClient.get<EncryptionStatus>('/credentials/status')).data
}

export async function createCredential(request: CreateCredentialRequest): Promise<Credential> {
  return (await apiClient.post<Credential>('/credentials', request)).data
}

export async function updateCredential(id: string, request: UpdateCredentialRequest): Promise<Credential> {
  return (await apiClient.put<Credential>(`/credentials/${id}`, request)).data
}

export async function rotateCredential(id: string, secret: Record<string, string>): Promise<Credential> {
  return (await apiClient.post<Credential>(`/credentials/${id}/rotate`, { secret })).data
}

export async function deleteCredential(id: string): Promise<void> {
  await apiClient.delete(`/credentials/${id}`)
}

export async function getCredentialUsage(id: string): Promise<CredentialUsage[]> {
  return (await apiClient.get<{ workflows: CredentialUsage[] }>(`/credentials/${id}/usage`)).data.workflows
}
