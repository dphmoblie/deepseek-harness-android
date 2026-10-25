import { useEffect, useRef } from 'react'
import { t } from '../i18n'

/**
 * 密钥掩码：只留头尾几位，让用户确认「确实输进去了」，又不把整串密钥摆在屏幕上。
 *
 * 为什么需要它：密钥落盘后原生侧不回显，输入框清空之后用户没有任何证据能证明自己填过；
 * 而「填了却看不到」正是他们重填三遍、最后放弃的直接原因。掩码同时满足两头 ——
 * 头尾能对上自己手里那份密钥，中间那截仍然不落到屏幕上。
 *
 * 长度分档是为了不把短密钥整个露出来：8 位以下连头都不留。
 */
// eslint-disable-next-line react-refresh/only-export-components -- 掩码规则要单独测（ApiKeyInput.test.tsx），和组件放在一起最贴近它服务的输入框。
export function maskApiKey(value: string): string {
  if (value === '') return ''
  if (value.length >= 16) return `${value.slice(0, 5)}****${value.slice(-4)}`
  if (value.length >= 8) return `${value.slice(0, 3)}****${value.slice(-2)}`
  return `****${value.slice(-2)}`
}

/**
 * 密钥输入框：**非受控**，值由 DOM 自己持有，React 只在需要时回写。
 *
 * Android 的输入法在组合态（拼音候选、英文联想、粘贴补全都算）里会持续改写
 * `input.value`；受控输入每敲一个字符都要把 value 再写回去一遍，组合被打断时
 * WebView 会把刚输入的字符抹掉——用户看到的就是「API Key 刚输入就被清除」。
 * 首次配置页（src/components/Onboarding.tsx）早就踩过同一个坑并改成非受控，这里
 * 把同一套做法收成一个组件，设置页里所有密码框共用（模型密钥、白名单验证密码）。
 *
 * 回写规则：
 * - 外部值变了且与 DOM 不一致时同步（清除密钥、切换供应商都会走到）；
 * - 但用户正在这一格里编辑时一概不动它，空值也一样 —— 保存完成的清空正好也发生在
 *   用户还在打字的时候，那正是「输入的密钥凭空消失」最刺眼的一种；
 * - 输入框下面显示掩码（`sk-5e****7777`），这是「输入被记住了」的唯一可视证据。
 *   `type="password"` 只渲染圆点，把掩码写进框里等于没写，所以另起一行。
 *   掩码带 `aria-hidden`：它只是给眼睛看的确认，不该混进字段的可读名。
 */
export function ApiKeyInput({
  value, onValueChange, placeholder, maxLength = 200, ariaLabel,
}: {
  value: string
  onValueChange: (value: string) => void
  placeholder?: string
  maxLength?: number
  ariaLabel?: string
}) {
  const input = useRef<HTMLInputElement | null>(null)

  useEffect(() => {
    const element = input.current
    if (element === null || element.value === value) return
    // 有焦点就一个字都不回写：任何回写都可能打断输入法组合，或抹掉刚打的字符。
    if (document.activeElement === element) return
    element.value = value
  }, [value])

  return (
    <>
      <input
        ref={input}
        type="password"
        autoComplete="new-password"
        spellCheck={false}
        maxLength={maxLength}
        defaultValue={value}
        placeholder={placeholder}
        aria-label={ariaLabel}
        onChange={event => onValueChange(event.currentTarget.value)}
        /*
         * 安卓输入法的「粘贴」不一定派发 change：有的实现只改 value 并触发一次 input，
         * 有的连 input 都不发。只挂 onChange 就会出现「粘完值还在 DOM 里、state 还是空」
         * ——保存时提示未提供密钥，退出重进才"识别到"。
         * 这里三条都补上：input 直接同步；paste 延迟到 WebView 写完 value 再读一次；
         * 组合输入结束时也同步一次。
         */
        onInput={event => onValueChange(event.currentTarget.value)}
        onPaste={() => {
          window.setTimeout(() => {
            const element = input.current
            if (element !== null) onValueChange(element.value)
          }, 0)
        }}
        onCompositionEnd={event => onValueChange(event.currentTarget.value)}
      />
      {value !== '' && (
        <small className="api-key-mask" aria-hidden="true">{t('已输入 {0}', maskApiKey(value))}</small>
      )}
    </>
  )
}
