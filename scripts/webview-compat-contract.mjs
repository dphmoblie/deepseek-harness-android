/**
 * 旧 WebView 兼容契约：Harness 上游前端用到的浏览器 API × 最低内核版本。
 *
 * 为什么需要单独一张表：Harness 前端（官方 dist + 运行时里的客户端插件产物）跑在**系统
 * WebView** 里，它用哪套 API 由上游决定、随 dsh 版本漂移；而我们的补丁是一段手写的 ES5
 * 注入脚本（`HarnessPolyfill.SCRIPT`）。两边没有任何编译期联系——上游某次升级开始调用
 * `structuredClone`，构建照样绿，只有用户点开某个面板时才会看到
 * "structuredClone is not a function"。这张表就是把那条隐形依赖写成可扫描、可门禁的数据。
 *
 * 三类条目，处理方式完全不同：
 * 1. **API 层**（本表主体）：缺了就抛 `X is not a function`。能用 polyfill 补，补丁放在
 *    `HarnessPolyfill.SCRIPT`，`guard` 字段是补丁里必须出现的守卫表达式；扫描器据此判断
 *    「用了但没补」。
 * 2. **语法层**（`SYNTAX_FEATURES`）：可选链、`??=`、class static block 之类，旧内核是
 *    **解析期** SyntaxError，整包一行都执行不到，polyfill 无从下手。只能在打包时把产物
 *    降级（`scripts/downlevel-harness-frontend.mjs`），或明确不支持。
 * 3. **语义敏感层**（`polyfillable: false` 且 `reason` 非空）：`WeakRef`、`structuredClone`
 *    这类补不齐语义的（弱引用/结构化克隆的深拷贝语义写不对就是静默数据损坏），只能由人
 *    评估后决定降级还是改成可选路径。扫描器把它们单独列出，不算「已覆盖」。
 *
 * 版本号口径：最低 **Chromium 主版本号**（Android WebView 与 Chrome 同源）。数值来自
 * MDN/caniuse 的兼容表与 Chrome/V8 发布说明，改动时请带上出处。
 */

/**
 * 基线：把「哪个 Android 版本 / 哪台机器」翻译成内核主版本号。
 *
 * - `android10-stock`：Android 10 出厂镜像自带的 WebView 是 Chromium 74
 *   （平台 external/chromium-webview 的分支版本）。能上 Google Play 的机器可以一直更新，
 *   国内无 GMS 的机器常常停在出厂版本——这就是「从安卓10开始」要面对的最坏情况。
 * - `upstream-syntax`：当前官方前端产物的**语法**下限（见 SYNTAX_FEATURES 里命中的最高项）。
 *   它不是一个"支持的机型"，而是"再低就连解析都过不去"的硬边界。
 * - `coloros-13`：实测机型口径（ColorOS 13 自带 WebView：有可选链，但没有 `toSorted`）。
 */
export const BASELINES = Object.freeze({
  'android10-stock': 74,
  'upstream-syntax': 94,
  'coloros-13': 100,
  'honor-browser-116': 116,
  modern: 131,
})

/**
 * 数值基线的可读别名；`--baseline 74` 与 `--baseline android10-stock` 等价。
 */
export function resolveBaseline(value) {
  if (typeof value === 'number') return value
  const text = String(value ?? '').trim()
  if (text === '') return BASELINES['android10-stock']
  if (/^\d+$/u.test(text)) return Number(text)
  const named = BASELINES[text]
  if (named === undefined) {
    throw new Error(
      `未知基线 ${text}；可用：${Object.keys(BASELINES).join(' / ')} 或一个 Chromium 主版本号`,
    )
  }
  return named
}

/**
 * API 层特性表。
 *
 * - `usage`：命中「调用点」的正则源码。全部按**调用形态**匹配（带 `(` 或全名），避免把
 *   字符串、注释、CSS 里的同名片段算成使用。
 * - `guard`：`HarnessPolyfill.SCRIPT`（注入 Harness 页面）里必须出现的守卫表达式；
 * - `shellGuard`：`src/legacyGlobals.ts`（应用外壳）里必须出现的守卫表达式；
 * - `reason`：不能补的原因；有值的条目（`polyfillable: false`）在扫描报告里单列，且
 *   **永远不算已覆盖**。
 *
 * 两个 realm 分开记是有意的：注入脚本装不到 Capacitor 的 WebView 上，外壳的垫片也进不了
 * Harness 页面。合并成一个布尔值会让人误以为"哪儿都补了"。
 */
