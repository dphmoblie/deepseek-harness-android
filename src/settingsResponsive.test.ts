import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const styles = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8').replace(/\r\n?/g, '\n')

function ruleBody(selector: string): string {
  const start = styles.indexOf(`${selector} {`)
  if (start < 0) throw new Error(`src/styles.css 里找不到规则：${selector}`)
  const end = styles.indexOf('}', start)
  if (end < 0) throw new Error(`规则没有闭合：${selector}`)
  return styles.slice(start + selector.length + 2, end)
}

describe('手机端按钮布局', () => {
  it('通用动作容器使用可换行的 flex 布局', () => {
    for (const selector of ['.settings-inline-actions', '.button-row']) {
      const body = ruleBody(selector)
      expect(body).toContain('display: flex')
      expect(body).toContain('flex-wrap: wrap')
      expect(body).toContain('gap: 8px')
    }
  })

  it('窄屏按钮保持整段文字，不在按钮内部断行', () => {
    const mobileBlock = styles.slice(styles.indexOf('@media (max-width: 560px) {', styles.indexOf('.settings-categories button')))
    expect(mobileBlock).toContain('.settings-inline-actions > .button')
    expect(mobileBlock).toContain('.button-row > .button')
    expect(mobileBlock).toContain('.section-title-action > .button')
    expect(mobileBlock).toContain('flex: 1 1 max-content')
    expect(mobileBlock).toContain('min-width: max-content')
    expect(mobileBlock).toContain('white-space: nowrap')
  })
})
