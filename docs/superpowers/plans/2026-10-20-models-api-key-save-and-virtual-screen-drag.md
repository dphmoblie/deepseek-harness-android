# 模型密钥保存与副屏拖动 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 在「模型与密钥」页恢复 API Key 的保存按钮（并让该区域完整跟随主题），并把副屏小窗改成可直接拖动的悬浮窗、控制卡置于页面最上方。

**Architecture:** 保存按钮只做一件事——调用 App 里已有的 `saveSettingsDraft()`（与离开页面自动保存同一条写盘路径），不新增第二条保存逻辑；主题适配是把模型/供应商区域的硬编码颜色换成现有语义令牌。副屏拖动从「长按后才能拖」改成窗口自带一条顶栏拖动条：顶栏由 `VirtualScreenFrame` 持有并直接驱动 `VirtualScreenDragHost`，画面其余区域的触摸继续透传给副屏；窗口高度相应增加顶栏高度。

**Tech Stack:** React 18 + TypeScript + Vite（前端）、Kotlin + Android View/WindowManager（副屏悬浮窗）、vitest + JUnit4（测试）、pnpm + Gradle。

**Spec:** 用户原话：①「配置模型与密钥的 api key 应该有原来的保存按钮，引入时加入主题适配的ui」；②「副屏可以像悬浮窗一样自由拖动位置，启动副屏的卡片位置位于最上方」（原文「嘴上方」按「最上方」理解）。

## Global Constraints

- 提交信息用中文约定式提交（`.agents/rules/git-workflow.md`）；每个任务独立提交。
- 不改动 `AGENTS.md` 已记录的取舍：不恢复自动进入 Harness、不删预启动守卫、不改启动等待参数。
- 设置写盘只有一条路径：`App.saveSettingsDraft()`；按钮不得自己拼载荷、不得绕过 `settingsUpdateFromDraft`。
- 颜色一律用 `src/styles.css` 的语义令牌（`--blue` / `--surface` / `--ink` / `--card-*` / `--control-opacity`），禁止新增 `#rrggbb` 字面量。
- 副屏窗口只能在主线程操作 `WindowManager`；拖动、缩放都通过 `VirtualScreenOverlayMachine` 的 effect 落地，不在 View 里直接 `updateViewLayout`。
- 每个任务结束必须跑：`npx tsc -b`、`npx eslint . --max-warnings 0`、相关 `npx vitest run`；涉及 Kotlin 的加 `android/gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --offline`。

## Review Focus

- **保存按钮与自动保存竞争**：用户点「保存」后立刻返回设置首页，离开页面的自动保存可能再发一次；两次写盘参数必须一致，且不能把已清空的密钥输入重新提交。
- **保存按钮的可用状态**：没有改动时必须禁用；保存中必须显示进行中且禁止连点；保存失败要保留输入并给出可重试的提示。
- **主题下的对比度**：深色模式、非默认强调色、`--control-opacity` 降低时，新按钮与供应商卡片文字仍要能读。
- **拖动与透传的冲突**：顶栏拖动不能吞掉副屏应用自己的顶部手势；从顶栏以外区域起手的滑动仍应透传。
- **拖动/旋转/收起期间的状态**：拖动中途旋转、收起窗口、会话结束时不能留下已释放的窗口参数，也不能抛异常。
- **控制卡置顶后的锁定态**：设置仍在读取、目标应用为空时，置顶的启动按钮必须保持禁用并说明原因。

---

### Task 1: 「模型与密钥」页恢复 API Key 保存按钮

**Files:**
- Modify: `src/App.tsx`（`SettingsScreenProps`、`SettingsScreen` 解构、`credential-actions` 区块、`<SettingsScreen …/>` 挂载）
- Modify: `src/styles.css`（`.credential-actions` 的按钮布局）
- Test: `src/App.settings.test.tsx`（新增「点保存立即写盘」用例）

