import { Braces, Clock, GitBranch, Globe, HelpCircle, Play, ScrollText, Shuffle, Sparkles, Webhook, type LucideIcon } from 'lucide-react'

import { DEFAULT_HANDLE } from '@/lib/workflow/definition'

/**
 * Node types the editor can create. Identifiers, versions and handles mirror the canonical catalog
 * backend/src/main/resources/schemas/node-types.v1.json (checked by nodeTypes.test.ts). Never add a type here
 * without adding it to that file.
 */
export const NODE_TYPE_IDS = {
  manualTrigger: 'core.manualTrigger',
  webhookTrigger: 'core.webhookTrigger',
  scheduleTrigger: 'core.scheduleTrigger',
  setVariables: 'core.setVariables',
  condition: 'core.condition',
  transform: 'core.transform',
  logger: 'core.logger',
  httpRequest: 'core.httpRequest',
  aiModel: 'core.aiModel',
} as const

export interface NodeTypeSpec {
  type: string
  typeVersion: number
  label: string
  /** `trigger` starts the workflow (exactly one per workflow, no inputs); `action` runs when reached. */
  role: 'trigger' | 'action'
  description: string
  icon: LucideIcon
  /** Target handle ids. */
  inputs: string[]
  /** Source handle ids. More than one means the node branches. */
  outputs: string[]
}

const MAIN = [DEFAULT_HANDLE]

export const NODE_TYPES: readonly NodeTypeSpec[] = [
  {
    type: NODE_TYPE_IDS.manualTrigger, typeVersion: 1, label: 'Manual Trigger', role: 'trigger', icon: Play,
    description: 'Starts the workflow on demand', inputs: [], outputs: MAIN,
  },
  {
    type: NODE_TYPE_IDS.webhookTrigger, typeVersion: 1, label: 'Webhook Trigger', role: 'trigger', icon: Webhook,
    description: 'Starts on an authenticated HTTP request', inputs: [], outputs: MAIN,
  },
  {
    type: NODE_TYPE_IDS.scheduleTrigger, typeVersion: 1, label: 'Schedule Trigger', role: 'trigger', icon: Clock,
    description: 'Starts on a cron schedule', inputs: [], outputs: MAIN,
  },
  {
    type: NODE_TYPE_IDS.setVariables, typeVersion: 1, label: 'Set Variables', role: 'action', icon: Braces,
    description: 'Define or overwrite variables', inputs: MAIN, outputs: MAIN,
  },
  {
    type: NODE_TYPE_IDS.condition, typeVersion: 1, label: 'Condition', role: 'action', icon: GitBranch,
    description: 'Branch on true or false', inputs: MAIN, outputs: ['true', 'false'],
  },
  {
    type: NODE_TYPE_IDS.transform, typeVersion: 1, label: 'Transform', role: 'action', icon: Shuffle,
    description: 'Reshape data', inputs: MAIN, outputs: MAIN,
  },
  {
    type: NODE_TYPE_IDS.logger, typeVersion: 1, label: 'Logger', role: 'action', icon: ScrollText,
    description: 'Write a message to the run log', inputs: MAIN, outputs: MAIN,
  },
  {
    type: NODE_TYPE_IDS.httpRequest, typeVersion: 1, label: 'HTTP Request', role: 'action', icon: Globe,
    description: 'Call an HTTP endpoint', inputs: MAIN, outputs: MAIN,
  },
  {
    type: NODE_TYPE_IDS.aiModel, typeVersion: 1, label: 'AI Model', role: 'action', icon: Sparkles,
    description: 'Ask a chat model and get text or validated JSON', inputs: MAIN, outputs: MAIN,
  },
]

const BY_TYPE = new Map(NODE_TYPES.map((spec) => [spec.type, spec]))

export function isKnownNodeType(type: string): boolean {
  return BY_TYPE.has(type)
}

/**
 * Looks up a node type. Workflows may contain types this editor does not know (e.g. created by a newer backend);
 * they get a generic spec so they can still be displayed, moved, connected and saved unchanged.
 */
export function getNodeTypeSpec(type: string): NodeTypeSpec {
  return (
    BY_TYPE.get(type) ?? {
      type, typeVersion: 1, label: type, role: 'action', icon: HelpCircle, description: 'Unknown node type',
      inputs: MAIN, outputs: MAIN,
    }
  )
}
