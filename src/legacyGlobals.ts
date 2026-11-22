/**
 * 应用外壳对旧内核的 API 补充（Android 10 出厂 WebView = Chromium 74）。
 *
 * 为什么外壳要单独补一遍：Capacitor 的 WebView 里跑的是**应用自己的** React 代码，与
 * Harness 页面是两个 realm。`HarnessPolyfill` 那段注入脚本只装在 Harness 页面上（见
 * `HarnessActivity.bindHarnessAccess`），对外壳没有任何作用。
 *
 * 两条互补的修法，缺一不可：
 * 1. **语法**降不了补不了，只能改构建目标——`vite.config.ts` 的 `build.target` 是
 *    `chrome74`（原来 `es2022`，产出的 `?.`/`??` 在 Chrome 74 上是 SyntaxError，整包白屏）；
 * 2. **API** 降不了（esbuild 不提供运行时垫片），就是本文件：`Object.hasOwn`（Chrome 93）、
 *    `Promise.allSettled`（76）、`crypto.randomUUID`（92）、`Element.replaceChildren`（86）。
 *    清单来自 `node scripts/webview-compat-scan.mjs --baseline android10-stock`，不是拍脑袋。
 *
 * 三条纪律：
 * - 只在缺失时补，已有实现一个属性都不动（老内核上才生效）；
 * - **导入即安装**，且必须在 `App` 求值之前——i18n 的 `t()` 第一条就走 `Object.hasOwn`；
 * - 语义与规范一致，`legacyGlobals.test.ts` 直接测实现，不靠「删全局」来假装旧内核。
 */

/** `Object.hasOwn` 的等价实现：只看自有属性，不管可枚举性，null/undefined 抛 TypeError。 */
export function hasOwnFallback(object: object, key: PropertyKey): boolean {
  if (object === null || object === undefined) {
    throw new TypeError('Cannot convert undefined or null to object')
  }
  return Object.prototype.hasOwnProperty.call(Object(object), key)
}

/**
 * `Promise.allSettled` 的等价实现：等最慢的那个落地，按输入顺序返回，永不整体拒绝。
 *
 * 逐个 `await` 不会把耗时变成累加——入参里的 promise 在调用前就已经开始执行了。
 */
export async function allSettledFallback<T>(
  values: Iterable<T | PromiseLike<T>>,
): Promise<PromiseSettledResult<Awaited<T>>[]> {
  const results: PromiseSettledResult<Awaited<T>>[] = []
  for (const value of Array.from(values)) {
    try {
      results.push({ status: 'fulfilled', value: await value })
    } catch (reason) {
      results.push({ status: 'rejected', reason })
    }
  }
  return results
}

/** `crypto.randomUUID` 的等价实现：v4 UUID，随机源优先 `getRandomValues`。 */
export function randomUuidFallback(): string {
  const bytes = new Uint8Array(16)
  const source = globalThis.crypto
  if (source !== undefined && typeof source.getRandomValues === 'function') {
    source.getRandomValues(bytes)
  } else {
    for (let index = 0; index < bytes.length; index += 1) bytes[index] = Math.floor(Math.random() * 256)
  }
  // 版本位 v4 与变体位（RFC 4122）。
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** `Element.prototype.replaceChildren` 的等价实现：先清空，再按顺序插入（字符串转文本节点）。 */
export function replaceChildrenFallback(this: Element, ...nodes: (Node | string)[]): void {
  const owner = this.ownerDocument
  while (this.firstChild !== null) this.removeChild(this.firstChild)
  for (const node of nodes) {
    this.appendChild(typeof node === 'string' ? owner.createTextNode(node) : node)
  }
}

/**
 * 把缺失的 API 补到全局对象上；已有的一律跳过。
 *
 * 幂等：重复调用不会重新定义任何属性（`typeof` 守卫之后才 `defineProperty`）。
 */
export function installLegacyGlobals(): void {
  if (typeof (Object as { hasOwn?: unknown }).hasOwn !== 'function') {
    Object.defineProperty(Object, 'hasOwn', {
      configurable: true,
      writable: true,
      value: hasOwnFallback,
    })
  }
  const promise = Promise as unknown as { allSettled?: unknown }
  if (typeof promise.allSettled !== 'function') {
    Object.defineProperty(Promise, 'allSettled', {
      configurable: true,
      writable: true,
      value: allSettledFallback,
    })
  }
  // 非安全上下文里 crypto 可能整个不存在；这种环境下 sessionId 走别的兜底，不在本文件处理。
  const cryptoScope = globalThis.crypto as unknown as { randomUUID?: unknown } | undefined
  if (cryptoScope !== undefined && typeof cryptoScope.randomUUID !== 'function') {
    Object.defineProperty(cryptoScope, 'randomUUID', {
      configurable: true,
      writable: true,
      value: randomUuidFallback,
    })
  }
  const elementPrototype = (globalThis as unknown as { Element?: { prototype: Record<string, unknown> } })
    .Element?.prototype
  if (elementPrototype !== undefined && typeof elementPrototype.replaceChildren !== 'function') {
    Object.defineProperty(elementPrototype, 'replaceChildren', {
      configurable: true,
      writable: true,
      value: replaceChildrenFallback,
    })
  }
}

installLegacyGlobals()
