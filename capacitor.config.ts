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
    captureInput: true,
    webContentsDebuggingEnabled: false,
  },
}

export default config
