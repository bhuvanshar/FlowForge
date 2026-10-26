import { apiClient } from './client'

/** Effective server configuration (read-only, no secrets). Changed with environment variables and a restart. */
export interface ServerSettings {
  application: { version: string }
  execution: {
    workerThreads: number
    queueCapacity: number
    nodeTimeoutSeconds: number
    maxDurationSeconds: number
    maxActivePerWorkflow: number
    maxStoredPayloadBytes: number
  }
  outbound: {
    allowedHosts: string[]
    allowPrivateAddresses: boolean
    allowedPorts: number[]
    maxResponseBytes: number
    maxRequestBytes: number
    maxRedirects: number
  }
  triggers: {
    schedulerEnabled: boolean
    schedulerPollSeconds: number
    allowUnauthenticatedWebhooks: boolean
    webhookMaxBodyBytes: number
  }
  credentials: { encryptionAvailable: boolean; activeKeyId?: string; keyCount: number }
  ai: { maxPromptChars: number; maxResponseBytes: number }
  security: { allowedHostNames: string[]; allowedOrigins: string[]; maxRequestBytes: number }
}

export async function getServerSettings(): Promise<ServerSettings> {
  return (await apiClient.get<ServerSettings>('/settings')).data
}
