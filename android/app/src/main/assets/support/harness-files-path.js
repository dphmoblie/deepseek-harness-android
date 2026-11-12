/*
 * harness-files-path.js — 把「文件」面板顶栏的路径变成可点的面包屑（Android 适配层）。
 *
 * 官方文件面板的顶栏只有一个 `PathLabel`：整段路径写成静态文本（`[data-files-path][data-path-label]`），
 * 目录要一层层点开、想退回上一层只能再点一次那棵目录树。本脚本把这段路径按目录切开，
 * 每个上层目录都变成可点的面包屑；点一下就收回该层之下的展开，回到那一层的文件列表。
 *
 * 边界（不要越过）：
 * - 文件面板的根是**会话工作目录**，Host 只允许读它和它下面的子目录（`workspace-file/outside-workspace`）。
 *   根之上的路径（例如根是 `/root/dsh` 时的 `/root`）照常显示，但标记为不可点，并在 title 里说明原因；
 *   不要为了"能点"去伪造一个跳转。
 * - 脚本不读页面业务数据、不发请求、不碰文件系统；只用面板自己渲染出来的 `data-files-*` 锚点，
 *   点击效果等同用户自己点目录行（复用官方的展开/收起动作）。
 *
 * 幂等：重复注入只补扫一遍，不叠加观察器；React 重渲染把面包屑冲掉后由观察器补回。
 */
