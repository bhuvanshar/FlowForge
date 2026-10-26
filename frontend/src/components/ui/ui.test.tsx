import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Inbox } from 'lucide-react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'

import { Badge } from './badge'
import { ConfirmDialog } from './confirm-dialog'
import { Dialog } from './dialog'
import { EmptyState } from './empty-state'
import { SelectField, TextAreaField, TextField } from './form-field'
import { ActionMenu } from './menu'
import { Notice } from './notice'
import { PageHeader } from './page-header'
import { SegmentedControl } from './segmented-control'
import { Skeleton } from './skeleton'

describe('Badge', () => {
  it('shows its label with the tone classes from the design tokens, plus an optional dot', () => {
    const { rerender } = render(<Badge tone="success" dot>Active</Badge>)
    const badge = screen.getByText('Active')
    expect(badge).toHaveClass('bg-success-soft', 'text-success-foreground')
    expect(badge.querySelector('[aria-hidden]')).not.toBeNull()
    rerender(<Badge tone="danger">Failed</Badge>)
    expect(screen.getByText('Failed')).toHaveClass('bg-destructive-soft')
    expect(screen.getByText('Failed').querySelector('[aria-hidden]')).toBeNull()
  })
})

describe('Notice', () => {
  it('announces errors at once (alert) and other messages politely (status), and can be dismissed', async () => {
    const dismiss = vi.fn()
    const { rerender } = render(<Notice tone="danger" onDismiss={dismiss}>Saving failed</Notice>)
    expect(screen.getByRole('alert')).toHaveTextContent('Saving failed')
    await userEvent.click(screen.getByRole('button', { name: 'Dismiss message' }))
    expect(dismiss).toHaveBeenCalled()
    rerender(<Notice tone="success">Saved</Notice>)
    expect(screen.getByRole('status')).toHaveTextContent('Saved')
    expect(screen.queryByRole('button', { name: 'Dismiss message' })).not.toBeInTheDocument()
  })
})

describe('EmptyState, PageHeader, Skeleton', () => {
  it('explains an empty list and offers the next step', () => {
    render(<EmptyState icon={Inbox} title="Nothing here" description="Add the first one." action={<button>Add</button>} />)
    expect(screen.getByRole('heading', { name: 'Nothing here', level: 2 })).toBeInTheDocument()
    expect(screen.getByText('Add the first one.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add' })).toBeInTheDocument()
  })

  it('renders one h1 with description and actions', () => {
    render(<PageHeader eyebrow="Workspace" title="Settings" description="Tune it." actions={<button>Save</button>} />)
    expect(screen.getByRole('heading', { name: 'Settings', level: 1 })).toBeInTheDocument()
    expect(screen.getByText('Workspace')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument()
  })

  it('hides decorative skeletons from assistive technology', () => {
    const { container } = render(<Skeleton className="h-4" />)
    expect(container.firstElementChild).toHaveAttribute('aria-hidden', 'true')
  })
})

describe('form fields', () => {
  it('connect label, hint and error to the control', () => {
    render(<TextField label="Name" hint="Shown in lists" error="Required" />)
    const input = screen.getByLabelText('Name')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAccessibleDescription('Shown in lists Required')
    expect(screen.getByRole('alert')).toHaveTextContent('Required')
  })

  it('leave out error wiring when valid, for all three control types', () => {
    render(
      <>
        <TextField label="One" />
        <TextAreaField label="Two" />
        <SelectField label="Three"><option>a</option></SelectField>
      </>,
    )
    for (const label of ['One', 'Two', 'Three']) {
      expect(screen.getByLabelText(label)).not.toHaveAttribute('aria-invalid')
      expect(screen.getByLabelText(label)).not.toHaveAttribute('aria-describedby')
    }
  })
})