**Interfaces:**
- Consumes: `App.saveSettingsDraft(options?: { background?: boolean }): Promise<boolean>`（已有，定义在 `src/App.tsx`）
- Produces: `SettingsScreenProps.onSaveCredentials: () => Promise<boolean>`；`SettingsScreen` 内部 `const saveCredentials = () => void onSaveCredentials()`

- [x] **Step 1: 写失败用例**

在 `src/App.settings.test.tsx` 的 `describe('设置页保存语义')` 里追加：

```tsx
it('模型与密钥页的保存按钮立即写盘，不用离开页面', async () => {
  render(<App />)
  await arriveAtSettingsHome()
  await openSettingsPage('模型与密钥')

  fireEvent.change(screen.getByLabelText(/DeepSeek API Key/), { target: { value: 'unit-test-inline-save' } })
  fireEvent.click(screen.getByRole('button', { name: '保存密钥' }))

  await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith(expect.objectContaining({
    providerApiKeys: { deepseek: 'unit-test-inline-save' },
  })))
  // 保存成功后仍停在这一页，输入框没有被禁用（是不是清空由既有草稿规则决定，不在这里钉死）。
  expect(screen.getByLabelText(/DeepSeek API Key/)).toBeEnabled()
  expect(screen.getByRole('heading', { name: '模型与密钥' })).toBeVisible()
})

it('没有改动时「保存密钥」禁用', async () => {
  render(<App />)
  await arriveAtSettingsHome()
  await openSettingsPage('模型与密钥')
  expect(await screen.findByRole('button', { name: '保存密钥' })).toBeDisabled()
})
```

- [x] **Step 2: 跑用例确认失败**

Run: `npx vitest run src/App.settings.test.tsx -t "保存密钥"`
Expected: FAIL，找不到 `保存密钥` 按钮。

- [x] **Step 3: 加 props 与按钮**

在 `SettingsScreenProps`（`src/App.tsx` 约 1660 行）加：

```ts
  /** 模型与密钥页的「保存密钥」：与离开页面自动保存走同一条写盘路径。 */
  onSaveCredentials: () => Promise<boolean>
```

在 `SettingsScreen` 解构参数里加 `onSaveCredentials`，并在组件内（`saveDraft` 原位置附近）加：

```ts
  // 只用于模型与密钥页：密钥是用户马上就想确认落盘的一类输入，不该逼他先离开页面。
  const credentialDirty = Object.keys(credentialDrafts).length > 0 || clearedProviders.length > 0
  const saveCredentials = (): void => { void onSaveCredentials() }
```

在 `credential-actions` 区块（`src/App.tsx` 约 2400 行）第一个按钮之前插入：

```tsx
            <button
              className="button button-primary compact-button"
              type="button"
              disabled={!credentialDirty || busy !== null}
              onClick={saveCredentials}
            >
              {busy === 'save-settings' ? <Loader2 className="spin" size={16} /> : <Save size={16} />}
              {busy === 'save-settings' ? t('保存中…') : t('保存密钥')}
            </button>
```

在 `<SettingsScreen …/>` 挂载处（`src/App.tsx` 约 5300 行）加：

```tsx
          onSaveCredentials={() => saveSettingsDraft()}
```

- [x] **Step 4: 跑用例确认通过**

Run: `npx vitest run src/App.settings.test.tsx -t "保存密钥"`
Expected: PASS。

- [x] **Step 5: 确认没有破坏既有保存语义**

Run: `npx vitest run src/App.settings.test.tsx src/App.test.tsx`
Expected: 全部 PASS（离开页面/切后台仍会自动保存）。

- [x] **Step 6: 提交**

```bash
git add src/App.tsx src/App.settings.test.tsx
git commit -m "feat(设置): 模型与密钥页恢复 API Key 保存按钮"
```

---

### Task 2: 保存反馈与按钮的主题化外观

**Files:**
- Modify: `src/App.tsx`（`credential-actions` 里的保存状态提示、`onValueChange` 时复位状态）
- Modify: `src/styles.css`（新增 `.credential-save-status`，只读语义令牌）
- Test: `src/App.settings.test.tsx`（成功/失败两条反馈）

