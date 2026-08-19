const root = document.getElementById('root')
if (root === null) throw new Error('缺少 #root 挂载点')

const surface = new URLSearchParams(window.location.search).get('surface')
const pluginWorkbenchLoader = '/plugin-workbench-loader.js'
const loading = surface === 'plugins'
  ? import(/* @vite-ignore */ pluginWorkbenchLoader)
  : import('./mobile')

void loading.catch((failure: unknown) => {
  const message = failure instanceof Error && failure.message.trim() !== ''
    ? failure.message.trim().slice(0, 500)
    : '客户端模块未提供详细原因'
  root.replaceChildren()
  const panel = document.createElement('main')
  panel.setAttribute('role', 'alert')
  panel.style.cssText = 'display:flex;max-width:720px;margin:64px auto;padding:24px;flex-direction:column;gap:16px;font:14px/1.6 system-ui;color:#b42318'
  const detail = document.createElement('p')
  detail.style.cssText = 'margin:0'
  detail.textContent = `Harness 前端启动失败：${message}`
  panel.append(detail)
  if (surface === 'plugins' && document.querySelector('[aria-label="插件工作台导航"]') === null) {
    const back = document.createElement('a')
    const target = new URL(window.location.href)
    target.searchParams.delete('surface')
    back.href = target.toString()
    back.textContent = '返回移动对话'
    back.style.cssText = 'align-self:flex-start;padding:7px 11px;border:1px solid #d0d5dd;border-radius:7px;color:#344054;background:#fff;font-weight:600;text-decoration:none'
    panel.append(back)
  }
  root.append(panel)
})