describe('SegmentedControl', () => {
  function Demo({ variant }: { variant?: 'pill' | 'icon' }) {
    const [value, setValue] = useState('b')
    return (
      <SegmentedControl
        label="Choose"
        value={value}
        onChange={setValue}
        variant={variant}
        options={[{ value: 'a', label: 'Alpha', ariaLabel: 'Alpha option' }, { value: 'b', label: 'Beta' }]}
      />
    )
  }

  it('is a labelled group of toggle buttons that reports the selection', async () => {
    render(<Demo />)
    expect(screen.getByRole('group', { name: 'Choose' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Beta' })).toHaveAttribute('aria-pressed', 'true')
    await userEvent.click(screen.getByRole('button', { name: 'Alpha option' }))
    expect(screen.getByRole('button', { name: 'Alpha option' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Beta' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('works from the keyboard', async () => {
    render(<Demo />)
    await userEvent.tab()
    await userEvent.keyboard('{Enter}')
    expect(screen.getByRole('button', { name: 'Alpha option' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('icon variant relies on the accessible name instead of the label text', () => {
    render(<Demo variant="icon" />)
    expect(screen.getByRole('button', { name: 'Alpha option' })).toHaveTextContent('')
  })
})

describe('Dialog and ConfirmDialog', () => {
  it('Dialog is a named, described modal that closes on cancel (Escape) and on backdrop click only', async () => {
    const close = vi.fn()
    render(
      <Dialog title="Rename" description="Pick a new name" onClose={close}>
        <button>Inside</button>
      </Dialog>,
    )
    const dialog = screen.getByRole('dialog', { name: 'Rename' })
    expect(dialog).toHaveAccessibleDescription('Pick a new name')
    await userEvent.click(screen.getByRole('button', { name: 'Inside' }))
    expect(close).not.toHaveBeenCalled()
    const escape = new Event('cancel', { cancelable: true })
    fireEvent(dialog, escape)
    expect(escape.defaultPrevented).toBe(true) // the owner decides to unmount
    expect(close).toHaveBeenCalledTimes(1)
    await userEvent.click(dialog)
    expect(close).toHaveBeenCalledTimes(2)
  })

  it('ConfirmDialog focuses the safe choice, reports both answers and shows failures', async () => {
    const confirm = vi.fn()
    const cancel = vi.fn()
    const { rerender } = render(
      <ConfirmDialog title="Delete it?" description="Gone for good." confirmLabel="Delete" tone="danger"
        onConfirm={confirm} onCancel={cancel} />,
    )
    const dialog = screen.getByRole('dialog', { name: 'Delete it?' })
    expect(within(dialog).getByRole('button', { name: 'Cancel' })).toHaveFocus()
    expect(within(dialog).getByRole('button', { name: 'Delete' })).toHaveClass('bg-destructive')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))
    expect(confirm).toHaveBeenCalledTimes(1)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(cancel).toHaveBeenCalledTimes(1)

    rerender(
      <ConfirmDialog title="Delete it?" description="Gone for good." confirmLabel="Delete" busy error="Server said no"
        onConfirm={confirm} onCancel={cancel} />,
    )
    expect(screen.getByRole('alert')).toHaveTextContent('Server said no')
    expect(screen.getByRole('button', { name: 'Working…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled()
  })
})

describe('ActionMenu', () => {
  const items = () => [
    { key: 'a', label: 'First', onSelect: vi.fn() },
    { key: 'b', label: 'Disabled one', disabled: true, onSelect: vi.fn() },
    { key: 'c', label: 'Third', tone: 'danger' as const, onSelect: vi.fn() },
  ]

  it('opens with the mouse, runs an item and returns focus to its button', async () => {
    const list = items()
    render(<ActionMenu label="More actions" items={list} />)
    const button = screen.getByRole('button', { name: 'More actions' })
    expect(button).toHaveAttribute('aria-haspopup', 'menu')
    expect(button).toHaveAttribute('aria-expanded', 'false')

    await userEvent.click(button)
    expect(button).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('menu', { name: 'More actions' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('menuitem', { name: 'Third' }))

    expect(list[2].onSelect).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    expect(button).toHaveFocus()
  })

  it('is fully keyboard operable: arrows skip disabled items and wrap, Home/End jump, Escape closes', async () => {
    render(<ActionMenu label="More actions" items={items()} />)
    screen.getByRole('button', { name: 'More actions' }).focus()
    await userEvent.keyboard('{ArrowDown}')

    expect(screen.getByRole('menuitem', { name: 'First' })).toHaveFocus()
    await userEvent.keyboard('{ArrowDown}')
    expect(screen.getByRole('menuitem', { name: 'Third' })).toHaveFocus() // skipped the disabled item
    await userEvent.keyboard('{ArrowDown}')
    expect(screen.getByRole('menuitem', { name: 'First' })).toHaveFocus() // wrapped
    await userEvent.keyboard('{End}')
    expect(screen.getByRole('menuitem', { name: 'Third' })).toHaveFocus()
    await userEvent.keyboard('{Home}')
    expect(screen.getByRole('menuitem', { name: 'First' })).toHaveFocus()
    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'More actions' })).toHaveFocus()
  })

  it('ArrowUp opens at the last item; Enter activates; Tab and outside clicks close', async () => {
    const list = items()
    render(<><ActionMenu label="More actions" items={list} /><p>elsewhere</p></>)
    screen.getByRole('button', { name: 'More actions' }).focus()
    await userEvent.keyboard('{ArrowUp}')
    expect(screen.getByRole('menuitem', { name: 'Third' })).toHaveFocus()
    await userEvent.keyboard('{Enter}')
    expect(list[2].onSelect).toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: 'More actions' }))
    await userEvent.tab()
    await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: 'More actions' }))
    await userEvent.click(screen.getByText('elsewhere'))
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('can be disabled as a whole', () => {
    render(<ActionMenu label="More actions" items={items()} disabled />)
    expect(screen.getByRole('button', { name: 'More actions' })).toBeDisabled()
  })
})
