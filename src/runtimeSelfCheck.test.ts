import { describe, expect, it } from 'vitest'
import {
  SELF_CHECK_CODES,
  SELF_CHECK_IDS,
  SELF_CHECK_REPAIRABLE_CODES,
  SELF_CHECK_STATUSES,
  isSelfCheckReport,
  selfCheckAdvice,
  selfCheckNeedsRepair,
  validateSelfCheckOperation,
  validateSelfCheckReport,
  type SelfCheckCode,
  type SelfCheckItem,
} from './runtimeSelfCheck'

/** 一份最小的合法 check 载荷。 */
function checkPayload(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    operation: 'check',
    availableBytes: 12_345_678,
    dshVersion: '0.1.5-rc.2',
    checks: [
      { id: 'shell', status: 'ok' },
      { id: 'pty_sandbox', status: 'fail', code: 'PTY_EXIT_EARLY' },
    ],
    ...overrides,
  }
}

describe('运行时自检载荷校验', () => {
  it('接受合法的 check 载荷，并按固定顺序返回检查项', () => {
    const report = validateSelfCheckReport(checkPayload({
      // 载荷乱序：界面仍必须按 shell → … → pty_sandbox 的固定顺序展示。
      checks: [
        { id: 'pty_sandbox', status: 'fail', code: 'PTY_EXIT_EARLY' },
        { id: 'shell', status: 'ok' },
        { id: 'rg', status: 'warn', code: 'RG_NOT_EXECUTABLE' },
      ],
    }))

    expect(report.operation).toBe('check')
    if (report.operation !== 'check') throw new Error('载荷类型判定错误')
    expect(report.availableBytes).toBe(12_345_678)
    expect(report.dshVersion).toBe('0.1.5-rc.2')
    expect(report.checks.map(item => item.id)).toEqual(['shell', 'pty_sandbox', 'rg'])
  })

  it('接受缺失版本（null）与全部四种状态', () => {
    const report = validateSelfCheckReport(checkPayload({
      dshVersion: null,
      checks: SELF_CHECK_STATUSES.map((status, index) => (
        status === 'ok'
          ? { id: SELF_CHECK_IDS[index], status }
          : { id: SELF_CHECK_IDS[index], status, code: 'PTY_TIMEOUT' }
      )),
    }))

    if (report.operation !== 'check') throw new Error('载荷类型判定错误')
    expect(report.dshVersion).toBeNull()
    expect(report.checks).toHaveLength(SELF_CHECK_STATUSES.length)
  })

  it('拒绝未知检查项标识', () => {
    expect(() => validateSelfCheckReport(checkPayload({
      checks: [{ id: 'systemd', status: 'ok' }],
    }))).toThrow('自检项标识无效')
  })

  it('拒绝未知结论码', () => {
    expect(() => validateSelfCheckReport(checkPayload({
      checks: [{ id: 'pty', status: 'fail', code: 'PTY_EXPLODED' }],
    }))).toThrow('自检项结论码无效')
  })

  it('拒绝 ok 项携带结论码', () => {
    // 「正常 + 修复建议」是自相矛盾的组合，界面无从取舍，因此整份载荷拒收。
    expect(() => validateSelfCheckReport(checkPayload({
      checks: [{ id: 'shell', status: 'ok', code: 'SHELL_MISSING' }],
    }))).toThrow('自检项状态与结论码不一致')
  })

  it('拒绝非 ok 项缺少结论码', () => {
    expect(() => validateSelfCheckReport(checkPayload({
      checks: [{ id: 'shell', status: 'fail' }],
    }))).toThrow('自检项结论码无效')
  })

  it('拒绝重复的检查项、未知状态与非法计数', () => {
    expect(() => validateSelfCheckReport(checkPayload({
      checks: [{ id: 'shell', status: 'ok' }, { id: 'shell', status: 'ok' }],
    }))).toThrow('自检项标识重复')
    expect(() => validateSelfCheckReport(checkPayload({
      checks: [{ id: 'shell', status: 'broken', code: 'SHELL_MISSING' }],
    }))).toThrow('自检项状态无效')
    expect(() => validateSelfCheckReport(checkPayload({ availableBytes: -1 }))).toThrow('自检可用空间格式无效')
    expect(() => validateSelfCheckReport(checkPayload({ availableBytes: 1.5 }))).toThrow('自检可用空间格式无效')
    expect(() => validateSelfCheckReport(checkPayload({ checks: 'shell' }))).toThrow('自检项列表格式无效')
  })

  it('拒绝会被渲染进界面的可疑版本号', () => {
    for (const dshVersion of ['', ' 0.1.5', '<img src=x>', 'v'.repeat(65), '0.1.5\n注入']) {
      expect(() => validateSelfCheckReport(checkPayload({ dshVersion }))).toThrow('dsh 版本格式无效')
    }
  })

  it('接受同一个结论码出现在多个检查项上（含 skipped）', () => {
    // 启动器不存在时，后三项没有可测的前提，必须报「跳过」而不是报 fail：
    // 因此 code 与 id 不是一一绑定，校验只查全局码集合与状态规则。
    const report = validateSelfCheckReport(checkPayload({
      checks: [
        { id: 'sandbox_launcher', status: 'fail', code: 'LAUNCHER_MISSING' },
        { id: 'sandbox_probe', status: 'skipped', code: 'LAUNCHER_MISSING' },
        { id: 'sandbox_exec', status: 'skipped', code: 'LAUNCHER_MISSING' },
        { id: 'pty_sandbox', status: 'skipped', code: 'LAUNCHER_MISSING' },
      ],
    }))

    if (report.operation !== 'check') throw new Error('载荷类型判定错误')
    expect(report.checks.map(item => item.status)).toEqual(['fail', 'skipped', 'skipped', 'skipped'])
    // 四项共用同一条结论与下一步：跳过的项不另编说法，用户看到的就是同一个原因。
    const advice = report.checks.map(item => selfCheckAdvice(item))
    expect(new Set(advice.map(item => item.meaning))).toEqual(new Set(['找不到沙箱启动器 landlock-run']))
    expect(new Set(advice.map(item => item.nextStep))).toEqual(new Set(['先点「修复运行时权限」，仍失败则重装运行时']))
  })

  it('hardlink 的合法与非法组合：ok 不带码，失败只认受控码 HARDLINK_DENIED', () => {
    // 三处契约（访客脚本 / Kotlin 白名单 / 前端）的 id 顺序逐字一致：hardlink 紧跟 attachments、在 rg 之前。
    expect(SELF_CHECK_IDS).toEqual([
      'shell', 'node', 'sandbox_launcher', 'sandbox_probe', 'sandbox_exec',
      'pty', 'pty_sandbox', 'dsh_home', 'attachments', 'hardlink', 'rg',
    ])
    expect(SELF_CHECK_CODES).toContain('HARDLINK_DENIED')

    const report = validateSelfCheckReport(checkPayload({
      checks: [
        { id: 'rg', status: 'warn', code: 'RG_MISSING' },
        { id: 'hardlink', status: 'ok' },
        { id: 'attachments', status: 'ok' },
      ],
    }))
    if (report.operation !== 'check') throw new Error('载荷类型判定错误')
    // 顺序按契约归一化，不随载荷顺序变化。
    expect(report.checks.map(item => item.id)).toEqual(['attachments', 'hardlink', 'rg'])
    expect(report.checks[1]).toEqual({ id: 'hardlink', status: 'ok' })

    const denied = validateSelfCheckReport(checkPayload({
      checks: [{ id: 'hardlink', status: 'fail', code: 'HARDLINK_DENIED' }],
    }))
    if (denied.operation !== 'check') throw new Error('载荷类型判定错误')
    expect(denied.checks).toEqual([{ id: 'hardlink', status: 'fail', code: 'HARDLINK_DENIED' }])

    // 非法组合一：ok 带码（会渲染出「正常 + 修复建议」）。
    expect(() => validateSelfCheckReport(checkPayload({
      checks: [{ id: 'hardlink', status: 'ok', code: 'HARDLINK_DENIED' }],
    }))).toThrow('自检项状态与结论码不一致')
    // 非法组合二：非 ok 不带码。
    expect(() => validateSelfCheckReport(checkPayload({
      checks: [{ id: 'hardlink', status: 'fail' }],
    }))).toThrow('自检项结论码无效')
    // 非法组合三：码不在受控集合里。注意前端只查全局码表与状态规则，
    // 「码是否属于这一项」由原生侧的白名单（RuntimeSelfCheckPolicy）负责。
    expect(() => validateSelfCheckReport(checkPayload({
      checks: [{ id: 'hardlink', status: 'fail', code: 'HARDLINK_OK' }],
    }))).toThrow('自检项结论码无效')
  })

  it('接受合法的 repair 载荷', () => {
    const report = validateSelfCheckReport({
      operation: 'repair',
      availableBytes: 12_345_678,
      repaired: 2,
      candidates: 3,
    })

    expect(report).toEqual({
      operation: 'repair',
      availableBytes: 12_345_678,
      repaired: 2,
      candidates: 3,
    })
  })

  it('拒绝自相矛盾的 repair 载荷与未知操作', () => {
    expect(() => validateSelfCheckReport({
      operation: 'repair',
      availableBytes: 0,
      repaired: 3,
      candidates: 1,
    })).toThrow('自检已修复项数超过可修复项数')
    expect(() => validateSelfCheckReport({ operation: 'reset', availableBytes: 0 })).toThrow('自检操作类型无效')
    expect(() => validateSelfCheckReport(null)).toThrow('自检结果格式无效')
  })

  it('isSelfCheckReport 只对合法载荷返回真', () => {
    expect(isSelfCheckReport(checkPayload())).toBe(true)
    expect(isSelfCheckReport({ operation: 'check', availableBytes: 0, dshVersion: null, checks: [] })).toBe(true)
    expect(isSelfCheckReport(checkPayload({ checks: [{ id: 'unknown', status: 'ok' }] }))).toBe(false)
  })

  it('只接受 check 与 repair 两种操作', () => {
    expect(validateSelfCheckOperation('check')).toBe('check')
    expect(validateSelfCheckOperation('repair')).toBe('repair')
    expect(() => validateSelfCheckOperation('install')).toThrow('自检操作类型无效')
    expect(() => validateSelfCheckOperation(undefined)).toThrow('自检操作类型无效')
  })
})

