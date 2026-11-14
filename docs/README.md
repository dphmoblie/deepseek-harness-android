# 文档索引

本仓库的文档只有一个根：`docs/`。代码目录、IDE 目录与仓库根不再放第二份说明文档。
新增或移动文档前先读放置规范：[`.agents/rules/docs-layout.md`](../.agents/rules/docs-layout.md)。

## 放在哪里

| 路径 | 放什么 | 约定 |
| --- | --- | --- |
| `docs/发布说明-<版本>.md` | 每个版本的发布说明 | 文件名固定：CI 按 `docs/发布说明-${APP_VERSION}.md` 读取并写进 Release 正文，改名或移动会让发布正文缺失 |
| `docs/ARCHITECTURE.md` | 架构与安全边界总览 | 英文名沿用历史，不改 |
| `docs/RELEASE_CHECKLIST.md` | 发布前门禁清单与坏版本处置口径 | 同上 |
| `docs/mobile-*.md` | 移动端验收、插件兼容等长期契约 | 同上 |
| `docs/<中文主题>.md` | 专题设计、实测记录、问题清单 | 新增文档用中文主题名，一个主题一个文件 |
| `docs/android.md` | Android 构建、签名与运行时边界 | 原 `android/` 目录下的 README，已收编 |
| `docs/specs/<主题>/` | 需求与方案（`spec.md` / `tasks.md` / `checklist.md`） | 原仓库根 `specs/`，已收编 |
| `docs/plans/` | 带日期或带背景的历史实施计划 | 从 `.trae/documents/` 收编的计划稿也在这里；文中 `file:///` 绝对链接是历史记录，不随仓库路径变化 |
| `docs/superpowers/plans/` | 工具自动落盘的计划 | 路径由工具约定，不要手改 |
| `docs/images/` | 文档与 README 引用的图片 | `star-download-history.svg` 由 CI 每日刷新并自动提交 |

## 不入库的文档

- `.trae/`：IDE 的本地文档目录，已在 `.gitignore` 中忽略；需要长期保留的内容先收编进 `docs/plans/`。
- `android/app/src/main/assets/legal/ubuntu-packages/` 下的 `inventory.json` 与 `*-copyright.txt`：CI 收集的产物，只有 `README.md` 入库。
- `output/`、`_logs/`：真机截图与 UI 层级 dump，按 `.gitignore` 忽略。

## 例外（不进 `docs/`）

| 文件 | 原因 |
| --- | --- |
| `README.md`、`README.en.md`、`README.ja.md`、`README.ko.md`、`README.zh-TW.md` | GitHub 首页与语言切换约定 |
| `AGENTS.md` | agent 在仓库根发现规则的约定 |
| `LICENSE`、`THIRD_PARTY_NOTICES.md` | 许可证与随 APK 分发的第三方声明，构建脚本按根路径读取 |
| `.github/pull_request_template.md`、`.agents/rules/*.md` | 平台模板与代理规则，不是项目文档 |
| `legal/licenses/*.txt` | 法律原文，`android/app/build.gradle` 按路径打包进 APK |
| `android/app/src/main/assets/legal/ubuntu-packages/README.md` | 随 APK 分发的资产说明 |

## 常用入口

- [架构与安全边界](ARCHITECTURE.md)
- [项目状态](项目状态.md)
- [发布清单](RELEASE_CHECKLIST.md)
- [移动端验收清单](mobile-acceptance-checklist.md)
- [移动端插件兼容](mobile-plugin-compat.md)
- [Android 构建与签名](android.md)
- [发布说明（当前 0.4.0）](发布说明-0.4.0.md)
- [规格：崩溃与权限审计](specs/crash-permission-audit/spec.md)
- [规格：版本管理页移动布局](specs/versions-page-mobile-layout/spec.md)
- [历史计划：应用更新测试版频道](plans/应用更新测试版频道.md)
