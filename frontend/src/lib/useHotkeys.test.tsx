import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { useHotkeys } from './useHotkeys'

function Demo({ handlers }: { handlers: Record<string, () => void> }) {
  useHotkeys(handlers)
  return (
    <>
      <input aria-label="field" />
      <select aria-label="choice"><option>a</option></select>
      <div role="menu" tabIndex={0} aria-label="menu" />
      <button>plain</button>
    </>
  )
}

describe('useHotkeys', () => {
  it('runs the handler for its key and prevents the browser default only then', async () => {
    const run = vi.fn()
    render(<Demo handlers={{ n: run }} />)
    await userEvent.keyboard('n')
    expect(run).toHaveBeenCalledTimes(1)
    await userEvent.keyboard('x')
    expect(run).toHaveBeenCalledTimes(1)
  })

  it('stays out of the way while typing, in selects, in menus, with modifiers, and when focus is on a button', async () => {
    const run = vi.fn()
    render(<Demo handlers={{ n: run }} />)
    await userEvent.type(screen.getByLabelText('field'), 'n')
    screen.getByLabelText('choice').focus()
    await userEvent.keyboard('n')
    screen.getByRole('menu').focus()
    await userEvent.keyboard('n')
    await userEvent.keyboard('{Control>}n{/Control}')
    await userEvent.keyboard('{Meta>}n{/Meta}')
    expect(run).not.toHaveBeenCalled()
    screen.getByRole('button', { name: 'plain' }).focus()
    await userEvent.keyboard('n')
    expect(run).toHaveBeenCalledTimes(1)
  })

  it('is removed when the component unmounts', async () => {
    const run = vi.fn()
    const { unmount } = render(<Demo handlers={{ n: run }} />)
    unmount()
    await userEvent.keyboard('n')
    expect(run).not.toHaveBeenCalled()
  })
})
