import { describe, expect, it } from 'vitest'
import {
  allSettledFallback,
  hasOwnFallback,
  installLegacyGlobals,
  randomUuidFallback,
  replaceChildrenFallback,
} from './legacyGlobals'

/**
 * 外壳的旧内核垫片直接测实现，而不是删掉全局来假装旧内核。
 *
 * 理由：删全局会影响同文件后面所有用例（`Object.hasOwn` 是全进程共享的），而「删掉再造」
 * 也测不到真正要保的东西——**语义**。这里测的是语义，`installLegacyGlobals` 只测"已有实现
 * 时不动它"这一条，因为在新内核上它本来就什么都不该做。
 */
describe('legacyGlobals', () => {
  it('hasOwnFallback 只看自有属性，null 输入抛 TypeError', () => {
    const source = { own: 1 }
    Object.defineProperty(source, 'hidden', { value: 2, enumerable: false })
    const inherited = Object.create({ inherited: 1 }) as Record<string, number>
    inherited.own = 2
    const nullPrototype = Object.create(null) as Record<string, number>
    nullPrototype.key = 3

    expect(hasOwnFallback(source, 'own')).toBe(true)
    expect(hasOwnFallback(source, 'hidden')).toBe(true)
    expect(hasOwnFallback(inherited, 'inherited')).toBe(false)
    expect(hasOwnFallback(inherited, 'own')).toBe(true)
    expect(hasOwnFallback(nullPrototype, 'key')).toBe(true)
    expect(hasOwnFallback([1, 2], '0')).toBe(true)
    expect(hasOwnFallback([1, 2], 'length')).toBe(true)
    expect(() => hasOwnFallback(null as unknown as object, 'x')).toThrow(TypeError)
  })

  it('allSettledFallback 按输入顺序收集，且不因拒绝整体失败', async () => {
    const results = await allSettledFallback([
      Promise.resolve('ok'),
      Promise.reject(new Error('boom')),
      'literal',
    ])
    expect(results).toHaveLength(3)
    expect(results[0]).toEqual({ status: 'fulfilled', value: 'ok' })
    const rejected = results[1]
    expect(rejected?.status).toBe('rejected')
    if (rejected?.status === 'rejected') expect((rejected.reason as Error).message).toBe('boom')
    expect(results[2]).toEqual({ status: 'fulfilled', value: 'literal' })
    await expect(allSettledFallback([])).resolves.toEqual([])
  })

  it('randomUuidFallback 产出 v4 UUID 且不重复', () => {
    const first = randomUuidFallback()
    const second = randomUuidFallback()
    expect(first).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u)
    expect(first).not.toBe(second)
  })

  it('replaceChildrenFallback 先清空再按顺序插入，字符串落成文本节点', () => {
    const element = document.createElement('div')
    element.appendChild(document.createElement('span'))
    const node = document.createElement('b')
    replaceChildrenFallback.call(element, 'text', node)
    expect(element.childNodes).toHaveLength(2)
    expect(element.firstChild?.nodeType).toBe(Node.TEXT_NODE)
    expect(element.firstChild?.textContent).toBe('text')
    expect(element.lastChild).toBe(node)
  })

  it('installLegacyGlobals 在新内核上是空操作，不改写已有实现', () => {
    // 用属性描述符比较：直接引用 `Object.hasOwn` 会被 lint 判为 unbound method。
    const descriptorOf = (target: object, key: string) => Object.getOwnPropertyDescriptor(target, key)
    const before = {
      hasOwn: descriptorOf(Object, 'hasOwn'),
      allSettled: descriptorOf(Promise, 'allSettled'),
      replaceChildren: descriptorOf(Element.prototype, 'replaceChildren'),
    }
    installLegacyGlobals()
    expect(descriptorOf(Object, 'hasOwn')).toEqual(before.hasOwn)
    expect(descriptorOf(Promise, 'allSettled')).toEqual(before.allSettled)
    expect(descriptorOf(Element.prototype, 'replaceChildren')).toEqual(before.replaceChildren)
  })
})
