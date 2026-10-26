/** Status colours come from the design tokens in index.css; every pair meets WCAG AA contrast. */
export type Tone = 'neutral' | 'primary' | 'success' | 'warning' | 'info' | 'danger'

export const TONE_CLASSES: Record<Tone, string> = {
  neutral: 'bg-neutral-soft text-neutral-foreground ring-neutral-foreground/15',
  primary: 'bg-primary-soft text-primary-soft-foreground ring-primary/20',
  success: 'bg-success-soft text-success-foreground ring-success/20',
  warning: 'bg-warning-soft text-warning-foreground ring-warning/25',
  info: 'bg-info-soft text-info-foreground ring-info/20',
  danger: 'bg-destructive-soft text-destructive-foreground ring-destructive/20',
}
