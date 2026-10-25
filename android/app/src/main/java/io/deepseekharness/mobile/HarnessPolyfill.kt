package io.deepseekharness.mobile

import android.webkit.WebView

/**
 * 给系统 WebView 补上 Harness 页面用到的较新 API，只在缺失时才补。
 *
 * 为什么需要它：Harness 前端（以及它的客户端插件页面）跑在**系统 WebView** 里，而不是内置
 * Node.js 里——两者版本互不相干，把运行时的 Node 提到 24 也补不了浏览器的 API。真机上已经
 * 撞到过一次：Chrome 低于 124 的 WebView 打开「选择工作区目录」时，页面直接显示
 * `AbortSignal.any is not a function`，目录列不出来，弹窗只能取消。
 *
 * 为什么是 `onPageFinished` 之后注入就够：这些 API 是**调用时**才查表的
 * （`AbortSignal.any([...])`），而触发它的操作（打开目录选择、发起请求）都发生在页面加载
 * 之后。boot 阶段就用到的 API 不在本脚本的射程内——那类只能靠 document-start 注入，
 * 而 document-start 要额外的 WebKitX 依赖，这里按实际故障点收窄，不提前上重武器。
 *
 * 边界：只补缺失项，已经是函数就原样返回；不覆盖、不代理、不读页面状态，也不接触凭据。
 * 脚本是纯 ECMAScript，不依赖任何页面全局变量，可被重复执行（幂等）。
 */
internal object HarnessPolyfill {
    /**
     * 补齐 `AbortSignal.any`（Node 20.3 / Chrome 124 才有）。
     *
     * 语义按规范来：任一信号已中止就用它的 reason 立刻中止；否则等第一个 abort 事件，
     * 用它的 reason 中止，并摘掉所有监听，避免长命页面里越积越多。
     * `instanceof` 在跨 realm 时不可靠，这里按形态判断（`aborted` + `addEventListener`）。
     */
    const val SCRIPT: String = """(function () {
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
