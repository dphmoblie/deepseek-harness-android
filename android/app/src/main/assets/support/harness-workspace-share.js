/*
 * harness-workspace-share.js — 在官方「文档预览」面板顶栏补一个分享按钮（Android 适配层）。
 *
 * 官方预览面板只提供换行、自动刷新和重新加载，没有把当前文件交给系统分享的入口。
 * 应用侧在每次主框架加载完成后注入本脚本：它只观察 `[data-textpreview-state]` 面板，
 * 在顶栏末尾追加一个与官方工具按钮同款的分享按钮；点击后把标题栏的绝对路径
 * （`[data-textpreview-path]` 的 `title`，宿主已解析）交给原生
 * `dsh-share://file?path=…`，同时附上 `address` 供原生在路径不可用时兜底。
 *
 * 为什么优先绝对路径：资源地址里的路径相对于会话工作目录，cwd 可能是 `/root`、
 * `/root/1` 或 `/root/1` 的某个子目录；原生无法从地址里还原 cwd，曾因此把文件拼到
 * 错误位置并报「工作区里找不到这个文件」。绝对路径没有这个歧义。
 *
 * 安全边界：
 * - 不读取、不代理页面里的业务数据，只取面板自己的资源地址与标题栏路径；
 * - 不直接访问文件系统，也不暴露任何 JS 桥；路径是否可分享由原生重新校验；
 * - 重复注入是幂等的，React 重渲染把按钮冲掉后会由观察器补回。
 */
