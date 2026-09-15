import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * 生产构建的手工分块策略（登记册 5.6-A）。
 *
 * 目标只有一个：**首屏不需要的代码不要出现在入口包和入口的静态依赖图里**。
 *
 * 1. `@xterm/*` 只在用户真正打开终端面板时才需要（`src/App.tsx` 里 TerminalPanel 已经是
 *    `lazy(() => import(...))`）。固定成名为 `xterm` 的独立块之后，它只挂在终端那个异步块上：
 *    既能一眼看出「首屏没加载它」，也避免了以后有人把 xterm 挪进首屏静态导入时悄无声息地变大。
 * 2. `react` / `react-dom` / `scheduler` 是运行时底座，和业务代码的更新节奏完全不同。
 *    单独成块后，入口包（`index-*.js`）只反映应用自身的代码量，升级业务代码时这一块不变，
 *    在 WebView 缓存里可以长期复用。
 *
 * 两条踩过的坑，都写在这里免得后人重犯：
 *
 * - **样式表也要算进归属**。manualChunks 只看模块 id，`@xterm/xterm/css/xterm.css` 同样命中
 *   `@xterm/`。该 CSS 原先由 `src/main.tsx` 静态引入，于是这条「CSS 依赖」把整个 xterm 块
 *   变成入口的静态依赖，Vite 直接给 `index.html` 加了一条 `modulepreload` —— 首屏凭空多下
 *   ~290 KB 的终端 JS（实测见 `docs/终端面板握手缓冲与首屏分块.md`）。
 *   现在的做法是：CSS 一律不参与手工分块（跟它自己的引入方待在一起），xterm 的样式由
 *   TerminalPanel 自己引入，随终端块按需加载。
 * - 入口包变小的前提是「拆出去的块真的不在首屏」。判断依据不是看文件名，而是看
 *   `dist/index.html` 里 `<script type="module">` 与 `rel="modulepreload"` 指向了哪些文件。
 */
const XPACKAGE = /[\\/]@xterm[\\/]/
const RUNTIME_PACKAGE = /[\\/]node_modules[\\/](react|react-dom|scheduler)[\\/]/

function manualChunks(id: string): string | undefined {
  // CSS 不参与手工分块：样式跟着引入它的块走，避免「样式依赖」把 JS 块拖进首屏。
  const file = id.split('?')[0]
  if (file.endsWith('.css')) return undefined
  if (XPACKAGE.test(id)) return 'xterm'
  if (RUNTIME_PACKAGE.test(id)) return 'react'
  return undefined
}

export default defineConfig({
  plugins: [react()],
  base: './',
  server: {
    host: '127.0.0.1',
    port: 4173,
    strictPort: false,
  },
  preview: {
    host: '127.0.0.1',
    port: 4173,
    strictPort: false,
  },
  build: {
    target: 'es2022',
    sourcemap: false,
    rollupOptions: {
      output: { manualChunks },
    },
  },
})
