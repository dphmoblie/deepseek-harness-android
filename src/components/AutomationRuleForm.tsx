import { useState } from 'react'
import { Loader2, Plus, Trash2, TriangleAlert } from 'lucide-react'
import { t } from '../i18n'
import {
  ACCESSIBILITY_AUTOMATION_ACTION_KINDS,
  ACCESSIBILITY_AUTOMATION_RESET_ON_VALUES,
  ACCESSIBILITY_AUTOMATION_SWIPE_DIRECTIONS,
  AUTOMATION_KEY_CODES,
  AUTOMATION_RULE_LIMITS,
} from '../platform/types'
import type {
  AccessibilityAutomationActionInput,
  AccessibilityAutomationActionKind,
  AccessibilityAutomationResetOn,
  AccessibilityAutomationRuleInput,
  AccessibilityAutomationRuleWriteMode,
  AccessibilityAutomationSelectorInput,
  AccessibilityAutomationSwipeDirection,
} from '../platform/types'
import './AutomationRuleForm.css'

/**
 * 「无障碍自动化规则」的写入表单：新建与编辑共用一套。
 *
 * 这个组件存在的唯一理由是**编辑时不能把用户原有的选择器抹掉**。
 * 原生侧的 `update` 是**整条替换**：没提交的字段不会保持原样，而是按原生默认值写下去。
 * 所以草稿必须携带整条规则的全部字段——包括界面没打算单独排开的冷门字段——
 * 提交时整条写回。选择器的 13 个字段在这里一个都不少（见 [selectorToDraft] / [draftToSelector]），
 * 冷门字段只是收进折叠区，输入框与取值始终在。
 *
 * 表单里的预检只做「明显不合法」这一档：空标识、没有选择器、数字越界、启动动作缺参数。
 * 选择器条件够不够、正则能不能编译、这个包放不放行，判定权都在原生——
 * 在这里复刻一套，只会和 Kotlin 那套慢慢走偏，而它们才是真正的判定标准。
 */

/** 把源类型每个字段映射成字符串：草稿一律以文本保存，空串表示原生侧的「不填」。 */
type StringDraft<T> = { [K in keyof Required<T>]: string }

/**
 * 一个选择器在表单里的形态：**13 个字段全部有落点**。
 *
 * 故意用映射类型而不是手写接口：以后给 `AccessibilityAutomationSelectorInput` 加字段时，
 * [selectorToDraft] / [draftToSelector] / [SELECTOR_FIELD_LABELS] 三处会立刻过不了 tsc，
 * 「加了字段但界面没管」这件事不会悄悄发生。
 */
type SelectorDraft = StringDraft<AccessibilityAutomationSelectorInput>

/** 三态布尔字段（`''` 是「不限制」，与 `false`「明确要求不是」不是一回事）。 */
type SelectorFlagField = 'clickable' | 'enabled' | 'editable'
/** 尺寸字段。 */
type SelectorSizeField = 'minWidth' | 'minHeight'

/** 动作参数一律以文本保存；提交时只带上当前类型允许的那几个键。 */
interface ActionDraft {
  type: AccessibilityAutomationActionKind
  durationMs: string
  direction: AccessibilityAutomationSwipeDirection
  /** 允许表里的键**名**；`#数字` 是读回时才会出现的允许表外键值。 */
  keyCode: string
  delayMs: string
  component: string
  uri: string
}

interface RuleDraft {
  id: string
  enabled: boolean
  /** Activity 白名单正则，每行一个。 */
  allowActivities: string
  /** Activity 黑名单正则，每行一个。 */
  denyActivities: string
  matchDelayMs: string
  maxActions: string
  actionCoolDownMs: string
  resetOn: AccessibilityAutomationResetOn
  selectors: SelectorDraft[]
  action: ActionDraft
}

/**
 * 新建规则表单的三个数字初值。
 *
 * 形状与「每应用参数」读数里的三个 `default*` 一致，由 App 从读数里取出来传进来；
 * 调用方没读到参数时**不传**，由表单回落到原生默认值（1 / 3000 / 0）。
 * 这份回落只是**表单初值**：它不会把任何「没读到的读数」当成真相显示给用户看——
 * 用户看到的始终是自己填进输入框的数字。
 */
export interface AutomationRuleDefaults {
  maxActions: number
  actionCoolDownMs: number
  matchDelayMs: number
}

interface AutomationRuleFormProps {
  /** 当前查看的包名。写入请求必须与它一致，表单里不允许改（改了等于写到别的应用上）。 */
  packageName: string
  /** 编辑时要预填的整条规则；新建时为 `null`。 */
  rule: AccessibilityAutomationRuleInput | null
  mode: AccessibilityAutomationRuleWriteMode
  /** 新建时的三个数字初值；省略即用原生默认值。**编辑模式一律忽略它**（用读回的规则值）。 */
  defaults?: AutomationRuleDefaults
  /** 一次写操作还在路上：输入与按钮全部禁用。 */
  busy: boolean
  /** 上一次写入（或读回）失败的原因；原样展示，不吞掉也不改写。 */
  error: string | null
  onSubmit: (rule: AccessibilityAutomationRuleInput) => void
  onCancel: () => void
}

