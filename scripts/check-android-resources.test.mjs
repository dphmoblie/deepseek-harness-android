import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { spawnSync } from 'node:child_process'
import { test } from 'node:test'
import assert from 'node:assert/strict'

// Android 资源检查的夹具测试：真建临时 res 目录、真跑 scripts/check-android-resources.py，
// 断言退出码与中文报错文案。既有「真实资源树通过」的正例，也有逐项坏样本，
// 所以脚本一旦退化成永远通过，这些用例会立刻失败。
const appRoot = resolve(import.meta.dirname, '..')
const checker = resolve(appRoot, 'scripts/check-android-resources.py')
const python = process.platform === 'win32' ? 'python' : 'python3'

/** 生成一份 strings.xml 内容。 */
function stringsXml(entries) {
  const body = entries.map(([name, value]) => `    <string name="${name}">${value}</string>`)
  return `<resources>\n${body.join('\n')}\n</resources>\n`
}

const DEFAULT_STRINGS = stringsXml([
  ['app_name', 'DeepSeek Harness'],
  ['keep_alive_channel_name', '保持运行'],
])

/** 生成一份引用 @string/app_name 的 AndroidManifest.xml。 */
function manifestXml(extraAttributes = '') {
  return [
    '<?xml version="1.0" encoding="utf-8"?>',
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
    `    <application android:label="@string/app_name"${extraAttributes} />`,
    '</manifest>',
    '',
  ].join('\n')
}

/**
 * 建一个临时工程夹具。
 * translated 为 undefined 表示不建 values-en；extra 是相对 res 的附加文件。
 */
async function fixture(t, { strings, translated, extra = {}, manifest } = {}) {
  const root = await mkdtemp(join(tmpdir(), 'dsh-android-resources-'))
  t.after(() => rm(root, { recursive: true, force: true }))
  const res = join(root, 'res')
  await mkdir(join(res, 'values'), { recursive: true })
  await writeFile(join(res, 'values', 'strings.xml'), strings ?? DEFAULT_STRINGS)
  if (translated !== undefined) {
    await mkdir(join(res, 'values-en'), { recursive: true })
    await writeFile(join(res, 'values-en', 'strings.xml'), translated)
  }
  for (const [relative, content] of Object.entries(extra)) {
    const target = join(res, relative)
    await mkdir(dirname(target), { recursive: true })
    await writeFile(target, content)
  }
  await writeFile(join(root, 'AndroidManifest.xml'), manifest ?? manifestXml())
  return root
}

/** 跑检查脚本；返回 { status, stdout, stderr }。 */
function runCheck(root, { res, manifest } = {}) {
  const args = [
    '--res',
    res ?? join(root, 'res'),
    '--manifest',
    manifest ?? join(root, 'AndroidManifest.xml'),
  ]
  return spawnSync(python, [checker, ...args], {
    cwd: appRoot,
    encoding: 'utf8',
    timeout: 60_000,
    windowsHide: true,
    // Windows 上 Python 默认按本地代码页往管道写，中文会变成乱码，这里钉死 UTF-8。
    env: { ...process.env, PYTHONUTF8: '1', PYTHONIOENCODING: 'utf-8' },
  })
}

/** 断言以 1 退出并包含指定中文报错。 */
function assertReports(result, pattern, hint) {
  assert.equal(result.status, 1, `期望退出码 1（${hint}），实际 ${result.status}：${result.stdout}${result.stderr}`)
  assert.match(result.stderr, /\[资源检查\] 不通过/)
  assert.match(result.stderr, pattern, `${hint} 的报错文案不对：${result.stderr}`)
}

test('真实资源树通过检查，并打印文件数与字符串键数', () => {
  const result = spawnSync(python, [checker], {
    cwd: appRoot,
    encoding: 'utf8',
    timeout: 60_000,
    windowsHide: true,
    env: { ...process.env, PYTHONUTF8: '1', PYTHONIOENCODING: 'utf-8' },
  })
  assert.equal(result.status, 0, `${result.stdout}${result.stderr}`)
  const match = /\[资源检查\] 通过：(\d+) 个资源文件、(\d+) 个默认语言字符串，引用全部可解析/.exec(
    result.stdout,
  )
  assert.ok(match, `输出格式不对：${result.stdout}`)
  const [, files, strings] = match.map(Number)
  assert.ok(files >= 40, `资源文件数偏少（${files}），夹具/目录被改动了？`)
  assert.ok(strings >= 70, `默认语言字符串数偏少（${strings}），strings.xml 被改动了？`)
})

