import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { AutomationRuleForm } from './AutomationRuleForm'
import type {
  AccessibilityAutomationRuleInput,
  AccessibilityAutomationRuleWriteMode,
} from '../platform/types'

/**
 * 规则写入表单。
 *
 * 这里盯的是「点一次保存会不会把规则改坏」：默认值能不能直接提交、切换动作类型会不会
 * 留下上一种类型的参数、折叠区里的冷门字段会不会在读回—提交之间掉队。
 */

const PACKAGE_NAME = 'com.example.reader'

/** 一份「字段全满」的读回规则：编辑模式的回归用例拿它当输入，也拿它当期望输出。 */
const READ_BACK_RULE: AccessibilityAutomationRuleInput = {
  id: 'rule-1',
  packageName: PACKAGE_NAME,
  enabled: false,
  allowActivities: ['.*ReaderActivity'],
  denyActivities: ['.*PayActivity'],
  matchDelayMs: 1500,
  maxActions: 7,
  actionCoolDownMs: 45000,
  resetOn: 'screen',
  selectors: [
    {
      text: '开始',
      textContains: '开始',
      textStartsWith: '开',
      textEndsWith: '始',
      id: ':id/target',
      desc: '目标按钮',
      descContains: '冷门描述',
      className: 'android.widget.Button',
      clickable: false,
      enabled: true,
      editable: false,
      minWidth: 320,
      minHeight: 48,
    },
    // 第二个选择器只填折叠区里的冷门字段：它是「收起不等于丢掉」的对照组。
    { descContains: '只填了冷门字段', minWidth: 120 },
  ],
  action: { type: 'swipe', durationMs: 800, direction: 'left' },
}

function renderForm(options: {
  mode: AccessibilityAutomationRuleWriteMode
  rule: AccessibilityAutomationRuleInput | null
}) {
  const submitted: AccessibilityAutomationRuleInput[] = []
  const onSubmit = vi.fn((rule: AccessibilityAutomationRuleInput): void => {
    submitted.push(rule)
  })
  const onCancel = vi.fn()
  render(
    <AutomationRuleForm
      packageName={PACKAGE_NAME}
      rule={options.rule}
      mode={options.mode}
      busy={false}
      error={null}
      onSubmit={onSubmit}
      onCancel={onCancel}
    />,
  )
  return { submitted, onSubmit, onCancel }
}

/** 新建时用户至少得填的东西：标识，以及第一个选择器的一个条件。 */
function fillCreateBasics(id = 'demo-rule'): void {
  fireEvent.change(screen.getByLabelText('规则标识'), { target: { value: id } })
  fireEvent.change(screen.getByLabelText('选择器 1 · 文本'), { target: { value: '开始' } })
}

function saveRule(): void {
  fireEvent.click(screen.getByRole('button', { name: '保存规则' }))
}

function selectorTextInputCount(): number {
  return screen.getAllByLabelText(/^选择器 \d+ · 文本$/u).length
}

