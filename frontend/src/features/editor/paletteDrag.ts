import { useReactFlow } from '@xyflow/react'

import { useEditorStore } from './editorStore'

/** Drag-and-drop payload key carrying the backend node type identifier from the palette to the canvas. */
export const DRAG_MIME = 'application/x-flowforge-node-type'

/** Hook-backed helper: adds a node of `type` at the centre of the current viewport. Must be used inside ReactFlowProvider. */
export function useAddNodeAtCenter() {
  const { screenToFlowPosition } = useReactFlow()
  const addNode = useEditorStore((s) => s.addNode)
  return (type: string) => {
    const pane = document.querySelector('.react-flow')?.getBoundingClientRect()
    const center = pane
      ? { x: pane.left + pane.width / 2, y: pane.top + pane.height / 2 }
      : { x: window.innerWidth / 2, y: window.innerHeight / 2 }
    const position = screenToFlowPosition(center)
    // Lay successive additions out on a grid around the centre so they never overlap (nodes are ~192x56).
    const index = useEditorStore.getState().nodes.length
    const dx = ((index % 3) - 1) * 224
    const dy = (Math.floor(index / 3) % 4) * 88 - 100
    addNode(type, { x: Math.round(position.x - 96 + dx), y: Math.round(position.y - 24 + dy) })
  }
}