/** 三态布尔的三个取值：`''` 是「不限」。 */
const FLAG_ANY = ''
const FLAG_TRUE = 'true'
const FLAG_FALSE = 'false'

/** 允许表外的键值（只有读回时才可能出现）在草稿里的前缀：原样带回原生，由原生判定。 */
const UNKNOWN_KEY_PREFIX = '#'

/** 滑动时长的默认值：落在 [AUTOMATION_RULE_LIMITS.swipeDurationMs] 区间内。 */
const DEFAULT_SWIPE_DURATION_MS = 300
/** 等待时长的默认值：落在 [AUTOMATION_RULE_LIMITS.waitDelayMs] 区间内。 */
const DEFAULT_WAIT_DELAY_MS = 1000

/** 常用选择器条件：直接排开，不折叠。 */
const SELECTOR_HOT_TEXT_FIELDS = ['text', 'textContains', 'id', 'desc', 'className'] as const
/** 冷门选择器条件：收进「更多条件」折叠区，但输入框与取值一直参与提交。 */
const SELECTOR_COLD_TEXT_FIELDS = ['textStartsWith', 'textEndsWith', 'descContains'] as const
const SELECTOR_FLAG_FIELDS: readonly SelectorFlagField[] = ['clickable', 'enabled', 'editable']
const SELECTOR_SIZE_FIELDS: readonly SelectorSizeField[] = ['minWidth', 'minHeight']

/** 每个选择器字段的中文名。`Record` 由联合类型收口：漏一个字段 tsc 就报错。 */
const SELECTOR_FIELD_LABELS: Record<keyof SelectorDraft, string> = {
  text: '文本',
  textContains: '包含文本',
  textStartsWith: '文本开头',
  textEndsWith: '文本结尾',
  id: '视图 ID',
  desc: '描述',
  descContains: '描述包含',
  className: '类名',
  clickable: '是否可点击',
  enabled: '是否已启用',
  editable: '是否可编辑',
  minWidth: '最小宽度',
  minHeight: '最小高度',
}

/** 动作类型的中文名。顺序取自 [ACCESSIBILITY_AUTOMATION_ACTION_KINDS]。 */
const ACTION_KIND_LABELS: Record<AccessibilityAutomationActionKind, string> = {
  click: '点击',
  clickCenter: '点击中心',
  longClick: '长按',
  back: '返回',
  swipe: '滑动',
  key: '按键',
  wait: '等待',
  launch: '启动',
}

const SWIPE_DIRECTION_LABELS: Record<AccessibilityAutomationSwipeDirection, string> = {
  up: '向上',
  down: '向下',
  left: '向左',
  right: '向右',
}

const RESET_ON_LABELS: Record<AccessibilityAutomationResetOn, string> = {
  activity: '进入新的 Activity 时重置',
  screen: '屏幕开/关时重置',
}

/** 一个选择器字段输入框的标签：带上序号，否则多个选择器之间的同名控件无法区分。 */
function selectorFieldLabel(index: number, field: keyof SelectorDraft): string {
  return `${t('选择器')} ${index + 1} · ${t(SELECTOR_FIELD_LABELS[field])}`
}

/** 空串与「不填」同义：递空串过去会让原生以为这项有条件、实际什么都没匹配。 */
function emptyToUndefined(value: string): string | undefined {
  const trimmed = value.trim()
  return trimmed === '' ? undefined : trimmed
}

function flagToDraft(value: boolean | undefined): string {
  if (value === undefined) return FLAG_ANY
  return value ? FLAG_TRUE : FLAG_FALSE
}

function draftToFlag(raw: string, index: number, field: SelectorFlagField): boolean | undefined {
  if (raw === FLAG_ANY) return undefined
  if (raw === FLAG_TRUE) return true
  if (raw === FLAG_FALSE) return false
  throw new Error(t('{0}只能是「不限」「是」或「否」', selectorFieldLabel(index, field)))
}

function sizeToDraft(value: number | undefined): string {
  return value === undefined ? '' : String(value)
}

/** 选择器尺寸：可留空；填了就必须是不小于 0 的整数（原生只做非负整数校验，界面不另立上界）。 */
function draftToSize(raw: string, index: number, field: SelectorSizeField): number | undefined {
  const trimmed = raw.trim()
  if (trimmed === '') return undefined
  if (!/^\d+$/u.test(trimmed)) {
    throw new Error(t('{0}必须是不小于 0 的整数', selectorFieldLabel(index, field)))
  }
  return Number(trimmed)
}

