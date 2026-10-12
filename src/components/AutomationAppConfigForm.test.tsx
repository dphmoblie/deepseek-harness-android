import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { AutomationAppConfigForm } from './AutomationAppConfigForm'
import type {
  AccessibilityAutomationAppConfig,
  AccessibilityAutomationAppConfigDetail,
} from '../platform/types'

/**
 * 「每应用参数」表单。
 *
 * 这里盯的是最容易把真机数据改坏的两处：读不到读数时会不会拿一份默认值顶上去
 * （点一次保存就是用界面编的数字覆盖盘上的真数据），以及保存时会不会只发改动的字段
 * （原生是整条替换，没提交的字段会被写回默认值）。
 */

const PACKAGE_NAME = 'com.example.reader'

/**
 * 一份「四个字段都不是原生默认值」的读数。
 *
 * 刻意全部取非默认值：任何字段被漏发、或被写成原生默认值，断言都会当场露馅。
 * `paused` 也取非默认的 `true`，这样暂停开关抽风时同样看得见。
 */
const READ_BACK: AccessibilityAutomationAppConfigDetail = {
  packageName: PACKAGE_NAME,
  allowed: true,
  corrupt: null,
  config: {
    paused: true,
    defaultMaxActions: 7,
    defaultActionCoolDownMs: 45000,
    defaultMatchDelayMs: 1500,
  },
}

function renderForm(options: {
  detail?: AccessibilityAutomationAppConfigDetail | null
  status?: 'idle' | 'loading' | 'failed'
  busy?: boolean
  locked?: boolean
  error?: string | null
} = {}) {
  const saved: AccessibilityAutomationAppConfig[] = []
  const onSave = vi.fn((config: AccessibilityAutomationAppConfig): void => {
    saved.push(config)
  })
  const onReload = vi.fn()
  render(
    <AutomationAppConfigForm
      // `undefined` 与 `null` 在这里是两件事：前者表示「用默认那份读数」。
      detail={options.detail === undefined ? READ_BACK : options.detail}
      packageName={PACKAGE_NAME}
      status={options.status ?? 'idle'}
      busy={options.busy ?? false}
      locked={options.locked ?? false}
      error={options.error ?? null}
      onSave={onSave}
      onReload={onReload}
    />,
  )
  return { saved, onSave, onReload }
}

function save(): void {
  fireEvent.click(screen.getByTestId('automation-app-config-save'))
}

