package io.deepseekharness.mobile

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * 给系统 WebView 补上 Harness 页面用到的较新 API，只在缺失时才补。
 *
 * 为什么需要它：Harness 前端（以及它的客户端插件页面）跑在**系统 WebView** 里，而不是内置
 * Node.js 里——两者版本互不相干，把运行时的 Node 提到 24 也补不了浏览器的 API。真机上已经
 * 撞到过三次：
 * - Chrome 低于 124 的 WebView 打开「选择工作区目录」时，页面直接显示
 *   `AbortSignal.any is not a function`；
 * - ColorOS 13 自带的旧 WebView 在模型列表里显示 `groups.toSorted is not a function`
 *   （`Array.prototype.toSorted` 要 Chrome 110+）；
 * - 同一个旧 WebView 的 `AbortSignal` 也没有 `throwIfAborted`（Chrome 100）：官方客户端
 *   每次 RPC 返回后都调 `signal.throwIfAborted()`，于是目录选择显示
 *   `client api: directoryPicker/list failed: signal.throwIfAborted is not a function`。
 *
 * 注入时机分两层：
 * 1. WebView 支持 `DOCUMENT_START_SCRIPT`（AndroidX WebKit）时，在主框架任何脚本之前注入，
 *    boot 阶段就调用的 API 也能补上；
 * 2. 不支持的 provider 退回 `onPageFinished`，仍能覆盖目录选择、模型列表这类用户交互时才
 *    调用的 API。
 *
 * 边界：只补缺失项，已经是函数就原样返回；不覆盖、不代理、不读页面状态，也不接触凭据。
 * 脚本是纯 ECMAScript（ES5 语法），不依赖任何页面全局变量，可被重复执行（幂等）。
 */