export const WEB_API_FEATURES = Object.freeze([
  {
    id: 'array-at',
    api: 'Array/String.prototype.at()',
    need: 92,
    usage: String.raw`\.at\s*\(`,
    guard: "Object.defineProperty(Array.prototype, 'at'",
  },
  {
    id: 'object-hasown',
    api: 'Object.hasOwn()',
    need: 93,
    usage: String.raw`Object\.hasOwn\s*\(`,
    guard: "Object.defineProperty(Object, 'hasOwn'",
    shellGuard: "Object.defineProperty(Object, 'hasOwn'",
  },
  {
    id: 'string-replaceall',
    api: 'String.prototype.replaceAll()',
    need: 85,
    usage: String.raw`\.replaceAll\s*\(`,
    guard: "Object.defineProperty(String.prototype, 'replaceAll'",
  },
  {
    id: 'promise-allsettled',
    api: 'Promise.allSettled()',
    need: 76,
    usage: String.raw`Promise\.allSettled\s*\(`,
    guard: 'Promise.allSettled = function',
    shellGuard: "Object.defineProperty(Promise, 'allSettled'",
  },
  {
    id: 'promise-any',
    api: 'Promise.any()',
    need: 85,
    usage: String.raw`Promise\.any\s*\(`,
    guard: null,
    polyfillable: false,
    reason: '`Promise.any` 的拒绝路径必须抛 AggregateError；ES5 里伪造错误聚合会掩盖真实原因',
  },
  {
    id: 'aggregate-error',
    api: 'AggregateError',
    need: 85,
    usage: String.raw`\bAggregateError\b`,
    guard: null,
    polyfillable: false,
    reason: '与 Promise.any 同源：`instanceof AggregateError` 与 `errors` 数组语义写不对就是静默错判',
  },
  {
    id: 'array-findlast',
    api: 'Array.prototype.findLast/findLastIndex()',
    need: 97,
    usage: String.raw`\.findLast(?:Index)?\s*\(`,
    guard: "Object.defineProperty(Array.prototype, 'findLast'",
  },
  {
    id: 'array-copy-methods',
    api: 'Array.prototype.toSorted/toReversed/toSpliced/with()',
    need: 110,
    // `.with(` 在压缩代码里无法与普通方法调用区分，故只按三个不易混淆的形态匹配。
    usage: String.raw`\.(?:toSorted|toReversed|toSpliced)\s*\(`,
    guard: "Object.defineProperty(Array.prototype, 'toSorted'",
  },
  {
    id: 'promise-withresolvers',
    api: 'Promise.withResolvers()',
    need: 119,
    usage: String.raw`\.withResolvers\s*\(`,
    guard: "Object.defineProperty(Promise, 'withResolvers'",
  },
  {
    id: 'url-canparse',
    api: 'URL.canParse()',
    need: 120,
    usage: String.raw`\.canParse\s*\(`,
    guard: 'URL.canParse = function',
  },
  {
    id: 'url-parse',
    api: 'URL.parse()',
    need: 126,
    usage: String.raw`URL\.parse\s*\(`,
    guard: null,
    polyfillable: false,
    reason: '与 canParse 不同，它返回 URL 实例或 null；在旧内核上补会改变 `instanceof URL` 语义',
  },
  {
    id: 'abortsignal-any',
    api: 'AbortSignal.any()',
    need: 116,
    usage: String.raw`AbortSignal\.any\s*\(`,
    guard: 'AbortSignal.any = function',
  },
  {
    id: 'abortsignal-timeout',
    api: 'AbortSignal.timeout()',
    need: 103,
    usage: String.raw`AbortSignal\.timeout\s*\(`,
    guard: 'AbortSignal.timeout = function',
  },
  {
    id: 'abortsignal-reason',
    api: 'AbortSignal.prototype.reason/throwIfAborted()',
    need: 100,
    usage: String.raw`\.throwIfAborted\s*\(`,
    guard: "Object.defineProperty(AbortSignal.prototype, 'throwIfAborted'",
  },
  {
    id: 'structured-clone',
    api: 'structuredClone()',
    need: 98,
    usage: String.raw`\bstructuredClone\s*\(`,
    guard: null,
    polyfillable: false,
    reason: '深拷贝语义（Map/Set/循环引用/Transferable）写不全就是静默数据损坏，只接受原生实现',
  },
  {
    id: 'weakref',
    api: 'WeakRef',
    need: 84,
    usage: String.raw`\bWeakRef\b`,
    guard: null,
    polyfillable: false,
    reason: '弱引用补不出来；用强引用顶替会让缓存永不释放',
  },
  {
    id: 'globalthis',
    api: 'globalThis',
    need: 71,
    usage: String.raw`\bglobalThis\b`,
    guard: null,
    polyfillable: false,
    reason: '可作为 window 别名补上，但会与页面自身的 globalThis 探测逻辑打架；74 及以上原生已有',
  },
  {
    id: 'queue-microtask',
    api: 'queueMicrotask()',
    need: 71,
    usage: String.raw`\bqueueMicrotask\s*\(`,
    guard: null,
    polyfillable: false,
    reason: '74 及以上原生已有（Chrome 71 引入），无需补；低于 71 的机型已被语法层挡在外面',
  },
  {
    id: 'resize-observer',
    api: 'ResizeObserver',
    need: 64,
    usage: String.raw`\bResizeObserver\b`,
    guard: null,
    polyfillable: false,
    reason: '观察器补不了（需要布局引擎回调），只能降级布局',
  },
  {
    id: 'intersection-observer',
    api: 'IntersectionObserver',
    need: 51,
    usage: String.raw`\bIntersectionObserver\b`,
    guard: null,
    polyfillable: false,
    reason: '同上；74 及以上原生已有',
  },
  {
    id: 'blob-text',
    api: 'Blob.prototype.text()/arrayBuffer()',
    need: 76,
    usage: String.raw`\.(?:text|arrayBuffer)\s*\(\s*\)`,
    guard: "Object.defineProperty(Blob.prototype, 'text'",
    shellGuard: null,
  },
  {
    id: 'element-replacechildren',
    api: 'Element.prototype.replaceChildren()',
    need: 86,
    usage: String.raw`\.replaceChildren\s*\(`,
    guard: 'Element.prototype.replaceChildren = function',
    shellGuard: "Object.defineProperty(elementPrototype, 'replaceChildren'",
  },
  {
    id: 'object-fromentries',
    api: 'Object.fromEntries()',
    need: 73,
    usage: String.raw`Object\.fromEntries\s*\(`,
    guard: null,
    polyfillable: false,
    reason: '74 及以上原生已有；更低版本被语法层挡住',
  },
  {
    id: 'array-flat',
    api: 'Array.prototype.flat()/flatMap()',
    need: 69,
    usage: String.raw`\.(?:flat|flatMap)\s*\(`,
    guard: null,
    polyfillable: false,
    reason: '同上（Chrome 69 引入）',
  },
  {
    id: 'string-matchall',
    api: 'String.prototype.matchAll()',
    need: 73,
    usage: String.raw`\.matchAll\s*\(`,
    guard: null,
    polyfillable: false,
    reason: '同上（Chrome 73 引入）',
  },
  {
    id: 'crypto-randomuuid',
    api: 'crypto.randomUUID()',
    need: 92,
    usage: String.raw`\.randomUUID\s*\(`,
    // 只在外壳里补：`src/legacyGlobals.ts` 的 `sessionId` 生成要用它，而 Harness 页面
    // 侧的产物目前不调（调的话也会被 --strict 抓到，因为注入脚本里没有这条守卫）。
    guard: null,
    shellGuard: "Object.defineProperty(cryptoScope, 'randomUUID'",
  },
  {
    id: 'navigator-clipboard',
    api: 'navigator.clipboard',
    need: 66,
    usage: String.raw`navigator\.clipboard\b`,
    guard: null,
    polyfillable: false,
    reason: '需要安全上下文与用户手势，替代实现只能退化成 execCommand；74 及以上原生已有',
  },
  {
    id: 'file-system-access',
    api: 'showOpenFilePicker()/showDirectoryPicker()',
    need: 86,
    usage: String.raw`\bshow(?:OpenFile|Directory)Picker\s*\(`,
    guard: null,
    polyfillable: false,
    reason: 'API 存在但旧内核没有实现，polyfill 不能凭空造出选择器；应用侧另有原生目录选择通道',
  },
  {
    id: 'intl-segmenter',
    api: 'Intl.Segmenter',
    need: 87,
    usage: String.raw`\bIntl\.Segmenter\b`,
    guard: null,
    polyfillable: false,
    reason: '分词数据在 ICU 里，JS 层补不了；缺了只能按码点切',
  },
  {
    id: 'string-wellformed',
    api: 'String.prototype.isWellFormed()/toWellFormed()',
    need: 111,
    usage: String.raw`\.(?:isWellFormed|toWellFormed)\s*\(`,
    guard: null,
    polyfillable: false,
    reason: '可用代理对扫描补，但只在确实出现时再补（当前产物未使用）',
  },
  {
    id: 'object-groupby',
    api: 'Object.groupBy()/Map.groupBy()',
    need: 117,
    usage: String.raw`(?:Object|Map)\.groupBy\s*\(`,
    guard: null,
    polyfillable: false,
    reason: '可用 reduce 补，但键的顺序与空组语义易错；出现时单独补并加语义测试',
  },
  {
    id: 'view-transition',
    api: 'document.startViewTransition()',
    need: 111,
    usage: String.raw`\.startViewTransition\s*\(`,
    guard: null,
    polyfillable: false,
    reason: '动画 API，polyfill 只会变成瞬切；属于外观降级，不阻断功能',
  },
  {
    id: 'popover',
    api: 'HTMLElement.popover/showPopover()',
    need: 114,
    usage: String.raw`\.showPopover\s*\(`,
    guard: null,
    polyfillable: false,
    reason: '顶层图层语义（light dismiss、:popover-open）补不齐，旧内核上表现为弹层不出现',
  },
])

