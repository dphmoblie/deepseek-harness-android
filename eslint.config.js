import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  {
    ignores: [
      'dist',
      'packages/**', // 独立包的 lib/src 由其自身 tsconfig/typecheck 覆盖
      'android/app/src/main/assets/public',
      'android/**/build',
      // 运行时 profile 构建产出的 vendored 依赖补丁副本：已被 .gitignore 忽略，
      // 不是本仓库源码。它们不在任一 tsconfig 的 project 内，lint 会退化成
      // 354 条 "file was not found in any of the provided project(s)" 解析错误，
      // 进而让 CI 的 Lint 步骤失败 —— 故在此显式排除。
      'scripts/runtime-profile/.patch-source',
      'scripts/runtime-profile/.pnpm-pi-ai-patch',
      // 本地排查与素材生成的临时产物目录：已被 .gitignore 忽略，不是本仓库源码。
      // 其中的解包依赖同样不在任何 tsconfig 的 project 内，会让本地 lint 直接报错。
      'output',
      '*.config.js',
      '*.config.d.ts',
      '*.tsbuildinfo',
    ],
  },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommendedTypeChecked],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
      parserOptions: {
        project: ['./tsconfig.app.json', './tsconfig.node.json'],
        tsconfigRootDir: import.meta.dirname,
      },
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      '@typescript-eslint/consistent-type-imports': 'error',
      '@typescript-eslint/no-floating-promises': 'error',
    },
  },
)
