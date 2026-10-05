import type { InstalledApplication } from '../platform/types'

/**
 * 应用选择器的纯查询规则：搜索词规范化、已勾选前置、两个可见性开关的判定。
 *
 * 单独成文件的原因：这些规则决定了「输入什么能搜到什么、列表以什么顺序显示」，
 * 必须能在单测里逐条钉死；组件里只剩状态与渲染。**匹配与拼音**在原生侧
 * （`ApplicationSearch.kt`，用系统 ICU 转写），这里只做与原生一致的**规范化**，
 * 保证前端发过去的查询与原生判定的口径相同。
 */

/** 与原生侧一致的搜索词上限（超出会被桥直接拒绝）。 */
export const MAX_QUERY_CHARS = 160

/** 与原生 `ApplicationSearch.normalize` 第 2 步对齐：全角空格归一到半角。 */
const IDEOGRAPHIC_SPACE = /\u3000/g

/**
 * 搜索词规范化：全角空格 → 半角、去掉零宽/双向控制符、折叠连续空白、去首尾空格、限长。
 *
 * 这里**不丢**空格：`wx 支付` 的多关键词切分靠它；也**不丢**连字符（原生侧先切词、
 * 再对整串丢分隔符，所以 `we-chat` 两种形态都能命中，前端不该提前破坏这个形态）。
 *
 * **制表符/换行必须先于控制符清理**：`\u0000-\u001f` 把 `\t`、`\n` 一起包了进去，
 * 「清理控制符」在折叠空白之前跑会把粘贴进来的制表符**直接删掉**——
 * `wx\t\t支付` 会变成 `wx支付` 这一个词，多关键词搜索当场失效（测试里就是这么抓到的）。
 * 所以顺序固定为：先清理控制符（放行 `\t`/`\n`/`\r`）→ 再折叠空白。
 *
 * 控制符一律不发给桥：桥会直接抛「应用筛选参数无效」，用户只会看到一次莫名其妙的失败。
 */
export function normalizeSearchQuery(raw: string): string {
  return raw
    .replace(/[\u200b-\u200d\ufeff]/g, '')
    .replace(/[\u202a-\u202e\u2066-\u2069]/g, '')
    // eslint-disable-next-line no-control-regex -- 这些控制符是**要删掉**的目标字符：桥会直接拒绝带它们的查询。
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, '')
    // 全角空格先归一再折叠空白，两步的顺序换过也不会出问题，但保持「归一在前」更好读。
    .replace(IDEOGRAPHIC_SPACE, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, MAX_QUERY_CHARS)
}

/**
 * 显示顺序：**已勾选的排最前**，其余保持原生给的顺序（原生已按匹配权重与字典序排好，
 * 前端再排一遍只会把已经算好的顺序改坏）。
 *
 * `Array.prototype.sort` 是稳定排序，所以这里的比较器只需回答「谁该被前置」。
 */
export function orderForDisplay(
  apps: readonly InstalledApplication[],
  selected: readonly string[],
): InstalledApplication[] {
  const chosen = new Set(selected)
  return [...apps].sort((left, right) =>
    Number(chosen.has(right.packageName)) - Number(chosen.has(left.packageName)))
}

export interface VisibilityFilters {
  /** 只看可自动化应用：系统安全组件（原生 `selectable === false`）不显示。 */
  automationOnly: boolean
  /** 隐藏系统组件：厂商预装的系统应用（原生 `system`）不显示。 */
  hideSystem: boolean
}

/** 应用是否该出现在当前列表里；两个开关都关就是全部可见。 */
export function isVisible(app: InstalledApplication, filters: VisibilityFilters): boolean {
  if (filters.automationOnly && !app.selectable) return false
  if (filters.hideSystem && app.system) return false
  return true
}