/**
 * 语法层特性表：命中即表示**整包无法解析**，与 API 不同，不能用注入脚本救。
 *
 * `usage` 是粗口径（压缩代码里无法 100% 区分字符串/正则里的同形片段），所以报告里只用来
 * 说明「上游语法下限大约在哪」，判定"能不能跑"以 `minChrome` 的最大命中项为准，并建议用
 * `scripts/downlevel-harness-frontend.mjs` 实测。
 */
export const SYNTAX_FEATURES = Object.freeze([
  { id: 'optional-chaining', syntax: '可选链 ?.', need: 80, usage: String.raw`\?\.[A-Za-z_$\(\[]` },
  { id: 'nullish-coalescing', syntax: '空值合并 ??', need: 80, usage: String.raw`(?<![?])\?\?(?![?=])` },
  { id: 'logical-assignment', syntax: '逻辑赋值 ??= ||= &&=', need: 85, usage: String.raw`\?\?=|\|\|=|&&=` },
  { id: 'class-static-block', syntax: 'class static {} 静态块', need: 94, usage: String.raw`\bstatic\s*\{` },
  { id: 'class-private-member', syntax: 'class 私有成员 #x', need: 84, usage: String.raw`\bthis\.#[a-zA-Z_$]` },
  { id: 'numeric-separator', syntax: '数字分隔符 1_000', need: 75, usage: String.raw`(?<![\w$.])\d{1,3}(?:_\d{3})+` },
  { id: 'error-cause', syntax: 'Error cause 选项', need: 93, usage: String.raw`\{[^{}]{0,40}\bcause\s*:` },
])