test('可解析的引用与齐全的翻译：通过', async t => {
  const root = await fixture(t, {
    translated: DEFAULT_STRINGS,
    extra: {
      'drawable/ic_sample.xml': '<shape xmlns:android="http://schemas.android.com/apk/res/android" />\n',
      'values/colors.xml': '<resources>\n    <color name="brand">#FF0000</color>\n</resources>\n',
    },
    manifest: manifestXml(' android:icon="@drawable/ic_sample"'),
  })
  const result = runCheck(root)
  assert.equal(result.status, 0, `${result.stdout}${result.stderr}`)
})

test('翻译缺键：点名缺失的字符串', async t => {
  const root = await fixture(t, {
    translated: stringsXml([['app_name', 'DeepSeek Harness']]),
  })
  assertReports(
    runCheck(root),
    /values-en\/strings\.xml：缺少默认语言里的字符串 keep_alive_channel_name/,
    '英文目录少了 keep_alive_channel_name',
  )
})

test('翻译多键：点名多余的字符串', async t => {
  const root = await fixture(t, {
    translated: stringsXml([
      ['app_name', 'DeepSeek Harness'],
      ['keep_alive_channel_name', 'Keep alive'],
      ['ghost_key', '这个键默认语言里没有'],
    ]),
  })
  assertReports(
    runCheck(root),
    /values-en\/strings\.xml：多出默认语言没有的字符串 ghost_key/,
    '英文目录多了 ghost_key',
  )
})

test('同一个 values 目录里重复定义资源键：报重复', async t => {
  const root = await fixture(t, {
    strings: stringsXml([
      ['app_name', 'DeepSeek Harness'],
      ['app_name', '重复的名字'],
    ]),
  })
  assertReports(runCheck(root), /资源 string\/app_name 在同一个 values 目录里重复定义/, '同名 string 出现两次')
})

test('字符串值为空：报空值', async t => {
  const root = await fixture(t, {
    strings: stringsXml([
      ['app_name', 'DeepSeek Harness'],
      ['keep_alive_channel_name', '   '],
    ]),
  })
  assertReports(runCheck(root), /字符串 keep_alive_channel_name 的值是空的/, '字符串只有空白')
})

test('引用解析不了：报出引用名与所在文件', async t => {
  const root = await fixture(t, {
    translated: DEFAULT_STRINGS,
    manifest: manifestXml(' android:icon="@drawable/ic_missing"'),
  })
  assertReports(
    runCheck(root),
    /AndroidManifest\.xml：icon="@drawable\/ic_missing" 引用的 drawable\/ic_missing 在 res 里没有定义/,
    'manifest 引用了不存在的 drawable',
  )
})

test('资源 XML 坏掉：报行号与列号', async t => {
  const root = await fixture(t, {
    strings: '<resources>\n    <string name="app_name">没闭合\n</resources>\n',
  })
  assertReports(runCheck(root), /不是合法 XML（第 \d+ 行第 \d+ 列）/, 'strings.xml 标签没闭合')
})

test('兜底：目录缺失、资源为空、字符串为零时以 2 退出', async t => {
  const missing = await fixture(t, {})
  const noRes = runCheck(missing, { res: join(missing, 'res-不存在') })
  assert.equal(noRes.status, 2)
  assert.match(noRes.stderr, /资源目录不存在/)

  const noManifest = await fixture(t, {})
  const noManifestResult = runCheck(noManifest, { manifest: join(noManifest, '不存在.xml') })
  assert.equal(noManifestResult.status, 2)
  assert.match(noManifestResult.stderr, /AndroidManifest\.xml 不存在/)

  const emptyStrings = await fixture(t, { strings: '<resources>\n</resources>\n' })
  const emptyResult = runCheck(emptyStrings)
  assert.equal(emptyResult.status, 2)
  assert.match(emptyResult.stderr, /res\/values 里一个字符串都没有/)

  const emptyRes = await fixture(t, {})
  await rm(join(emptyRes, 'res', 'values'), { recursive: true, force: true })
  const emptyResResult = runCheck(emptyRes)
  assert.equal(emptyResResult.status, 2)
  assert.match(emptyResResult.stderr, /资源目录是空的/)

  const noValues = await fixture(t, {
    extra: {
      'drawable/ic_sample.xml': '<shape xmlns:android="http://schemas.android.com/apk/res/android" />\n',
    },
  })
  await rm(join(noValues, 'res', 'values'), { recursive: true, force: true })
  const noValuesResult = runCheck(noValues)
  assert.equal(noValuesResult.status, 2)
  assert.match(noValuesResult.stderr, /没有默认语言目录 res\/values/)
})
