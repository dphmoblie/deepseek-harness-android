import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createContext, runInContext } from 'node:vm'

const appRoot = resolve(import.meta.dirname, '..')
const kotlinPath = resolve(
  appRoot,
  'android/app/src/main/java/io/deepseekharness/mobile/HarnessPolyfill.kt',
)

/**
 * 注入脚本是 Kotlin 里的一整个原始字符串，编译期不检查它，JVM 单测也只能做字符串断言。
 * 这里把真正的脚本文本取出来在 `node:vm` 里执行，用两个「内核」跑同一份代码：
 * 一个是缺 API 的旧 WebView，一个是已经全有的新 WebView。
 */
function readPolyfillScript() {
  const source = readFileSync(kotlinPath, 'utf8')
  const marker = 'const val SCRIPT: String = """'
  const start = source.indexOf(marker)
  assert.ok(start > 0, 'HarnessPolyfill.kt 里找不到 SCRIPT 原始字符串声明')
  const bodyStart = start + marker.length
  const bodyEnd = source.indexOf('"""', bodyStart)
  assert.ok(bodyEnd > bodyStart, 'SCRIPT 原始字符串没有闭合')
  return source.slice(bodyStart, bodyEnd)
}

const script = readPolyfillScript()

/**
 * 把 vm realm 里的对象抄进宿主 realm。
 *
 * 跨 realm 的数组/对象原型不是同一个对象，`assert.deepEqual`（strict 版）会因此判不等，
 * 报错信息还长得像"值一样但不等"。这里显式按值复制，比较的才是内容。
 */
function toHost(value) {
  if (Array.isArray(value)) return Array.from(value)
  if (value !== null && typeof value === 'object') return Object.fromEntries(Object.entries(value))
  return value
}

/**
 * 造一个 ColorOS 13 那类旧内核的 AbortSignal：
 * - 有 EventTarget 语义（`addEventListener` 可用），所以不会走「补 EventTarget」那条分支；
 * - 没有 `reason`、没有 `throwIfAborted`、没有 `AbortSignal.any`；
 * - `abort(reason)` 的入参被丢掉（旧实现直接 `return`）；
 * - 没有 `DOMException` 与 `Event` 构造器，兜底路径必须自己造错误对象。
 */
function legacyRealm() {
  const context = createContext({})
  runInContext(
    `
    function LegacySignal() { this.entries = []; this.aborted_ = false; }
    Object.defineProperty(LegacySignal.prototype, 'aborted', {
      configurable: true,
      get: function () { return this.aborted_; },
    });
    LegacySignal.prototype.addEventListener = function (type, listener) {
      if (type === 'abort' && typeof listener === 'function') this.entries.push(listener);
    };
    LegacySignal.prototype.removeEventListener = function (type, listener) {
      var index = this.entries.indexOf(listener);
      if (index >= 0) this.entries.splice(index, 1);
    };
    LegacySignal.prototype.dispatchEvent = function (event) {
      var listeners = this.entries.slice();
      for (var index = 0; index < listeners.length; index += 1) {
        listeners[index].call(this, event);
      }
      return true;
    };
    function LegacyController() { this.signal = new LegacySignal(); }
    LegacyController.prototype.abort = function () {
      if (this.signal.aborted_) return;
      this.signal.aborted_ = true;
      this.signal.dispatchEvent({ type: 'abort', target: this.signal });
    };
    AbortSignal = LegacySignal;
    AbortController = LegacyController;
    `,
    context,
  )
  return context
}

test('注入脚本是 ES5 语法，重复执行不报错也不改写已有函数', () => {
  assert.doesNotMatch(script, /=>/u)
  assert.doesNotMatch(script, /\bconst\s/u)
  assert.doesNotMatch(script, /\blet\s/u)
  const context = createContext({})
  runInContext(script, context)
  const toSorted = runInContext('Array.prototype.toSorted', context)
  runInContext(script, context)
  assert.equal(runInContext('Array.prototype.toSorted', context), toSorted)
})

