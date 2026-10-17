// scripts/star-download-chart.mjs — 抓 GitHub 的星标与 Release 下载量，画成 README 用的折线图（SVG）。
//
// 用法：
//   node scripts/star-download-chart.mjs
//   node scripts/star-download-chart.mjs --repo owner/name --out docs/images/star-download-history.svg
//
// 数据来源与口径（写进图里，别在别处许愿）：
// - 星标：`GET /repos/{repo}/stargazers`（Accept: application/vnd.github.star+json）能拿到每颗星的
//   `starred_at`，所以按天累计是真实历史。
// - 下载量：GitHub 只给「当前累计」，没有历史曲线。这里把每个 Release 的资产计数记在它的发布日，
//   按天累加成阶梯线——它说明的是「到这一天为止，这批 Release 一共被下过多少次」。
import { execSync } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'

const argv = process.argv.slice(2)
const arg = (name, fallback) => {
  const index = argv.indexOf(`--${name}`)
  return index >= 0 && argv[index + 1] ? argv[index + 1] : fallback
}

const repo = arg('repo', 'dphmoblie/deepseek-harness-android')
const out = resolve(arg('out', 'docs/images/star-download-history.svg'))
const DAY_MS = 86_400_000

function readToken() {
  if (process.env.GITHUB_TOKEN) return process.env.GITHUB_TOKEN
  try {
    return execSync('gh auth token', { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
  } catch {
    return ''
  }
}

const token = readToken()
if (token === '') {
  console.error('需要 GITHUB_TOKEN 环境变量，或者本机已登录的 gh CLI。')
  process.exit(1)
}

const headers = {
  accept: 'application/vnd.github+json',
  authorization: `Bearer ${token}`,
  'user-agent': 'dsh-star-download-chart',
}

async function getJson(path, extra = {}) {
  const response = await fetch(`https://api.github.com${path}`, { headers: { ...headers, ...extra } })
  if (!response.ok) throw new Error(`${path} → HTTP ${response.status} ${response.statusText}`)
  return response.json()
}

/** 逐页取完；GitHub 的列表接口每页最多 100 条。 */
async function getAll(path, extra = {}) {
  const items = []
  for (let page = 1; ; page += 1) {
    const batch = await getJson(`${path}${path.includes('?') ? '&' : '?'}per_page=100&page=${page}`, extra)
    items.push(...batch)
    if (batch.length < 100) return items
  }
}

const dayKey = date => date.toISOString().slice(0, 10)
const parseDay = key => new Date(`${key}T00:00:00Z`)
const addDays = (date, days) => new Date(date.getTime() + days * DAY_MS)

const [stars, releases] = await Promise.all([
  getAll(`/repos/${repo}/stargazers`, { accept: 'application/vnd.github.star+json' }),
  getAll(`/repos/${repo}/releases`),
])

const starredDays = stars
  .map(entry => (typeof entry === 'string' ? entry : entry.starred_at))
  .filter(Boolean)
  .map(value => dayKey(new Date(value)))
  .sort()

const releaseDays = releases
  .filter(release => release.published_at && release.draft !== true)
  .map(release => ({
    day: dayKey(new Date(release.published_at)),
    downloads: (release.assets ?? []).reduce((sum, asset) => sum + (asset.download_count ?? 0), 0),
  }))
  .sort((left, right) => left.day.localeCompare(right.day))

if (starredDays.length === 0 && releaseDays.length === 0) {
  console.error('仓库没有星标也没有已发布版本，没什么可画的。')
  process.exit(1)
}

const firstCandidates = [starredDays[0], releaseDays[0]?.day].filter(Boolean).sort()
const firstDay = firstCandidates[0]
const totalDownloads = releaseDays.reduce((sum, entry) => sum + entry.downloads, 0)
const today = dayKey(new Date())
const lastDay = [today, starredDays.at(-1), releaseDays.at(-1)?.day].filter(Boolean).sort().at(-1)
const totalDays = Math.round((parseDay(lastDay) - parseDay(firstDay)) / DAY_MS)

const starsPerDay = new Map()
for (const day of starredDays) starsPerDay.set(day, (starsPerDay.get(day) ?? 0) + 1)
const downloadsPerDay = new Map()
for (const entry of releaseDays) downloadsPerDay.set(entry.day, (downloadsPerDay.get(entry.day) ?? 0) + entry.downloads)

const starSeries = []
const downloadSeries = []
let starRunning = 0
let downloadRunning = 0
for (let index = 0; index <= totalDays; index += 1) {
  const key = dayKey(addDays(parseDay(firstDay), index))
  starRunning += starsPerDay.get(key) ?? 0
  downloadRunning += downloadsPerDay.get(key) ?? 0
  starSeries.push(starRunning)
  downloadSeries.push(downloadRunning)
}

/** 坐标轴上限取整：1 / 2 / 2.5 / 5 开头的整数，免得刻度出现 2713 这种数。 */
function niceCeil(value) {
  if (value <= 0) return 1
  const magnitude = 10 ** Math.floor(Math.log10(value))
  for (const step of [1, 2, 2.5, 5, 10]) {
    const candidate = step * magnitude
    if (candidate >= value) return candidate
  }
  return 10 * magnitude
}

const downloadMax = niceCeil(totalDownloads)
const starMax = niceCeil(starSeries.at(-1) ?? 0)

const width = 900
const height = 350
const padding = { top: 78, right: 66, bottom: 52, left: 66 }
const plotWidth = width - padding.left - padding.right
const plotHeight = height - padding.top - padding.bottom

const x = index => padding.left + (totalDays === 0 ? 0 : (index / totalDays) * plotWidth)
const yDownload = value => padding.top + plotHeight - (value / downloadMax) * plotHeight
const yStar = value => padding.top + plotHeight - (value / starMax) * plotHeight
const baseline = padding.top + plotHeight

/** 累计序列画成阶梯：值只在落点变，直线插值会假装中间有增长。 */
function stepPath(series, yScale) {
  let path = `M ${x(0).toFixed(2)} ${yScale(series[0]).toFixed(2)}`
  for (let index = 1; index < series.length; index += 1) {
    path += ` H ${x(index).toFixed(2)} V ${yScale(series[index]).toFixed(2)}`
  }
  return path
}

const downloadPath = stepPath(downloadSeries, yDownload)
const starPath = stepPath(starSeries, yStar)
const downloadArea = `${downloadPath} L ${x(totalDays).toFixed(2)} ${baseline.toFixed(2)} L ${x(0).toFixed(2)} ${baseline.toFixed(2)} Z`
const number = new Intl.NumberFormat('zh-CN')

const gridLines = [0, 0.25, 0.5, 0.75, 1]
  .map(ratio => {
    const y = baseline - ratio * plotHeight
    const downloads = number.format(Math.round(ratio * downloadMax))
    const stars = number.format(Math.round(ratio * starMax))
    return `
    <line x1="${padding.left}" y1="${y.toFixed(2)}" x2="${(padding.left + plotWidth).toFixed(2)}" y2="${y.toFixed(2)}" stroke="#8b949e" stroke-opacity="0.22" stroke-width="1"/>
    <text x="${padding.left - 12}" y="${(y + 4).toFixed(2)}" text-anchor="end" font-size="11" fill="#4285f4">${downloads}</text>
    <text x="${(padding.left + plotWidth + 12).toFixed(2)}" y="${(y + 4).toFixed(2)}" text-anchor="start" font-size="11" fill="#e3a008">${stars}</text>`
  })
  .join('')

const tickCount = totalDays >= 40 ? 6 : Math.max(2, Math.ceil(totalDays / 7))
const xTicks = Array.from({ length: tickCount + 1 }, (_, tick) => {
  const index = Math.round((tick / tickCount) * totalDays)
  const day = dayKey(addDays(parseDay(firstDay), index))
  return { index, label: day.slice(5) }
})

const xAxis = xTicks
  .map(tick => `
    <line x1="${x(tick.index).toFixed(2)}" y1="${baseline.toFixed(2)}" x2="${x(tick.index).toFixed(2)}" y2="${(baseline + 5).toFixed(2)}" stroke="#8b949e" stroke-opacity="0.45" stroke-width="1"/>
    <text x="${x(tick.index).toFixed(2)}" y="${(baseline + 20).toFixed(2)}" text-anchor="middle" font-size="11" fill="#8b949e">${tick.label}</text>`)
  .join('')

const starTotal = number.format(starSeries.at(-1) ?? 0)
const downloadTotal = number.format(totalDownloads)
const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-label="星标数与下载量累计折线图：星标 ${starTotal}、下载 ${downloadTotal}（数据抓取于 ${today}）">
  <title>星标与下载量累计（${repo}）</title>
  <desc>蓝线为各 Release 资产下载量的累计值，按发布日计入；黄线为星标累计值，按加星时间计入。数据抓取于 ${today}。</desc>
  <style>
    text { font-family: system-ui, -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif; }
    .label { fill: #57606a; }
    .metric { fill: #1f2328; font-weight: 600; }
  </style>
  <rect x="0.5" y="0.5" width="${width - 1}" height="${height - 1}" rx="12" fill="#ffffff" stroke="#d0d7de"/>
  <text x="${padding.left}" y="26" class="label" font-size="11">星标累计</text>
  <text x="${padding.left}" y="46" class="metric" font-size="19">${starTotal}</text>
  <text x="${padding.left + 104}" y="26" class="label" font-size="11">下载累计</text>
  <text x="${padding.left + 104}" y="46" class="metric" font-size="19">${downloadTotal}</text>
  <text x="${(padding.left + plotWidth).toFixed(2)}" y="26" class="label" font-size="11" text-anchor="end">抓取于 ${today}</text>
  <g transform="translate(${padding.left + 236}, 24)">
    <rect x="0" y="8" width="12" height="3" rx="1.5" fill="#e3a008"/>
    <text x="18" y="13" class="label" font-size="11">星标</text>
    <rect x="58" y="8" width="12" height="3" rx="1.5" fill="#4285f4"/>
    <text x="76" y="13" class="label" font-size="11">下载</text>
  </g>
  ${gridLines}
  ${xAxis}
  <path d="${downloadArea}" fill="#4285f4" fill-opacity="0.14"/>
  <path d="${downloadPath}" fill="none" stroke="#4285f4" stroke-width="2" stroke-linejoin="round"/>
  <path d="${starPath}" fill="none" stroke="#e3a008" stroke-width="2" stroke-linejoin="round"/>
  <circle cx="${x(totalDays).toFixed(2)}" cy="${yStar(starSeries.at(-1)).toFixed(2)}" r="3" fill="#e3a008"/>
  <circle cx="${x(totalDays).toFixed(2)}" cy="${yDownload(downloadSeries.at(-1)).toFixed(2)}" r="3" fill="#4285f4"/>
</svg>
`

// 行尾空格会被仓库的 pre-commit 空白检查拦住，写盘前先清掉；连续空行压成一个。
const cleaned = svg.replace(/[ \t]+$/gmu, '').replace(/\n{3,}/gmu, '\n\n')
await mkdir(dirname(out), { recursive: true })
await writeFile(out, cleaned, 'utf8')
console.log(JSON.stringify({
  out,
  repo,
  stars: starSeries.at(-1) ?? 0,
  downloads: totalDownloads,
  releases: releaseDays.length,
  range: `${firstDay}..${lastDay}`,
  bytes: Buffer.byteLength(cleaned),
}, null, 2))