/** 有区间的整数：区间与原生 `AutomationRuleLimits` 同源，越界在提交时就报出来。 */
function parseBoundedInteger(
  raw: string,
  label: string,
  range: { readonly min: number; readonly max: number },
): number {
  const trimmed = raw.trim()
  if (trimmed === '' || !/^-?\d+$/u.test(trimmed)) {
    throw new Error(t('{0}必须填写 {1} 到 {2} 之间的整数', label, range.min, range.max))
  }
  const parsed = Number(trimmed)
  if (parsed < range.min || parsed > range.max) {
    throw new Error(t('{0}必须在 {1} 到 {2} 之间', label, range.min, range.max))
  }
  return parsed
}

/** 表里没有的键值（正常读不回来）：原样带回去，让原生说「不在允许的按键表内」。 */
function parseKeyCode(raw: string): number {
  const entry = AUTOMATION_KEY_CODES.find(([name]) => name === raw)
  if (entry !== undefined) return entry[1]
  if (raw.startsWith(UNKNOWN_KEY_PREFIX)) {
    const parsed = Number(raw.slice(UNKNOWN_KEY_PREFIX.length))
    if (Number.isInteger(parsed)) return parsed
  }
  throw new Error(t('请选择一个按键'))
}

function keyCodeToDraftName(keyCode: number | undefined): string {
  if (keyCode === undefined) return AUTOMATION_KEY_CODES[0][0]
  const entry = AUTOMATION_KEY_CODES.find(([, code]) => code === keyCode)
  if (entry !== undefined) return entry[0]
  return `${UNKNOWN_KEY_PREFIX}${keyCode}`
}

/** 一种动作类型的默认参数：切换类型时用它重建，上一种类型的参数因此不会残留。 */
function defaultActionDraft(type: AccessibilityAutomationActionKind): ActionDraft {
  return {
    type,
    durationMs: String(DEFAULT_SWIPE_DURATION_MS),
    direction: 'up',
    keyCode: AUTOMATION_KEY_CODES[0][0],
    delayMs: String(DEFAULT_WAIT_DELAY_MS),
    component: '',
    uri: '',
  }
}

function actionToDraft(action: AccessibilityAutomationActionInput): ActionDraft {
  const base = defaultActionDraft(action.type)
  return {
    type: action.type,
    durationMs: action.durationMs === undefined ? base.durationMs : String(action.durationMs),
    direction: action.direction ?? base.direction,
    keyCode: action.type === 'key' ? keyCodeToDraftName(action.keyCode) : base.keyCode,
    delayMs: action.delayMs === undefined ? base.delayMs : String(action.delayMs),
    component: action.component ?? '',
    uri: action.uri ?? '',
  }
}

function selectorToDraft(selector: AccessibilityAutomationSelectorInput): SelectorDraft {
  return {
    text: selector.text ?? '',
    textContains: selector.textContains ?? '',
    textStartsWith: selector.textStartsWith ?? '',
    textEndsWith: selector.textEndsWith ?? '',
    id: selector.id ?? '',
    desc: selector.desc ?? '',
    descContains: selector.descContains ?? '',
    className: selector.className ?? '',
    clickable: flagToDraft(selector.clickable),
    enabled: flagToDraft(selector.enabled),
    editable: flagToDraft(selector.editable),
    minWidth: sizeToDraft(selector.minWidth),
    minHeight: sizeToDraft(selector.minHeight),
  }
}

function emptySelectorDraft(): SelectorDraft {
  return {
    text: '',
    textContains: '',
    textStartsWith: '',
    textEndsWith: '',
    id: '',
    desc: '',
    descContains: '',
    className: '',
    clickable: FLAG_ANY,
    enabled: FLAG_ANY,
    editable: FLAG_ANY,
    minWidth: '',
    minHeight: '',
  }
}

/** 把一个选择器草稿整项写回：**全部 13 个字段都过一遍**，没有任何字段因为「界面没做」而掉队。 */
function draftToSelector(draft: SelectorDraft, index: number): AccessibilityAutomationSelectorInput {
  return {
    text: emptyToUndefined(draft.text),
    textContains: emptyToUndefined(draft.textContains),
    textStartsWith: emptyToUndefined(draft.textStartsWith),
    textEndsWith: emptyToUndefined(draft.textEndsWith),
    id: emptyToUndefined(draft.id),
    desc: emptyToUndefined(draft.desc),
    descContains: emptyToUndefined(draft.descContains),
    className: emptyToUndefined(draft.className),
    clickable: draftToFlag(draft.clickable, index, 'clickable'),
    enabled: draftToFlag(draft.enabled, index, 'enabled'),
    editable: draftToFlag(draft.editable, index, 'editable'),
    minWidth: draftToSize(draft.minWidth, index, 'minWidth'),
    minHeight: draftToSize(draft.minHeight, index, 'minHeight'),
  }
}

