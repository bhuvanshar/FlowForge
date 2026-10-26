import {
  Background,
  BackgroundVariant,
  Controls,
  MarkerType,
  MiniMap,
  ReactFlow,
  useReactFlow,
  type NodeTypes,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useCallback, useMemo, type DragEvent } from 'react'

import { decorateEdges } from './edgeDecoration'
import { useEditorStore } from './editorStore'
import { DRAG_MIME } from './paletteDrag'
import { isKnownNodeType } from './nodeTypes'
import { FLOW_NODE_TYPE } from './types'
import { WorkflowNode } from './WorkflowNode'

// Defined once at module level: React Flow warns and re-renders everything if this object changes identity.
const nodeTypes: NodeTypes = { [FLOW_NODE_TYPE]: WorkflowNode }
const defaultEdgeOptions = { markerEnd: { type: MarkerType.ArrowClosed } }

export function WorkflowCanvas() {
  const nodes = useEditorStore((s) => s.nodes)
  const storedEdges = useEditorStore((s) => s.edges)
  const edgeIssues = useEditorStore((s) => s.validation?.byEdge)
  const edges = useMemo(() => decorateEdges(storedEdges, edgeIssues), [storedEdges, edgeIssues])
  const onNodesChange = useEditorStore((s) => s.onNodesChange)
  const onEdgesChange = useEditorStore((s) => s.onEdgesChange)
  const onConnect = useEditorStore((s) => s.onConnect)
  const isValidConnection = useEditorStore((s) => s.isValidConnection)
  const addNode = useEditorStore((s) => s.addNode)
  const beginDrag = useEditorStore((s) => s.beginDrag)
  const endDrag = useEditorStore((s) => s.endDrag)
  const setViewport = useEditorStore((s) => s.setViewport)
  const initialViewport = useEditorStore.getState().viewport
  const hasSavedViewport = useEditorStore.getState().hasSavedViewport
  const { screenToFlowPosition } = useReactFlow()

  const onDragOver = useCallback((event: DragEvent) => {
    event.preventDefault()
    event.dataTransfer.dropEffect = 'move'
  }, [])

  const onDrop = useCallback(
    (event: DragEvent) => {
      event.preventDefault()
      const type = event.dataTransfer.getData(DRAG_MIME)
      if (!type || !isKnownNodeType(type)) return
      const position = screenToFlowPosition({ x: event.clientX, y: event.clientY })
      addNode(type, { x: Math.round(position.x), y: Math.round(position.y) })
    },
    [addNode, screenToFlowPosition],
  )

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={nodeTypes}
      defaultEdgeOptions={defaultEdgeOptions}
      onNodesChange={onNodesChange}
      onEdgesChange={onEdgesChange}
      onConnect={onConnect}
      isValidConnection={isValidConnection}
      onNodeDragStart={beginDrag}
      onNodeDragStop={endDrag}
      onMoveEnd={(_event, viewport) => setViewport(viewport)}
      onDrop={onDrop}
      onDragOver={onDragOver}
      defaultViewport={initialViewport}
      fitView={!hasSavedViewport}
      fitViewOptions={{ maxZoom: 1, padding: 0.3 }}
      minZoom={0.2}
      maxZoom={2}
      // Deletion goes through the store (one undo step, edges cascade), see useEditorShortcuts.
      deleteKeyCode={null}
    >
      <Background variant={BackgroundVariant.Dots} gap={16} />
      <Controls />
      <MiniMap pannable zoomable style={{ width: 140, height: 90 }} />
    </ReactFlow>
  )
}
