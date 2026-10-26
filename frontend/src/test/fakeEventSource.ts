/**
 * A controllable stand-in for the browser's EventSource. Tests create it via `vi.stubGlobal('EventSource', FakeEventSource)`
 * and drive the stream with `emit`, `open`, `failTemporarily` and `failPermanently`.
 */
export class FakeEventSource {
  static instances: FakeEventSource[] = []

  static reset() {
    FakeEventSource.instances = []
  }

  static get last(): FakeEventSource {
    return FakeEventSource.instances[FakeEventSource.instances.length - 1]
  }

  readonly url: string
  /** 0 = connecting, 1 = open, 2 = closed (same numbers as the real thing). */
  readyState = 0
  closeCalls = 0
  private listeners = new Map<string, ((event: Event) => void)[]>()

  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }

  addEventListener(name: string, listener: (event: Event) => void) {
    this.listeners.set(name, [...(this.listeners.get(name) ?? []), listener])
  }

  close() {
    this.readyState = 2
    this.closeCalls++
  }

  private dispatch(name: string, event: Event) {
    if (this.readyState === 2 && name !== 'error') return
    for (const listener of this.listeners.get(name) ?? []) listener(event)
  }

  open() {
    this.readyState = 1
    this.dispatch('open', new Event('open'))
  }

  emit(name: string, data: unknown) {
    if (this.readyState === 0) this.readyState = 1
    this.dispatch(name, new MessageEvent(name, { data: JSON.stringify(data) }))
  }

  /** Network blip: the browser keeps the source alive and retries. */
  failTemporarily() {
    this.readyState = 0
    this.dispatch('error', new Event('error'))
  }

  /** The server refused or ended the stream: no automatic retry. */
  failPermanently() {
    this.readyState = 2
    this.dispatch('error', new Event('error'))
  }
}
