import { beforeEach, expect, it, vi } from 'vitest'
import { runtimeBridge } from './native'

const nativeRuntime = vi.hoisted(() => ({
  switchRuntimeVersion: vi.fn(),
  deleteRuntimeVersion: vi.fn(),
}))

vi.mock('@capacitor/core', () => ({
  Capacitor: { isNativePlatform: () => true },
  registerPlugin: () => nativeRuntime,
}))

beforeEach(() => {
  vi.clearAllMocks()
})

it.each(['previous', 'bundled'])('把运行时切换请求 %s 交给 Android 原生桥', async target => {
  const versions = { versions: [], canSwitch: true, canDelete: true }
  nativeRuntime.switchRuntimeVersion.mockResolvedValue(versions)

  await expect(runtimeBridge.switchRuntimeVersion(target)).resolves.toEqual(versions)
  expect(nativeRuntime.switchRuntimeVersion).toHaveBeenCalledWith({ target })
})

it('拒绝删除内置槽位以及切换到未知槽位', () => {
  expect(() => runtimeBridge.deleteRuntimeVersion('bundled')).toThrow('运行时版本操作目标无效')
  expect(() => runtimeBridge.switchRuntimeVersion('current')).toThrow('运行时版本操作目标无效')
  expect(nativeRuntime.deleteRuntimeVersion).not.toHaveBeenCalled()
  expect(nativeRuntime.switchRuntimeVersion).not.toHaveBeenCalled()
})
