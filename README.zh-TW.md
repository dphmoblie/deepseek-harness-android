# DeepSeek Harness 安卓版

[English](README.en.md) · [简体中文](README.md) · [한국어](README.ko.md) · [日本語](README.ja.md)

[![最新版本](https://img.shields.io/github/v/release/dphmoblie/deepseek-harness-android?label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC&logo=github)](https://github.com/dphmoblie/deepseek-harness-android/releases)
[![下载总量](https://img.shields.io/github/downloads/dphmoblie/deepseek-harness-android/total?label=%E4%B8%8B%E8%BD%BD%E6%80%BB%E9%87%8F&logo=github)](https://github.com/dphmoblie/deepseek-harness-android/releases)
[![许可证](https://img.shields.io/github/license/dphmoblie/deepseek-harness-android?label=%E8%AE%B8%E5%8F%AF%E8%AF%81)](LICENSE)
[![最近提交](https://img.shields.io/github/last-commit/dphmoblie/deepseek-harness-android?label=%E6%9C%80%E8%BF%91%E6%8F%90%E4%BA%A4)](https://github.com/dphmoblie/deepseek-harness-android/commits)
[![欢迎 PR](https://img.shields.io/badge/PR-%E6%AC%A2%E8%BF%8E%E8%B4%A1%E7%8C%AE-brightgreen.svg)](https://github.com/dphmoblie/deepseek-harness-android/pulls)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-3DDC84?logo=arm&logoColor=white)](https://developer.android.com/ndk/guides/abis)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)

<p align="center"><img src="docs/images/app-icon-512.png" width="256" alt="DeepSeek Harness 安卓版應用程式圖示"></p>

**專為 Android 打造的本機 AI 工作台。** 將 [DeepSeek Harness](https://github.com/deepseek-ai/dsh)、Ubuntu 執行環境、工作階段、外掛與檔案管理整合到手機中；開啟相應授權後，AI 還能透過 Shizuku 執行裝置 Shell（命令列）工作，或透過無障礙服務操作指定應用程式。

無需 Root（超級使用者權限）。Linux 環境透過 [PRoot](https://github.com/proot-me/proot) 在使用者空間執行，主控台由內建網頁檢視畫面呈現；模型推論使用你設定的服務。

<a id="qq-group"></a><a id="community-qq-group"></a>**專案 QQ 群：** `1108895375`，歡迎交流使用體驗、回報問題與參與開發。

| | |
| --- | --- |
| 應用程式套件名稱 | `io.deepseekharness.mobile` |
| 最低系統版本 | Android 8.0（API 26）及以上 |
| 目標應用程式副螢幕 | Android 10（API 29）及以上，需 Shizuku 與裝置驗證 |
| 支援架構 | 僅 `arm64-v8a`（64 位元 ARM） |
| 內建執行環境 | Ubuntu 24.04 ARM64 · Node.js 24.19 · `@deepseek-ai/dsh` 0.2.0-rc.2 |
| 應用程式授權條款 | MIT（執行環境元件沿用各自授權條款，見[授權條款](#授權條款)） |

## 目錄

- [功能特性](#功能特性)
- [運作原理](#運作原理)
- [安裝](#安裝)
- [模型供應商](#模型供應商)
- [選用的 Shizuku 整合](#選用的-shizuku-整合)
- [目標應用程式副螢幕（實驗功能）](#目標應用程式副螢幕實驗功能)
- [從原始碼建置](#從原始碼建置)
- [安全與隱私](#安全與隱私)
- [參與貢獻](#參與貢獻)
- [授權條款](#授權條款)
- [相關文件](#相關文件)

## 功能特性

- **手機上的完整 Linux 代理程式環境。** Ubuntu 24.04 完全在裝置本機透過 PRoot 執行，不依賴雲端伺服器、遠端桌面，也無需註冊帳號：代理程式執行環境與網頁主控台均在本機執行。
- **為手機調適的 Harness 主控台。** 殼內以首頁、外掛、設定三個底部頁籤組織功能，並為窄螢幕、橫向與安全區域提供調適；工作階段管理整合到首頁。第三方網頁外掛的介面與相依性各不相同，相容性需依外掛與版本逐一驗證。
- **外掛匯入、更新與復原。** 支援從 npm 套件名稱、HTTPS 壓縮檔連結或 `git+https` 儲存庫位址匯入，管理套件與子外掛的啟用與停用；更新時依支援的目錄規則保留資料，並可切回上一版。需要安裝腳本、原生編譯或不同核心 SDK 的套件有其限制，詳見[外掛管理](docs/插件管理.md)。
- **檔案與外觀管理。** 投遞區支援資料夾分類；殼內可選圖片或影片背景，調整卡片顏色、通透度、模糊程度與強調色。
- **免 Root 執行。** 在一般原廠裝置上即可透過 PRoot 實現使用者空間容器化。選用的 [Shizuku](https://shizuku.rikka.app/) 整合可在使用者主動授權後額外提供 Shell 層級的裝置終端機（`/system/bin/sh`）。Shizuku 提供的是安卓 Shell 權限，而非 Root 權限。
- **開箱即用、支援離線安裝。** 正式版 APK 內建經過驗證的 `rootfs.bundle` 與資訊清單檔案，無網路環境也可完成執行環境安裝；同時也支援以摘要固定（digest-pinned）的遠端執行環境來源。
- **防竄改的執行環境發布。** 每份資訊清單與根檔案系統映像在使用前均依精確長度與 SHA-256 驗證；下載僅接受 HTTPS 目標位址、拒絕指向私人位址的 DNS 解析結果，支援 HTTP 範圍要求續傳，解壓縮時具備路徑穿越與裝置節點防護。環境準備完成後以原子方式切換生效。
- **內建與自訂模型供應商。** DeepSeek、OpenAI、Anthropic、Google Gemini、OpenRouter、Groq、xAI、Mistral 以及自建 OpenAI 相容端點的憑證均透過 Android Keystore 加密保存，且只會注入執行環境行程，絕不回傳至 WebView。
- **本機主控台存取。** Harness 只繫結 `127.0.0.1`。每次啟動都會產生全新的 256 位元傳輸權杖，同時保護 HTTP 與 WebSocket 要求；權杖僅保存在行程記憶體中，不會持續保存，也不會寫入 URL。模型呼叫與使用者發起的下載、外掛安裝等仍可能存取網路。
- **整合式終端機。** 可在同一介面中使用 PRoot 環境內的 Ubuntu 終端機，以及（選用）由 Shizuku 支援的安卓裝置終端機。
- **應用程式內執行環境自我檢查。** 執行環境出問題時不必依賴 bash——自我檢查會逐項探測 shell、Node.js、沙盒啟動器（含執行位元）、Landlock 探測、真實沙盒內執行、PTY 的兩組煙霧測試（裸 PTY 與沙盒內 PTY）、訪客資料目錄與附件目錄的寫入、ripgrep 執行位元，並回報可用空間。發現執行位元遺失或目錄遺失時可以就地修復，不修改任何檔案內容。
- **日誌隨需檢視與判讀。** 只含內部狀態碼與計數的診斷日誌可直接在應用程式內閱讀（尾端視窗 64 / 256 KB）；執行日誌視窗可選 8 / 64 / 256 KB 並支援關鍵字篩選與等級著色。命中已確診的錯誤特徵（缺少憑證、模組身分分裂、外掛載入失敗、連接埠被占用等）時，介面會給出結論與下一步，而不是丟一段原始文字讓人猜。
- **背景保持與懸浮球（選用）。** 前景服務可提高執行環境行程在背景存活的優先順序——但**無法阻止**系統在記憶體、電量或廠商策略下結束行程；短按懸浮球可展開可拖曳的 AI 對話小視窗，長按顯示選單，球的位置會持續保存並在螢幕旋轉後回到可視範圍。
- **首次設定把關。** 本機沒有保存過模型憑證時不會開啟 Harness（沒有金鑰時對話必然失敗），而是直接引導到「模型與金鑰」；同時保留「我已在 Harness 內設定過，仍要開啟」的明確略過入口。

## 運作原理

應用程式分為三層：

1. **管理介面（Capacitor + React）。** 原生安卓外殼，負責執行環境安裝、服務控制、模型供應商設定、終端機、執行環境來源與環境重設。
2. **原生執行環境層（Kotlin）。** 負責根檔案系統的驗證與解壓縮、以原生函式庫形式隨套件提供的 PRoot 執行器與載入器管理、Harness 行程與 PTY 工作階段監督，以及在使用者授權後連線 Shizuku UserService。
3. **Ubuntu 執行環境（PRoot）。** 透過固定的允許清單進入點在 Ubuntu 24.04 內啟動 `dsh web` 並僅監聽回送位址。Node.js 預載模組會在任何要求到達 Harness 之前驗證當次啟動權杖，內建 WebView 也被限制在同一回送來源內。

完整架構與安全邊界見 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 安裝

1. 從 [Releases](https://github.com/dphmoblie/deepseek-harness-android/releases) 頁面下載最新版 APK，並閱讀該版本的發行說明。
2. 安裝 APK（依系統提示允許來自信任來源的安裝）。
3. 開啟應用程式，等待內建執行環境完成讀取、驗證與安裝——官方自包含版本無需連網。
4. 在**設定 → 模型與金鑰**中新增模型供應商與 API 金鑰，隨後啟動 Harness。

執行環境就緒後，應用程式會直接開啟 Harness 主控台並還原最近一次工作階段。

### 環境需求

- Android 8.0 及以上、**arm64-v8a**（64 位元 ARM）處理器的裝置。
- 數 GB 左右的可用儲存空間，用於存放解壓縮後的 Ubuntu 環境。
- 至少一個受支援模型供應商的 API 金鑰，或一個相容的自訂端點。

## 模型供應商

內建供應商：**DeepSeek、OpenAI、Anthropic、Google Gemini、OpenRouter、Groq、xAI、Mistral**。

也可以將任意 OpenAI 相容端點設定為自訂供應商（基礎位址、API 金鑰與模型清單）。憑證透過 Android Keystore 靜態加密，僅以行程環境變數形式注入 Harness 執行環境；儲存設定時若 Harness 正在執行會自動重新啟動，確保執行環境狀態始終與介面顯示一致。

## 選用的 Shizuku 整合

Shizuku 完全為選用項目，且不會隨應用程式一同安裝：

1. 自行安裝並啟動 [Shizuku](https://shizuku.rikka.app/)（透過無線偵錯或 Shizuku 官方指引的方式）。
2. 在應用程式內授予權限，再點選明確的**連線 Shizuku** 操作按鈕。
3. 在「設定 → Shizuku 與裝置自動化」中連線成功後，開啟「允許 AI 呼叫 Shell」。一次開啟會持續生效，直到你關閉開關；不需要每條指令逐一確認。

開啟後，模型可呼叫裝置 Shell 讀取、寫入、建立目錄、上傳、下載檔案，也可查詢指定應用程式的行程、服務與 Activity 摘要。Shell 使用 Shizuku 的實際 Android 權限，不等同於 Root；指令內容與輸出不會寫入診斷日誌。請不要讓模型讀取或回顯金鑰、簡訊、通訊錄、權杖等隱私資料。

無障礙自動化需要你另外在系統設定中手動開啟服務，再在應用程式內從已安裝應用程式清單選擇目標套件名稱並儲存允許清單。允許清單數量不設上限；一般廠商應用程式可加入，系統設定、權限、付款、驗證碼與密碼頁面仍由原生服務拒絕。服務不能略過鎖定畫面，也不能保證廠商背景策略下的持續執行。

當 Shizuku 無法使用、未授權或連線中斷時，裝置工具會明確報錯；Ubuntu 執行環境與 Harness 不受影響。

## 目標應用程式副螢幕（實驗功能）

在 **設定 → AI Shell → 目標應用程式副螢幕（實驗功能）** 中選擇目標應用程式，可嘗試將其執行到獨立虛擬顯示器，並透過原生預覽頁面或可拖曳小視窗檢視與操作。此處顯示的是目標應用程式介面；AI 對話懸浮視窗是另一項功能。

進入點要求 Android 10 及以上、已授權並連線 Shizuku，且使用者已開啟 AI Shell。鎖定畫面或螢幕關閉時，該副螢幕通道暫停讀取與輸入。目前已接入副螢幕狀態、PNG 螢幕擷取畫面、點擊、滑動、返回與結束工作階段的 AI 工具；**尚無副螢幕專用無障礙節點樹、中文文字輸入或高影格率視訊串流。**

截至 2026-10-02，已在 MuMu Android 15 中以一般 Shell 權限驗證測試應用程式的橫向與縱向螢幕擷取畫面、定向點擊與工作階段回收，並檢查 `363×800 dp` 與 `800×363 dp` 的原生選擇頁面版面配置。**尚未驗證完整 Shizuku 授權與繫結鏈路、真實第三方應用程式、小視窗互切、長時間背景執行與鎖定畫面還原**；模擬器探測結果不能視為所有手機或所有應用程式的相容保證。

已有執行環境還需要更新或重新封裝 `dsh-mobile-shizuku` 外掛，才能使用新增的 AI 工具；僅安裝新 APK 不會改寫現有外掛資料。機制、工具介面與完整驗證範圍見[目標應用程式副螢幕說明](docs/目标应用副屏.md)。

## 從原始碼建置

### 建置相依項目

- Node.js `^22.19.0` 或 `>=24.0.0`，以及 [pnpm](https://pnpm.io/) 11
- Android SDK 35、NDK、CMake 3.22.1、JDK 21、Gradle 8.11.1
- 來自 Operit2 安卓執行環境工具鏈、與發行版本固定對應的 ARM64 PRoot 執行器與載入器（`libdsh_proot.so`、`libdsh_proot_loader.so`）——確切的上游版本號與雜湊見 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- 自包含建置還需從同一原始碼版本產生的 `runtime-manifest.json` 與 `rootfs.bundle`

### Web 與安卓建置

```bash
pnpm install --frozen-lockfile
pnpm run build          # TypeScript 檢查 + Vite 正式版建置
pnpm run android:sync   # 建置並同步到安卓專案
pnpm run android:open   # 在 Android Studio 中開啟，或直接使用 Gradle 建置
```

開發版建置可以不內建執行環境，改為同時設定 `DSH_RUNTIME_MANIFEST_URL` 與
`DSH_RUNTIME_MANIFEST_SHA256` 以固定遠端資訊清單。完整建置說明與簽章策略見
[android/README.md](android/README.md)。

### 檢查與測試

```bash
pnpm test          # Vitest 單元測試
pnpm --dir scripts/runtime-profile install --frozen-lockfile --ignore-scripts # 外掛測試相依項目
pnpm run test:scripts
pnpm lint          # ESLint，零警告通過
```

## 安全與隱私

- **模型資料去向。** 本機主控台不等於離線模型。對話、AI 工具回傳的檔案內容及螢幕擷取畫面可能進入目前工作階段並傳送至設定的模型服務；本機副螢幕預覽本身不會上傳影像。只授權與目前工作相關的應用程式與資料。
- **僅監聽回送位址。** Harness 不會繫結任何非回送的網路介面；內建 WebView 封鎖存取回送來源以外的導覽與 HTTP 資源。
- **臨時傳輸憑證。** 每次啟動 Harness 都會透過 `SecureRandom` 產生全新的 256 位元權杖。權杖不會持續保存、不會寫入日誌或 URL，也不會回傳給 JavaScript。
- **憑證儲存。** 供應商 API 金鑰透過 Android Keystore 加密，離開管理介面時僅作為 PRoot 執行環境的行程環境變數存在。
- **可驗證的執行環境供應鏈。** 資訊清單與根檔案系統映像均經過 schema 驗證與摘要固定，解壓縮時執行嚴格的封存檔邊界檢查；續傳遇到非法範圍或異常回應時採失敗即關閉（fail-closed）處理。
- **稽核記錄。** 原生稽核日誌存放在應用程式私有的禁備份目錄中，檔案僅擁有者可讀寫，依 UTC 日期輪替並保留 90 天。記錄只包含固定的事件/結果列舉，絕不包含 URL、指令、權杖或終端機資料。
- **無登入、無追蹤。** 應用程式不設帳號、不含廣告、不蒐集遙測資料。

## 參與貢獻

歡迎在 <https://github.com/dphmoblie/deepseek-harness-android> 提交 Issue 與 Pull Request。

提交前請保持變更聚焦、為新行為補充測試，並執行 `pnpm lint` 與 `pnpm test`。
涉及安全的變更必須維護 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 所述邊界，
尤其不得削弱回送存取控制、摘要驗證、進入點允許清單或 Shizuku UserService 契約。

### 貢獻者

[![贡献者](https://contrib.rocks/image?repo=dphmoblie/deepseek-harness-android)](https://github.com/dphmoblie/deepseek-harness-android/graphs/contributors)

歡迎更多開發者參與，你的名字也可以出現在這裡。

### 交流社群

- **QQ 交流群：1108895375**——歡迎入群提問、回饋建議、取得版本發行通知。

## 授權條款

本儲存庫中的應用程式程式碼依 [MIT 授權條款](LICENSE)發布。

正式版 APK 還以各自授權條款再散布了第三方執行環境元件，包括 PRoot
（GPL-2.0-or-later）、Operit2 執行環境工具鏈（AGPL-3.0）、Ubuntu 24.04
軟體套件、Node.js，以及採用 MIT 授權條款的 DeepSeek Harness 執行環境與前端。
元件來源、確切上游版本、製品雜湊及相應授權條款文字記錄於
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，並隨 APK 內
`assets/legal/` 目錄一併提供。

## 相關文件

- [目標應用程式副螢幕與驗證範圍](docs/目标应用副屏.md)
- [應用程式讀取與無障礙自動化](docs/应用自动化能力.md)
- [外掛匯入、資料保留與復原](docs/插件管理.md)
- [投遞區與儲存權限](docs/存储权限与导入落点.md)
- [架構與安全邊界](docs/ARCHITECTURE.md)
- [行動裝置外掛相容性設計](docs/mobile-plugin-compat.md)
- [發行檢查清單](docs/RELEASE_CHECKLIST.md)
- [安卓平台建置說明](android/README.md)
- [第三方聲明](THIRD_PARTY_NOTICES.md)
