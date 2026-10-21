import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ApiKeyInput, maskApiKey } from './ApiKeyInput'

/**
 * 密钥框必须是非受控的：Android 输入法在组合态（拼音/联想）里每敲一个字都会触发 change，
 * 受控输入随即把 props 里的旧值写回 DOM，组合被打断，用户看到的就是「输入被清空」。
 * 这几条钉住「DOM 自己持有内容」这个前提，以及外部同步的三个边界。
 */
const inputOf = (container: HTMLElement): HTMLInputElement => {
  const input = container.querySelector('input')
  if (input === null) throw new Error('缺少 input')
  return input
}

describe('ApiKeyInput 密钥输入框', () => {
  it('不受控：props 里的旧值不会把键入的内容顶掉', () => {
    const onValueChange = vi.fn()
    const { container, rerender } = render(<ApiKeyInput value="" onValueChange={onValueChange} />)
    const input = inputOf(container)
    input.focus()

    for (const value of ['s', 'sk', 'sk-a']) fireEvent.change(input, { target: { value } })
    expect(input.value).toBe('sk-a')
    expect(onValueChange).toHaveBeenLastCalledWith('sk-a')

    // 受控写法在重渲染时会把 DOM 写回 props 里的空串；非受控输入必须留着用户打的字符。
    rerender(<ApiKeyInput value="" onValueChange={onValueChange} />)
    expect(input.value).toBe('sk-a')
  })

  it('用户正在输入时，外部传来的空值不会把输入框清空', () => {
    const { container, rerender } = render(<ApiKeyInput value="saved-key" onValueChange={() => {}} />)
    const input = inputOf(container)
    input.focus()
    fireEvent.change(input, { target: { value: 'typing-new' } })

    // 保存流程结束会把 value 同步成空串：此刻焦点还在框里，不能抹掉正在输入的内容。
    rerender(<ApiKeyInput value="" onValueChange={() => {}} />)
    expect(input.value).toBe('typing-new')
  })

  it('没有焦点时，外部清空与外部改值都会同步到输入框', () => {
    const { container, rerender } = render(<ApiKeyInput value="saved-key" onValueChange={() => {}} />)
    const input = inputOf(container)
    expect(input.value).toBe('saved-key')

    rerender(<ApiKeyInput value="other-key" onValueChange={() => {}} />)
    expect(input.value).toBe('other-key')

    // 保存成功后应用把密钥框清空：非受控输入必须跟着清，否则界面上还留着已落盘的密钥。
    rerender(<ApiKeyInput value="" onValueChange={() => {}} />)
    expect(input.value).toBe('')
  })
})

/**
 * 掩码是「我确实输进去了」的唯一可视证据：密钥框是 password，框里只会是圆点，
 * 所以掩码必须另起一行显示，并且只露头尾、不露中间。
 */
describe('maskApiKey 密钥掩码', () => {
  it('长密钥露头 5 位与尾 4 位', () => {
    expect(maskApiKey('sk-5e1234567890abcdef7777')).toBe('sk-5e****7777')
  })

  it('中等长度缩短头尾，短密钥连头都不露', () => {
    expect(maskApiKey('sk-1234567')).toBe('sk-****67')
    expect(maskApiKey('short')).toBe('****rt')
  })

  it('空值不产生掩码', () => {
    expect(maskApiKey('')).toBe('')
  })
})

describe('ApiKeyInput 掩码提示', () => {
  it('有输入时在框下面显示掩码，空值时什么都不显示', () => {
    // 故意用低熵的占位串：这类假凭据不该被密钥扫描器当成真值（预置钩子会拦提交）。
    const { rerender } = render(<ApiKeyInput value="unit-test-key-0000000000" onValueChange={() => {}} />)
    expect(screen.getByText('已输入 unit-****0000')).toBeInTheDocument()

    rerender(<ApiKeyInput value="" onValueChange={() => {}} />)
    expect(screen.queryByText(/已输入/)).not.toBeInTheDocument()
  })
})
