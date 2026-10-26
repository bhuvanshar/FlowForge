import { render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import type { NodeProps } from '@xyflow/react'

import { WorkflowNode } from './WorkflowNode'
import type { WorkflowFlowNode, WorkflowNodeData } from './types'

// Handles need a live React Flow instance; replace them with inspectable markers.
vi.mock('@xyflow/react', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@xyflow/react')>()),
  Handle: ({ type, id }: { type: string; id: string }) => <span data-testid={`handle-${type}-${id}`} />,
}))

function renderNode(data: Partial<WorkflowNodeData> = {}, selected = false) {
  const props = {
    id: 'n1', type: 'workflowNode', selected, dragging: false, zIndex: 0, isConnectable: true,
    positionAbsoluteX: 0, positionAbsoluteY: 0,
    data: { name: 'My node', nodeType: 'core.httpRequest', typeVersion: 1, configuration: {}, ...data },
  } as unknown as NodeProps<WorkflowFlowNode>
  return render(<WorkflowNode {...props} />)
}

describe('WorkflowNode', () => {
  it('shows the display name, the node type label and an icon', () => {
    const { container } = renderNode()
    const node = screen.getByTestId('workflow-node')

    expect(within(node).getByText('My node')).toBeInTheDocument()
    expect(within(node).getByText('HTTP Request')).toBeInTheDocument()
    expect(node).toHaveAttribute('data-node-type', 'core.httpRequest')
    expect(container.querySelector('svg')).toBeInTheDocument()
  })

  it('renders one input and one output handle for ordinary nodes', () => {
    renderNode()
    expect(screen.getByTestId('handle-target-main')).toBeInTheDocument()
    expect(screen.getByTestId('handle-source-main')).toBeInTheDocument()
  })

  it('renders no input handle for triggers', () => {
    renderNode({ nodeType: 'core.manualTrigger' })
    expect(screen.queryByTestId('handle-target-main')).not.toBeInTheDocument()
    expect(screen.getByTestId('handle-source-main')).toBeInTheDocument()
  })

  it('renders labelled true/false output handles for conditions', () => {
    renderNode({ nodeType: 'core.condition' })
    expect(screen.getByTestId('handle-source-true')).toBeInTheDocument()
    expect(screen.getByTestId('handle-source-false')).toBeInTheDocument()
    expect(screen.getByText('true')).toBeInTheDocument()
    expect(screen.getByText('false')).toBeInTheDocument()
  })

  it.each(['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED'] as const)('shows the %s execution status', (status) => {
    renderNode({ executionStatus: status })
    expect(screen.getByRole('img', { name: `Status: ${status}` })).toHaveAttribute('data-status', status)
  })

  it('shows no status indicator without execution data', () => {
    renderNode()
    expect(screen.queryByRole('img', { name: /Status/ })).not.toBeInTheDocument()
  })

  it('flags incomplete configuration and hides the flag once the configuration is valid', () => {
    const { unmount } = renderNode({ configuration: { method: 'GET', url: '', headers: [], query: [], timeoutSeconds: 30 } })
    expect(screen.getByRole('img', { name: 'Configuration incomplete' })).toBeInTheDocument()
    unmount()

    renderNode({ configuration: { method: 'GET', url: 'https://example.com', headers: [], query: [], timeoutSeconds: 30 } })
    expect(screen.queryByRole('img', { name: 'Configuration incomplete' })).not.toBeInTheDocument()
  })

  it('never flags nodes that need no configuration or have an unknown type', () => {
    const { unmount } = renderNode({ nodeType: 'core.manualTrigger', configuration: {} })
    expect(screen.queryByRole('img', { name: 'Configuration incomplete' })).not.toBeInTheDocument()
    unmount()
    renderNode({ nodeType: 'vendor.custom', configuration: { anything: true } })
    expect(screen.queryByRole('img', { name: 'Configuration incomplete' })).not.toBeInTheDocument()
  })

  it('still renders unknown node types with their identifier', () => {
    renderNode({ nodeType: 'vendor.custom' })
    expect(screen.getByText('vendor.custom')).toBeInTheDocument()
  })
})