test('旧内核缺 throwIfAborted/reason 时，调用点不再抛 is not a function', () => {
  const context = legacyRealm()
  runInContext(script, context)
  const observed = runInContext(
    `
    var controller = new AbortController();
    var signal = controller.signal;
    signal.throwIfAborted();
    var beforeAbort = signal.reason;
    controller.abort('boom');
    var thrown;
    try { signal.throwIfAborted(); } catch (error) { thrown = error; }
    ({
      beforeAbort: beforeAbort,
      aborted: signal.aborted,
      reason: signal.reason,
      thrown: thrown,
      typeofThrow: typeof signal.throwIfAborted
    });
    `,
    context,
  )
  assert.equal(observed.typeofThrow, 'function')
  assert.equal(observed.beforeAbort, undefined, '未中止时 reason 必须是 undefined')
  assert.equal(observed.aborted, true)
  assert.equal(observed.reason, 'boom', 'abort(reason) 的入参要留下来')
  assert.equal(observed.thrown, 'boom', 'throwIfAborted 抛的必须是那个 reason')
})

test('旧内核 abort() 不带原因时抛 AbortError，且不依赖 DOMException 存在', () => {
  const context = legacyRealm()
  runInContext(script, context)
  const observed = runInContext(
    `
    var controller = new AbortController();
    controller.abort();
    var thrown;
    try { controller.signal.throwIfAborted(); } catch (error) { thrown = error; }
    ({ name: thrown && thrown.name, message: thrown && thrown.message, reason: controller.signal.reason });
    `,
    context,
  )
  assert.equal(observed.name, 'AbortError')
  assert.match(observed.message ?? '', /aborted without reason/u)
  assert.equal(observed.reason?.name, 'AbortError', 'reason 与 throwIfAborted 抛的必须是同一个错误')
})

test('AbortSignal.any 在旧内核上把真正生效的中止原因传给合成信号', () => {
  const context = legacyRealm()
  runInContext(script, context)
  const observed = runInContext(
    `
    var first = new AbortController();
    var second = new AbortController();
    var fused = AbortSignal.any([first.signal, second.signal]);
    second.abort('second-reason');
    var thrown;
    try { fused.throwIfAborted(); } catch (error) { thrown = error; }
    ({ aborted: fused.aborted, reason: fused.reason, thrown: thrown });
    `,
    context,
  )
  assert.equal(observed.aborted, true)
  assert.equal(observed.reason, 'second-reason')
  assert.equal(observed.thrown, 'second-reason')
})

test('AbortSignal.any 已存在时也要补 throwIfAborted（补丁必须排在早退之前）', () => {
  // 脚本在末尾对「已经有 AbortSignal.any」的 WebView 会 return；补丁排在它后面就永远不生效。
  const context = legacyRealm()
  runInContext('AbortSignal.any = function () { return new AbortController().signal; };', context)
  runInContext(script, context)
  const observed = runInContext(
    `
    var controller = new AbortController();
    controller.abort('kept');
    var thrown;
    try { controller.signal.throwIfAborted(); } catch (error) { thrown = error; }
    ({ typeofThrow: typeof controller.signal.throwIfAborted, thrown: thrown });
    `,
    context,
  )
  assert.equal(observed.typeofThrow, 'function')
  assert.equal(observed.thrown, 'kept')
})

test('已经全有的新内核不被覆盖：原型方法保持原对象', () => {
  const context = createContext({ AbortSignal, AbortController, DOMException, Event })
  const before = {
    throwIfAborted: runInContext('AbortSignal.prototype.throwIfAborted', context),
    any: runInContext('AbortSignal.any', context),
    abort: runInContext('AbortController.prototype.abort', context),
    reasonGetter: runInContext(
      "Object.getOwnPropertyDescriptor(AbortSignal.prototype, 'reason').get",
      context,
    ),
  }
  runInContext(script, context)
  assert.equal(runInContext('AbortSignal.prototype.throwIfAborted', context), before.throwIfAborted)
  assert.equal(runInContext('AbortSignal.any', context), before.any)
  assert.equal(runInContext('AbortController.prototype.abort', context), before.abort)
  assert.equal(
    runInContext("Object.getOwnPropertyDescriptor(AbortSignal.prototype, 'reason').get", context),
    before.reasonGetter,
  )
})

/**
 * Android 10 出厂 WebView（Chromium 74）那一档：删掉 74 之后才有的 API。
 *
 * 直接 `delete` 比手写一整套假实现可靠——V8 里这些都是 configurable 的属性，删掉之后
 * 剩下的行为与旧内核在"缺这个函数"这一点上等价，而其余语义仍是真实的。
 */
