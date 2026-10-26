import {
  applyEdgeChanges,
  applyNodeChanges,
  type Connection,
  type EdgeChange,
  type NodeChange,
  type XYPosition,
} from '@xyflow/react'
import { create } from 'zustand'

import type { ValidationIssue, ValidationResult, WorkflowResponse } from '@/lib/api/workflows'
import {
  DEFAULT_HANDLE,
  MAX_NODES,
  type NodeExecutionStatus,
  type Viewport,
  type WorkflowDefinition,
  type WorkflowStatus,
} from '@/lib/workflow/definition'

import { getConfigDefinition, getDefaultConfiguration, validateFormValues } from './config/registry'
import type { FormValues } from './config/fieldSpec'
import { getNodeTypeSpec, isKnownNodeType } from './nodeTypes'
import { edgeLabel, fromDefinition, toDefinition } from './serialization'
import { FLOW_NODE_TYPE, type WorkflowFlowEdge, type WorkflowFlowNode } from './types'

export const MAX_HISTORY = 100
export const DEFAULT_VIEWPORT: Viewport = { x: 0, y: 0, zoom: 1 }
export const NEW_WORKFLOW_KEY = 'new'

interface Snapshot {
  nodes: WorkflowFlowNode[]
  edges: WorkflowFlowEdge[]
}

/** Last backend validation result, indexed for highlighting. */
export interface ValidationState {
  result: ValidationResult
  /** Content signature that was validated; differs from the current one when the result is outdated. */
  signature: string
  byNode: Record<string, ValidationIssue[]>
  byEdge: Record<string, ValidationIssue[]>
}

export type ValidationStatus = 'idle' | 'running' | 'failed'

export interface EditorState {
  /** Changes on load/reset, including reopening the same workflow. Tags in-flight editor requests. */
  sessionId: number
  /** Which workflow the store currently holds: `'new'` or a workflow id; null when empty. */
  loadedFor: string | null
  workflowId: string | null
  name: string
  description: string | undefined
  status: WorkflowStatus
  version: number
  lockVersion: number | null

  nodes: WorkflowFlowNode[]
  edges: WorkflowFlowEdge[]
  viewport: Viewport
  /** True when the loaded workflow had a saved viewport, so the canvas should restore it instead of fitting. */
  hasSavedViewport: boolean

  /** Signature of the last persisted content (name, description, graph; not viewport or selection). */
  savedSignature: string
  past: Snapshot[]
  future: Snapshot[]
  dragStart: Snapshot | null
  /**
   * Unapplied settings-form edits, keyed by node id (raw form values). They survive switching nodes, count as unsaved
   * changes, and are validated and applied when saving.
   */
  drafts: Record<string, FormValues>
  validation: ValidationState | null
  validationStatus: ValidationStatus
  /** Execution shown in the bottom inspector, and its node statuses mirrored onto the canvas (display only). */
  activeExecutionId: string | null
  /** Incremented when a run is started from the editor; the bottom panel opens on every change. */
  inspectorRequest: number
  runStatuses: Record<string, NodeExecutionStatus> | null

  newWorkflow: () => void
  load: (workflow: WorkflowResponse) => void
  reset: () => void
  setName: (name: string) => void
  setViewport: (viewport: Viewport) => void
  addNode: (type: string, position: XYPosition) => string | null
  onNodesChange: (changes: NodeChange<WorkflowFlowNode>[]) => void
  onEdgesChange: (changes: EdgeChange<WorkflowFlowEdge>[]) => void
  isValidConnection: (connection: ConnectionLike) => boolean
  onConnect: (connection: Connection) => void
  deleteSelected: () => void
  setDraft: (nodeId: string, values: FormValues) => void
  clearDraft: (nodeId: string) => void
  /** Commits a validated configuration to a node as one undo step and discards its draft. */
  applyConfiguration: (nodeId: string, configuration: Record<string, unknown>) => void
  /**
   * Applies every draft that validates (one undo step). Drafts that are invalid stay as drafts and their node ids are
   * returned so the caller can block saving.
   */
  applyDrafts: () => { ok: boolean; invalidNodeIds: string[] }
  selectOnly: (nodeId: string) => void
  selectEdgeOnly: (edgeId: string) => void
  setValidation: (result: ValidationResult, signature: string) => void
  setValidationStatus: (status: ValidationStatus) => void
  setActiveExecutionId: (id: string | null) => void
  /** Shows a freshly started execution and opens the bottom inspector. */
  showExecution: (id: string) => void
  setRunStatuses: (statuses: Record<string, NodeExecutionStatus> | null) => void
  beginDrag: () => void
  endDrag: () => void
  undo: () => void
  redo: () => void
  /** Marks the saved server state as current. `savedSignature` is the signature that was sent, so edits made while saving stay dirty. */
  markSaved: (workflow: WorkflowResponse, savedSignature: string) => void
  /** Takes status and lock version from a server response that did not change the definition (activate/deactivate). */
  applyServerStatus: (workflow: WorkflowResponse) => void
}

