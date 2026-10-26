import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// jsdom does not implement native modal dialog behavior; expose its open state to accessibility queries.
HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }

// Browser APIs React Flow needs that jsdom lacks (see https://reactflow.dev/learn/advanced-use/testing).
class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver = ResizeObserverMock as unknown as typeof ResizeObserver

class DOMMatrixReadOnlyMock {
  m22: number
  constructor(transform?: string) {
    const scale = transform?.match(/scale\(([1-9.]+)\)/)?.[1]
    this.m22 = scale !== undefined ? Number(scale) : 1
  }
}
globalThis.DOMMatrixReadOnly = DOMMatrixReadOnlyMock as unknown as typeof DOMMatrixReadOnly

Object.defineProperties(HTMLElement.prototype, {
  offsetHeight: { get: () => 600, configurable: true },
  offsetWidth: { get: () => 800, configurable: true },
})
;(SVGElement.prototype as unknown as { getBBox: () => DOMRect }).getBBox = () =>
  ({ x: 0, y: 0, width: 0, height: 0 }) as DOMRect

afterEach(() => {
  cleanup()
})
