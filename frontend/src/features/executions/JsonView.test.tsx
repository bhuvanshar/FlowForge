import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { JsonView } from './JsonView'

describe('JsonView', () => {
  it('renders indented, readable JSON', () => {
    render(<JsonView label="Output" value={{ id: 'u-1', tags: ['a', 'b'], nested: { ok: true, none: null } }} />)

    const pre = screen.getByLabelText('Output')
    expect(pre.textContent).toBe(JSON.stringify({ id: 'u-1', tags: ['a', 'b'], nested: { ok: true, none: null } }, null, 2))
    expect(pre.textContent).toContain('\n  "tags": [')
  })

  it('shows an empty label for absent values and renders falsy values', () => {
    const { rerender } = render(<JsonView label="Input" value={undefined} emptyLabel="No input" />)
    expect(screen.getByText('No input')).toBeInTheDocument()
    rerender(<JsonView label="Input" value={null} />)
    expect(screen.getByLabelText('Input').textContent).toBe('null')
    rerender(<JsonView label="Input" value={0} />)
    expect(screen.getByLabelText('Input').textContent).toBe('0')
  })

  it('explains truncated payloads and shows the stored preview', () => {
    render(<JsonView label="Output" value={{ _truncated: true, originalSizeBytes: 307_200, preview: '{"blob":"xxxx' }} />)

    expect(screen.getByRole('note')).toHaveTextContent('Large payload (300 KiB): only a preview is stored.')
    expect(screen.getByLabelText('Output').textContent).toBe('{"blob":"xxxx…')
  })
})