const usageCache = new Map()

/** 取 `usage` 对应的全局正则（带缓存，避免每次扫描重复编译）。 */
function usagePattern(source) {
  let pattern = usageCache.get(source)
  if (pattern === undefined) {
    pattern = new RegExp(source, 'gu')
    usageCache.set(source, pattern)
  }
  pattern.lastIndex = 0
  return pattern
}

/** 统计一段代码里对某个特性的调用次数。 */
export function countUsage(feature, text) {
  const pattern = usagePattern(feature.usage)
  let count = 0
  while (pattern.exec(text) !== null) count += 1
  return count
}

/**
 * 扫描一段 JS 代码，返回 `Map<id, 命中次数>`（只包含命中的条目）。
 *
 * @param {string} text
 * @param {{ features?: readonly object[] }} [options]
 */
export function scanUsage(text, options = {}) {
  const features = options.features ?? WEB_API_FEATURES
  const hits = new Map()
  for (const feature of features) {
    const count = countUsage(feature, text)
    if (count > 0) hits.set(feature.id, count)
  }
  return hits
}

/** 基线之上原生就有、基线之下需要补的条目。 */
export function unsupportedAt(baseline, features = WEB_API_FEATURES) {
  return features.filter(feature => feature.need > baseline)
}

/**
 * 读取 `HarnessPolyfill.SCRIPT` 的脚本文本。
 *
 * 补丁是 Kotlin 里的三引号原始字符串，编译期不检查语法；扫描器只关心其中出现了哪些守卫，
 * 所以这里做的是文本抽取而不是 Kotlin 解析。
 */