function ruleToDraft(rule: AccessibilityAutomationRuleInput): RuleDraft {
  return {
    id: rule.id,
    enabled: rule.enabled,
    allowActivities: rule.allowActivities.join('\n'),
    denyActivities: rule.denyActivities.join('\n'),
    matchDelayMs: String(rule.matchDelayMs),
    maxActions: String(rule.maxActions),
    actionCoolDownMs: String(rule.actionCoolDownMs),
    resetOn: rule.resetOn,
    selectors: rule.selectors.map(selectorToDraft),
    action: actionToDraft(rule.action),
  }
}

/** 没读到「每应用参数」时新建规则的三个数字：与原生 `AccessibilityAppConfig` 的默认值同源。 */
const CREATE_RULE_NUMBER_DEFAULTS: AutomationRuleDefaults = {
  matchDelayMs: 0,
  maxActions: 1,
  actionCoolDownMs: 3000,
}

/**
 * 新建的默认值：填完标识就能直接保存，不需要用户先去猜哪些字段是必填。
 *
 * 三个数字优先用「每应用参数」读到的初值（`defaults`）；调用方没读到参数时省略它，
 * 这里回落到原生默认值——那是表单初值的回落，不是「把没读到的读数当真相显示」。
 */
function createRuleDraft(defaults?: AutomationRuleDefaults): RuleDraft {
  const numbers = defaults ?? CREATE_RULE_NUMBER_DEFAULTS
  return {
    id: '',
    enabled: true,
    allowActivities: '',
    denyActivities: '',
    matchDelayMs: String(numbers.matchDelayMs),
    maxActions: String(numbers.maxActions),
    actionCoolDownMs: String(numbers.actionCoolDownMs),
    resetOn: 'activity',
    selectors: [emptySelectorDraft()],
    action: defaultActionDraft('click'),
  }
}

/** 名单文本：每行一个，去掉空行与首尾空白。空串与「不限制」同义。 */
function parseActivityPatterns(value: string): string[] {
  return value
    .split('\n')
    .map(line => line.trim())
    .filter(line => line !== '')
}

function draftToAction(draft: ActionDraft): AccessibilityAutomationActionInput {
  switch (draft.type) {
    case 'swipe':
      return {
        type: 'swipe',
        durationMs: parseBoundedInteger(draft.durationMs, t('滑动时长'), AUTOMATION_RULE_LIMITS.swipeDurationMs),
        direction: draft.direction,
      }
    case 'key':
      return { type: 'key', keyCode: parseKeyCode(draft.keyCode) }
    case 'wait':
      return { type: 'wait', delayMs: parseBoundedInteger(draft.delayMs, t('等待时长'), AUTOMATION_RULE_LIMITS.waitDelayMs) }
    case 'launch': {
      const component = emptyToUndefined(draft.component)
      const uri = emptyToUndefined(draft.uri)
      if (component === undefined && uri === undefined) {
        throw new Error(t('启动动作至少要给组件或链接其中之一'))
      }
      if (uri !== undefined && !/^(https?:\/\/|market:\/\/)/u.test(uri)) {
        throw new Error(t('启动链接只支持 http://、https://、market:// 开头'))
      }
      return { type: 'launch', component, uri }
    }
    default:
      // click / clickCenter / longClick / back 只带类型：原生对多余字段是整体拒收。
      return { type: draft.type }
  }
}

/**
 * 把整份草稿装回一条规则：**整条写回**，一个字段都不打折。
 *
 * 写入方式（create / update）由调用方决定，这里只负责内容：把「这条规则是什么」与
 * 「拿它去干什么」分开，免得 update 模式下有人顺手把 id 改成新的。
 */
function draftToRule(draft: RuleDraft, packageName: string): AccessibilityAutomationRuleInput {
  const id = draft.id.trim()
  if (id === '') throw new Error(t('规则标识不能为空'))
  if (draft.selectors.length === 0) throw new Error(t('规则至少要有一个选择器'))
  return {
    id,
    // 包名固定为当前查看的应用：表单里不给改，写入请求也不带别处来的值。
    packageName,
    enabled: draft.enabled,
    allowActivities: parseActivityPatterns(draft.allowActivities),
    denyActivities: parseActivityPatterns(draft.denyActivities),
    matchDelayMs: parseBoundedInteger(draft.matchDelayMs, t('匹配延迟'), AUTOMATION_RULE_LIMITS.matchDelayMs),
    maxActions: parseBoundedInteger(draft.maxActions, t('动作上限'), AUTOMATION_RULE_LIMITS.maxActions),
    actionCoolDownMs: parseBoundedInteger(draft.actionCoolDownMs, t('动作冷却'), AUTOMATION_RULE_LIMITS.actionCoolDownMs),
    resetOn: draft.resetOn,
    selectors: draft.selectors.map(draftToSelector),
    action: draftToAction(draft.action),
  }
}

