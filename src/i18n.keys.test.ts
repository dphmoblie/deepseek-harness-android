import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { english } from './locales/en'

/**
 * 字面量 `t("…")` 的键必须都登记在 `src/locales/en.ts` 里。
 *
 * 缺键不会报错：`t()` 查不到就把中文原样返回，于是「英文界面里冒出中文」这类缺陷
 * 只能靠人眼在真机上发现（曾经漏掉过 Shizuku 授权指引的三条）。这里把键集合与调用点
 * 做一次静态交叉校验，让缺键在门禁里立刻失败。
 */
const ROOT = join(process.cwd(), 'src')
const SKIP_DIRS = new Set(['locales', 'node_modules'])

function sourceFiles(dir: string, collected: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry)
    if (statSync(path).isDirectory()) {
      if (!SKIP_DIRS.has(entry)) sourceFiles(path, collected)
      continue
    }
    if (!/\.tsx?$/.test(entry)) continue
    if (/\.test\.tsx?$/.test(entry)) continue
    if (entry.endsWith('.d.ts')) continue
    collected.push(path)
  }
  return collected
}

/** 只收 `t("…")` 与 `t('…')` 两种字面量调用；模板字符串与变量拼接无法静态判断，跳过。 */
const ESCAPES: Readonly<Record<string, string>> = {
  n: '\n',
  r: '\r',
  t: '\t',
  '\\': '\\',
  "'": "'",
  '"': '"',
  '`': '`',
}

function calledKeys(source: string): string[] {
  const keys: string[] = []
  const pattern = /(?<![\w.])t\(\s*(["'])((?:\\.|(?!\1).)*)\1\s*[,)]/g
  let match: RegExpExecArray | null
  while ((match = pattern.exec(source)) !== null) {
    // 源文件里的 `\n` 是转义序列，运行时键里是真正的换行 —— 按同一条规则还原。
    const raw = match[2].replace(/\\(.)/g, (whole, char: string) => ESCAPES[char] ?? whole)
    keys.push(raw)
  }
  return keys
}

describe('文案键与 en.ts 的一致性', () => {
  it('src/ 下所有字面量 t() 键都在 en.ts 里登记', () => {
    const files = sourceFiles(ROOT)
    expect(files.length).toBeGreaterThan(30)

    const missing = new Map<string, string[]>()
    for (const file of files) {
      for (const key of calledKeys(readFileSync(file, 'utf8'))) {
        if (key in english) continue
        const relative = file.slice(ROOT.length + 1).replace(/\\/g, '/')
        missing.set(key, [...(missing.get(key) ?? []), relative])
      }
    }

    const report = [...missing.entries()].map(([key, where]) => `${key} ← ${where.join(', ')}`)
    expect(report).toEqual([])
  })
})