(function () {
  'use strict'

  if (typeof document === 'undefined') return
  if (window.__dshAndroidFilesPath && window.__dshAndroidFilesPath.scan) {
    window.__dshAndroidFilesPath.scan()
    return
  }

  var TREE = '[data-files-state="tree"]'
  var ROW = '[data-files-entry="directory"][data-files-path]'
  var LABEL = '[data-files-path][data-path-label]'
  var CRUMBS = 'data-dsh-files-crumbs'
  var CRUMB = 'data-dsh-files-crumb'
  var SEP = 'data-dsh-files-crumbs-sep'
  var STYLE_MARK = 'data-dsh-files-crumbs-style'
  var ROOT_ATTR = 'data-files-crumbs-for'
  var OUTSIDE_TITLE = '工作区之外的目录不能浏览：Host 只允许读会话工作目录及其子目录'
  var CURRENT_TITLE = '当前目录'
  var ROOT_TITLE = '工作区根目录'
  var MAX_COLLAPSE_CLICKS = 64
  var LABEL_TEXT = '当前目录路径'
  var HINT_MARK = 'data-dsh-files-crumbs-hint'
  var HINT_MS = 2600

  /*
   * 面包屑自己的样式随脚本注入，不写进 android.css：这套 DOM 只有本脚本在时才有，
   * 样式也应该跟着它一起出现/消失。颜色一律取官方变量，深浅主题自动跟随。
   * 最后一条把官方那段静态路径藏掉 —— 它和面包屑是同一份信息，留着会重复；
   * 选择器要求面包屑与标签是兄弟节点，脚本没插入时标签照常显示。
   */
  var STYLE = [
    '[' + CRUMBS + ']{display:flex;align-items:center;gap:2px;min-width:0;flex:0 1 auto;',
    'overflow-x:auto;overflow-y:hidden;margin-right:12px;font:inherit;line-height:inherit;',
    'scrollbar-width:none;-webkit-overflow-scrolling:touch}',
    '[' + CRUMBS + ']::-webkit-scrollbar{display:none}',
    '[' + CRUMBS + ']>[' + SEP + ']{color:var(--dsw-alias-label-tertiary,#8b8f97);flex:none}',
    '[' + CRUMBS + ']>button,[' + CRUMBS + ']>span[' + CRUMB + ']{max-width:38vw;overflow:hidden;',
    'text-overflow:ellipsis;white-space:nowrap;font:inherit;line-height:inherit;padding:4px 0;',
    'border:0;background:none;color:var(--dsw-alias-label-secondary,#5c6169)}',
    '[' + CRUMBS + ']>button{cursor:pointer;-webkit-tap-highlight-color:transparent;min-height:28px}',
    '[' + CRUMBS + ']>button[aria-disabled="true"]{color:var(--dsw-alias-label-tertiary,#8b8f97);cursor:default}',
    '[' + CRUMBS + ']>button:not([aria-disabled="true"]):hover{color:var(--dsw-alias-label-primary,#15181d);',
    'text-decoration:underline}',
    '[' + CRUMBS + ']>[aria-current="step"]{color:var(--dsw-alias-label-primary,#15181d)}',
    '[' + CRUMBS + ']~' + LABEL + '{display:none!important}',
    '[' + HINT_MARK + ']{position:fixed;left:50%;bottom:calc(env(safe-area-inset-bottom,0px) + 18px);',
    'transform:translateX(-50%);z-index:2147483000;max-width:86vw;padding:8px 14px;border-radius:12px;',
    'font-size:13px;line-height:1.5;color:var(--dsw-alias-label-primary,#f5f6f8);',
    'background-color:var(--dsw-alias-bg-layer-3,#23262b);box-shadow:0 6px 20px rgba(0,0,0,.28);',
    'pointer-events:none;text-align:center}',
  ].join('')

  /** 统一成 `/a/b` 形态：反斜杠当分隔符、折叠重复斜杠、去掉尾部斜杠（根 `/` 保留）。 */
  var normalize = function (value) {
    if (typeof value !== 'string') return ''
    var path = value.replace(/\\/gu, '/').replace(/\/{2,}/gu, '/')
    if (path.length > 1) path = path.replace(/\/+$/u, '')
    return path
  }

  /** `target` 是不是 `path` 本身或它的祖先目录。 */
  var contains = function (target, path) {
    if (target === '' || path === '') return false
    if (target === path) return true
    if (target === '/') return path.charAt(0) === '/'
    return path.indexOf(target + '/') === 0
  }

  /** 目录深度：`/` 是 0 层，`/a/b` 是 2 层。 */
  var depthOf = function (path) {
    if (path === '' || path === '/') return 0
    return path.split('/').length - 1
  }

  /**
   * 把绝对路径切成逐级目录。
   * @param {string} path - 绝对路径，例如 `/root/dsh/audit`。
   * @returns {Array<{ name: string, path: string }>} 每一级的显示名与它自己的绝对路径。
   */
  var segmentsOf = function (path) {
    var normalized = normalize(path)
    var result = []
    if (normalized === '' || normalized === '/') return result
    var parts = normalized.slice(1).split('/')
    var accumulated = ''
    for (var index = 0; index < parts.length; index += 1) {
      if (parts[index] === '') continue
      accumulated += '/' + parts[index]
      result.push({ name: parts[index], path: accumulated })
    }
    return result
  }

  /** 一棵树的工作区根；树还没渲染出 `data-files-root` 时退回路径标签的 title / 文本。 */
  var rootOf = function (tree, label) {
    var root = normalize(tree.getAttribute('data-files-root') || '')
    if (root !== '') return root
    if (label === null) return ''
    var title = label.getAttribute('title')
    if (typeof title === 'string' && title.charAt(0) === '/') return normalize(title)
    var text = typeof label.textContent === 'string' ? label.textContent : ''
    return text.charAt(0) === '/' ? normalize(text) : ''
  }

  /** 当前看得最深的那个已展开目录：没有展开就返回根。 */
  var deepestOf = function (tree, root) {
    var current = root
    if (typeof tree.querySelectorAll !== 'function') return current
    var rows = tree.querySelectorAll(ROW)
    for (var index = 0; index < rows.length; index += 1) {
      var row = rows[index]
      var toggle = row.querySelector('button[aria-expanded]')
      if (toggle === null || toggle.getAttribute('aria-expanded') !== 'true') continue
      var path = normalize(row.getAttribute('data-files-path') || '')
      if (path === '' || path === root || !contains(root, path)) continue
      if (depthOf(path) >= depthOf(current)) current = path
    }
    return current
  }

  var ensureStyle = function () {
    if (document.querySelector('style[' + STYLE_MARK + ']') !== null) return
    var style = document.createElement('style')
    style.setAttribute(STYLE_MARK, '')
    style.textContent = STYLE
    var parent = document.head !== null ? document.head : document.documentElement
    if (parent !== null) parent.appendChild(style)
  }

  var separator = function () {
    var span = document.createElement('span')
    span.setAttribute(SEP, '')
    span.setAttribute('aria-hidden', 'true')
    span.textContent = '/'
    return span
  }

  /**
   * 一条短提示：点的是工作区之外的层级时，说明为什么不动。
   *
   * 面包屑在手机上无法靠 `title` 传达原因（触屏没有悬停），点了没反应会被当成坏掉的按钮；
   * 提示固定挂在 body 上、2.6 秒后自己消失，重复点击只重置计时，不叠第二块。
   */
  var hintTimer = null
  var showHint = function (text) {
    var host = document.body !== null ? document.body : document.documentElement
    if (host === null) return
    var hint = document.querySelector('[' + HINT_MARK + ']')
    if (hint === null) {
      hint = document.createElement('div')
      hint.setAttribute(HINT_MARK, '')
      hint.setAttribute('role', 'status')
      host.appendChild(hint)
    }
    hint.textContent = text
    if (hintTimer !== null && typeof window.clearTimeout === 'function') window.clearTimeout(hintTimer)
    hintTimer = window.setTimeout(function () {
      hintTimer = null
      if (hint.parentNode !== null) hint.parentNode.removeChild(hint)
    }, HINT_MS)
  }

  /**
   * 收起 `target` 之下的展开目录，让那一层的文件列表回到可见位置。
   *
   * 点的是目录行自己的 toggle（官方唯一的展开入口）：先点浅的，一次点击连子树一起收掉，
   * 后面已经脱离文档的节点直接跳过。
   * @param {Element} tree - 文件面板的树容器。
   * @param {string} target - 要回到的目录绝对路径。
   * @returns {boolean} 是否真的点了至少一个 toggle。
   */
  var collapseTo = function (tree, target) {
    var rows = tree.querySelectorAll(ROW)
    var deeper = []
    for (var index = 0; index < rows.length; index += 1) {
      var row = rows[index]
      var path = normalize(row.getAttribute('data-files-path') || '')
      if (path === '' || path === target || !contains(target, path)) continue
      var toggle = row.querySelector('button[aria-expanded]')
      if (toggle === null || toggle.getAttribute('aria-expanded') !== 'true') continue
      deeper.push({ row: row, depth: depthOf(path) })
    }
    deeper.sort(function (left, right) { return left.depth - right.depth })
    var clicked = 0
    for (var index2 = 0; index2 < deeper.length && clicked < MAX_COLLAPSE_CLICKS; index2 += 1) {
      var entry = deeper[index2]
      if (entry.row.isConnected === false) continue
      var current = entry.row.querySelector('button[aria-expanded="true"]')
      if (current === null) continue
      current.click()
      clicked += 1
    }
    return clicked > 0
  }

  /** 把某一层滚到可见位置并聚焦它的目录行；根没有对应的行。 */
  var reveal = function (tree, target) {
    var root = normalize(tree.getAttribute('data-files-root') || '')
    if (target === root) {
      if (typeof tree.scrollIntoView === 'function') tree.scrollIntoView({ block: 'nearest' })
      return
    }
    var rows = tree.querySelectorAll(ROW)
    for (var index = 0; index < rows.length; index += 1) {
      if (normalize(rows[index].getAttribute('data-files-path') || '') !== target) continue
      if (typeof rows[index].scrollIntoView === 'function') rows[index].scrollIntoView({ block: 'nearest' })
      var toggle = rows[index].querySelector('button[aria-expanded]')
      if (toggle !== null && typeof toggle.focus === 'function') toggle.focus({ preventScroll: true })
      return
    }
  }

  var navigate = function (tree, target) {
    if (target === '') return
    collapseTo(tree, target)
    reveal(tree, target)
    schedule()
  }

  /** 面包屑上的一次点击：当前层不响应；工作区之外的层级给一条说明，其余收起更深层。 */
  var onClick = function (event) {
    var nav = event.currentTarget
    var element = event.target
    if (nav === null || element === null || typeof element.closest !== 'function') return
    var crumb = element.closest('[' + CRUMB + ']')
    if (crumb === null || !nav.contains(crumb)) return
    if (crumb.getAttribute('aria-current') === 'step') return
    var path = normalize(crumb.getAttribute(CRUMB) || '')
    if (path === '') return
    event.preventDefault()
    event.stopPropagation()
    if (crumb.getAttribute('aria-disabled') === 'true') {
      showHint(OUTSIDE_TITLE)
      return
    }
    var tree = nav.closest(TREE)
    if (tree === null) return
    navigate(tree, path)
  }

  var render = function (tree, nav, label, root, current) {
    var key = root + '|' + current
    if (nav.getAttribute(ROOT_ATTR) === key) return
    nav.setAttribute(ROOT_ATTR, key)
    nav.setAttribute('aria-label', LABEL_TEXT)
    while (nav.firstChild !== null) nav.removeChild(nav.firstChild)
    // 首段之前的那颗斜杠就是文件系统根；根目录自身（`/`）不再多画一颗，避免出现 `//`。
    var crumbs = segmentsOf(current)
    if (crumbs.length === 0) crumbs = [{ name: '/', path: '/' }]
    if (crumbs[0].path !== '/') nav.appendChild(separator())
    for (var index = 0; index < crumbs.length; index += 1) {
      if (index > 0) nav.appendChild(separator())
      var entry = crumbs[index]
      var isCurrent = index === crumbs.length - 1
      var outside = !contains(root, entry.path)
      var element
      if (isCurrent) {
        element = document.createElement('span')
        element.setAttribute('aria-current', 'step')
      } else {
        element = document.createElement('button')
        element.type = 'button'
        // 不用 disabled：禁用的按钮在触屏上没有任何反馈，用户只会觉得"点了没用"。
        // aria-disabled 保留给读屏，点击改由 onClick 给一条说明。
        if (outside) element.setAttribute('aria-disabled', 'true')
      }
      element.setAttribute(CRUMB, entry.path)
      element.setAttribute('title', outside
        ? OUTSIDE_TITLE
        : (isCurrent ? CURRENT_TITLE + '：' + entry.path : (entry.path === root ? ROOT_TITLE + '：' + entry.path : entry.path)))
      element.textContent = entry.name
      nav.appendChild(element)
    }
    if (label.parentNode === nav.parentNode && nav.nextSibling !== label) {
      // 官方标签与面包屑是同一份信息：上面那条 CSS 已经把它藏掉，这里只保证顺序（面包屑在前）。
      nav.parentNode.insertBefore(nav, label)
    }
    // 路径比顶栏宽时让**当前目录**留在可见位置（右端），而不是把最深一级裁掉。
    if (typeof nav.scrollWidth === 'number') nav.scrollLeft = nav.scrollWidth
  }

  /** 同步一棵树：缺面包屑就插一个，路径或展开层变了就重画。 */
  var sync = function (tree) {
    ensureStyle()
    var label = tree.querySelector(LABEL)
    if (label === null || label.parentElement === null) return
    var root = rootOf(tree, label)
    if (root === '') return
    var current = deepestOf(tree, root)
    var header = label.parentElement
    var nav = header.querySelector('[' + CRUMBS + ']')
    if (nav === null) {
      nav = document.createElement('nav')
      nav.setAttribute(CRUMBS, '')
      nav.addEventListener('click', onClick)
      header.insertBefore(nav, label)
    }
    render(tree, nav, label, root, current)
  }

  var syncAll = function () {
    var trees = document.querySelectorAll(TREE)
    for (var index = 0; index < trees.length; index += 1) sync(trees[index])
  }

  /** 只在「有树且面包屑与当前展开层不一致」时才安排重画，流式输出时不做全量重排。 */
  var needsAnywhere = function () {
    var trees = document.querySelectorAll(TREE)
    for (var index = 0; index < trees.length; index += 1) {
      var tree = trees[index]
      var label = tree.querySelector(LABEL)
      if (label === null || label.parentElement === null) continue
      var root = rootOf(tree, label)
      if (root === '') continue
      var nav = label.parentElement.querySelector('[' + CRUMBS + ']')
      if (nav === null) return true
      if (nav.getAttribute(ROOT_ATTR) !== root + '|' + deepestOf(tree, root)) return true
    }
    return false
  }

  var scheduled = false
  var schedule = function () {
    if (scheduled) return
    scheduled = true
    var run = function () {
      scheduled = false
      if (needsAnywhere()) syncAll()
    }
    if (typeof window.requestAnimationFrame === 'function') window.requestAnimationFrame(run)
    else window.setTimeout(run, 50)
  }

  window.__dshAndroidFilesPath = {
    scan: syncAll,
    normalize: normalize,
    contains: contains,
    segmentsOf: segmentsOf,
    rootOf: rootOf,
    deepestOf: deepestOf,
    collapseTo: collapseTo,
  }

  if (typeof MutationObserver === 'function') {
    var observer = new MutationObserver(function (records) {
      for (var index = 0; index < records.length; index += 1) {
        var record = records[index]
        var target = record.target
        if (target !== null && typeof target.closest === 'function' && target.closest(TREE) !== null) {
          schedule()
          return
        }
        if (record.type !== 'childList') continue
        var added = record.addedNodes
        for (var addedIndex = 0; addedIndex < added.length; addedIndex += 1) {
          var node = added[addedIndex]
          if (node.nodeType !== 1) continue
          if (node.matches(TREE) || node.querySelector(TREE) !== null) {
            schedule()
            return
          }
        }
      }
    })
    observer.observe(document.body !== null ? document.body : document.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['aria-expanded', 'data-files-root'],
    })
  }

  syncAll()
})()