/** 折叠区里已经填了东西的冷门字段个数：收起来不等于丢掉，得让人看得出来。 */
function filledColdFieldCount(selector: SelectorDraft): number {
  let count = 0
  for (const field of SELECTOR_COLD_TEXT_FIELDS) {
    if (selector[field].trim() !== '') count += 1
  }
  for (const field of SELECTOR_FLAG_FIELDS) {
    if (selector[field] !== FLAG_ANY) count += 1
  }
  for (const field of SELECTOR_SIZE_FIELDS) {
    if (selector[field].trim() !== '') count += 1
  }
  return count
}

/** 按字段名写回一格选择器草稿；字段名由 [keyof SelectorDraft] 收口，敲错名字过不了 tsc。 */
function withSelectorField(draft: SelectorDraft, field: keyof SelectorDraft, value: string): SelectorDraft {
  return { ...draft, [field]: value }
}

/** 数字输入框的公共部分：范围提示与 min/max 都取自 [AUTOMATION_RULE_LIMITS]。 */
function NumberField({ label, value, range, disabled, onChange }: {
  label: string
  value: string
  range?: { readonly min: number; readonly max: number }
  disabled: boolean
  onChange: (next: string) => void
}) {
  return (
    <label className="field automation-rule-field">
      <span>{label}</span>
      <input
        type="number"
        inputMode="numeric"
        step={1}
        min={range?.min}
        max={range?.max}
        aria-label={label}
        value={value}
        disabled={disabled}
        onChange={event => onChange(event.target.value)}
      />
      {range !== undefined && (
        <small className="automation-rule-hint">{t('范围 {0} 到 {1}', range.min, range.max)}</small>
      )}
    </label>
  )
}

function SelectorFields({ index, draft, disabled, onChange }: {
  index: number
  draft: SelectorDraft
  disabled: boolean
  onChange: (field: keyof SelectorDraft, value: string) => void
}) {
  const filled = filledColdFieldCount(draft)
  return (
    <div className="automation-rule-selector-body">
      <div className="automation-rule-grid">
        {SELECTOR_HOT_TEXT_FIELDS.map(field => (
          <label key={field} className="field automation-rule-field">
            <span>{t(SELECTOR_FIELD_LABELS[field])}</span>
            <input
              type="text"
              spellCheck={false}
              autoComplete="off"
              maxLength={AUTOMATION_RULE_LIMITS.maxStringChars}
              aria-label={selectorFieldLabel(index, field)}
              value={draft[field]}
              disabled={disabled}
              onChange={event => onChange(field, event.target.value)}
            />
          </label>
        ))}
      </div>
      {/*
        冷门字段用 <details> 收起：收起只影响视觉，输入框与取值一直在表单里，
        提交时照样按读回值写回去——这正是「编辑不抹掉原有选择器」的关键一环。
      */}
      <details className="automation-rule-more">
        <summary>
          {filled === 0 ? t('更多条件') : t('更多条件（已填 {0} 项）', filled)}
        </summary>
        <div className="automation-rule-grid">
          {SELECTOR_COLD_TEXT_FIELDS.map(field => (
            <label key={field} className="field automation-rule-field">
              <span>{t(SELECTOR_FIELD_LABELS[field])}</span>
              <input
                type="text"
                spellCheck={false}
                autoComplete="off"
                maxLength={AUTOMATION_RULE_LIMITS.maxStringChars}
                aria-label={selectorFieldLabel(index, field)}
                value={draft[field]}
                disabled={disabled}
                onChange={event => onChange(field, event.target.value)}
              />
            </label>
          ))}
          {SELECTOR_FLAG_FIELDS.map(field => (
            <label key={field} className="field automation-rule-field">
              <span>{t(SELECTOR_FIELD_LABELS[field])}</span>
              <select
                aria-label={selectorFieldLabel(index, field)}
                value={draft[field]}
                disabled={disabled}
                onChange={event => onChange(field, event.target.value)}
              >
                <option value={FLAG_ANY}>{t('不限')}</option>
                <option value={FLAG_TRUE}>{t('是')}</option>
                <option value={FLAG_FALSE}>{t('否')}</option>
              </select>
            </label>
          ))}
          {SELECTOR_SIZE_FIELDS.map(field => (
            <NumberField
              key={field}
              label={selectorFieldLabel(index, field)}
              value={draft[field]}
              disabled={disabled}
              onChange={next => onChange(field, next)}
            />
          ))}
        </div>
        <p className="automation-rule-hint">
          {t('「不限」与「否」不是一回事：填「否」表示只匹配不具备该状态的节点。')}
        </p>
      </details>
    </div>
  )
}

