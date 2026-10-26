import { beforeEach, describe, expect, it } from 'vitest'

import example from '../../../../backend/src/test/resources/workflow/example-definition.v1.json'
import type { WorkflowResponse } from '@/lib/api/workflows'
import type { WorkflowDefinition } from '@/lib/workflow/definition'

import {
  MAX_HISTORY,
  NEW_WORKFLOW_KEY,
  selectCanRedo,
  selectCanUndo,
  selectDefinition,
  selectIsDirty,
  selectSignature,
  useEditorStore,
} from './editorStore'

const store = () => useEditorStore.getState()

function workflow(overrides: Partial<WorkflowResponse> = {}): WorkflowResponse {
  return {
    id: 'wf-1', name: 'Orders', status: 'DRAFT', version: 3, lockVersion: 5,
    definition: example as WorkflowDefinition, createdAt: 't', updatedAt: 't', ...overrides,
  }
}

function addTrigger(x = 0, y = 0) {
  return store().addNode('core.manualTrigger', { x, y })!
}

describe('editor store', () => {
  beforeEach(() => {
    store().reset()
    store().newWorkflow()
  })

  describe('adding nodes', () => {
    it('creates a node with backend type, version, handles-ready data and a unique name', () => {
      const a = addTrigger(10, 20)
      const b = store().addNode('core.manualTrigger', { x: 0, y: 0 })!

      const [first, second] = store().nodes
      expect(first.id).toBe(a)
      expect(first.id).toMatch(/^manualTrigger_[0-9a-f]{8}$/)
      expect(first.position).toEqual({ x: 10, y: 20 })
      expect(first.data).toMatchObject({ nodeType: 'core.manualTrigger', typeVersion: 1, name: 'Manual Trigger', configuration: {} })
      expect(second.id).toBe(b)
      expect(second.data.name).toBe('Manual Trigger 2')
      expect(a).not.toBe(b)
    })

    it('selects only the newly added node', () => {
      addTrigger()
      addTrigger()
      expect(store().nodes.map((n) => n.selected)).toEqual([false, true])
    })

    it('serializes node ids that satisfy the contract pattern', () => {
      const id = store().addNode('core.httpRequest', { x: 0, y: 0 })!
      expect(id).toMatch(/^[A-Za-z0-9_-]{1,64}$/)
    })
  })

  describe('connecting', () => {
    function twoNodes() {
      const trigger = addTrigger()
      const logger = store().addNode('core.logger', { x: 200, y: 0 })!
      const condition = store().addNode('core.condition', { x: 400, y: 0 })!
      return { trigger, logger, condition }
    }

    it('creates an edge with explicit default handles', () => {
      const { trigger, logger } = twoNodes()
      store().onConnect({ source: trigger, target: logger, sourceHandle: null, targetHandle: null })

      expect(store().edges).toHaveLength(1)
      expect(store().edges[0]).toMatchObject({ source: trigger, target: logger, sourceHandle: 'main', targetHandle: 'main' })
      expect(store().edges[0].id).toMatch(/^e_[0-9a-f]{8}$/)
    })

    it('uses branch handles from the node type and labels them', () => {
      const { logger, condition } = twoNodes()
      store().onConnect({ source: condition, target: logger, sourceHandle: 'false', targetHandle: 'main' })

      expect(store().edges[0]).toMatchObject({ sourceHandle: 'false', label: 'false' })
    })

    it('rejects self-loops, duplicates, unknown handles, unknown nodes and inputs on triggers', () => {
      const { trigger, logger, condition } = twoNodes()
      const connect = (source: string, target: string, sourceHandle = 'main', targetHandle = 'main') =>
        store().isValidConnection({ source, target, sourceHandle, targetHandle })

      expect(connect(logger, logger)).toBe(false)
      expect(connect(trigger, 'missing')).toBe(false)
      expect(connect(condition, logger, 'main')).toBe(false) // condition has true/false only
      expect(connect(logger, trigger)).toBe(false) // trigger has no input
      expect(connect(trigger, logger)).toBe(true)

      store().onConnect({ source: trigger, target: logger, sourceHandle: 'main', targetHandle: 'main' })
      expect(connect(trigger, logger)).toBe(false) // duplicate
      store().onConnect({ source: trigger, target: logger, sourceHandle: 'main', targetHandle: 'main' })
      expect(store().edges).toHaveLength(1)
    })

    it('allows both branches of a condition to different nodes', () => {
      const { logger, condition } = twoNodes()
      const other = store().addNode('core.transform', { x: 0, y: 100 })!
      store().onConnect({ source: condition, target: logger, sourceHandle: 'true', targetHandle: 'main' })
      store().onConnect({ source: condition, target: other, sourceHandle: 'false', targetHandle: 'main' })

      expect(store().edges.map((e) => e.sourceHandle)).toEqual(['true', 'false'])
    })
  })

  describe('deleting', () => {
    it('removes selected nodes together with their edges in one undo step', () => {
      const a = addTrigger()
      const b = store().addNode('core.logger', { x: 1, y: 1 })!
      const c = store().addNode('core.logger', { x: 2, y: 2 })!
      store().onConnect({ source: a, target: b, sourceHandle: 'main', targetHandle: 'main' })
      store().onConnect({ source: b, target: c, sourceHandle: 'main', targetHandle: 'main' })
      store().onNodesChange([{ type: 'select', id: b, selected: true }, { type: 'select', id: c, selected: false }])
      const undoDepth = store().past.length

      store().deleteSelected()

      expect(store().nodes.map((n) => n.id)).toEqual([a, c])
      expect(store().edges).toHaveLength(0)
      expect(store().past).toHaveLength(undoDepth + 1)

      store().undo()
      expect(store().nodes).toHaveLength(3)
      expect(store().edges).toHaveLength(2)
    })

    it('removes a selected edge only', () => {
      const a = addTrigger()
      const b = store().addNode('core.logger', { x: 1, y: 1 })!
      store().onConnect({ source: a, target: b, sourceHandle: 'main', targetHandle: 'main' })
      store().onNodesChange([{ type: 'select', id: b, selected: false }]) // addNode left b selected
      store().onEdgesChange([{ type: 'select', id: store().edges[0].id, selected: true }])

      store().deleteSelected()

      expect(store().edges).toHaveLength(0)
      expect(store().nodes).toHaveLength(2)
    })

    it('does nothing and records no history when nothing is selected', () => {
      addTrigger()
      store().onNodesChange([{ type: 'select', id: store().nodes[0].id, selected: false }])
      const depth = store().past.length

      store().deleteSelected()

      expect(store().past).toHaveLength(depth)
      expect(store().nodes).toHaveLength(1)
    })
  })

  describe('undo / redo', () => {
    it('undoes and redoes node creation and edge creation', () => {
      const a = addTrigger()
      const b = store().addNode('core.logger', { x: 1, y: 1 })!
      store().onConnect({ source: a, target: b, sourceHandle: 'main', targetHandle: 'main' })

      store().undo()
      expect(store().edges).toHaveLength(0)
      store().undo()
      expect(store().nodes.map((n) => n.id)).toEqual([a])
      expect(selectCanRedo(store())).toBe(true)

      store().redo()
      store().redo()
      expect(store().nodes).toHaveLength(2)
      expect(store().edges).toHaveLength(1)
      expect(selectCanRedo(store())).toBe(false)
    })

    it('clears the redo stack when a new action follows an undo', () => {
      addTrigger()
      store().undo()
      expect(selectCanRedo(store())).toBe(true)
      addTrigger()
      expect(selectCanRedo(store())).toBe(false)
    })

    it('is a no-op with empty stacks', () => {
      store().undo()
      store().redo()
      expect(store().nodes).toEqual([])
      expect(selectCanUndo(store())).toBe(false)
    })

    it('records one history step per drag, only when nodes actually moved', () => {
      const id = addTrigger(0, 0)
      const base = store().past.length

      store().beginDrag()
      store().endDrag() // click without movement
      expect(store().past).toHaveLength(base)

      store().beginDrag()
      store().onNodesChange([{ type: 'position', id, position: { x: 5, y: 5 }, dragging: true }])
      store().onNodesChange([{ type: 'position', id, position: { x: 50, y: 60 }, dragging: true }])
      store().endDrag()
      expect(store().past).toHaveLength(base + 1)
      expect(store().nodes[0].position).toEqual({ x: 50, y: 60 })

      store().undo()
      expect(store().nodes[0].position).toEqual({ x: 0, y: 0 })
    })

    it('caps history length', () => {
      for (let i = 0; i < MAX_HISTORY + 20; i++) addTrigger(i, 0)
      expect(store().past).toHaveLength(MAX_HISTORY)
    })

    it('does not restore selection', () => {
      addTrigger()
      addTrigger()
      store().undo()
      expect(store().nodes.every((n) => !n.selected)).toBe(true)
    })
  })

  describe('dirty state', () => {
    it('is clean for a fresh new workflow and after loading', () => {
      expect(selectIsDirty(store())).toBe(false)
      store().load(workflow())
      expect(selectIsDirty(store())).toBe(false)
    })

    it('becomes dirty on edits and clean again when undone back to the saved state', () => {
      store().load(workflow())
      addTrigger()
      expect(selectIsDirty(store())).toBe(true)
      store().undo()
      expect(selectIsDirty(store())).toBe(false)
    })

    it('becomes dirty on renaming the workflow and on moving a node', () => {
      store().load(workflow())
      store().setName('Renamed')
      expect(selectIsDirty(store())).toBe(true)
      store().setName('Orders')
      expect(selectIsDirty(store())).toBe(false)

      const id = store().nodes[0].id
      store().onNodesChange([{ type: 'position', id, position: { x: 99, y: 99 } }])
      expect(selectIsDirty(store())).toBe(true)
    })

    it('ignores selection, measurement and viewport changes', () => {
      store().load(workflow())
      const id = store().nodes[0].id
      store().onNodesChange([{ type: 'select', id, selected: true }])
      store().onNodesChange([{ type: 'dimensions', id, dimensions: { width: 10, height: 10 } }])
      store().setViewport({ x: 5, y: 5, zoom: 2 })

      expect(selectIsDirty(store())).toBe(false)
    })

    it('markSaved updates server metadata and keeps edits made while saving dirty', () => {
      store().load(workflow())
      addTrigger()
      const sentSignature = selectSignature(store())
      addTrigger() // edited during the request

      store().markSaved(workflow({ version: 4, lockVersion: 6 }), sentSignature)

      expect(store()).toMatchObject({ version: 4, lockVersion: 6 })
      expect(selectIsDirty(store())).toBe(true)

      store().markSaved(workflow({ lockVersion: 7 }), selectSignature(store()))
      expect(selectIsDirty(store())).toBe(false)
    })

    it('markSaved turns a new workflow into a persisted one', () => {
      addTrigger()
      store().markSaved(workflow({ id: 'created-1' }), selectSignature(store()))

      expect(store()).toMatchObject({ workflowId: 'created-1', loadedFor: 'created-1' })
      expect(selectIsDirty(store())).toBe(false)
    })
  })

  describe('loading', () => {
    it('loads nodes, edges, metadata and the saved viewport, and resets history', () => {
      addTrigger()
      store().load(workflow({ description: 'desc' }))

      expect(store()).toMatchObject({
        loadedFor: 'wf-1', workflowId: 'wf-1', name: 'Orders', description: 'desc', version: 3, lockVersion: 5,
        hasSavedViewport: true, viewport: { x: 0, y: 0, zoom: 1 },
      })
      expect(store().nodes.map((n) => n.id)).toEqual(['trigger', 'check', 'notify', 'ignore'])
      expect(store().nodes[2].position).toEqual({ x: 480, y: -80.5 })
      expect(store().edges).toHaveLength(3)
      expect(store().past).toEqual([])
    })

    it('marks a workflow without editor metadata as having no saved viewport', () => {
      const { editor: _editor, ...rest } = example as WorkflowDefinition
      void _editor
      store().load(workflow({ definition: rest }))
      expect(store().hasSavedViewport).toBe(false)
    })

    it('newWorkflow starts empty and keyed as new', () => {
      store().load(workflow())
      store().newWorkflow()

      expect(store()).toMatchObject({ loadedFor: NEW_WORKFLOW_KEY, workflowId: null, name: '', lockVersion: null })
      expect(store().nodes).toEqual([])
    })

    it('serialized state after load equals the loaded definition', () => {
      store().load(workflow())
      expect(selectDefinition(store())).toEqual(example)
    })
  })
})
