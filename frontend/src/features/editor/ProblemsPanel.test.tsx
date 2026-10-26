import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { NodeProps } from '@xyflow/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { ValidationResult } from '@/lib/api/workflows'

import { selectSignature, selectValidationStale, useEditorStore } from './editorStore'
import { ProblemsPanel } from './ProblemsPanel'
import { PropertiesPanel } from './PropertiesPanel'
import type { WorkflowFlowNode } from './types'
import { WorkflowNode } from './WorkflowNode'

vi.mock('@xyflow/react', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@xyflow/react')>()),
  Handle: () => null,
}))

const store = () => useEditorStore.getState()

function result(issues: ValidationResult['issues']): ValidationResult {
  return {
    valid: !issues.some((i) => i.severity === 'ERROR'),
    errorCount: issues.filter((i) => i.severity === 'ERROR').length,
    warningCount: issues.filter((i) => i.severity === 'WARNING').length,
    issues,
    executionOrder: [],
  }
}

function setupGraph() {
  store().load({
    id: 'wf', name: 'W', status: 'DRAFT', version: 1, lockVersion: 0, createdAt: 't', updatedAt: 't',
    definition: {
      schemaVersion: 1,
      nodes: [
        { id: 't', type: 'core.manualTrigger', typeVersion: 1, name: 'Start', position: { x: 0, y: 0 }, configuration: {} },
        { id: 'c', type: 'core.condition', typeVersion: 1, name: 'Check', position: { x: 0, y: 0 }, configuration: { field: 'a', operator: 'equals', value: '1' } },
      ],
      edges: [{ id: 'e1', source: 't', sourceHandle: 'main', target: 'c', targetHandle: 'main' }],
    },
  })
}

const ISSUES: ValidationResult['issues'] = [
  { code: 'BRANCH_NOT_CONNECTED', severity: 'WARNING', message: "The 'false' branch of 'Check' is not connected", nodeId: 'c', path: 'nodes[1]' },
  { code: 'UNSUPPORTED_MERGE', severity: 'ERROR', message: "'Check' already has an incoming connection", nodeId: 'c', edgeId: 'e1', path: 'edges[0]' },
  { code: 'DUPLICATE_EDGE_ID', severity: 'ERROR', message: 'Connection id is used twice', edgeId: 'e1', path: 'edges[0].id' },
  { code: 'NO_TRIGGER', severity: 'ERROR', message: 'The workflow has no trigger', path: 'nodes' },
]

describe('validation state in the store', () => {
  beforeEach(() => {
    store().reset()
    setupGraph()
  })

  it('indexes issues by node and by edge', () => {
    store().setValidation(result(ISSUES), selectSignature(store()))

    expect(store().validation!.byNode.c.map((i) => i.code)).toEqual(['BRANCH_NOT_CONNECTED', 'UNSUPPORTED_MERGE'])
    expect(store().validation!.byEdge.e1.map((i) => i.code)).toEqual(['UNSUPPORTED_MERGE', 'DUPLICATE_EDGE_ID'])
    expect(store().validationStatus).toBe('idle')
  })

  it('is outdated after a content change and current again after undo', () => {
    store().setValidation(result([]), selectSignature(store()))
    expect(selectValidationStale(store())).toBe(false)

    store().addNode('core.logger', { x: 0, y: 0 })
    expect(selectValidationStale(store())).toBe(true)

    store().undo()
    expect(selectValidationStale(store())).toBe(false)
  })

  it('is not outdated by selection or viewport changes', () => {
    store().setValidation(result([]), selectSignature(store()))
    store().selectOnly('c')
    store().setViewport({ x: 9, y: 9, zoom: 2 })
    expect(selectValidationStale(store())).toBe(false)
  })

  it('is cleared when another workflow is loaded or a new one is started', () => {
    store().setValidation(result(ISSUES), selectSignature(store()))
    setupGraph()
    expect(store().validation).toBeNull()
    store().setValidation(result(ISSUES), selectSignature(store()))
    store().newWorkflow()
    expect(store().validation).toBeNull()
  })
})