function chrome74Realm() {
  const context = createContext({ AbortSignal, AbortController, DOMException, Event, setTimeout })
  runInContext(
    `
    delete Object.hasOwn;
    delete Array.prototype.at;
    delete String.prototype.at;
    delete String.prototype.replaceAll;
    delete Promise.allSettled;
    delete Array.prototype.findLast;
    delete Array.prototype.findLastIndex;
    delete AbortSignal.timeout;
    `,
    context,
  )
  return context
}

test('chromium 74 缺少的 API 都被补上，且不改写已有实现', () => {
  const context = chrome74Realm()
  assert.equal(runInContext('typeof Object.hasOwn', context), 'undefined')
  assert.equal(runInContext('typeof Array.prototype.at', context), 'undefined')
  runInContext(script, context)
  for (const expression of [
    'Object.hasOwn',
    'Array.prototype.at',
    'String.prototype.at',
    'String.prototype.replaceAll',
    'Promise.allSettled',
    'Array.prototype.findLast',
    'Array.prototype.findLastIndex',
    'AbortSignal.timeout',
  ]) {
    assert.equal(runInContext(`typeof ${expression}`, context), 'function', `${expression} 没补上`)
  }
})

test('at() 按规范换算负下标、NaN 与越界', () => {
  const context = chrome74Realm()
  runInContext(script, context)
  const observed = runInContext(
    `
    var list = ['a', 'b', 'c'];
    ({
      last: list.at(-1),
      first: list.at(0),
      beyond: list.at(3),
      negativeBeyond: list.at(-4),
      nan: list.at(NaN),
      stringLast: 'abc'.at(-1),
      stringBeyond: 'abc'.at(5)
    });
    `,
    context,
  )
  assert.equal(observed.last, 'c')
  assert.equal(observed.first, 'a')
  assert.equal(observed.beyond, undefined)
  assert.equal(observed.negativeBeyond, undefined)
  assert.equal(observed.nan, 'a')
  assert.equal(observed.stringLast, 'c')
  assert.equal(observed.stringBeyond, undefined)
})

test('Object.hasOwn 不区分自有可枚举性，也不把 null 当空对象放过', () => {
  const context = chrome74Realm()
  runInContext(script, context)
  const observed = runInContext(
    `
    var source = { own: 1 };
    Object.defineProperty(source, 'hidden', { value: 2, enumerable: false });
    var inherited = Object.create({ inherited: 1 });
    inherited.own = 2;
    var nullPrototype = Object.create(null);
    nullPrototype.key = 3;
    var failure;
    try { Object.hasOwn(null, 'x'); } catch (error) { failure = error && error.name; }
    ({
      own: Object.hasOwn(source, 'own'),
      hidden: Object.hasOwn(source, 'hidden'),
      inherited: Object.hasOwn(inherited, 'inherited'),
      ownInherited: Object.hasOwn(inherited, 'own'),
      nullPrototype: Object.hasOwn(nullPrototype, 'key'),
      failure: failure
    });
    `,
    context,
  )
  assert.deepEqual(toHost(observed), {
    own: true,
    hidden: true,
    inherited: false,
    ownInherited: true,
    nullPrototype: true,
    failure: 'TypeError',
  })
})

test('replaceAll 保留 $& / $$ 替换语义，并要求正则带 g', () => {
  const context = chrome74Realm()
  runInContext(script, context)
  const observed = runInContext(
    `
    var failure;
    try { 'aaa'.replaceAll(/a/, 'b'); } catch (error) { failure = error && error.name; }
    ({
      plain: 'a.b.c'.replaceAll('.', '-'),
      dollar: 'ab'.replaceAll('b', '[$&]'),
      money: 'ab'.replaceAll('b', '$$'),
      regexp: 'a1a2'.replaceAll(/a/g, 'x'),
      empty: 'ab'.replaceAll('', '-'),
      failure: failure
    });
    `,
    context,
  )
  assert.equal(observed.plain, 'a-b-c')
  assert.equal(observed.dollar, 'a[b]', '$& 必须替换成匹配到的文本')
  assert.equal(observed.money, 'a$', '$$ 必须变成一个字面 $')
  assert.equal(observed.regexp, 'x1x2')
  assert.equal(observed.empty, '-a-b-')
  assert.equal(observed.failure, 'TypeError')
})

