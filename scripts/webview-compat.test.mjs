import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  BASELINES,
  SYNTAX_FEATURES,
  WEB_API_FEATURES,
  coverageOf,
  countUsage,
  gapsFor,
  polyfillCovers,
  readPolyfillScript,
  resolveBaseline,
  scanUsage,
  syntaxFloor,
  unsupportedAt,
} from './webview-compat-contract.mjs'
import { downlevelCode, downlevelUntilStable } from './downlevel-harness-frontend.mjs'

const appRoot = resolve(import.meta.dirname, '..')
const harnessScript = readPolyfillScript(
  readFileSync(
    resolve(appRoot, 'android/app/src/main/java/io/deepseekharness/mobile/HarnessPolyfill.kt'),
    'utf8',
  ),
)
const shellSource = readFileSync(resolve(appRoot, 'src/legacyGlobals.ts'), 'utf8')

/** 每条特性一份「最小使用样例」，用来验证 usage 正则确实能命中真实调用形态。 */
const SAMPLES = {
  'array-at': 'const last = list.at(-1)',
  'object-hasown': "Object.hasOwn(options, 'verbose')",
  'string-replaceall': "text.replaceAll('a', 'b')",
  'promise-allsettled': 'Promise.allSettled(tasks)',
  'promise-any': 'Promise.any(tasks)',
  'aggregate-error': 'throw new AggregateError(errors, "boom")',
  'array-findlast': 'list.findLast(item => item.ok)',
  'array-copy-methods': 'groups.toSorted(compare)',
  'promise-withresolvers': 'const { promise, resolve } = Promise.withResolvers()',
  'url-canparse': "URL.canParse(input, base)",
  'url-parse': 'URL.parse(address)',
  'abortsignal-any': 'AbortSignal.any([first, second])',
  'abortsignal-timeout': 'AbortSignal.timeout(5000)',
  'abortsignal-reason': 'signal.throwIfAborted()',
  'structured-clone': 'const copy = structuredClone(state)',
  weakref: 'const ref = new WeakRef(target)',
  globalthis: 'globalThis.fetch(url)',
  'queue-microtask': 'queueMicrotask(flush)',
  'resize-observer': 'new ResizeObserver(onResize)',
  'intersection-observer': 'new IntersectionObserver(onIntersect)',
  'blob-text': 'await blob.text()',
  'element-replacechildren': 'element.replaceChildren(...nodes)',
  'object-fromentries': 'Object.fromEntries(entries)',
  'array-flat': 'list.flatMap(step)',
  'string-matchall': 'text.matchAll(pattern)',
  'crypto-randomuuid': 'const id = crypto.randomUUID()',
  'navigator-clipboard': 'await navigator.clipboard.writeText(text)',
  'file-system-access': 'await showDirectoryPicker()',
  'intl-segmenter': 'new Intl.Segmenter(locale)',
  'string-wellformed': 'text.toWellFormed()',
  'object-groupby': 'Object.groupBy(list, key)',
  'view-transition': 'document.startViewTransition(update)',
  popover: 'element.showPopover()',
}

test('特性表里的每一条都有一个能命中的最小样例', () => {
  assert.equal(Object.keys(SAMPLES).length, WEB_API_FEATURES.length, '样例数与特性数不一致：新增条目要补样例')
  for (const feature of WEB_API_FEATURES) {
    const sample = SAMPLES[feature.id]
    assert.equal(typeof sample, 'string', `${feature.id} 缺少样例`)
    assert.ok(countUsage(feature, sample) > 0, `${feature.id} 的 usage 正则命中不了样例：${sample}`)
  }
})

test('usage 正则不会被产物里的同形片段骗到（SVG id / hex 颜色 / 正则字面量）', () => {
  const noise = [
    '<path fill="url(#__DSH_CODE_ICON_INSTANCE__a)" d="M63.895 94.303c27.433 37.3" />',
    'oO = { title: "npm", hex: "FC5531", guidelines: "https://brand.netflix.com/en/assets/logos" }',
    'var pattern = /^\\s*(?:(?:[-+]?\\d+M)|(?:[-+]?\\d+(?:[.,]\\d+)?S))??$/',
  ].join('\n')
  for (const feature of WEB_API_FEATURES) {
    assert.equal(countUsage(feature, noise), 0, `${feature.id} 把噪声当成使用`)
  }
})

test('syntaxFloor 认得出 class static block，也不把 url(#id) 当私有成员', () => {
  const floor = syntaxFloor('class A { static { this.x = 1 } }')
  assert.equal(floor.floor, 94)
  assert.ok(floor.hits.some(hit => hit.feature.id === 'class-static-block'))
  assert.equal(syntaxFloor('<path fill="url(#a)"/><stop color="#fff"/>').floor, 0)
})