**Interfaces:**
- Consumes: Task 1 的 `credentialDirty` 与 `onSaveCredentials(): Promise<boolean>`
- Produces: `SettingsScreen` 内部状态 `credentialSaveState: 'idle' | 'saving' | 'saved' | 'failed'`

- [x] **Step 1: 写失败用例**

在 `src/App.settings.test.tsx` 追加：

```tsx
it('保存密钥成功后显示已保存提示', async () => {
  render(<App />)
  await arriveAtSettingsHome()
  await openSettingsPage('模型与密钥')
  fireEvent.change(screen.getByLabelText(/DeepSeek API Key/), { target: { value: 'unit-test-saved' } })
  fireEvent.click(screen.getByRole('button', { name: '保存密钥' }))
  expect(await screen.findByText('已保存')).toBeVisible()
})

it('保存密钥失败时提示可重试且不清空输入', async () => {
  bridge.saveSettings.mockRejectedValueOnce(new Error('write failed'))
  render(<App />)
  await arriveAtSettingsHome()
  await openSettingsPage('模型与密钥')
  fireEvent.change(screen.getByLabelText(/DeepSeek API Key/), { target: { value: 'unit-test-keep' } })
  fireEvent.click(screen.getByRole('button', { name: '保存密钥' }))
  expect(await screen.findByText('保存失败，请重试')).toBeVisible()
  expect(screen.getByLabelText(/DeepSeek API Key/)).toHaveValue('unit-test-keep')
  expect(screen.getByRole('button', { name: '保存密钥' })).toBeEnabled()
})
```

- [x] **Step 2: 跑用例确认失败**

Run: `npx vitest run src/App.settings.test.tsx -t "保存密钥"`
Expected: FAIL，找不到 `已保存` / `保存失败，请重试`。

- [x] **Step 3: 加状态与提示**

`SettingsScreen` 里：

```tsx
  const [credentialSaveState, setCredentialSaveState] = useState<'idle' | 'saving' | 'saved' | 'failed'>('idle')
  const saveCredentials = (): void => {
    setCredentialSaveState('saving')
    void onSaveCredentials().then(ok => setCredentialSaveState(ok ? 'saved' : 'failed'))
  }
```

`credential-actions` 里保存按钮之后加：

```tsx
            {credentialSaveState === 'saved' && (
              <span className="credential-save-status success" role="status">
                <CheckCircle2 size={15} />{t('已保存')}
              </span>
            )}
            {credentialSaveState === 'failed' && (
              <span className="credential-save-status error" role="alert">{t('保存失败，请重试')}</span>
            )}
```

API Key 的 `onValueChange` 开头加 `setCredentialSaveState('idle')`（改了输入就不再显示上一次的成功/失败）。

- [x] **Step 4: 样式只用语义令牌**

`src/styles.css` 末尾加：

```css
/* 密钥保存反馈：颜色跟主题走，不写死绿/红。 */
.credential-save-status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 0.8125rem;
}
.credential-save-status.success { color: var(--green); }
.credential-save-status.error { color: var(--red); }
```

- [x] **Step 5: 跑用例确认通过**

Run: `npx vitest run src/App.settings.test.tsx`
Expected: 全部 PASS。

- [x] **Step 6: 提交**

```bash
git add src/App.tsx src/styles.css src/App.settings.test.tsx
git commit -m "feat(设置): 密钥保存反馈跟随主题"
```

---

### Task 3: 副屏小窗改成可直接拖动的顶栏

**Files:**
- Modify: `android/app/src/main/java/io/deepseekharness/mobile/virtualscreen/VirtualScreenSizing.kt`（新增 `CHROME_HEIGHT_DP` 与 `overlayTotalHeight`）
- Modify: `android/app/src/main/java/io/deepseekharness/mobile/virtualscreen/VirtualScreenFrame.kt`（顶栏 + 拖动接口，芯片移入顶栏）
- Modify: `android/app/src/main/java/io/deepseekharness/mobile/virtualscreen/VirtualScreenService.kt`（窗口高度加顶栏、安装拖动、拖动实现）
- Test: `android/app/src/test/java/io/deepseekharness/mobile/virtualscreen/VirtualScreenSizingTest.kt`（新增纯函数断言）

