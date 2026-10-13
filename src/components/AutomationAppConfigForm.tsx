import { useState } from 'react'
import { Loader2, RefreshCw, RotateCcw, Save, TriangleAlert } from 'lucide-react'
import { t } from '../i18n'
import { AUTOMATION_RULE_LIMITS } from '../platform/types'
import type {
  AccessibilityAutomationAppConfig,
  AccessibilityAutomationAppConfigDetail,
} from '../platform/types'
import './AutomationAppConfigForm.css'

/**
 * 「每应用自动化参数」的写入表单：暂停开关 + 新建规则的三个默认值。
 *
 * 这里有两条容易写错、写错了会直接改坏真机数据的地方：
 *
 * 1. **读不到读数时绝不显示输入框，也绝不用默认值顶上。** 原生侧的 `set` 是整条替换
 *    （四个字段全给，缺一个就拒收），所以界面只要在没读到参数时拿一份「没暂停、上限 1」
 *    顶上去，用户点一次「保存参数」就是用界面编的数字覆盖盘上的真数据。读失败时这里
 *    只说明原因并给一个「重试」，一个输入框都不出现。
 * 2. **草稿只在挂载时按读数建立一次，之后不跟 props 走。** 父组件每次重渲染都会传进来
 *    新的对象，跟着走会把用户刚敲进去的内容冲掉。换应用时由父组件换 `key` 重新挂载，
 *    那时才该重建草稿；第一次拿到读数之前草稿是空的，拿到之后补建一次（这段时间界面
 *    上没有任何输入框，补建不会冲掉谁敲的东西）。
 *
 * 与「草稿」相对的是「当前生效」那行读数：它**始终直接来自读数**，不经过草稿。保存成功后
 * 原生返回的是「写完之后」的整份读数，界面拿它刷新这一行——用户因此看得见盘上真正生效的
 * 是什么，而不是只看见自己刚敲的那几个数字。
 */

/** 原生 `AccessibilityAppConfig` 的默认值；与规则表单新建分支的初值同源，改一处要一起改。 */
const APP_CONFIG_DEFAULTS = {
  paused: false,
  defaultMaxActions: 1,
  defaultActionCoolDownMs: 3000,
  defaultMatchDelayMs: 0,
} as const

/**
 * 表单草稿：数字一律以**字符串**保存。
 *
 * 用数字存会在用户清空输入框的那一刻变成 `0` 或 `NaN`——「还没填」与「填了 0」是两件事，
 * 前者应该被预检挡下并说明范围，后者在冷却时间与匹配延迟上是合法值。
 */
interface AppConfigDraft {
  paused: boolean
  defaultMaxActions: string
  defaultActionCoolDownMs: string
  defaultMatchDelayMs: string
}

function configToDraft(config: AccessibilityAutomationAppConfig): AppConfigDraft {
  return {
    paused: config.paused,
    defaultMaxActions: String(config.defaultMaxActions),
    defaultActionCoolDownMs: String(config.defaultActionCoolDownMs),
    defaultMatchDelayMs: String(config.defaultMatchDelayMs),
  }
}

/** 有区间的整数：区间与原生 `AutomationRuleLimits` 同源，越界在提交前就报出来。 */
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

/** 数字输入框：范围提示与 min/max 都取自 [AUTOMATION_RULE_LIMITS]，不在这里另立数字。 */
function ConfigNumberField({ label, value, range, disabled, onChange }: {
  label: string
  value: string
  range: { readonly min: number; readonly max: number }
  disabled: boolean
  onChange: (next: string) => void
}) {
  return (
    <label className="field automation-app-config-field">
      <span>{label}</span>
      <input
        type="number"
        inputMode="numeric"
        step={1}
        min={range.min}
        max={range.max}
        aria-label={label}
        value={value}
        disabled={disabled}
        onChange={event => onChange(event.target.value)}
      />
      <small className="automation-app-config-hint">{t('范围 {0} 到 {1}', range.min, range.max)}</small>
    </label>
  )
}

interface AutomationAppConfigFormProps {
  /** 当前查看的（也就是写入目标）包名；表单里不允许改，改了等于写到别的应用上。 */
  packageName: string
  /** 参数读数；`null` 一律表示「还没读到」，与「读到了」必须分开渲染。 */
  detail: AccessibilityAutomationAppConfigDetail | null
  /** 读取状态；与 `detail` 合起来决定渲染哪一种读数状态。 */
  status: 'idle' | 'loading' | 'failed'
  /** 本次参数写入是否在途。 */
  busy: boolean
  /** 「规则」那一块的写入口是否有在途操作（由父组件判定并传进来）。 */
  locked: boolean
  /** 桥回来的原因（读或写）；原样展示，不吞也不改写。 */
  error: string | null
  /** 提交**四个字段整条**参数；本地预检只挡明显不合法的一档。 */
  onSave: (config: AccessibilityAutomationAppConfig) => void
  /** 重新读一次参数（只在读取失败时出现）。 */
  onReload: () => void
}