describe('ProblemsPanel', () => {
  beforeEach(() => {
    store().reset()
    setupGraph()
  })

  it('asks to save new workflows first', () => {
    store().newWorkflow()
    render(<ProblemsPanel />)
    expect(screen.getByTestId('problems-summary')).toHaveTextContent('Save the workflow to validate it')
  })

  it('shows progress before the first result and a failure if the check could not run', () => {
    render(<ProblemsPanel />)
    expect(screen.getByTestId('problems-summary')).toHaveTextContent('Validating…')
    act(() => store().setValidationStatus('failed'))
    expect(screen.getByTestId('problems-summary')).toHaveTextContent('Validation failed to run')
  })

  it('reports a valid workflow', () => {
    store().setValidation(result([]), selectSignature(store()))
    render(<ProblemsPanel />)
    expect(screen.getByTestId('problems-summary')).toHaveTextContent('No problems')
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
  })

  it('lists errors before warnings with counts and affected node names', () => {
    store().setValidation(result(ISSUES), selectSignature(store()))
    render(<ProblemsPanel />)

    expect(screen.getByTestId('problems-summary')).toHaveTextContent('3 errors, 1 warning')
    const items = within(screen.getByRole('region', { name: 'Problems' })).getAllByRole('button').slice(1)
    expect(items.map((b) => b.getAttribute('data-code'))).toEqual([
      'UNSUPPORTED_MERGE', 'DUPLICATE_EDGE_ID', 'NO_TRIGGER', 'BRANCH_NOT_CONNECTED',
    ])
    expect(items[0]).toHaveTextContent('Check')
    expect(within(items[0]).getByLabelText('Error')).toBeInTheDocument()
    expect(within(items[3]).getByLabelText('Warning')).toBeInTheDocument()
  })

  it('selects the affected node, or the affected connection, when a problem is clicked', async () => {
    const onFocus = vi.fn()
    store().setValidation(result(ISSUES), selectSignature(store()))
    render(<ProblemsPanel onFocusIssue={onFocus} />)

    await userEvent.click(screen.getByText("'Check' already has an incoming connection"))
    expect(store().nodes.find((n) => n.id === 'c')!.selected).toBe(true)
    expect(onFocus).toHaveBeenCalledWith(ISSUES[1])

    await userEvent.click(screen.getByText('Connection id is used twice'))
    expect(store().edges[0].selected).toBe(true)
    expect(store().nodes.every((n) => !n.selected)).toBe(true)
  })

  it('marks results as outdated after an edit', () => {
    store().setValidation(result(ISSUES), selectSignature(store()))
    render(<ProblemsPanel />)
    act(() => {
      store().addNode('core.logger', { x: 0, y: 0 })
    })
    expect(screen.getByText('(outdated)')).toBeInTheDocument()
  })
})

describe('highlighting', () => {
  beforeEach(() => {
    store().reset()
    setupGraph()
  })

  const renderNode = (id: string) => {
    const node = store().nodes.find((n) => n.id === id)!
    return render(<WorkflowNode {...({ id, data: node.data, selected: false } as unknown as NodeProps<WorkflowFlowNode>)} />)
  }

  it('marks nodes with errors and shows the problem count', () => {
    store().setValidation(result(ISSUES), selectSignature(store()))
    renderNode('c')

    expect(screen.getByTestId('workflow-node')).toHaveAttribute('data-validation', 'error')
    expect(screen.getByRole('img', { name: '1 error, 1 warning' })).toHaveTextContent('2')
  })

  it('marks nodes with only warnings differently, and leaves clean nodes alone', () => {
    store().setValidation(result([ISSUES[0]]), selectSignature(store()))
    const { unmount } = renderNode('c')
    expect(screen.getByTestId('workflow-node')).toHaveAttribute('data-validation', 'warning')
    expect(screen.getByRole('img', { name: '1 warning' })).toBeInTheDocument()
    unmount()

    renderNode('t')
    expect(screen.getByTestId('workflow-node')).not.toHaveAttribute('data-validation')
  })

  it('lists the selected node problems in the properties panel', () => {
    store().setValidation(result(ISSUES), selectSignature(store()))
    store().selectOnly('c')
    render(<PropertiesPanel />)

    const list = screen.getByRole('list', { name: 'Node problems' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(2)
    expect(list).toHaveTextContent("The 'false' branch of 'Check' is not connected")
  })
})