type ConnectionLike = Pick<Connection, 'source' | 'target'> & {
  sourceHandle?: string | null
  targetHandle?: string | null
}

export function contentSignature(
  state: Pick<EditorState, 'name' | 'description' | 'nodes' | 'edges'>,
): string {
  return JSON.stringify([state.name, state.description ?? null, toDefinition(state.nodes, state.edges)])
}

export const selectSignature = (s: EditorState) => contentSignature(s)
export const selectHasDrafts = (s: EditorState) => Object.keys(s.drafts).length > 0
export const selectIsDirty = (s: EditorState) => contentSignature(s) !== s.savedSignature || selectHasDrafts(s)
export const selectValidationStale = (s: EditorState) =>
  s.validation !== null && s.validation.signature !== contentSignature(s)
export const selectCanUndo = (s: EditorState) => s.past.length > 0
export const selectCanRedo = (s: EditorState) => s.future.length > 0
/** The canonical definition for saving; the viewport is included as editor metadata. */
export function selectDefinition(s: EditorState, viewport: Viewport = s.viewport): WorkflowDefinition {
  return toDefinition(s.nodes, s.edges, viewport)
}

function shortId(): string {
  return crypto.randomUUID().replace(/-/g, '').slice(0, 8)
}

function uniqueName(base: string, nodes: readonly WorkflowFlowNode[]): string {
  const taken = new Set(nodes.map((n) => n.data.name))
  if (!taken.has(base)) return base
  for (let i = 2; ; i++) {
    const candidate = `${base} ${i}`
    if (!taken.has(candidate)) return candidate
  }
}

const emptyContent = () => ({
  nodes: [] as WorkflowFlowNode[],
  edges: [] as WorkflowFlowEdge[],
  past: [] as Snapshot[],
  future: [] as Snapshot[],
  dragStart: null,
  drafts: {} as Record<string, FormValues>,
})

let nextSessionId = 0

const initialState = () => {
  const content = emptyContent()
  return {
    sessionId: ++nextSessionId,
    loadedFor: null as string | null,
    workflowId: null as string | null,
    name: '',
    description: undefined as string | undefined,
    status: 'DRAFT' as WorkflowStatus,
    version: 1,
    lockVersion: null as number | null,
    viewport: DEFAULT_VIEWPORT,
    hasSavedViewport: false,
    validation: null as ValidationState | null,
    validationStatus: 'idle' as ValidationStatus,
    activeExecutionId: null as string | null,
    inspectorRequest: 0,
    runStatuses: null as Record<string, NodeExecutionStatus> | null,
    savedSignature: contentSignature({ name: '', description: undefined, nodes: content.nodes, edges: content.edges }),
    ...content,
  }
}

const snapshot = (s: Pick<EditorState, 'nodes' | 'edges'>): Snapshot => ({ nodes: s.nodes, edges: s.edges })

const pushHistory = (s: Pick<EditorState, 'past' | 'nodes' | 'edges'>): Pick<EditorState, 'past' | 'future'> => ({
  past: [...s.past, snapshot(s)].slice(-MAX_HISTORY),
  future: [],
})

const pruneDrafts = (drafts: Record<string, FormValues>, nodes: readonly WorkflowFlowNode[]) => {
  const ids = new Set(nodes.map((n) => n.id))
  return Object.fromEntries(Object.entries(drafts).filter(([id]) => ids.has(id)))
}

const deselected = (nodes: WorkflowFlowNode[]) => nodes.map((n) => (n.selected ? { ...n, selected: false } : n))
const deselectedEdges = (edges: WorkflowFlowEdge[]) => edges.map((e) => (e.selected ? { ...e, selected: false } : e))

function positionsEqual(a: WorkflowFlowNode[], b: WorkflowFlowNode[]): boolean {
  if (a.length !== b.length) return false
  const byId = new Map(b.map((n) => [n.id, n]))
  return a.every((n) => {
    const other = byId.get(n.id)
    return other !== undefined && other.position.x === n.position.x && other.position.y === n.position.y
  })
}

