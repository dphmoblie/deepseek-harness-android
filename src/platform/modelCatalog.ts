import type { CustomProviderModel, ModelCatalogEntry } from './types'
import { MAX_CUSTOM_MODELS } from './customProviders'

/**
 * 自动取回的模型条目填进表单时的默认上下文长度。
 *
 * 上游那份列表只给 id 与显示名（各家的 token 口径并不统一），所以这里用一个够用的默认值，
 * 用户仍可自己改。数值与应用里「添加模型」按钮的初值保持一致，避免同一件事有两个默认。
 */
export const CATALOG_DEFAULT_CONTEXT_WINDOW = 131072
export const CATALOG_DEFAULT_MAX_TOKENS = 8192

/**
 * 模型 id 的合法形态。
 *
 * 与平台层校验（`src/platform/validation.ts` 的 `MODEL_ID_PATTERN`）、原生
 * `RuntimeValidation.customModelIdPattern` 同一套规则：输入框可以容忍半成品，
 * 但只有符合它的值才会写进草稿 —— 否则保存时会在原生侧整份被拒。
 */
const MODEL_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:/+-]{0,199}$/

export function isValidModelId(value: string): boolean {
  return MODEL_ID_PATTERN.test(value.trim())
}

/**
 * 从上游模型列表里选出「最靠前的一个」。
 *
 * 原生侧已经把明显不是对话模型（向量化、语音、图像）的条目排到后面，这里只取第一项；
 * 拿不到时返回空串，由调用方保持原值不动。
 */
export function firstCatalogModelId(entries: ModelCatalogEntry[]): string {
  return entries[0]?.id ?? ''
}

/**
 * 把上游模型列表合并进自定义供应商的模型表。
 *
 * 规则（与用户确认过的方案一致）：
 * 1. 上游**第一项置顶**：它已经存在就移到首位（保留用户改过的字段），不存在就插到首位；
 * 2. 其余条目按 id **去重追加**，已存在的一律保持原样 —— 用户改过的上下文窗口不能被一次
 *    「获取模型」悄悄改回去；
 * 3. 结果仍受 [MAX_CUSTOM_MODELS] 限制：超出部分截掉，而不是让表单变成非法状态。
 *
 * 一个都不能少的情形：上游只回了一条时，结果就是「这一条在最前面」，其余原有模型依次跟上。
 */
export function mergeCatalogModels(
  current: CustomProviderModel[],
  entries: ModelCatalogEntry[],
): CustomProviderModel[] {
  const incoming = entries.filter((entry, index) => entries.findIndex(item => item.id === entry.id) === index)
  if (incoming.length === 0) return current
  const [first, ...rest] = incoming
  const existing = new Map(current.map(model => [model.id, model]))
  const toModel = (entry: ModelCatalogEntry): CustomProviderModel => {
    const known = existing.get(entry.id)
    if (known !== undefined) return known
    return {
      id: entry.id,
      name: entry.name.trim() === '' ? entry.id : entry.name,
      contextWindow: CATALOG_DEFAULT_CONTEXT_WINDOW,
      maxTokens: CATALOG_DEFAULT_MAX_TOKENS,
    }
  }
  const merged: CustomProviderModel[] = [toModel(first)]
  // 其余原有模型保持用户原本的顺序（首位被移走后，它不会再出现第二次）。
  for (const model of current) {
    if (model.id === first.id) continue
    if (merged.length >= MAX_CUSTOM_MODELS) break
    merged.push(model)
  }
  for (const entry of rest) {
    if (merged.length >= MAX_CUSTOM_MODELS) break
    if (merged.some(model => model.id === entry.id)) continue
    merged.push(toModel(entry))
  }
  return merged.slice(0, MAX_CUSTOM_MODELS)
}