**Interfaces:**
- Consumes: `VirtualScreenDragHost`（`beginDrag(): Boolean` / `drag(deltaX, deltaY)` / `endDrag(deltaX, deltaY)`，定义在 `VirtualScreenService.kt`）
- Produces: `VirtualScreenWindow.CHROME_HEIGHT_DP: Float`；`VirtualScreenWindow.overlayTotalHeight(contentHeightPx: Int, density: Float): Int`；`VirtualScreenFrame.installDrag(gesture: VirtualScreenDragGesture)`

- [x] **Step 1: 写失败用例**

在 `VirtualScreenSizingTest.kt` 追加：

```kotlin
@Test fun `窗口总高等于内容高加顶栏高`() {
    // 顶栏 40dp 在 2.5 密度下是 100px。
    assertEquals(1100, VirtualScreenWindow.overlayTotalHeight(1000, 2.5f))
    // 非法内容高度也至少留 1px，不返回 0 尺寸窗口。
    assertEquals(VirtualScreenWindow.overlayTotalHeight(0, 2f) , (VirtualScreenWindow.CHROME_HEIGHT_DP * 2f).toInt() + 1)
}
```

- [x] **Step 2: 跑用例确认失败**

Run: `android/gradlew.bat :app:testDebugUnitTest --offline --tests "*VirtualScreenSizingTest*"`
Expected: FAIL，`overlayTotalHeight` 未定义。

- [x] **Step 3: 实现几何函数与顶栏**

`VirtualScreenSizing.kt` 增加：

```kotlin
    /** 小窗顶栏高度：拖动条 + 两个出口芯片都在这一条里，不再压住副屏画面。 */
    const val CHROME_HEIGHT_DP = 40f

    fun overlayTotalHeight(contentHeightPx: Int, density: Float): Int =
        (CHROME_HEIGHT_DP * density).toInt().coerceAtLeast(1) + contentHeightPx.coerceAtLeast(1)
```

`VirtualScreenFrame.kt`：把 `controlRow` 的父容器改成顶部 `chromeRow`（`LinearLayout`，高 `CHROME_HEIGHT_DP`，半透明 `--control` 底色），`preview` 用 `topMargin = chromeHeightPx`，`handle` 仍固定右下角；新增：

```kotlin
internal class VirtualScreenDragGesture(private val host: () -> VirtualScreenDragHost?) {
    private var active = false
    private var downX = 0f
    private var downY = 0f
    fun onTouch(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            active = host()?.beginDrag() == true
            downX = event.rawX; downY = event.rawY
            true
        }
        MotionEvent.ACTION_MOVE -> {
            if (active) host()?.drag((event.rawX - downX).toInt(), (event.rawY - downY).toInt())
            active
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            if (active) host()?.endDrag((event.rawX - downX).toInt(), (event.rawY - downY).toInt())
            active = false
            true
        }
        else -> false
    }
}

fun installDrag(gesture: VirtualScreenDragGesture) { chromeRow.setOnTouchListener { _, e -> gesture.onTouch(e) } }
```

`VirtualScreenService.kt` 的 `overlayWindowLayout()` 返回前把高度换成 `VirtualScreenWindow.overlayTotalHeight(size.heightPx, metrics.density)`，并用 `sizingHeight` 夹取（顶栏不能让窗口超出可用区域）；`area` 不变，拖动夹取仍按可用区域。`showOverlay()` 里装上 `frame.installDrag(...)`，窗口高度使用同一结果。

- [x] **Step 4: 跑 Kotlin 测试确认通过**

Run: `android/gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --offline`
Expected: BUILD SUCCESSFUL。

- [x] **Step 5: 确认没有破坏「点一下置前」与透传**

Run: `android/gradlew.bat :app:testDebugUnitTest --offline --tests "*VirtualScreenPreviewTouchFlow*" --tests "*VirtualScreenOverlayMachine*"`
Expected: PASS（顶栏拖动不走预览触摸流，画面区域的透传与长按拖动行为保持原样）。

