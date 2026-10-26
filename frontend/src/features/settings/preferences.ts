import { create } from 'zustand'

/** Per-browser preferences. They only change what this browser shows; nothing is sent to the server. */
export type DashboardView = 'grid' | 'list'

export const SORT_OPTIONS = [
  { value: 'updatedAt,desc', label: 'Last updated' },
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'name,asc', label: 'Name: A–Z' },
  { value: 'name,desc', label: 'Name: Z–A' },
] as const
export const PAGE_SIZES = [12, 24, 48] as const

export interface Preferences {
  defaultView: DashboardView
  defaultSort: (typeof SORT_OPTIONS)[number]['value']
  pageSize: (typeof PAGE_SIZES)[number]
}

export const DEFAULT_PREFERENCES: Preferences = { defaultView: 'grid', defaultSort: 'updatedAt,desc', pageSize: 12 }

const STORAGE_KEY = 'flowforge.preferences.v1'

/** Accepts only known values: a corrupted or hand-edited entry falls back to the defaults field by field. */
export function sanitizePreferences(raw: unknown): Preferences {
  const value = (typeof raw === 'object' && raw !== null ? raw : {}) as Record<string, unknown>
  return {
    defaultView: value.defaultView === 'list' || value.defaultView === 'grid' ? value.defaultView : DEFAULT_PREFERENCES.defaultView,
    defaultSort: SORT_OPTIONS.find((o) => o.value === value.defaultSort)?.value ?? DEFAULT_PREFERENCES.defaultSort,
    pageSize: PAGE_SIZES.find((s) => s === value.pageSize) ?? DEFAULT_PREFERENCES.pageSize,
  }
}

function load(): Preferences {
  try {
    return sanitizePreferences(JSON.parse(window.localStorage.getItem(STORAGE_KEY) ?? 'null'))
  } catch {
    return DEFAULT_PREFERENCES // storage unavailable (private window, blocked) or unreadable
  }
}

function save(preferences: Preferences) {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(preferences))
  } catch {
    // The preference still applies until the page is closed.
  }
}

interface PreferencesState extends Preferences {
  update: (changes: Partial<Preferences>) => void
  reset: () => void
}

export const usePreferences = create<PreferencesState>((set, get) => ({
  ...load(),
  update: (changes) => {
    const next = sanitizePreferences({ ...get(), ...changes })
    set(next)
    save(next)
  },
  reset: () => {
    set(DEFAULT_PREFERENCES)
    save(DEFAULT_PREFERENCES)
  },
}))
