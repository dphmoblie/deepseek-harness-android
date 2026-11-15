import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const css = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8')

/** 取某个选择器第一条规则里的 z-index；没有就返回 NaN，避免把缺失当成 0。 */
function zIndex(selector: string): number {
  const block = css.match(new RegExp(`${selector}\\s*\\{([^}]*)\\}`))?.[1] ?? ''
  const value = block.match(/z-index:\s*(\d+)/)?.[1]
  return value === undefined ? Number.NaN : Number(value)
}

describe('提示条与对话框层级', () => {
  it('toast 必须压在对话框遮罩之上', () => {
    // 更新运行时的确认对话框是 z-index 200 的全屏遮罩；安装失败/超时的 toast 若在它下面，
    // 用户只能看到「确认更新」没反应，看不到真正的失败原因。
    expect(zIndex('\\.toast')).toBeGreaterThan(zIndex('\\.dialog-backdrop'))
  })
})