describe('AutomationRuleForm', () => {
  it('新建模式的默认值填完标识就能直接保存', () => {
    const { submitted } = renderForm({ mode: 'create', rule: null })
    fillCreateBasics()

    saveRule()

    expect(submitted).toHaveLength(1)
    expect(submitted[0]).toEqual({
      id: 'demo-rule',
      packageName: PACKAGE_NAME,
      enabled: true,
      allowActivities: [],
      denyActivities: [],
      matchDelayMs: 0,
      maxActions: 1,
      actionCoolDownMs: 3000,
      resetOn: 'activity',
      selectors: [{ text: '开始' }],
      action: { type: 'click' },
    })
    // 点击类动作只带类型：多带一个字段原生就整条拒收。
    expect(submitted[0].action).toStrictEqual({ type: 'click' })
  })

  it('切换动作类型会丢掉上一种类型的参数', () => {
    const { submitted } = renderForm({ mode: 'create', rule: null })
    fillCreateBasics()

    fireEvent.change(screen.getByLabelText('动作类型'), { target: { value: 'swipe' } })
    fireEvent.change(screen.getByLabelText('滑动时长（毫秒）'), { target: { value: '500' } })
    fireEvent.change(screen.getByLabelText('滑动方向'), { target: { value: 'down' } })
    saveRule()

    expect(submitted[0].action).toStrictEqual({ type: 'swipe', durationMs: 500, direction: 'down' })

    fireEvent.change(screen.getByLabelText('动作类型'), { target: { value: 'click' } })
    saveRule()

    expect(submitted[1].action).toStrictEqual({ type: 'click' })

    fireEvent.change(screen.getByLabelText('动作类型'), { target: { value: 'key' } })
    saveRule()

    // 键值是下拉的默认项 BACK=4，而不是上一种类型留下来的任何东西。
    expect(submitted[2].action).toStrictEqual({ type: 'key', keyCode: 4 })
  })

  it('选择器最多加到 16 个，到顶后按钮禁用且不再增加', () => {
    renderForm({ mode: 'create', rule: null })
    const addButton = screen.getByRole('button', { name: '添加选择器' })
    expect(selectorTextInputCount()).toBe(1)

    for (let added = 1; added < 16; added += 1) {
      fireEvent.click(addButton)
    }

    expect(selectorTextInputCount()).toBe(16)
    expect(screen.getAllByRole('button', { name: /^删除选择器 \d+$/u })).toHaveLength(16)
    expect(addButton).toBeDisabled()

    fireEvent.click(addButton)
    expect(selectorTextInputCount()).toBe(16)
  })

  it('越界的数字在提交时就被拦下，不进桥', () => {
    const { submitted } = renderForm({ mode: 'create', rule: null })
    fillCreateBasics()

    fireEvent.change(screen.getByLabelText('动作上限'), { target: { value: '0' } })
    saveRule()

    expect(submitted).toHaveLength(0)
    expect(screen.getByTestId('automation-rule-form-error')).toHaveTextContent('动作上限必须在 1 到 99 之间')

    fireEvent.change(screen.getByLabelText('动作上限'), { target: { value: '3' } })
    fireEvent.change(screen.getByLabelText('匹配延迟（毫秒）'), { target: { value: '' } })
    saveRule()

    expect(submitted).toHaveLength(0)
    expect(screen.getByTestId('automation-rule-form-error')).toHaveTextContent(
      '匹配延迟必须填写 0 到 5000 之间的整数',
    )
  })

  it('明显不合法的内容在表单里就被拦下', () => {
    const { submitted } = renderForm({ mode: 'create', rule: null })

    saveRule()
    expect(submitted).toHaveLength(0)
    expect(screen.getByTestId('automation-rule-form-error')).toHaveTextContent('规则标识不能为空')

    fillCreateBasics()
    fireEvent.change(screen.getByLabelText('动作类型'), { target: { value: 'launch' } })
    saveRule()
    expect(screen.getByTestId('automation-rule-form-error')).toHaveTextContent(
      '启动动作至少要给组件或链接其中之一',
    )

    fireEvent.change(screen.getByLabelText('启动链接'), { target: { value: 'ftp://example.com' } })
    saveRule()
    expect(screen.getByTestId('automation-rule-form-error')).toHaveTextContent('启动链接只支持')

    fireEvent.change(screen.getByLabelText('启动链接'), { target: { value: 'https://example.com/page' } })
    saveRule()

    expect(submitted).toHaveLength(1)
    expect(submitted[0].action).toEqual({ type: 'launch', uri: 'https://example.com/page' })
  })

  it('编辑模式把读回的冷门字段原样写回，一个字都不改也不会抹掉', () => {
    const { submitted } = renderForm({ mode: 'update', rule: READ_BACK_RULE })

    // 读回值确实落在界面上：标识只读，折叠区里的冷门字段也有自己的输入框。
    const idInput = screen.getByLabelText('规则标识')
    expect(idInput).toHaveValue('rule-1')
    expect(idInput).toHaveAttribute('readonly')
    expect(screen.getByLabelText('选择器 1 · 描述包含')).toHaveValue('冷门描述')
    expect(screen.getByLabelText('选择器 1 · 最小宽度')).toHaveValue(320)
    expect(screen.getByLabelText('选择器 1 · 是否可点击')).toHaveValue('false')
    expect(screen.getByLabelText('选择器 1 · 是否已启用')).toHaveValue('true')
    expect(screen.getByLabelText('选择器 1 · 文本结尾')).toHaveValue('始')
    expect(screen.getByLabelText('选择器 2 · 最小宽度')).toHaveValue(120)
    expect(screen.getByText('更多条件（已填 8 项）')).toBeInTheDocument()
    expect(screen.getByText('更多条件（已填 2 项）')).toBeInTheDocument()

    saveRule()

    expect(submitted).toHaveLength(1)
    expect(submitted[0]).toEqual(READ_BACK_RULE)
    // 第二个选择器的值全部在折叠区里：逐项再确认一次，防的是「收起来就丢了」。
    expect(submitted[0].selectors[1].descContains).toBe('只填了冷门字段')
    expect(submitted[0].selectors[1].minWidth).toBe(120)
    expect(submitted[0].selectors[1].text).toBeUndefined()
    expect(submitted[0].selectors[0].clickable).toBe(false)
    expect(submitted[0].selectors[0].minHeight).toBe(48)
  })

  it('编辑模式没有读回内容时只报原因，不渲染任何输入框', () => {
    const { submitted, onCancel } = renderForm({ mode: 'update', rule: null })

    expect(screen.getByTestId('automation-rule-form-error')).toHaveTextContent('没有读回这条规则的内容')
    expect(screen.queryByLabelText('规则标识')).toBeNull()
    expect(screen.queryByLabelText('选择器 1 · 文本')).toBeNull()
    expect(screen.queryByRole('button', { name: '保存规则' })).toBeNull()
    expect(submitted).toHaveLength(0)

    fireEvent.click(screen.getByRole('button', { name: '关闭' }))
    expect(onCancel).toHaveBeenCalledTimes(1)
  })

  it('取消按钮把控制权交回父组件', () => {
    const { onCancel } = renderForm({ mode: 'create', rule: null })

    fireEvent.click(screen.getByRole('button', { name: '取消' }))

    expect(onCancel).toHaveBeenCalledTimes(1)
  })
})
