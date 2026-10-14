/**
 * 输入法（软键盘）遮挡的高度。
 *
 * 需求：打字时要能看见自己输的那一行，也就是输入区最下端要贴在输入法框上沿。
 * 做法是把这个高度写成 CSS 变量 `--keyboard-inset`，由样式把整个界面往上让出这么多。
 *
 * 为什么不直接用 `100dvh`：`dvh` 跟的是布局视口，安卓 WebView 在软键盘弹起时**不保证**
 * 收缩布局视口（`adjustResize` 在部分机型/版本上只改视觉视口）。用 `visualViewport`
 * 读数可以两头都对：布局视口已经收缩时 `innerHeight` 一起变小，算出的遮挡高度就是 0，
 * 不会重复让位；没收缩时它才给出真正需要让出的高度。
 */

/**
 * 小于这个高度的「遮挡」当成噪声。
 *
 * 手机上输入法框远高于 80px，而这个量级的差值常来自地址栏收放、滚动时的视口抖动；
 * 为它们改变整页布局只会让界面在滚动时上下跳。
 */
export const MIN_KEYBOARD_INSET_PX = 80

export interface ViewportMetrics {
  /** 布局视口高度（`window.innerHeight`）。 */
  innerHeight: number
  /** 视觉视口高度（`visualViewport.height`）。 */
  visualHeight: number
  /** 视觉视口相对布局视口的纵向偏移（`visualViewport.offsetTop`）。 */
  visualOffsetTop: number
}

/** 视觉视口里够用的一小部分能力：真机上由 `window.visualViewport` 满足。 */
export interface KeyboardViewport {
  readonly height: number
  readonly offsetTop: number
  addEventListener(type: 'resize' | 'scroll', listener: () => void): void
  removeEventListener(type: 'resize' | 'scroll', listener: () => void): void
}

/**
 * 算出输入法遮挡的高度：布局视口高度减去视觉视口「底边」（高度 + 偏移）。
 *
 * 结果小于 [MIN_KEYBOARD_INSET_PX] 时返回 0——包括负数（视觉视口比布局视口还高时，
 * 比如浏览器 UI 收起）与噪声。
 */
export function keyboardInsetPx(metrics: ViewportMetrics): number {
  const inset = Math.round(metrics.innerHeight - (metrics.visualHeight + metrics.visualOffsetTop))
  return inset < MIN_KEYBOARD_INSET_PX ? 0 : inset
}

/**
 * 订阅视口变化并在每次变化后回报遮挡高度，立即回报一次当前值。
 *
 * @param view 视觉视口；真机缺失（老 WebView、jsdom）时只回报一次 0，不订阅。
 * @param apply 拿到遮挡高度后的落地动作（真机上是写 CSS 变量）。
 * @param readInnerHeight 读布局视口高度（传函数而不是数字，避免订阅时锁死旧值）。
 * @returns 取消订阅；调用后 `apply` 不会再被触发。
 */
export function observeKeyboardInset(
  view: KeyboardViewport | undefined,
  apply: (inset: number) => void,
  readInnerHeight: () => number,
): () => void {
  if (view === undefined) {
    apply(0)
    return () => undefined
  }
  const update = (): void => {
    apply(keyboardInsetPx({ innerHeight: readInnerHeight(), visualHeight: view.height, visualOffsetTop: view.offsetTop }))
  }
  update()
  view.addEventListener('resize', update)
  view.addEventListener('scroll', update)
  return () => {
    view.removeEventListener('resize', update)
    view.removeEventListener('scroll', update)
  }
}
