package io.deepseekharness.mobile

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * 给系统 WebView 补上 Harness 页面用到的较新 API，只在缺失时才补。
 *
 * 为什么需要它：Harness 前端（以及它的客户端插件页面）跑在**系统 WebView** 里，而不是内置
 * Node.js 里——两者版本互不相干，把运行时的 Node 提到 24 也补不了浏览器的 API。真机上已经
 * 撞到过两次：
 * - Chrome 低于 124 的 WebView 打开「选择工作区目录」时，页面直接显示
 *   `AbortSignal.any is not a function`；
 * - ColorOS 13 自带的旧 WebView 在模型列表里显示 `groups.toSorted is not a function`
 *   （`Array.prototype.toSorted` 要 Chrome 110+）。
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
     * - `Array.prototype.toSorted / toReversed / toSpliced / with`（Chrome 110）
     * - `Promise.withResolvers`（Chrome 119）
     * - `URL.canParse`（Chrome 120）
     * - `AbortSignal.any`（Chrome 116）与旧 AbortSignal 的最小 EventTarget 语义
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
   * 旧 WebView 的 AbortSignal 不是 EventTarget：没有 addEventListener，dsh 前端在
   * 目录选择里调用 signal.addEventListener 直接抛 "signal.addEventListener is not a function"
   * （实测：选择工作区目录弹窗报 directoryPicker/list failed，只能取消）。
   * 这里补一层最小 EventTarget 语义，并在 abort() 时派发事件。
   */
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.prototype.addEventListener !== 'function') {
    var registries = new WeakMap();
    var stateOf = function (signal) {
      var state = registries.get(signal);
      if (!state) { state = { listeners: {}, onabort: null }; registries.set(signal, state); }
      return state;
    };
    AbortSignal.prototype.addEventListener = function (type, listener) {
      if (type !== 'abort' || typeof listener !== 'function') { return; }
      var state = stateOf(this);
      (state.listeners.abort || (state.listeners.abort = [])).push(listener);
    };
    AbortSignal.prototype.removeEventListener = function (type, listener) {
      if (type !== 'abort') { return; }
      var list = stateOf(this).listeners.abort;
      if (!list) { return; }
      var index = list.indexOf(listener);
      if (index >= 0) { list.splice(index, 1); }
    };
    AbortSignal.prototype.dispatchEvent = function (event) {
      var state = stateOf(this);
      var listeners = (state.listeners.abort || []).slice();
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
    if (typeof AbortController !== 'undefined') {
      var originalAbort = AbortController.prototype.abort;
      AbortController.prototype.abort = function (reason) {
        originalAbort.call(this, reason);
        var signal = this.signal;
        if (signal && typeof signal.dispatchEvent === 'function' && typeof Event === 'function') {
          try { signal.dispatchEvent(new Event('abort')); } catch (error) { }
        }
      };
    }
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