export function readPolyfillScript(source) {
  const marker = 'const val SCRIPT: String = """'
  const start = source.indexOf(marker)
  if (start < 0) throw new Error('HarnessPolyfill.kt 里找不到 SCRIPT 原始字符串声明')
  const bodyStart = start + marker.length
  const bodyEnd = source.indexOf('"""', bodyStart)
  if (bodyEnd < bodyStart) throw new Error('SCRIPT 原始字符串没有闭合')
  return source.slice(bodyStart, bodyEnd)
}

/**
 * 覆盖来源：`'harness'`（注入脚本，Harness 页面）、`'shell'`（`src/legacyGlobals.ts`，应用
 * 外壳）、或 `null`（没补 / 补不了）。
 *
 * 为什么要分开记：两个 realm 的补丁互不相干——注入脚本装不到 Capacitor 的 WebView 上，
 * 外壳的垫片也进不了 Harness 页面。合并成一个布尔值会让人误以为"哪儿都补了"。
 */
export function coverageOf(feature, sources = {}) {
  if (feature.polyfillable === false) return null
  const harness = sources.harness ?? ''
  const shell = sources.shell ?? ''
  if (typeof feature.guard === 'string' && feature.guard.length > 0 && harness.includes(feature.guard)) {
    return 'harness'
  }
  if (
    typeof feature.shellGuard === 'string' &&
    feature.shellGuard.length > 0 &&
    shell.includes(feature.shellGuard)
  ) {
    return 'shell'
  }
  return null
}

/** 只关心注入脚本时的便捷判断（JVM 单测与旧调用方用）。 */
export function polyfillCovers(feature, script) {
  return coverageOf(feature, { harness: script }) === 'harness'
}

/**
 * 把一次扫描的结论整理成「缺口」列表。
 *
 * @param {{ hits: Map<string, number>, baseline: number, harness?: string, shell?: string }} input
 * @returns {{ feature: object, count: number, covered: 'harness'|'shell'|null }[]}
 */
export function gapsFor({ hits, baseline, harness = '', shell = '' }) {
  const gaps = []
  for (const feature of WEB_API_FEATURES) {
    const count = hits.get(feature.id) ?? 0
    if (count === 0) continue
    if (feature.need <= baseline) continue
    gaps.push({ feature, count, covered: coverageOf(feature, { harness, shell }) })
  }
  gaps.sort((left, right) => right.feature.need - left.feature.need || left.feature.id.localeCompare(right.feature.id))
  return gaps
}

/** 语法下限：命中的最高 `need`；没有命中时返回基线本身。 */
export function syntaxFloor(text, baseline = 0) {
  let floor = baseline
  const hits = []
  for (const feature of SYNTAX_FEATURES) {
    const count = countUsage(feature, text)
    if (count === 0) continue
    hits.push({ feature, count })
    if (feature.need > floor) floor = feature.need
  }
  hits.sort((left, right) => right.feature.need - left.feature.need)
  return { floor, hits }
}
