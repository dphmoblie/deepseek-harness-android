import { describe, expect, it, vi } from 'vitest'
import type { ServerRequest } from '../api/types'
import { EventBus } from './events'

function waitForAbort(signal: AbortSignal): Promise<void> {
  if (signal.aborted) return Promise.resolve()
  return new Promise((resolve) => signal.addEventListener('abort', () => resolve(), { once: true }))
}

describe('EventBus lifecycle', () => {
  it('stop 后 start 会为两条流建立全新的可用 signal', async () => {
    const signals: AbortSignal[] = []
    const openStream = vi.fn(
      (_baseUrl: string, _path: string, options: { signal?: AbortSignal }): AsyncGenerator<ServerRequest> => {
        const signal = options.signal
        if (signal === undefined) throw new Error('test stream requires an AbortSignal')
        signals.push(signal)
        return (async function* (): AsyncGenerator<ServerRequest> {
          await waitForAbort(signal)
          if (signal.aborted) return
          yield {} as ServerRequest
        })()
      },
    )
    const bus = new EventBus(openStream, () => 'http://127.0.0.1:3080')

    bus.start()
    await vi.waitFor(() => expect(signals).toHaveLength(2))
    const firstGeneration = [...signals]

    bus.stop()
    expect(firstGeneration.every((signal) => signal.aborted)).toBe(true)

    bus.start()
    await vi.waitFor(() => expect(signals).toHaveLength(4))
    const secondGeneration = signals.slice(2)

    expect(secondGeneration.every((signal) => !signal.aborted)).toBe(true)
    expect(secondGeneration[0]).not.toBe(firstGeneration[0])

    bus.stop()
    expect(secondGeneration.every((signal) => signal.aborted)).toBe(true)
  })
})