describe('运行时自检文案', () => {
  it('每个结论码都有结论与下一步', () => {
    for (const code of SELF_CHECK_CODES) {
      const advice = selfCheckAdvice({ id: 'shell', status: 'fail', code })
      expect(advice.meaning.length).toBeGreaterThan(0)
      expect(advice.nextStep.length).toBeGreaterThan(0)
    }
  })

  it('沙箱与内核相关结论不写成绝对承诺', () => {
    const forbidden = ['一定能', '保证', '绝对不会', '永久解决']
    for (const code of SELF_CHECK_CODES) {
      const { meaning, nextStep } = selfCheckAdvice({ id: 'shell', status: 'fail', code })
      for (const word of forbidden) {
        expect(`${meaning}${nextStep}`).not.toContain(word)
      }
    }
  })

  it('每个检查项都有中文标签，ok 项没有结论与下一步', () => {
    for (const id of SELF_CHECK_IDS) {
      const advice = selfCheckAdvice({ id, status: 'ok' })
      expect(advice.label.length).toBeGreaterThan(0)
      expect(advice.meaning).toBe('')
      expect(advice.nextStep).toBe('')
    }
  })

  it('沙箱内 PTY 的标签与 PTY_EXIT_EARLY 的下一步对得上', () => {
    // 「对照上一条「沙箱内 PTY」结果」这句指引必须能真的在列表里找到对应项。
    expect(selfCheckAdvice({ id: 'pty_sandbox', status: 'ok' }).label).toBe('沙箱内 PTY')
  })

  it('hardlink 项如实指向硬链接这一层，并与 en.ts 的词条逐字对应', () => {
    // 这里的原文就是 `src/locales/en.ts` 里词条的键：改动这一句必须同步改英文。
    const label = '硬链接（原子写入的前提）'
    const meaning = '本机不允许创建硬链接（同目录与跨目录都被拒绝）。dsh 的 write 工具新建文件与所有附件落盘都依赖硬链接做原子安装，因此这两条链路会失败。'
    const nextStep = '这是运行环境（PRoot/内核策略）层面的限制，应用侧无法绕过；需要写入新文件时改用 bash 重定向或终端里的 cp/mv，图片与截图类附件在当前版本上不可用。'
    expect(selfCheckAdvice({ id: 'hardlink', status: 'ok' }).label).toBe(label)
    expect(selfCheckAdvice({ id: 'hardlink', status: 'fail', code: 'HARDLINK_DENIED' })).toEqual({ label, meaning, nextStep })
  })

  it('只在权限位或缺失目录相关的码上提供修复入口', () => {
    const repairable: SelfCheckCode[] = ['LAUNCHER_NOT_EXECUTABLE', 'LAUNCHER_MISSING', 'RG_NOT_EXECUTABLE', 'RG_MISSING', 'ATTACHMENTS_MISSING']
    for (const code of repairable) {
      expect(SELF_CHECK_REPAIRABLE_CODES).toContain(code)
      expect(selfCheckNeedsRepair([{ id: 'shell', status: 'fail', code }])).toBe(true)
    }
    // 内核能力、Node/bash 缺失、PTY 层故障、硬链接被环境拒绝都修不了：给出修复按钮等于承诺做不到的事。
    const notRepairable: SelfCheckCode[] = ['PROBE_UNUSABLE', 'PROBE_PARTIAL', 'SHELL_MISSING', 'NODE_MISSING', 'PTY_EXIT_EARLY', 'HOME_NOT_WRITABLE', 'HARDLINK_DENIED']
    for (const code of notRepairable) {
      expect(SELF_CHECK_REPAIRABLE_CODES).not.toContain(code)
      expect(selfCheckNeedsRepair([{ id: 'shell', status: 'fail', code }])).toBe(false)
    }
    const okItems: SelfCheckItem[] = [{ id: 'shell', status: 'ok' }]
    expect(selfCheckNeedsRepair(okItems)).toBe(false)
  })
})
