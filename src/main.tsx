import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
// xterm 的样式表不在这里引入：终端面板本身是懒加载的（见 App.tsx 的 lazy import），
// 样式跟着 TerminalPanel 走才能让首屏 CSS 不包含终端相关的规则。
import './styles.css'
import { App } from './App'
import { AppErrorBoundary } from './components/AppErrorBoundary'
import { applyTheme, readThemeMode } from './theme'

const root = document.getElementById('root')

if (root === null) {
  throw new Error('应用根节点不存在')
}

// 首帧脚本负责明暗主题；模块加载后补落已保存的强调色，确保不进入设置页也能保持自定义外观。
applyTheme(readThemeMode())

// 顶层错误边界从 main.tsx 抽到了 components/AppErrorBoundary.tsx：
// 它现在带「导出诊断日志」入口，且文案走 i18n，需要独立的测试文件覆盖。
createRoot(root).render(
  <StrictMode>
    <AppErrorBoundary><App /></AppErrorBoundary>
  </StrictMode>,
)