internal object HarnessPolyfill {
    /**
     * 补齐旧 WebView 缺少的 API。
     *
     * 清单不是拍脑袋列的：`scripts/webview-compat-scan.mjs` 扫官方前端产物（含运行时里的
     * 客户端插件），按调用点计数出「目标基线之上、内核没有」的项，这里只补扫描器报出来的
     * 那些；补完在 `scripts/webview-polyfill.test.mjs` 里用缺 API 的假 realm 跑一遍语义。
     *
     * - `Array.prototype.toSorted / toReversed / toSpliced / with`（Chrome 110）
     * - `Promise.withResolvers`（Chrome 119）
     * - `URL.canParse`（Chrome 120）
     * - `AbortSignal.any`（Chrome 116）与旧 AbortSignal 的最小 EventTarget 语义
     * - `AbortSignal.prototype.reason`（Chrome 98）与 `throwIfAborted`（Chrome 100）
     * - Android 10 出厂 WebView（Chromium 74）还缺的一层：`Object.hasOwn`（93）、
     *   `at`（92）、`replaceAll`（85）、`Promise.allSettled`（76）、`findLast/findLastIndex`
     *   （97）、`AbortSignal.timeout`（103）、`Blob.prototype.text/arrayBuffer`（76）、
     *   `Element.prototype.replaceChildren`（86）
     */
    const val SCRIPT: String = """(function () {
  /*
   * ES2023 的数组副本方法：只复制、不原地改。旧 WebView 没有它们时，
   * 官方前端的模型列表会直接抛 "groups.toSorted is not a function"。
   * 先补在 AbortSignal 的早退之前——那段对已经有 AbortSignal.any 的 WebView 会 return。
   */
  function copyArray(source) {
    var length = source.length >>> 0;
    var copy = new Array(length);
    for (var index = 0; index < length; index += 1) { copy[index] = source[index]; }
    return copy;
  }
  if (typeof Array.prototype.toSorted !== 'function') {
    Object.defineProperty(Array.prototype, 'toSorted', {
      configurable: true,
      writable: true,
      value: function (compareFn) {
        var copy = copyArray(this);
        copy.sort(compareFn);
        return copy;
      },
    });
  }
  if (typeof Array.prototype.toReversed !== 'function') {
    Object.defineProperty(Array.prototype, 'toReversed', {
      configurable: true,
      writable: true,
      value: function () {
        var copy = copyArray(this);
        copy.reverse();
        return copy;
      },
    });
  }
  if (typeof Array.prototype.toSpliced !== 'function') {
    Object.defineProperty(Array.prototype, 'toSpliced', {
      configurable: true,
      writable: true,
      value: function () {
        var copy = copyArray(this);
        Array.prototype.splice.apply(copy, arguments);
        return copy;
      },
    });
  }
  if (typeof Array.prototype.with !== 'function') {
    Object.defineProperty(Array.prototype, 'with', {
      configurable: true,
      writable: true,
      value: function (index, value) {
        var copy = copyArray(this);
        var length = copy.length;
        var actual = Number(index);
        if (actual !== actual) { actual = 0; }
        actual = actual < 0 ? Math.ceil(actual) : Math.floor(actual);
        if (actual < 0) { actual += length; }
        if (actual < 0 || actual >= length) { throw new RangeError('Invalid index'); }
        copy[actual] = value;
        return copy;
      },
    });
  }
  if (typeof Promise !== 'undefined' && typeof Promise.withResolvers !== 'function') {
    Object.defineProperty(Promise, 'withResolvers', {
      configurable: true,
      writable: true,
      value: function () {
        var resolve;
        var reject;
        var promise = new Promise(function (resolvePromise, rejectPromise) {
          resolve = resolvePromise;
          reject = rejectPromise;
        });
        return { promise: promise, resolve: resolve, reject: reject };
      },
    });
  }
  if (typeof URL !== 'undefined' && typeof URL.canParse !== 'function') {
    URL.canParse = function (input, base) {
      try { new URL(input, base); return true; } catch (error) { return false; }
    };
  }

  /*
   * Android 10 出厂 WebView 是 Chromium 74，比上面那些还要少一整层 ES2022 的东西。
   * 这一段的每一项都是 scripts/webview-compat-scan.mjs 在官方产物里**数到调用点**之后
   * 才补的，不是"顺手补一堆"：补丁越多，越容易在某台机上改变原生语义。
   */
  if (typeof Object.hasOwn !== 'function') {
    Object.defineProperty(Object, 'hasOwn', {
      configurable: true,
      writable: true,
      value: function (object, key) {
        if (object === null || object === undefined) {
          throw new TypeError('Cannot convert undefined or null to object');
        }
        return Object.prototype.hasOwnProperty.call(Object(object), key);
      },
    });
  }
  /* at()（Chrome 92）：数组与字符串共用同一套负下标换算。 */
  function atIndex(list, index) {
    var length = list.length >>> 0;
    var relative = Number(index);
    if (relative !== relative) { relative = 0; }
    relative = relative < 0 ? Math.ceil(relative) : Math.floor(relative);
    var actual = relative >= 0 ? relative : length + relative;
    if (actual < 0 || actual >= length) { return undefined; }
    return list[actual];
  }
  if (typeof Array.prototype.at !== 'function') {
    Object.defineProperty(Array.prototype, 'at', {
      configurable: true,
      writable: true,
      value: function (index) { return atIndex(this, index); },
    });
  }
  if (typeof String.prototype.at !== 'function') {
    Object.defineProperty(String.prototype, 'at', {
      configurable: true,
      writable: true,
      value: function (index) { return atIndex(String(this), index); },
    });
  }
  /*
   * replaceAll（Chrome 85）：字符串模式转成转义后的全局正则再交给原生 replace，
   * 这样 `$&`/`$1`/`$$` 的替换语义与规范一致；用 split/join 会把这些原样留下。
   */
  if (typeof String.prototype.replaceAll !== 'function') {
    Object.defineProperty(String.prototype, 'replaceAll', {
      configurable: true,
      writable: true,
      value: function (searchValue, replaceValue) {
        if (typeof searchValue === 'string') {
          var escaped = '';
          for (var position = 0; position < searchValue.length; position += 1) {
            var character = searchValue.charAt(position);
            escaped += '\\^$.*+?()[]{}|'.indexOf(character) >= 0 ? '\\' + character : character;
          }
          return String(this).replace(new RegExp(escaped, 'g'), replaceValue);
        }
        if (searchValue !== null && typeof searchValue === 'object' &&
          typeof Symbol !== 'undefined' && typeof searchValue[Symbol.replace] === 'function') {
          if (searchValue.global === false) {
            throw new TypeError('replaceAll must be called with a global RegExp');
          }
          return String(this).replace(searchValue, replaceValue);
        }
        throw new TypeError('replaceAll: searchValue must be a string or a global RegExp');
      },
    });
  }
  if (typeof Promise !== 'undefined' && typeof Promise.allSettled !== 'function') {
    Promise.allSettled = function (iterable) {
      return new Promise(function (resolve, reject) {
        var items;
        try { items = Array.prototype.slice.call(iterable); } catch (error) { reject(error); return; }
        var results = new Array(items.length);
        var remaining = items.length;
        function settle(index, value, failed) {
          results[index] = failed ? { status: 'rejected', reason: value } : { status: 'fulfilled', value: value };
          remaining -= 1;
          if (remaining === 0) { resolve(results); }
        }
        if (remaining === 0) { resolve(results); return; }
        for (var index = 0; index < items.length; index += 1) {
          (function (position) {
            Promise.resolve(items[position]).then(
              function (value) { settle(position, value, false); },
              function (reason) { settle(position, reason, true); },
            );
          })(index);
        }
      });
    };
  }
  if (typeof Array.prototype.findLast !== 'function') {
    Object.defineProperty(Array.prototype, 'findLast', {
      configurable: true,
      writable: true,
      value: function (predicate, thisArg) {
        for (var index = (this.length >>> 0) - 1; index >= 0; index -= 1) {
          if (predicate.call(thisArg, this[index], index, this)) { return this[index]; }
        }
        return undefined;
      },
    });
  }
  if (typeof Array.prototype.findLastIndex !== 'function') {
    Object.defineProperty(Array.prototype, 'findLastIndex', {
      configurable: true,
      writable: true,
      value: function (predicate, thisArg) {
        for (var index = (this.length >>> 0) - 1; index >= 0; index -= 1) {
          if (predicate.call(thisArg, this[index], index, this)) { return index; }
        }
        return -1;
      },
    });
  }
  /*
   * 文本/二进制读取（Chrome 76）：Blob.prototype.text / arrayBuffer。只用 FileReader 实现，
   * 不碰任何页面全局；FileReader 都不存在的环境（非浏览器 realm）直接跳过。
   */
  if (typeof Blob !== 'undefined' && typeof FileReader !== 'undefined' && typeof Promise !== 'undefined') {
    var readBlobAs = function (blob, method) {
      return new Promise(function (resolve, reject) {
        var reader = new FileReader();
        reader.onload = function () { resolve(reader.result); };
        reader.onerror = function () { reject(reader.error); };
        reader[method](blob);
      });
    };
    if (typeof Blob.prototype.text !== 'function') {
      Object.defineProperty(Blob.prototype, 'text', {
        configurable: true,
        writable: true,
        value: function () { return readBlobAs(this, 'readAsText'); },
      });
    }
    if (typeof Blob.prototype.arrayBuffer !== 'function') {
      Object.defineProperty(Blob.prototype, 'arrayBuffer', {
        configurable: true,
        writable: true,
        value: function () { return readBlobAs(this, 'readAsArrayBuffer'); },
      });
    }
  }
  /* replaceChildren（Chrome 86）：清空后按参数顺序追加，字符串按文本节点插入。 */
  if (typeof Element !== 'undefined' && typeof Element.prototype.replaceChildren !== 'function') {
    Element.prototype.replaceChildren = function () {
      var owner = this.ownerDocument;
      while (this.firstChild) { this.removeChild(this.firstChild); }
      for (var index = 0; index < arguments.length; index += 1) {
        var child = arguments[index];
        this.appendChild(typeof child === 'string' ? owner.createTextNode(child) : child);
      }
    };
  }

  /*
   * AbortSignal 的取消语义：旧 WebView 在这条链路上缺三样东西，缺哪样上游就在哪样上崩。
   * - EventTarget（Chrome 66 之前）：没有 addEventListener，目录选择报
   *   "signal.addEventListener is not a function"（0.3.7 起补）；
   * - reason（Chrome 98）：中止原因恒为 undefined，取消被当成「无原因失败」；
   * - throwIfAborted（Chrome 100）：dsh 客户端每次 RPC 返回后都调它，缺了直接报
   *   "signal.throwIfAborted is not a function"——实测「选择工作区目录」弹窗显示
   *   client api: directoryPicker/list failed，只能取消。
   *
   * 三样都只在缺失时补。补 reason 得自己记住 abort(reason) 的入参，所以只有在真要补
   * reason、或要派发 abort 事件时，才包一层 AbortController.abort；新 WebView 上一个
   * 原型方法都不动。
   */
  if (typeof AbortSignal !== 'undefined') {
    var signalStates = new WeakMap();
    var stateOf = function (signal) {
      var state = signalStates.get(signal);
      if (!state) {
        state = { listeners: [], onabort: null, aborted: false, reason: undefined };
        signalStates.set(signal, state);
      }
      return state;
    };
    var abortReasonFallback = function () {
      var message = 'signal is aborted without reason';
      try { return new DOMException(message, 'AbortError'); } catch (error) { }
      var fallback = new Error(message);
      fallback.name = 'AbortError';
      return fallback;
    };
    var nativeReason = 'reason' in AbortSignal.prototype;
    var nativeEvents = typeof AbortSignal.prototype.addEventListener === 'function';
    if (!nativeEvents) {
      AbortSignal.prototype.addEventListener = function (type, listener) {
        if (type !== 'abort' || typeof listener !== 'function') { return; }
        var listeners = stateOf(this).listeners;
        if (listeners.indexOf(listener) < 0) { listeners.push(listener); }
      };
      AbortSignal.prototype.removeEventListener = function (type, listener) {
        if (type !== 'abort') { return; }
        var listeners = stateOf(this).listeners;
        var index = listeners.indexOf(listener);
        if (index >= 0) { listeners.splice(index, 1); }
      };
      AbortSignal.prototype.dispatchEvent = function (event) {
        var state = stateOf(this);
        var listeners = state.listeners.slice();
        if (typeof state.onabort === 'function') { listeners.unshift(state.onabort); }
        for (var i = 0; i < listeners.length; i += 1) {
          try { listeners[i].call(this, event); } catch (error) { }
        }
        return true;
      };
      Object.defineProperty(AbortSignal.prototype, 'onabort', {
        configurable: true,
        get: function () { return stateOf(this).onabort; },
        set: function (value) { stateOf(this).onabort = typeof value === 'function' ? value : null; },
      });
    }
    if (!nativeReason || !nativeEvents) {
      if (typeof AbortController !== 'undefined') {
        var nativeAbort = AbortController.prototype.abort;
        AbortController.prototype.abort = function (reason) {
          var signal = this.signal;
          if (signal) {
            var state = stateOf(signal);
            state.aborted = true;
            state.reason = arguments.length > 0 ? reason : abortReasonFallback();
          }
          nativeAbort.call(this, reason);
          if (!nativeEvents && signal && typeof signal.dispatchEvent === 'function' && typeof Event === 'function') {
            try { signal.dispatchEvent(new Event('abort')); } catch (error) { }
          }
        };
      }
    }
    if (!nativeReason) {
      Object.defineProperty(AbortSignal.prototype, 'reason', {
        configurable: true,
        get: function () {
          if (!this.aborted) { return undefined; }
          var state = stateOf(this);
          return state.aborted ? state.reason : abortReasonFallback();
        },
      });
    }
    var reasonOf = function (signal) {
      if (nativeReason) { return signal.reason; }
      var state = stateOf(signal);
      return state.aborted ? state.reason : abortReasonFallback();
    };
    if (typeof AbortSignal.prototype.throwIfAborted !== 'function') {
      Object.defineProperty(AbortSignal.prototype, 'throwIfAborted', {
        configurable: true,
        writable: true,
        value: function () {
          if (this.aborted) { throw reasonOf(this); }
        },
      });
    }
  }
  /*
   * timeout（Chrome 103）：dsh 的 RPC 超时用它，缺了报 "AbortSignal.timeout is not a function"。
   * 必须排在下面那条「已经有 any 就直接 return」之前，否则 newer-than-103 之外的内核永远补不上。
   */
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout !== 'function') {
    AbortSignal.timeout = function (delay) {
      var controller = new AbortController();
      var milliseconds = Number(delay);
      if (milliseconds !== milliseconds || milliseconds < 0) { milliseconds = 0; }
      setTimeout(function () {
        var reason;
        try { reason = new DOMException('signal timed out', 'TimeoutError'); } catch (error) { }
        if (reason === undefined) {
          reason = new Error('signal timed out');
          reason.name = 'TimeoutError';
        }
        controller.abort(reason);
      }, milliseconds);
      return controller.signal;
    };
  }
  if (typeof AbortSignal === 'undefined' || typeof AbortSignal.any === 'function') return;
  AbortSignal.any = function (signals) {
    var controller = new AbortController();
    var list = [];
    for (var index = 0; index < signals.length; index += 1) {
      var signal = signals[index];
      if (signal === null || typeof signal !== 'object' || typeof signal.aborted !== 'boolean' ||
        typeof signal.addEventListener !== 'function') {
        throw new TypeError('AbortSignal.any: member is not an AbortSignal');
      }
      list.push(signal);
    }
    for (var early = 0; early < list.length; early += 1) {
      if (list[early].aborted) {
        controller.abort(list[early].reason);
        return controller.signal;
      }
    }
    var listeners = [];
    var onAbort = function (event) {
      for (var i = 0; i < listeners.length; i += 1) {
        listeners[i].signal.removeEventListener('abort', onAbort);
      }
      listeners.length = 0;
      controller.abort(event.target.reason);
    };
    for (var next = 0; next < list.length; next += 1) {
      listeners.push({ signal: list[next] });
      list[next].addEventListener('abort', onAbort);
    }
    return controller.signal;
  };
})();"""

    /**
     * 在页面任何脚本执行之前注入，只对指定源生效。provider 不支持 document-start 时静默
     * 返回，由 [apply] 在 onPageFinished 兜底。调用方要保证同一组参数只装一次，避免重复。
     */
    fun installDocumentStart(webView: WebView, originRule: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        try {
            WebViewCompat.addDocumentStartJavaScript(webView, SCRIPT, setOf(originRule))
        } catch (_: Exception) {
            // provider 声称支持却注入失败：保留 onPageFinished 兜底，不打断页面加载。
        }
    }

    /** 页面主框架加载完成后调用；WebView 正在销毁或已 detach 时静默跳过。 */
    fun apply(webView: WebView) {
        webView.post {
            try {
                webView.evaluateJavascript(SCRIPT, null)
            } catch (_: Exception) {
                // 页面正在销毁：下一次加载会重新补上，不需要打断用户。
            }
        }
    }
}