test('Promise.allSettled 按输入顺序收集，且永不因拒绝而整体失败', async () => {
  const context = chrome74Realm()
  runInContext(script, context)
  const observed = await runInContext(
    `
    Promise.allSettled([
      Promise.resolve('ok'),
      Promise.reject(new Error('boom')),
      'literal'
    ]).then(function (results) {
      return results.map(function (entry) {
        return entry.status + ':' + (entry.status === 'fulfilled' ? entry.value : entry.reason.message);
      });
    });
    `,
    context,
  )
  assert.deepEqual(toHost(observed), ['fulfilled:ok', 'rejected:boom', 'fulfilled:literal'])
  const empty = await runInContext('Promise.allSettled([])', context)
  assert.deepEqual(toHost(empty), [])
})

test('findLast/findLastIndex 从尾部找，找不到时返回 undefined 与 -1', () => {
  const context = chrome74Realm()
  runInContext(script, context)
  const observed = runInContext(
    `
    var list = [1, 2, 3, 4];
    ({
      value: list.findLast(function (item) { return item % 2 === 1; }),
      index: list.findLastIndex(function (item) { return item % 2 === 1; }),
      missingValue: list.findLast(function (item) { return item > 9; }),
      missingIndex: list.findLastIndex(function (item) { return item > 9; })
    });
    `,
    context,
  )
  assert.deepEqual(toHost(observed), { value: 3, index: 2, missingValue: undefined, missingIndex: -1 })
})

test('AbortSignal.timeout 到点后用 TimeoutError 中止，未到点不中止', async () => {
  const context = chrome74Realm()
  runInContext(script, context)
  const pending = runInContext(
    `
    var signal = AbortSignal.timeout(200);
    ({ aborted: signal.aborted, typeofTimeout: typeof AbortSignal.timeout });
    `,
    context,
  )
  assert.equal(pending.aborted, false)
  assert.equal(pending.typeofTimeout, 'function')
  const observed = await runInContext(
    `
    var signal = AbortSignal.timeout(0);
    new Promise(function (resolve) {
      if (signal.aborted) { resolve(signal.reason && signal.reason.name); return; }
      signal.addEventListener('abort', function () { resolve(signal.reason && signal.reason.name); });
    });
    `,
    context,
  )
  assert.equal(observed, 'TimeoutError')
})

test('Element.prototype.replaceChildren 先清空再按顺序插入，字符串变文本节点', () => {
  const context = createContext({})
  runInContext(
    `
    function FakeElement() {
      this.children = [];
      this.ownerDocument = { createTextNode: function (text) { return { text: text, nodeType: 3 }; } };
    }
    Object.defineProperty(FakeElement.prototype, 'firstChild', {
      configurable: true,
      get: function () { return this.children.length > 0 ? this.children[0] : null; },
    });
    FakeElement.prototype.removeChild = function (child) {
      var index = this.children.indexOf(child);
      if (index >= 0) { this.children.splice(index, 1); }
      return child;
    };
    FakeElement.prototype.appendChild = function (child) { this.children.push(child); return child; };
    Element = FakeElement;
    `,
    context,
  )
  assert.equal(runInContext('typeof Element.prototype.replaceChildren', context), 'undefined')
  runInContext(script, context)
  const observed = runInContext(
    `
    var element = new Element();
    element.appendChild({ text: 'old', nodeType: 1 });
    var node = { text: 'node', nodeType: 1 };
    element.replaceChildren('text', node);
    element.children.map(function (child) { return child.text; });
    `,
    context,
  )
  assert.deepEqual(toHost(observed), ['text', 'node'])
})

test('Blob.prototype.text/arrayBuffer 用 FileReader 兜底，不依赖原生实现', async () => {
  const context = createContext({})
  runInContext(
    `
    function FakeBlob(text) { this.__text = text; }
    function FakeFileReader() {}
    FakeFileReader.prototype.readAsText = function (blob) {
      this.result = blob.__text;
      this.onload();
    };
    FakeFileReader.prototype.readAsArrayBuffer = function (blob) {
      this.result = 'buffer:' + blob.__text;
      this.onload();
    };
    Blob = FakeBlob;
    FileReader = FakeFileReader;
    `,
    context,
  )
  runInContext(script, context)
  assert.equal(runInContext('typeof Blob.prototype.text', context), 'function')
  assert.equal(runInContext('typeof Blob.prototype.arrayBuffer', context), 'function')
  assert.equal(await runInContext('new Blob("hello").text()', context), 'hello')
  assert.equal(await runInContext('new Blob("hello").arrayBuffer()', context), 'buffer:hello')
})