export const useEditorStore = create<EditorState>()((set, get) => ({
  ...initialState(),

  newWorkflow: () => set({ ...initialState(), loadedFor: NEW_WORKFLOW_KEY }),

  load: (workflow) => {
    const { nodes, edges, viewport } = fromDefinition(workflow.definition)
    set({
      ...initialState(),
      loadedFor: workflow.id,
      workflowId: workflow.id,
      name: workflow.name,
      description: workflow.description,
      status: workflow.status,
      version: workflow.version,
      lockVersion: workflow.lockVersion,
      nodes,
      edges,
      viewport: viewport ?? DEFAULT_VIEWPORT,
      hasSavedViewport: viewport !== undefined,
      savedSignature: contentSignature({ name: workflow.name, description: workflow.description, nodes, edges }),
    })
  },

  reset: () => set(initialState()),

  setName: (name) => set({ name }),

  setViewport: (viewport) => set({ viewport }),

  addNode: (type, position) => {
    const s = get()
    if (s.nodes.length >= MAX_NODES) return null
    const spec = getNodeTypeSpec(type)
    const id = `${type.split('.').pop()}_${shortId()}`
    const node: WorkflowFlowNode = {
      id,
      type: FLOW_NODE_TYPE,
      position,
      selected: true,
      data: {
        name: uniqueName(spec.label, s.nodes),
        nodeType: spec.type,
        typeVersion: spec.typeVersion,
        configuration: getDefaultConfiguration(spec.type),
      },
    }
    set({ ...pushHistory(s), nodes: [...deselected(s.nodes), node], edges: deselectedEdges(s.edges) })
    return id
  },

  onNodesChange: (changes) => set((s) => ({ nodes: applyNodeChanges(changes, s.nodes) })),

  onEdgesChange: (changes) => set((s) => ({ edges: applyEdgeChanges(changes, s.edges) })),

  isValidConnection: (c) => {
    const { nodes, edges } = get()
    const sourceHandle = c.sourceHandle ?? DEFAULT_HANDLE
    const targetHandle = c.targetHandle ?? DEFAULT_HANDLE
    if (!c.source || !c.target || c.source === c.target) return false
    const source = nodes.find((n) => n.id === c.source)
    const target = nodes.find((n) => n.id === c.target)
    if (!source || !target) return false
    const sourceSpec = getNodeTypeSpec(source.data.nodeType)
    const targetSpec = getNodeTypeSpec(target.data.nodeType)
    if (isKnownNodeType(source.data.nodeType) && !sourceSpec.outputs.includes(sourceHandle)) return false
    if (isKnownNodeType(target.data.nodeType) && !targetSpec.inputs.includes(targetHandle)) return false
    return !edges.some(
      (e) =>
        e.source === c.source && e.target === c.target &&
        (e.sourceHandle ?? DEFAULT_HANDLE) === sourceHandle && (e.targetHandle ?? DEFAULT_HANDLE) === targetHandle,
    )
  },

  onConnect: (connection) => {
    const s = get()
    if (!s.isValidConnection(connection)) return
    const sourceHandle = connection.sourceHandle ?? DEFAULT_HANDLE
    const edge: WorkflowFlowEdge = {
      id: `e_${shortId()}`,
      source: connection.source,
      sourceHandle,
      target: connection.target,
      targetHandle: connection.targetHandle ?? DEFAULT_HANDLE,
      label: edgeLabel(sourceHandle),
    }
    set({ ...pushHistory(s), edges: [...s.edges, edge] })
  },

  deleteSelected: () => {
    const s = get()
    const removedNodes = new Set(s.nodes.filter((n) => n.selected).map((n) => n.id))
    const edges = s.edges.filter((e) => !e.selected && !removedNodes.has(e.source) && !removedNodes.has(e.target))
    if (removedNodes.size === 0 && edges.length === s.edges.length) return
    const nodes = s.nodes.filter((n) => !removedNodes.has(n.id))
    set({ ...pushHistory(s), nodes, edges, drafts: pruneDrafts(s.drafts, nodes) })
  },

  setDraft: (nodeId, values) =>
    set((s) => ({ drafts: { ...s.drafts, [nodeId]: JSON.parse(JSON.stringify(values)) as FormValues } })),

  clearDraft: (nodeId) =>
    set((s) => {
      if (!(nodeId in s.drafts)) return {}
      const { [nodeId]: _removed, ...rest } = s.drafts
      void _removed
      return { drafts: rest }
    }),

  applyConfiguration: (nodeId, configuration) => {
    const s = get()
    if (!s.nodes.some((n) => n.id === nodeId)) return
    const { [nodeId]: _removed, ...drafts } = s.drafts
    void _removed
    set({
      ...pushHistory(s),
      drafts,
      nodes: s.nodes.map((n) => (n.id === nodeId ? { ...n, data: { ...n.data, configuration } } : n)),
    })
  },

  applyDrafts: () => {
    const s = get()
    const applied = new Map<string, Record<string, unknown>>()
    const invalidNodeIds: string[] = []
    for (const [nodeId, values] of Object.entries(s.drafts)) {
      const node = s.nodes.find((n) => n.id === nodeId)
      const definition = node && getConfigDefinition(node.data.nodeType)
      if (!node || !definition) continue
      if (validateFormValues(node.data.nodeType, values).valid) applied.set(nodeId, definition.toConfiguration(values))
      else invalidNodeIds.push(nodeId)
    }
    if (applied.size > 0) {
      const remaining = Object.fromEntries(Object.entries(s.drafts).filter(([id]) => !applied.has(id)))
      set({
        ...pushHistory(s),
        drafts: remaining,
        nodes: s.nodes.map((n) => (applied.has(n.id) ? { ...n, data: { ...n.data, configuration: applied.get(n.id)! } } : n)),
      })
    }
    return { ok: invalidNodeIds.length === 0, invalidNodeIds }
  },

  selectOnly: (nodeId) =>
    set((s) => ({
      nodes: s.nodes.map((n) => (n.selected !== (n.id === nodeId) ? { ...n, selected: n.id === nodeId } : n)),
      edges: deselectedEdges(s.edges),
    })),

  selectEdgeOnly: (edgeId) =>
    set((s) => ({
      nodes: deselected(s.nodes),
      edges: s.edges.map((e) => (e.selected !== (e.id === edgeId) ? { ...e, selected: e.id === edgeId } : e)),
    })),

  setValidation: (result, signature) => {
    const byNode: Record<string, ValidationIssue[]> = {}
    const byEdge: Record<string, ValidationIssue[]> = {}
    for (const issue of result.issues) {
      if (issue.nodeId) (byNode[issue.nodeId] ??= []).push(issue)
      if (issue.edgeId) (byEdge[issue.edgeId] ??= []).push(issue)
    }
    set({ validation: { result, signature, byNode, byEdge }, validationStatus: 'idle' })
  },

  setValidationStatus: (validationStatus) => set({ validationStatus }),

  setActiveExecutionId: (activeExecutionId) => set({ activeExecutionId }),

  showExecution: (id) => set((s) => ({ activeExecutionId: id, inspectorRequest: s.inspectorRequest + 1 })),

  setRunStatuses: (runStatuses) => set({ runStatuses }),

  beginDrag: () => set((s) => ({ dragStart: snapshot(s) })),

  endDrag: () =>
    set((s) => {
      if (!s.dragStart) return {}
      const moved = !positionsEqual(s.dragStart.nodes, s.nodes)
      return moved
        ? { dragStart: null, past: [...s.past, s.dragStart].slice(-MAX_HISTORY), future: [] }
        : { dragStart: null }
    }),

  undo: () =>
    set((s) => {
      const previous = s.past[s.past.length - 1]
      if (!previous) return {}
      return {
        past: s.past.slice(0, -1),
        future: [...s.future, snapshot(s)],
        nodes: deselected(previous.nodes),
        edges: deselectedEdges(previous.edges),
        drafts: pruneDrafts(s.drafts, previous.nodes),
      }
    }),

  redo: () =>
    set((s) => {
      const next = s.future[s.future.length - 1]
      if (!next) return {}
      return {
        future: s.future.slice(0, -1),
        past: [...s.past, snapshot(s)].slice(-MAX_HISTORY),
        nodes: deselected(next.nodes),
        edges: deselectedEdges(next.edges),
        drafts: pruneDrafts(s.drafts, next.nodes),
      }
    }),

  markSaved: (workflow, savedSignature) =>
    set({
      loadedFor: workflow.id,
      workflowId: workflow.id,
      status: workflow.status,
      version: workflow.version,
      lockVersion: workflow.lockVersion,
      savedSignature,
    }),

  applyServerStatus: (workflow) =>
    set((s) => (s.workflowId === workflow.id ? { status: workflow.status, lockVersion: workflow.lockVersion } : {})),
}))
