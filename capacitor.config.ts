import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'io.deepseekharness.mobile',
  appName: 'DeepSeek Harness',
  webDir: 'dist',
  server: {
    androidScheme: 'https',
    cleartext: false,
  },
  android: {
    allowMixedContent: false,
    backgroundColor: '#111315',
    /*
     * 必须保持 false（Capacitor 的默认值）：开启后 `CapacitorWebView.onCreateInputConnection`
     * 会改用 `BaseInputConnection`，那是系统提供的最小实现，**不参与剪贴板事务**——
     * 中文输入法（以及 Gboard 等）自带的「剪贴板 / 最近复制」按钮走的是 InputConnection 的
     * 粘贴动作，于是点上去毫无反应；普通打字因为走的是另一条路径，看起来一切正常，
     * 症状就表现成「输入法剪切板不能用、但能打字」。
     *
     * 当初这个字段是脚手架带过来的，没有任何功能依赖它（android/ 侧零引用）。改成 true 之前
     * 必须先有真机证据：中文输入、粘贴、联想词、长按选择都要在同一台设备上验过。
     */
    captureInput: false,
    webContentsDebuggingEnabled: false,
  },
}

export default config
