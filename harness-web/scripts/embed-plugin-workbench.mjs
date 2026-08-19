import { cp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..')
const sourceRoot = join(projectRoot, 'node_modules', '@deepseek-ai', 'dsh-web-frontend', 'dist')
const sourceAssetsRoot = join(sourceRoot, 'assets')
const outputRoot = join(projectRoot, 'dist')
const workbenchRoot = join(outputRoot, 'plugin-workbench')
const workbenchAssetsRoot = join(workbenchRoot, 'assets')

const indexHtml = await readFile(join(sourceRoot, 'index.html'), 'utf8')
const scriptSources = [...indexHtml.matchAll(/<script\b[^>]*\bsrc="([^"]+)"[^>]*><\/script>/gu)].map(match => match[1])
const styleSources = [...indexHtml.matchAll(/<link\b[^>]*\brel="stylesheet"[^>]*\bhref="([^"]+)"[^>]*>/gu)].map(match => match[1])
const preloadSources = [...indexHtml.matchAll(/<link\b[^>]*\brel="modulepreload"[^>]*\bhref="([^"]+)"[^>]*>/gu)].map(match => match[1])

if (scriptSources.length !== 1 || styleSources.length === 0) {
  throw new Error('官方插件工作台入口格式异常')
}
for (const source of [...scriptSources, ...styleSources, ...preloadSources]) validateAssetPath(source)

await rm(workbenchRoot, { recursive: true, force: true })
await mkdir(workbenchRoot, { recursive: true })
await cp(sourceAssetsRoot, workbenchAssetsRoot, { recursive: true, force: true })
await rewriteAbsoluteAssetUrls(workbenchAssetsRoot)

for (const source of [...scriptSources, ...styleSources, ...preloadSources]) {
  await readFile(join(workbenchRoot, source.slice(1)))
}

const loader = `const prefix = '/plugin-workbench'\n`
  + `validateBootManifest(globalThis.__DSH_BOOT__)\n`
  + `const toolbar = document.createElement('header')\n`
  + `const back = document.createElement('a')\n`
  + `const target = new URL(window.location.href); target.searchParams.delete('surface'); back.href = target.toString()\n`
  + `back.textContent = '\u2190 \u79fb\u52a8\u5bf9\u8bdd'; back.setAttribute('aria-label', '\u8fd4\u56de\u79fb\u52a8\u5bf9\u8bdd')\n`
  + `back.style.cssText = 'display:inline-flex;align-items:center;min-height:32px;padding:0 10px;border:1px solid #d0d5dd;border-radius:7px;color:#344054;background:#fff;font:600 12px/18px system-ui;text-decoration:none'\n`
  + `toolbar.setAttribute('aria-label', '\u63d2\u4ef6\u5de5\u4f5c\u53f0\u5bfc\u822a')\n`
  + `toolbar.style.cssText = 'box-sizing:border-box;display:flex;flex:0 0 auto;align-items:center;min-height:44px;padding:calc(6px + env(safe-area-inset-top)) 12px 6px;border-bottom:1px solid #e4e7ec;background:#f9fafb'\n`
  + `toolbar.append(back); document.body.insertBefore(toolbar, document.body.firstChild)\n`
  + `document.body.style.cssText = 'display:flex;flex-direction:column;min-height:100%;overflow:hidden'\n`
  + `const root = document.getElementById('root')\n`
  + `if (root === null) throw new Error('\u63d2\u4ef6\u5de5\u4f5c\u53f0\u7f3a\u5c11 #root \u6302\u8f7d\u70b9')\n`
  + `root.style.cssText = 'flex:1 1 auto;min-height:0;height:auto'\n`
  + `for (const href of ${JSON.stringify(preloadSources)}) {\n`
  + `  const link = document.createElement('link'); link.rel = 'modulepreload'; link.href = prefix + href; document.head.append(link)\n`
  + `}\n`
  + `for (const href of ${JSON.stringify(styleSources)}) {\n`
  + `  const link = document.createElement('link'); link.rel = 'stylesheet'; link.href = prefix + href; document.head.append(link)\n`
  + `}\n`
  + `await import(prefix + ${JSON.stringify(scriptSources[0])})\n`
  + `function validateBootManifest(value) {\n`
  + `  if (typeof value !== 'object' || value === null) throw new Error('\u63d2\u4ef6\u5de5\u4f5c\u53f0\u542f\u52a8\u6e05\u5355\u7f3a\u5931\uff0c\u8bf7\u786e\u8ba4 Harness \u670d\u52a1\u5df2\u542f\u52a8\u540e\u91cd\u8bd5')\n`
  + `  if (typeof value.rev !== 'string' || value.rev.length === 0 || value.rev.length > 256) throw new Error('\u63d2\u4ef6\u5de5\u4f5c\u53f0\u542f\u52a8\u6e05\u5355\u5df2\u635f\u574f\uff1arev \u65e0\u6548')\n`
  + `  if (!Array.isArray(value.entries) || value.entries.length > 512) throw new Error('\u63d2\u4ef6\u5de5\u4f5c\u53f0\u542f\u52a8\u6e05\u5355\u5df2\u635f\u574f\uff1aentries \u65e0\u6548')\n`
  + `  for (const [index, entry] of value.entries.entries()) {\n`
  + `    if (typeof entry !== 'object' || entry === null || typeof entry.id !== 'string' || entry.id.length === 0 || entry.id.length > 200 || typeof entry.url !== 'string' || entry.url.length === 0 || entry.url.length > 500 || typeof entry.rev !== 'string' || entry.rev.length === 0 || entry.rev.length > 256) throw new Error('\u63d2\u4ef6\u5de5\u4f5c\u53f0\u542f\u52a8\u6e05\u5355\u5df2\u635f\u574f\uff1a\u7b2c ' + (index + 1) + ' \u4e2a\u63d2\u4ef6\u6761\u76ee\u65e0\u6548')\n`
  + `    if (entry.inject !== undefined && (!Array.isArray(entry.inject) || entry.inject.some(item => typeof item !== 'string'))) throw new Error('\u63d2\u4ef6\u5de5\u4f5c\u53f0\u542f\u52a8\u6e05\u5355\u5df2\u635f\u574f\uff1a\u7b2c ' + (index + 1) + ' \u4e2a\u63d2\u4ef6\u4f9d\u8d56\u65e0\u6548')\n`
  + `    if (entry.immediately !== undefined && typeof entry.immediately !== 'boolean') throw new Error('\u63d2\u4ef6\u5de5\u4f5c\u53f0\u542f\u52a8\u6e05\u5355\u5df2\u635f\u574f\uff1a\u7b2c ' + (index + 1) + ' \u4e2a\u63d2\u4ef6\u542f\u52a8\u6807\u8bb0\u65e0\u6548')\n`
  + `  }\n`
  + `}\n`

await writeFile(join(outputRoot, 'plugin-workbench-loader.js'), loader, { encoding: 'utf8', mode: 0o644 })

function validateAssetPath(value) {
  if (!/^\/assets\/[A-Za-z0-9._/-]{1,240}$/u.test(value) || value.includes('..')) {
    throw new Error(`官方插件工作台包含非法资源路径：${JSON.stringify(value)}`)
  }
}

async function rewriteAbsoluteAssetUrls(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  for (const entry of entries) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) {
      await rewriteAbsoluteAssetUrls(path)
      continue
    }
    if (!entry.isFile() || !entry.name.endsWith('.css')) continue
    const original = await readFile(path, 'utf8')
    const rewritten = original.replaceAll('/assets/', '/plugin-workbench/assets/')
    if (rewritten.replaceAll('/plugin-workbench/assets/', '').includes('/assets/')) {
      throw new Error(`插件工作台样式仍包含未重写的绝对资源路径：${entry.name}`)
    }
    if (rewritten !== original) await writeFile(path, rewritten, 'utf8')
  }
}
