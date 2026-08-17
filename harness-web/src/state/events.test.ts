import { describe, expect, it, vi } from 'vitest'
import type { ServerRequest } from '../api/types'
import { TransportError } from '../api/wire'
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

  it('连接失败时向界面发布脱敏后的具体原因', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    const openStream = vi.fn((): AsyncGenerator<ServerRequest> => (
      async function* (): AsyncGenerator<ServerRequest> {
        await Promise.resolve()
        yield* []
        throw new TransportError('无法建立 Harness 事件流连接')
      }
    )())
    const bus = new EventBus(openStream, () => 'http://127.0.0.1:3080')
    const frames: unknown[] = []
    bus.subscribe((event) => {
      frames.push(event.frame)
      bus.stop()
    })

    bus.start()

    await vi.waitFor(() => expect(frames).toHaveLength(1))
    expect(frames[0]).toEqual({
      type: 'stream/error',
      error: {
        code: 'TRANSPORT_ERROR',
        message: '无法建立 Harness 事件流连接',
        details: {},
      },
    })
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('无法建立 Harness 事件流连接'))
    warn.mockRestore()
  })
})