function ActionFields({ draft, disabled, onChange }: {
  draft: ActionDraft
  disabled: boolean
  onChange: (patch: Partial<ActionDraft>) => void
}) {
  switch (draft.type) {
    case 'swipe':
      return (
        <div className="automation-rule-grid">
          <NumberField
            label={t('滑动时长（毫秒）')}
            value={draft.durationMs}
            range={AUTOMATION_RULE_LIMITS.swipeDurationMs}
            disabled={disabled}
            onChange={next => onChange({ durationMs: next })}
          />
          <label className="field automation-rule-field">
            <span>{t('滑动方向')}</span>
            <select
              aria-label={t('滑动方向')}
              value={draft.direction}
              disabled={disabled}
              onChange={event => onChange({ direction: event.target.value as AccessibilityAutomationSwipeDirection })}
            >
              {ACCESSIBILITY_AUTOMATION_SWIPE_DIRECTIONS.map(direction => (
                <option key={direction} value={direction}>{t(SWIPE_DIRECTION_LABELS[direction])}</option>
              ))}
            </select>
          </label>
        </div>
      )
    case 'key': {
      const known = AUTOMATION_KEY_CODES.some(([name]) => name === draft.keyCode)
      return (
        <label className="field automation-rule-field">
          <span>{t('按键')}</span>
          <select
            aria-label={t('按键')}
            value={draft.keyCode}
            disabled={disabled}
            onChange={event => onChange({ keyCode: event.target.value })}
          >
            {AUTOMATION_KEY_CODES.map(([name, code]) => (
              <option key={name} value={name}>{t('{0}（键值 {1}）', name, code)}</option>
            ))}
            {/*
              允许表外的键值只会在读回时出现。给一个占位选项把它原样带回去，
              总比悄悄换成表里的第一个按键好——那等于用户点一次保存把动作改掉了。
            */}
            {!known && (
              <option value={draft.keyCode}>
                {t('未知键值 {0}', draft.keyCode.slice(UNKNOWN_KEY_PREFIX.length))}
              </option>
            )}
          </select>
        </label>
      )
    }
    case 'wait':
      return (
        <div className="automation-rule-grid">
          <NumberField
            label={t('等待时长（毫秒）')}
            value={draft.delayMs}
            range={AUTOMATION_RULE_LIMITS.waitDelayMs}
            disabled={disabled}
            onChange={next => onChange({ delayMs: next })}
          />
        </div>
      )
    case 'launch':
      return (
        <div className="automation-rule-grid">
          <label className="field automation-rule-field">
            <span>{t('启动组件（包名/类名）')}</span>
            <input
              type="text"
              spellCheck={false}
              autoComplete="off"
              maxLength={AUTOMATION_RULE_LIMITS.maxStringChars}
              aria-label={t('启动组件（包名/类名）')}
              placeholder="com.example.app/.MainActivity"
              value={draft.component}
              disabled={disabled}
              onChange={event => onChange({ component: event.target.value })}
            />
          </label>
          <label className="field automation-rule-field">
            <span>{t('启动链接')}</span>
            <input
              type="text"
              spellCheck={false}
              autoComplete="off"
              maxLength={AUTOMATION_RULE_LIMITS.maxStringChars}
              aria-label={t('启动链接')}
              placeholder="https://example.com/page"
              value={draft.uri}
              disabled={disabled}
              onChange={event => onChange({ uri: event.target.value })}
            />
          </label>
          <p className="automation-rule-hint">
            {t('组件与链接至少填一个；链接只支持 http://、https://、market:// 开头。')}
          </p>
        </div>
      )
    default:
      return (
        <p className="automation-rule-hint">
          {t('这个动作类型不带参数：执行「{0}」。', t(ACTION_KIND_LABELS[draft.type]))}
        </p>
      )
  }
}

