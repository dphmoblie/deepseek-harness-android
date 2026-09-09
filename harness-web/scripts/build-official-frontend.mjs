import { cp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..')
const sourceRoot = join(projectRoot, 'node_modules', '@deepseek-ai', 'dsh-web-frontend', 'dist')
const outputRoot = join(projectRoot, 'dist')
const temporaryRoot = join(projectRoot, '.dist-android')
const marker = '<meta name="dsh-official-frontend" content="android-adapted-v1" />'
const styleLink = '<link rel="stylesheet" href="/dsh-android.css" />'

const sourceIndex = await readFile(join(sourceRoot, 'index.html'), 'utf8')
validateOfficialIndex(sourceIndex)
// 安全校验：只接受包内资源路径，拒绝外部入口和目录穿越；缺少资源时终止打包。
const sourceAssets = [...sourceIndex.matchAll(/(?:src|href)="([^"]+)"/gu)].map(match => match[1])
for (const path of sourceAssets) {
  if (!/^(?:\.\/|\/)[A-Za-z0-9._/-]{1,240}$/u.test(path) || path.includes('..')) {
    throw new Error('官方前端包含非法资源路径')
  }
  await readFile(join(sourceRoot, path.replace(/^\.\//u, '').replace(/^\//u, '')))
}

await rm(temporaryRoot, { recursive: true, force: true })
await mkdir(temporaryRoot, { recursive: true })
try {
  await cp(sourceRoot, temporaryRoot, { recursive: true, force: true })
  const adaptedIndex = sourceIndex
    .replace('content="width=device-width, initial-scale=1"', 'content="width=device-width, initial-scale=1, viewport-fit=cover"')
    .replace('</head>', `    ${marker}\n    ${styleLink}\n  </head>`)
  await writeFile(join(temporaryRoot, 'index.html'), adaptedIndex, 'utf8')
  await cp(join(projectRoot, 'android.css'), join(temporaryRoot, 'dsh-android.css'))

  // Windows directory watchers can hold rename handles; copy the validated staging tree.
  await rm(outputRoot, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
  await cp(temporaryRoot, outputRoot, { recursive: true })
  await rm(temporaryRoot, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
} catch (error) {
  await rm(temporaryRoot, { recursive: true, force: true })
  throw error
}

function validateOfficialIndex(value) {
  if (value.length === 0 || value.length > 1024 * 1024) {
    throw new Error('官方前端 index.html 大小异常')
  }
  if (!value.includes('<div id="root"></div>') || !value.includes('</head>')) {
    throw new Error('官方前端 index.html 结构异常')
  }
  if (!/<script\b[^>]*\bsrc="(?:\.\/|\/)assets\/[A-Za-z0-9._/-]+"/u.test(value)) {
    throw new Error('官方前端 index.html 缺少受支持的入口资源')
  }
  if (value.includes('dsh-mobile-frontend') || value.includes('plugin-workbench')) {
    throw new Error('官方前端包意外包含已移除的移动前端入口')
  }
}