(function () {
  'use strict'

  if (typeof document === 'undefined') return
  // 同一页面里可能因重载/重复注入再次执行：沿用已有观察器，只补扫一遍。
  if (window.__dshAndroidShare && window.__dshAndroidShare.scan) {
    window.__dshAndroidShare.scan()
    return
  }

  var ADDRESS_PREFIX = 'dsh-resource://file/session/'
  var MARK = 'data-dsh-android-share'
  var LABEL = '分享文件'
  var ICON = '<svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">' +
    '<path d="M8 10.6V1.9" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>' +
    '<path d="M4.9 5 8 1.9 11.1 5" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/>' +
    '<path d="M3 7.6v6h10v-6" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/>' +
    '</svg>'
  // 仅在官方工具按钮缺失（例如「不支持预览」的空态）时兜底，视觉上仍与 28×28 工具位一致。
  // 用长写而不是简写：部分旧内核（以及测试用的 jsdom）对 flex/background 简写解析不一致。
  var FALLBACK_STYLE = [
    'width:28px', 'height:28px', 'color:var(--dsw-alias-label-secondary)',
    'border-radius:var(--dsw-radius-sm)', 'cursor:pointer', 'background-color:transparent',
    'border-width:0', 'border-style:none', 'flex-grow:0', 'flex-shrink:0', 'flex-basis:auto',
    'justify-content:center', 'align-items:center', 'padding:6px', 'line-height:1',
    'display:inline-flex',
  ].join(';')

  var addressOf = function (panel) {
    var value = panel.getAttribute('data-textpreview-url') || ''
    return value.indexOf(ADDRESS_PREFIX) === 0 ? value : ''
  }

  // 标题栏显示的是宿主解析后的绝对路径（PathLabel 的 title）；宿主还没解析出绝对路径时
  // 它会退化成相对路径，那种值没有用，必须让给 address 的旧口径。
  // 老版本 PathLabel 不一定写 title，可见文本同样是完整路径（超长只是 CSS 省略）。
  var absolutePathOf = function (panel) {
    var label = panel.querySelector('[data-textpreview-path]')
    if (label === null) return ''
    var value = typeof label.getAttribute === 'function' ? label.getAttribute('title') : ''
    if (typeof value !== 'string' || value.charAt(0) !== '/') {
      value = typeof label.textContent === 'string' ? label.textContent : ''
    }
    if (value.charAt(0) !== '/' || /[\r\n]/.test(value)) return ''
    return value
  }

  // 分享目标：绝对路径优先，地址只作兜底。两者都带上，原生才能在工作区外的绝对路径
  // （例如会话工作区是手机目录）退回旧口径，而不是把请求判成无效。
  var shareTarget = function (panel) {
    var address = addressOf(panel)
    if (address === '') return ''
    var absolute = absolutePathOf(panel)
    if (absolute === '') return 'dsh-share://file?address=' + encodeURIComponent(address)
    return 'dsh-share://file?path=' + encodeURIComponent(absolute) +
      '&address=' + encodeURIComponent(address)
  }

  // 顶栏是路径标签的直接父级；不依赖官方哈希类名，换版本后仍能定位。
  var headerOf = function (panel) {
    var path = panel.querySelector('[data-textpreview-path]')
    if (path === null) return null
    var header = path.parentElement
    return header !== null && panel.contains(header) ? header : null
  }

  var needsButton = function (panel) {
    return addressOf(panel) !== '' && panel.querySelector('[' + MARK + ']') === null
  }

  var add = function (panel) {
    if (!needsButton(panel)) return
    var header = headerOf(panel)
    if (header === null) return
    var button = document.createElement('button')
    button.type = 'button'
    button.setAttribute(MARK, '')
    button.setAttribute('aria-label', LABEL)
    button.title = LABEL
    // 优先复用 reload/wrap 这种纯图标工具位的样式；查看器选择按钮带最大宽度，不能照抄。
    var sample = header.querySelector('[data-textpreview-tool="reload"]') ||
      header.querySelector('[data-textpreview-tool="wrap"]')
    if (sample !== null && typeof sample.className === 'string' && sample.className !== '') {
      // 复用官方工具按钮的类名，尺寸、悬停和图标大小都由面板自己的样式决定。
      button.className = sample.className
    } else {
      button.style.cssText = FALLBACK_STYLE
    }
    button.innerHTML = ICON
    button.addEventListener('click', function (event) {
      event.preventDefault()
      event.stopPropagation()
      // 自定义 scheme 由 HarnessActivity 的 WebViewClient 拦截：只认带用户手势的主框架 GET，
      // 并且原生会再次校验路径确实位于工作区，不会直接相信页面传来的字符串。
      var target = shareTarget(panel)
      if (target === '') return
      window.location.href = target
    })
    header.appendChild(button)
  }

  var scan = function () {
    var panels = document.querySelectorAll('[data-textpreview-state]')
    for (var index = 0; index < panels.length; index += 1) add(panels[index])
  }

  var scheduled = false
  var schedule = function () {
    if (scheduled) return
    scheduled = true
    var run = function () {
      scheduled = false
      scan()
    }
    if (typeof window.requestAnimationFrame === 'function') window.requestAnimationFrame(run)
    else window.setTimeout(run, 50)
  }

  // 只用一次轻量查询判断「是否还有面板缺按钮」，避免聊天流式输出时每帧全量扫描。
  var missingAnywhere = function () {
    var panels = document.querySelectorAll('[data-textpreview-state]')
    for (var index = 0; index < panels.length; index += 1) {
      if (needsButton(panels[index])) return true
    }
    return false
  }

  window.__dshAndroidShare = { scan: scan, targetFor: shareTarget }

  if (typeof MutationObserver === 'function') {
    var observer = new MutationObserver(function (records) {
      for (var index = 0; index < records.length; index += 1) {
        var record = records[index]
        if (record.type !== 'childList') continue
        var target = record.target
        if (target !== null && typeof target.closest === 'function' &&
            target.closest('[data-textpreview-state]') !== null) {
          if (missingAnywhere()) schedule()
          return
        }
        var added = record.addedNodes
        for (var addedIndex = 0; addedIndex < added.length; addedIndex += 1) {
          var node = added[addedIndex]
          if (node.nodeType !== 1) continue
          if (node.matches('[data-textpreview-state]') ||
              node.querySelector('[data-textpreview-state]') !== null) {
            if (missingAnywhere()) schedule()
            return
          }
        }
      }
    })
    observer.observe(document.body !== null ? document.body : document.documentElement, {
      childList: true,
      subtree: true,
    })
  }

  scan()
})()