export function AutomationRuleForm({ packageName, rule, mode, defaults, busy, error, onSubmit, onCancel }: AutomationRuleFormProps) {
  /**
   * 草稿只在挂载时建立一次：新建从「每应用参数」读到的初值（没读到就是原生默认值）起手，
   * 编辑一律从读回的整条规则起手——**编辑忽略 `defaults`**：参数读数与这条已经存在的规则
   * 之间没有任何关系，拿它覆盖编辑中的规则就是改错东西。
   *
   * 不跟着 props 走是刻意的——父组件每次重渲染都会传一个新对象，跟着走就会把用户
   * 刚敲进去的内容冲掉。换一条规则编辑时父组件会换 key 重新挂载，那时才该重建。
   */
  const [draft, setDraft] = useState<RuleDraft>(() => (
    mode === 'update' && rule !== null ? ruleToDraft(rule) : createRuleDraft(defaults)
  ))
  /** 表单自己的预检结果；与 [error]（桥回来的原因）分开显示，互不覆盖。 */
  const [localError, setLocalError] = useState<string | null>(null)

  const patch = (update: Partial<RuleDraft>): void => setDraft(current => ({ ...current, ...update }))

  const setSelectorField = (index: number, field: keyof SelectorDraft, value: string): void => {
    setDraft(current => ({
      ...current,
      selectors: current.selectors.map((selector, position) => (
        position === index ? withSelectorField(selector, field, value) : selector
      )),
    }))
  }

  const addSelector = (): void => {
    setDraft(current => current.selectors.length >= AUTOMATION_RULE_LIMITS.maxSelectors
      ? current
      : { ...current, selectors: [...current.selectors, emptySelectorDraft()] })
  }

  const removeSelector = (index: number): void => {
    // 至少留一个：0 个选择器的规则原生必然拒收，界面不留一个注定失败的中间态。
    setDraft(current => current.selectors.length <= 1
      ? current
      : { ...current, selectors: current.selectors.filter((_, position) => position !== index) })
  }

  /**
   * 切换动作类型：整组参数按新类型的默认值重建。
   *
   * 不是「把用不上的字段藏起来」——那样切回来时上一种类型的旧值会复活，而提交时它又确实会被带上。
   * 这里直接重建，切走就等于丢掉。
   */
  const changeActionKind = (type: AccessibilityAutomationActionKind): void => {
    setDraft(current => ({ ...current, action: defaultActionDraft(type) }))
  }

  const submit = (): void => {
    try {
      const next = draftToRule(draft, packageName)
      setLocalError(null)
      onSubmit(next)
    } catch (failure) {
      setLocalError(failure instanceof Error ? failure.message : t('规则内容不合法'))
    }
  }

  // 编辑模式却没有读回的原文：绝不打开一张空表单——用户点一次保存就会把原规则整片抹掉。
  if (mode === 'update' && rule === null) {
    return (
      <div className="automation-rule-form" data-testid="automation-rule-form">
        <p className="automation-rule-failure" role="alert" data-testid="automation-rule-form-error">
          <TriangleAlert size={18} />
          <span>{t('没有读回这条规则的内容，编辑器不会打开：直接保存会覆盖掉原有选择器。')}</span>
        </p>
        <div className="automation-rule-actions">
          <button className="button button-secondary" type="button" onClick={onCancel}>{t('关闭')}</button>
        </div>
      </div>
    )
  }

  const idLocked = mode === 'update'
  const canAddSelector = draft.selectors.length < AUTOMATION_RULE_LIMITS.maxSelectors

  return (
    /*
      这里用 div 而不是 form：设置页本身就是一个 <form>，表单不能再套表单——
      套起来时点「保存规则」的提交事件会冒泡到设置页那个表单上，顺手把别的设置也存了。
    */
    <div className="automation-rule-form" data-testid="automation-rule-form">
      <div className="section-title">
        <span className="section-icon"><Plus size={19} /></span>
        <div>
          <h4>{idLocked ? t('编辑规则') : t('新建规则')}</h4>
          <p>{t('保存时会整条替换这条规则，表单里带着读回的全部字段。')}</p>
        </div>
      </div>
      <p className="settings-note">{t('所属应用：{0}（固定，不可在表单里改）', packageName)}</p>

      <fieldset className="automation-rule-group" disabled={busy}>
        <legend>{t('基本项')}</legend>
        <div className="automation-rule-grid">
          <label className="field automation-rule-field">
            <span>{t('规则标识')}</span>
            <input
              type="text"
              spellCheck={false}
              autoComplete="off"
              maxLength={64}
              aria-label={t('规则标识')}
              value={draft.id}
              readOnly={idLocked}
              onChange={event => patch({ id: event.target.value })}
            />
            <small className="automation-rule-hint">
              {idLocked
                ? t('编辑时不能改标识：换了标识等于换一条规则。')
                : t('只能用字母、数字、点、下划线和短横线。')}
            </small>
          </label>
          <NumberField
            label={t('匹配延迟（毫秒）')}
            value={draft.matchDelayMs}
            range={AUTOMATION_RULE_LIMITS.matchDelayMs}
            disabled={busy}
            onChange={next => patch({ matchDelayMs: next })}
          />
          <NumberField
            label={t('动作上限')}
            value={draft.maxActions}
            range={AUTOMATION_RULE_LIMITS.maxActions}
            disabled={busy}
            onChange={next => patch({ maxActions: next })}
          />
          <NumberField
            label={t('动作冷却（毫秒）')}
            value={draft.actionCoolDownMs}
            range={AUTOMATION_RULE_LIMITS.actionCoolDownMs}
            disabled={busy}
            onChange={next => patch({ actionCoolDownMs: next })}
          />
          <label className="field automation-rule-field">
            <span>{t('重置时机')}</span>
            <select
              aria-label={t('重置时机')}
              value={draft.resetOn}
              disabled={busy}
              onChange={event => patch({ resetOn: event.target.value as AccessibilityAutomationResetOn })}
            >
              {ACCESSIBILITY_AUTOMATION_RESET_ON_VALUES.map(value => (
                <option key={value} value={value}>{t(RESET_ON_LABELS[value])}</option>
              ))}
            </select>
            <small className="automation-rule-hint">{t('动作次数上限的计数何时归零。')}</small>
          </label>
        </div>
        <label className="toggle-row">
          <span>
            <strong>{t('启用这条规则')}</strong>
            <small>{t('停用不会删掉规则，只是不再执行。')}</small>
          </span>
          <input
            type="checkbox"
            role="switch"
            aria-label={t('启用这条规则')}
            checked={draft.enabled}
            disabled={busy}
            onChange={event => patch({ enabled: event.target.checked })}
          />
        </label>
        <label className="field automation-rule-field">
          <span>{t('Activity 白名单（每行一个正则）')}</span>
          <textarea
            rows={2}
            spellCheck={false}
            aria-label={t('Activity 白名单（每行一个正则）')}
            placeholder=".*ReaderActivity"
            value={draft.allowActivities}
            disabled={busy}
            onChange={event => patch({ allowActivities: event.target.value })}
          />
          <small className="automation-rule-hint">{t('留空表示不限制；填了就只在匹配的页面上执行。')}</small>
        </label>
        <label className="field automation-rule-field">
          <span>{t('Activity 黑名单（每行一个正则）')}</span>
          <textarea
            rows={2}
            spellCheck={false}
            aria-label={t('Activity 黑名单（每行一个正则）')}
            placeholder=".*PayActivity"
            value={draft.denyActivities}
            disabled={busy}
            onChange={event => patch({ denyActivities: event.target.value })}
          />
          <small className="automation-rule-hint">{t('留空表示不限制；匹配到的页面一律不执行。')}</small>
        </label>
      </fieldset>

      <fieldset className="automation-rule-group" disabled={busy}>
        <legend>{t('选择器（多个之间必须同时满足）')}</legend>
        <p className="automation-rule-hint">
          {t('最多 {0} 个选择器；每个选择器至少要填一个条件。', AUTOMATION_RULE_LIMITS.maxSelectors)}
        </p>
        {draft.selectors.map((selector, index) => (
          // 序号参与 key：删掉中间一项时按位置重建，草稿不会错位到别的选择器上。
          <fieldset key={`selector-${index}`} className="automation-rule-selector">
            <legend>{t('选择器 {0}', index + 1)}</legend>
            <SelectorFields
              index={index}
              draft={selector}
              disabled={busy}
              onChange={(field, value) => setSelectorField(index, field, value)}
            />
            <div className="automation-rule-actions">
              <button
                className="button button-secondary compact-button"
                type="button"
                aria-label={t('删除选择器 {0}', index + 1)}
                disabled={busy || draft.selectors.length <= 1}
                onClick={() => removeSelector(index)}
              >
                <Trash2 size={18} />{t('删除选择器')}
              </button>
            </div>
          </fieldset>
        ))}
        <div className="automation-rule-actions">
          <button
            className="button button-secondary"
            type="button"
            disabled={busy || !canAddSelector}
            onClick={addSelector}
          >
            <Plus size={18} />{t('添加选择器')}
          </button>
          {!canAddSelector && (
            <span className="automation-rule-hint">{t('已达上限 {0} 个。', AUTOMATION_RULE_LIMITS.maxSelectors)}</span>
          )}
        </div>
      </fieldset>

      <fieldset className="automation-rule-group" disabled={busy}>
        <legend>{t('动作')}</legend>
        <div className="automation-rule-grid">
          <label className="field automation-rule-field">
            <span>{t('动作类型')}</span>
            <select
              aria-label={t('动作类型')}
              value={draft.action.type}
              disabled={busy}
              onChange={event => changeActionKind(event.target.value as AccessibilityAutomationActionKind)}
            >
              {ACCESSIBILITY_AUTOMATION_ACTION_KINDS.map(kind => (
                <option key={kind} value={kind}>{t(ACTION_KIND_LABELS[kind])}</option>
              ))}
            </select>
            <small className="automation-rule-hint">{t('切换类型会清掉上一种类型的参数。')}</small>
          </label>
        </div>
        <ActionFields
          draft={draft.action}
          disabled={busy}
          onChange={update => patch({ action: { ...draft.action, ...update } })}
        />
      </fieldset>

      {(localError !== null || error !== null) && (
        <p className="automation-rule-failure" role="alert" data-testid="automation-rule-form-error">
          <TriangleAlert size={18} />
          <span>{localError ?? error}</span>
        </p>
      )}

      <div className="automation-rule-actions">
        <button className="button button-primary" type="button" onClick={submit} disabled={busy}>
          {busy ? <Loader2 className="spin" size={18} /> : <Plus size={18} />}{t('保存规则')}
        </button>
        <button className="button button-secondary" type="button" disabled={busy} onClick={onCancel}>
          {t('取消')}
        </button>
      </div>
    </div>
  )
}