- [x] **Step 6: 提交**

```bash
git add android/app/src/main/java/io/deepseekharness/mobile/virtualscreen android/app/src/test
git commit -m "feat(副屏): 小窗顶栏直接拖动，芯片移出画面"
```

---

### Task 4: 「启动副屏」控制卡置顶

**Files:**
- Modify: `src/components/VirtualScreenSettings.tsx`（把「控制」区块移到描述之后、状态区块之前）
- Test: `src/components/VirtualScreenSettings.test.tsx`（新增顺序断言）

**Interfaces:**
- Consumes: 既有 `start()` / `stop()` / `restart()` / `openPreview()` 与 `busy` / `locked` / `target` 状态
- Produces: 无新导出；「控制」成为页面第一个 `<section>`

- [x] **Step 1: 写失败用例**

在 `VirtualScreenSettings.test.tsx` 追加：

```tsx
it('控制卡排在副屏状态与设置之前', async () => {
  const bridge = bridgeWith({
    getVirtualScreenSettings: vi.fn().mockResolvedValue(settings()),
    getVirtualScreenState: vi.fn().mockResolvedValue(state()),
  })
  render(<VirtualScreenSettings bridge={bridge} onBack={() => {}} />)
  const sections = await screen.findAllByRole('region')
  expect(sections[0]).toHaveAccessibleName('控制')
})
```

- [x] **Step 2: 跑用例确认失败**

Run: `npx vitest run src/components/VirtualScreenSettings.test.tsx -t "控制卡排在"`
Expected: FAIL，第一个 region 是「副屏状态」。

- [x] **Step 3: 搬移区块**

把 `<section className="settings-subsection" aria-labelledby="virtual-screen-control-title"> … </section>` 整块从文件末尾（约 984 行）剪切到 `<p className="virtual-screen-description">` 之后；其余区块顺序不变。区块内的按钮、禁用条件与提示原样保留。

- [x] **Step 4: 跑用例确认通过**

Run: `npx vitest run src/components/VirtualScreenSettings.test.tsx`
Expected: PASS。

- [x] **Step 5: 提交**

```bash
git add src/components/VirtualScreenSettings.tsx src/components/VirtualScreenSettings.test.tsx
git commit -m "feat(副屏): 启动/停止控制卡移到页面最上方"
```

---

### Task 5: 集成验证与预览构建

**Files:**
- Modify: 无（只跑验证与出包）
- Test: 全套

- [x] **Step 1: 前端全量**

Run: `npx tsc -b && npx eslint . --max-warnings 0 && npx vitest run`
Expected: 全部通过。

- [x] **Step 2: 脚本与门禁**

Run: `node --test scripts/*.test.mjs && node scripts/gate-check.mjs`
Expected: 342 项通过（4 跳过）；gate 4/4。

- [x] **Step 3: Android**

Run: `cd android && ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --offline`
Expected: BUILD SUCCESSFUL。

- [x] **Step 4: 出测试安装包**

Run: `pnpm build && npx cap sync android && cd android && ./gradlew :app:assembleDebug --offline`
Expected: `android/app/build/outputs/apk/debug/app-debug.apk` 生成；若 `assets/runtime/rootfs.bundle` 存在，用 `python scripts/verify-bundle.py --apk …` 复核。

- [x] **Step 5: 真机验收清单（人工）**

1. 模型页输入 API Key → 点「保存密钥」→ 不离开页面，杀进程重开仍显示已配置；
2. 深色 + 非默认强调色下模型页文字/按钮对比度正常；
3. 副屏小窗：按住顶栏可直接拖动（无需长按），画面区域滑动仍透传给目标应用；
4. 顶栏芯片不再压住目标应用顶部按钮；
5. 「启动副屏」卡位于页面最上方，未选应用时保持禁用并说明原因。

- [x] **Step 6: 提交（如有文档更新）**

```bash
git add docs/
git commit -m "docs: 0.3.3 验收清单补充保存按钮与副屏拖动"
```