export function AutomationAppConfigForm({
  packageName,
  detail,
  status,
  busy,
  locked,
  error,
  onSave,
  onReload,
}: AutomationAppConfigFormProps) {
  /**
   * 草稿：`null` 表示「还没有任何可用读数」，此时一个输入框都不渲染。
   *
   * 建立一次就不跟 props 走（见文件头第 2 条）。第一次拿到读数时在这里补建：
   * 那次补建发生在渲染期，React 会立刻用新状态重渲染，且此刻界面上还没有任何可输入的东西，
   * 不存在「冲掉用户刚敲的内容」的可能。
   */
  const [draft, setDraft] = useState<AppConfigDraft | null>(() => (
    detail === null ? null : configToDraft(detail.config)
  ))
  /** 本地预检的结果；与 [error]（桥回来的原因）分开显示，互不覆盖。 */
  const [localError, setLocalError] = useState<string | null>(null)

  if (draft === null && detail !== null) setDraft(configToDraft(detail.config))

  const patch = (update: Partial<AppConfigDraft>): void => {
    setDraft(current => (current === null ? current : { ...current, ...update }))
  }

  /** 读失败：只说原因 + 重试，**一个输入框都不给**。 */
  if (detail === null && status === 'failed') {
    return (
      <div className="automation-app-config" data-testid="automation-app-config">
        <p className="automation-app-config-failure" role="alert" data-testid="automation-app-config-read-failed">
          <TriangleAlert size={18} />
          <span>{t('参数读取失败，请稍后重试')}</span>
        </p>
        {error !== null && (
          <p className="settings-note" role="alert" data-testid="automation-app-config-read-error">{error}</p>
        )}
        <p className="settings-note">
          {t('读数没回来之前这里不会显示任何输入框，也不会拿默认值顶上：那会是一份界面编出来的数字，点一次保存就写进真机了。')}
        </p>
        <div className="automation-app-config-actions">
          <button className="button button-secondary" type="button"
            data-testid="automation-app-config-retry" onClick={onReload} disabled={busy}>
            {busy ? <Loader2 className="spin" size={18} /> : <RefreshCw size={18} />}{t('重试')}
          </button>
        </div>
      </div>
    )
  }

  /**
   * 还没读到读数（含刚挂载、读取在途）。
   *
   * 父组件在选包时立刻发起读取，所以 `idle` 只会出现在「读取刚发出、还没回来」这一刻；
   * 无论哪种都按「还没读到」渲染，绝不显示输入框。
   */
  if (detail === null || draft === null) {
    return (
      <div className="automation-app-config" data-testid="automation-app-config">
        <p className="settings-note" role="status" data-testid="automation-app-config-loading">
          {t('正在读取这个应用的参数')}
        </p>
      </div>
    )
  }

  /** 保存入口是否可用：写入在途、规则那块锁着、或这个应用不在白名单里时一律禁用。 */
  const saveDisabled = busy || locked || !detail.allowed

  const submit = (): void => {
    try {
      /*
        整条四个字段一起提交，哪怕用户只拨了暂停开关。

        原生侧的写入是**整条替换**：不提交的字段不会保持原样，而是被原生按自己的默认值写下去。
        所以「只发改动的那一个字段」在这里不是优化，是把用户配好的另外三个数字顺手冲掉。
        本地预检只挡「明显不合法」这一档（空、非整数、越界），够不够、放不放行由原生判定。
      */
      const config: AccessibilityAutomationAppConfig = {
        paused: draft.paused,
        defaultMaxActions: parseBoundedInteger(
          draft.defaultMaxActions, t('默认动作上限'), AUTOMATION_RULE_LIMITS.maxActions,
        ),
        defaultActionCoolDownMs: parseBoundedInteger(
          draft.defaultActionCoolDownMs, t('默认冷却时间'), AUTOMATION_RULE_LIMITS.actionCoolDownMs,
        ),
        defaultMatchDelayMs: parseBoundedInteger(
          draft.defaultMatchDelayMs, t('默认匹配延迟'), AUTOMATION_RULE_LIMITS.matchDelayMs,
        ),
      }
      setLocalError(null)
      onSave(config)
    } catch (failure) {
      // 预检失败时草稿一个字都不动：用户要能改一处再点保存，而不是从头再填一遍。
      setLocalError(failure instanceof Error ? failure.message : t('参数内容不合法'))
    }
  }

  /**
   * 「恢复默认值」**只改表单，不写盘**。
   *
   * 一键写盘等于把「盘上现在是坏的 / 读不出来」这件事顺手抹掉，用户连看都没看清；
   * 这里只把输入框填成原生默认值（1 / 3000 / 0 / 未暂停），要生效必须再点一次「保存参数」。
   */
  const restoreDefaults = (): void => {
    setDraft(configToDraft(APP_CONFIG_DEFAULTS))
    setLocalError(null)
  }

  return (
    <div className="automation-app-config" data-testid="automation-app-config">
      {/*
        损坏的读数：原生把 `config.paused` 按 `true` 返回（fail closed），并且不会自动删掉坏数据。
        界面必须把这件事说出来，而不是安静地显示一份默认值——用户点一次保存就是用这份读数覆盖掉它，
        那是他要知道的决定。输入框照常显示（值取自读数，此时暂停为真）。
      */}
      {detail.corrupt !== null && (
        <p className="automation-app-config-failure" role="alert" data-testid="automation-app-config-corrupt">
          <TriangleAlert size={18} />
          <span>
            {t('这份参数已经读不出来：{0}（受控码 {1}）。原生按「已暂停」处理，也不会自动删掉它；保存一次就会覆盖掉它。',
              detail.corrupt.message, detail.corrupt.code)}
          </span>
        </p>
      )}
      {!detail.allowed && (
        <p className="settings-note" role="status" data-testid="automation-app-config-not-allowed">
          {t('{0} 不在白名单里：保存参数会被原生拒绝。', packageName)}
        </p>
      )}
      {/*
        「当前生效」直接来自读数、不经过草稿：保存后原生返回的是「写完之后」的整份读数，
        界面拿它刷新这一行。写回结果与提交值不一致时（原生做了归一或部分拒绝），
        用户看得见盘上真正生效的是什么，而不是只看见自己刚敲的数字。
      */}
      <p className="settings-note" data-testid="automation-app-config-effective">
        {t('当前生效：{0} · 默认动作上限 {1} · 默认冷却时间 {2} 毫秒 · 默认匹配延迟 {3} 毫秒',
          detail.config.paused ? t('该应用已暂停') : t('该应用未暂停'),
          detail.config.defaultMaxActions,
          detail.config.defaultActionCoolDownMs,
          detail.config.defaultMatchDelayMs)}
      </p>
      <p className="settings-note">
        {t('这三个默认值只用于新建规则表单的初值，不会改动任何一条已有规则。')}
      </p>
      {/*
        暂停开关：只挡规则求值。设备桥的单次动作（AI 明确要求的那一下）不经过规则求值，
        所以这里必须写清楚它拦不住什么——否则用户会以为拨过去就什么都点不动了。
      */}
      <label className="toggle-row">
        <span>
          <strong>{t('暂停这个应用的自动化')}</strong>
          <small>{t('只挡规则自动求值：设备桥的单次动作（AI 明确要求的那一下）照旧走白名单与敏感窗口判定；暂停期间被丢掉的事件会计入运行记录里的「拦截原因」。')}</small>
        </span>
        <input type="checkbox" role="switch" aria-label={t('暂停这个应用的自动化')}
          checked={draft.paused} disabled={busy}
          onChange={event => patch({ paused: event.target.checked })} />
      </label>
      <div className="automation-app-config-grid">
        <ConfigNumberField
          label={t('默认动作上限')}
          value={draft.defaultMaxActions}
          range={AUTOMATION_RULE_LIMITS.maxActions}
          disabled={busy}
          onChange={next => patch({ defaultMaxActions: next })}
        />
        <ConfigNumberField
          label={t('默认冷却时间（毫秒）')}
          value={draft.defaultActionCoolDownMs}
          range={AUTOMATION_RULE_LIMITS.actionCoolDownMs}
          disabled={busy}
          onChange={next => patch({ defaultActionCoolDownMs: next })}
        />
        <ConfigNumberField
          label={t('默认匹配延迟（毫秒）')}
          value={draft.defaultMatchDelayMs}
          range={AUTOMATION_RULE_LIMITS.matchDelayMs}
          disabled={busy}
          onChange={next => patch({ defaultMatchDelayMs: next })}
        />
      </div>
      {localError !== null && (
        <p className="settings-note" role="alert" data-testid="automation-app-config-local-error">{localError}</p>
      )}
      {/*
        桥回来的原因原样显示（不改写、不吞掉），且**草稿不清空**：用户要能改一处再点保存。
      */}
      {error !== null && (
        <p className="settings-note" role="alert" data-testid="automation-app-config-write-error">{error}</p>
      )}
      <div className="automation-app-config-actions">
        <button className="button button-primary" type="button"
          data-testid="automation-app-config-save" onClick={submit} disabled={saveDisabled}>
          {busy ? <Loader2 className="spin" size={18} /> : <Save size={18} />}{t('保存参数')}
        </button>
        <button className="button button-secondary" type="button"
          data-testid="automation-app-config-restore" onClick={restoreDefaults} disabled={busy || locked}>
          <RotateCcw size={18} />{t('恢复默认值')}
        </button>
        <small className="automation-app-config-hint">
          {t('「恢复默认值」只把表单填成原生默认值，不会写盘：要生效还得再点一次「保存参数」。')}
        </small>
      </div>
    </div>
  )
}
