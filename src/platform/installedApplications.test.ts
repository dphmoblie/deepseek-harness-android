import { describe, expect, it } from 'vitest'
import { validateInstalledApplicationsPermission } from './installedApplications'

describe('「读取应用列表」权限结果', () => {
  it('两个布尔字段原样返回', () => {
    expect(validateInstalledApplicationsPermission({ supported: true, granted: false }))
      .toEqual({ supported: true, granted: false })
    expect(validateInstalledApplicationsPermission({ supported: false, granted: true }))
      .toEqual({ supported: false, granted: true })
  })

  it('缺字段或类型不对就拒收，不补默认值', () => {
    // 补默认值会把「旧原生没回这个字段」读成已授予或不存在，界面就会在该引导时不引导。
    expect(() => validateInstalledApplicationsPermission({ supported: true })).toThrow('应用列表权限结果')
    expect(() => validateInstalledApplicationsPermission({ granted: true })).toThrow('应用列表权限结果')
    expect(() => validateInstalledApplicationsPermission({ supported: 'yes', granted: true })).toThrow('应用列表权限结果')
    expect(() => validateInstalledApplicationsPermission(null)).toThrow('应用列表权限结果')
  })
})