test('resolveBaseline 支持别名、数字，未知名称直接报错', () => {
  assert.equal(resolveBaseline('android10-stock'), BASELINES['android10-stock'])
  assert.equal(resolveBaseline('94'), 94)
  assert.equal(resolveBaseline(undefined), BASELINES['android10-stock'])
  assert.throws(() => resolveBaseline('nope'), /未知基线/u)
})

test('scanUsage 只统计命中项，unsupportedAt 按基线切分', () => {
  const hits = scanUsage('Object.hasOwn(a, "b"); groups.toSorted(fn); list.at(0)')
  assert.equal(hits.get('object-hasown'), 1)
  assert.equal(hits.get('array-copy-methods'), 1)
  assert.equal(hits.get('array-at'), 1)
  assert.equal(hits.get('weakref'), undefined)
  assert.ok(unsupportedAt(74).every(feature => feature.need > 74))
  assert.equal(unsupportedAt(BASELINES.modern).length, 0)
})

test('覆盖来源分开记：注入脚本、外壳垫片、或没有', () => {
  const hasOwn = WEB_API_FEATURES.find(feature => feature.id === 'object-hasown')
  const weakref = WEB_API_FEATURES.find(feature => feature.id === 'weakref')
  assert.equal(coverageOf(hasOwn, { harness: harnessScript }), 'harness')
  assert.equal(coverageOf(hasOwn, { shell: shellSource }), 'shell')
  assert.equal(coverageOf(hasOwn, {}), null)
  // 补不了的条目永远不算覆盖，哪怕文本里恰好出现了同名字样。
  assert.equal(coverageOf(weakref, { harness: 'WeakRef', shell: 'WeakRef' }), null)
  assert.equal(polyfillCovers(hasOwn, harnessScript), true)
  assert.equal(polyfillCovers(hasOwn, ''), false)
})

test('android10 基线上，凡是要补的条目都至少有一个守卫（防止加了条目忘了补）', () => {
  const missing = WEB_API_FEATURES.filter(
    feature =>
      feature.polyfillable !== false &&
      feature.need > BASELINES['android10-stock'] &&
      typeof feature.guard !== 'string' &&
      typeof feature.shellGuard !== 'string',
  ).map(feature => feature.id)
  assert.deepEqual(missing, [], '这些条目声明可补，但两个 realm 都没有守卫')
  const unexplained = WEB_API_FEATURES.filter(
    feature => feature.polyfillable === false && (feature.guard != null || typeof feature.reason !== 'string'),
  ).map(feature => feature.id)
  assert.deepEqual(unexplained, [], '标了补不了就必须写 reason，且不能有守卫')
})

test('gapsFor 按基线排序，并把三种覆盖状态区分开', () => {
  const hits = scanUsage('Object.hasOwn(a, "b"); groups.toSorted(fn); new WeakRef(target)')
  const gaps = gapsFor({ hits, baseline: 74, harness: harnessScript, shell: shellSource })
  assert.deepEqual(
    gaps.map(gap => [gap.feature.id, gap.covered]),
    [
      ['array-copy-methods', 'harness'],
      ['object-hasown', 'harness'],
      ['weakref', null],
    ],
  )
  assert.equal(gapsFor({ hits, baseline: 200, harness: harnessScript }).length, 0)
})

test('语法特征表的 need 单调可解释（每条都有 id/说明/正则）', () => {
  for (const feature of SYNTAX_FEATURES) {
    assert.equal(typeof feature.id, 'string')
    assert.ok(feature.syntax.length > 0)
    assert.ok(feature.need >= 40 && feature.need <= 140)
    assert.ok(countUsage(feature, 'var a = 1;') === 0, `${feature.id} 在普通代码上误报`)
  }
})

test('降级脚本能把可选链/空值合并/逻辑赋值/static block 都降掉，并收敛到不动点', async () => {
  const source = `
    const value = config?.items ?? []
    let flag = false
    flag ||= value.length > 0
    class Registry {
      #entries = new Map()
      static { Registry.shared = new Registry() }
      has(key) { return this.#entries.has(key) }
    }
    export { value, flag, Registry }
  `
  const lowered = await downlevelUntilStable(source, 'sample.js', 'chrome74')
  assert.doesNotMatch(lowered.code, /\?\.[A-Za-z_$(\[]/u)
  assert.doesNotMatch(lowered.code, /\?\?=|\|\|=/u)
  assert.doesNotMatch(lowered.code, /static\s*\{/u)
  assert.doesNotMatch(lowered.code, /\bthis\.#[A-Za-z_$]/u)
  // 单趟不一定够（私有成员降级出的替身本身还会被下一趟改写），判据是收敛到的那个不动点。
  const again = await downlevelCode(lowered.code, 'sample.js', 'chrome74', 'esm')
  assert.equal(again.code, lowered.code, '降级必须收敛（判定"降级到位"就靠这一点）')
})