describe('AutomationAppConfigForm', () => {
  it(`正常读数把四个字段原样预填进表单`, () => {
    renderForm()

    expect(screen.getByLabelText('暂停这个应用的自动化')).toBeChecked()
    expect(screen.getByLabelText('默认动作上限')).toHaveValue(7)
    expect(screen.getByLabelText('默认冷却时间（毫秒）')).toHaveValue(45000)
    expect(screen.getByLabelText('默认匹配延迟（毫秒）')).toHaveValue(1500)
    // 输入框的上下限取自平台层常量，界面不另立数字。
    expect(screen.getByLabelText('默认动作上限')).toHaveAttribute('min', '1')
    expect(screen.getByLabelText('默认动作上限')).toHaveAttribute('max', '99')
    expect(screen.getByLabelText('默认冷却时间（毫秒）')).toHaveAttribute('max', '600000')
    expect(screen.getByLabelText('默认匹配延迟（毫秒）')).toHaveAttribute('max', '5000')
    // 「当前生效」那行只报读数，不报草稿。
    expect(screen.getByTestId('automation-app-config-effective')).toHaveTextContent('该应用已暂停')
  })

  it(`越界或非整数在本地就被挡下，连桥都到不了`, () => {
    const { onSave } = renderForm()

    fireEvent.change(screen.getByLabelText('默认动作上限'), { target: { value: '100' } })
    save()
    expect(onSave).not.toHaveBeenCalled()
    expect(screen.getByTestId('automation-app-config-local-error'))
      .toHaveTextContent('默认动作上限必须在 1 到 99 之间')

    fireEvent.change(screen.getByLabelText('默认动作上限'), { target: { value: '1.5' } })
    save()
    expect(onSave).not.toHaveBeenCalled()
    expect(screen.getByTestId('automation-app-config-local-error'))
      .toHaveTextContent('默认动作上限必须填写 1 到 99 之间的整数')

    // 空框不是 0：清空之后提交必须被挡下，而不是当成「填了 0」写进盘里。
    fireEvent.change(screen.getByLabelText('默认动作上限'), { target: { value: '7' } })
    fireEvent.change(screen.getByLabelText('默认匹配延迟（毫秒）'), { target: { value: '' } })
    save()
    expect(onSave).not.toHaveBeenCalled()
    expect(screen.getByTestId('automation-app-config-local-error'))
      .toHaveTextContent('默认匹配延迟必须填写 0 到 5000 之间的整数')

    fireEvent.change(screen.getByLabelText('默认匹配延迟（毫秒）'), { target: { value: '60000' } })
    save()
    expect(onSave).not.toHaveBeenCalled()
    expect(screen.getByTestId('automation-app-config-local-error'))
      .toHaveTextContent('默认匹配延迟必须在 0 到 5000 之间')

    // 预检失败不清草稿：改回一个合法值就该能直接存下去。
    fireEvent.change(screen.getByLabelText('默认匹配延迟（毫秒）'), { target: { value: '0' } })
    save()
    expect(onSave).toHaveBeenCalledTimes(1)
  })

  it(`损坏的读数把原因和受控码一并说出来，开关按「已暂停」显示`, () => {
    renderForm({
      detail: {
        packageName: PACKAGE_NAME,
        allowed: true,
        // 原生在读数损坏时把 `paused` 按 `true` 返回（fail closed），坏数据本身不删。
        corrupt: { code: 'config_unreadable', message: '参数文件解析失败' },
        config: { paused: true, defaultMaxActions: 1, defaultActionCoolDownMs: 3000, defaultMatchDelayMs: 0 },
      },
    })

    const alert = screen.getByTestId('automation-app-config-corrupt')
    expect(alert).toHaveTextContent('参数文件解析失败')
    expect(alert).toHaveTextContent('config_unreadable')
    expect(alert).toHaveTextContent('保存一次就会覆盖掉它')
    // 输入框照常显示（值取自这份读数），禁用不是这里的答案——用户得能自己覆盖掉坏数据。
    expect(screen.getByLabelText('暂停这个应用的自动化')).toBeChecked()
    expect(screen.getByTestId('automation-app-config-save')).toBeEnabled()
  })

  it(`不在白名单时字段仍可编辑，保存被禁用`, () => {
    renderForm({ detail: { ...READ_BACK, allowed: false } })

    expect(screen.getByTestId('automation-app-config-not-allowed'))
      .toHaveTextContent('com.example.reader 不在白名单里：保存参数会被原生拒绝。')
    expect(screen.getByLabelText('默认动作上限')).toBeEnabled()
    expect(screen.getByTestId('automation-app-config-save')).toBeDisabled()
  })

  it(`「恢复默认值」只把表单填成原生默认值，不写盘`, () => {
    const { onSave } = renderForm()

    fireEvent.click(screen.getByTestId('automation-app-config-restore'))

    expect(onSave).not.toHaveBeenCalled()
    expect(screen.getByLabelText('暂停这个应用的自动化')).not.toBeChecked()
    expect(screen.getByLabelText('默认动作上限')).toHaveValue(1)
    expect(screen.getByLabelText('默认冷却时间（毫秒）')).toHaveValue(3000)
    expect(screen.getByLabelText('默认匹配延迟（毫秒）')).toHaveValue(0)
    // 表单变了、盘上没变：这行读数仍来自原来的读数。
    expect(screen.getByTestId('automation-app-config-effective')).toHaveTextContent('默认动作上限 7')

    // 要生效必须由用户再点一次保存，且提交的是这份恢复后的值。
    save()
    expect(onSave).toHaveBeenCalledWith({
      paused: false,
      defaultMaxActions: 1,
      defaultActionCoolDownMs: 3000,
      defaultMatchDelayMs: 0,
    })
  })

  it(`保存时整条四个字段一起提交，未改动的字段按读数原样带走`, () => {
    const { saved } = renderForm()

    // 只改一个字段：另外三个必须原样提交（原生是整条替换，漏发就等于把它们写回默认值）。
    fireEvent.change(screen.getByLabelText('默认动作上限'), { target: { value: '9' } })
    save()

    expect(saved).toHaveLength(1)
    expect(saved[0]).toStrictEqual({
      paused: true,
      defaultMaxActions: 9,
      defaultActionCoolDownMs: 45000,
      defaultMatchDelayMs: 1500,
    })
  })

  it(`拨一下暂停开关也会把另外三个数字一起带上`, () => {
    const { saved } = renderForm()

    fireEvent.click(screen.getByLabelText('暂停这个应用的自动化'))
    save()

    expect(saved[0]).toStrictEqual({
      paused: false,
      defaultMaxActions: 7,
      defaultActionCoolDownMs: 45000,
      defaultMatchDelayMs: 1500,
    })
  })

  it(`读数还没回来时一个输入框都不渲染，更不会拿默认值顶上`, () => {
    renderForm({ detail: null, status: 'loading' })

    expect(screen.getByTestId('automation-app-config-loading')).toHaveTextContent('正在读取这个应用的参数')
    expect(screen.queryByTestId('automation-app-config-save')).toBeNull()
    expect(screen.queryByLabelText('默认动作上限')).toBeNull()
  })

  it(`读取失败只给原因和重试，一个输入框都没有`, () => {
    const { onSave, onReload } = renderForm({ detail: null, status: 'failed', error: '读参数超时了' })

    expect(screen.getByTestId('automation-app-config-read-failed')).toHaveTextContent('参数读取失败，请稍后重试')
    expect(screen.getByTestId('automation-app-config-read-error')).toHaveTextContent('读参数超时了')
    expect(screen.queryByTestId('automation-app-config-save')).toBeNull()
    expect(screen.queryByTestId('automation-app-config-restore')).toBeNull()
    expect(screen.queryByLabelText('默认动作上限')).toBeNull()
    expect(screen.queryByRole('switch')).toBeNull()

    fireEvent.click(screen.getByTestId('automation-app-config-retry'))
    expect(onReload).toHaveBeenCalledTimes(1)
    expect(onSave).not.toHaveBeenCalled()
  })

  it(`写入在途或规则那块锁着时保存与恢复都禁用`, () => {
    const { rerender } = render(
      <AutomationAppConfigForm
        packageName={PACKAGE_NAME} detail={READ_BACK} status="idle"
        busy locked={false} error={null} onSave={vi.fn()} onReload={vi.fn()}
      />,
    )
    expect(screen.getByTestId('automation-app-config-save')).toBeDisabled()
    expect(screen.getByTestId('automation-app-config-restore')).toBeDisabled()

    rerender(
      <AutomationAppConfigForm
        packageName={PACKAGE_NAME} detail={READ_BACK} status="idle"
        busy={false} locked error={null} onSave={vi.fn()} onReload={vi.fn()}
      />,
    )
    expect(screen.getByTestId('automation-app-config-save')).toBeDisabled()
    expect(screen.getByTestId('automation-app-config-restore')).toBeDisabled()
  })

  it(`桥回来的失败原因原样显示，且草稿不清空`, () => {
    renderForm({ error: '原生拒绝：defaultMaxActions 超出范围' })

    expect(screen.getByTestId('automation-app-config-write-error'))
      .toHaveTextContent('原生拒绝：defaultMaxActions 超出范围')
    // 草稿还在（用户改一处就能重试），不是被打回重建。
    expect(screen.getByLabelText('默认动作上限')).toHaveValue(7)
  })
})
