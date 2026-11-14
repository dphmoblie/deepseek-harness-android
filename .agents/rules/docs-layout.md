---
description: 文档放置规范（唯一文档根 docs/）
---

# 文档放置规范

仓库的文档只有一个根：`docs/`。代码目录、IDE 目录与仓库根不再新增说明文档。
总索引与分类见 [`docs/README.md`](../../docs/README.md)。

## 规则

1. **新文档一律进 `docs/`**：专题设计、实测记录、问题清单、方案规格、实施计划都不在 `android/`、`.trae/` 或仓库根另建目录。
2. **仓库根只留这些文档文件**：多语言 `README*.md`、`AGENTS.md`、`THIRD_PARTY_NOTICES.md`；`LICENSE` 不动。
3. **发布说明路径不可变**：固定 `docs/发布说明-<版本>.md`。`.github/workflows/android-build.yml` 按 `docs/发布说明-${APP_VERSION}.md` 读取它作为 Release 正文，改名或移入子目录会让发布正文缺失。
4. **规格与计划分开放**：需求/方案进 `docs/specs/<主题>/`（`spec.md` / `tasks.md` / `checklist.md`）；带日期的历史计划进 `docs/plans/`；`docs/superpowers/plans/` 是工具落盘路径，不手动改。
5. **资产说明留在资产旁**：`android/app/src/main/assets/legal/ubuntu-packages/README.md` 随 APK 分发，法律原文 `legal/licenses/` 被构建脚本按路径读取，二者都不迁入 `docs/`。
6. **IDE 目录不入库**：`.trae/` 已在 `.gitignore`；需要长期保留的内容先收编到 `docs/plans/` 再删本地目录。
7. **图片进 `docs/images/`**，文档内用相对路径引用；`star-download-history.svg` 由 CI 生成并提交，不要手改。
8. **移动或改名文档时同步改引用**：`git grep '<旧路径>'` 必须为空。README 多语言版本、`AGENTS.md`、`docs/` 内部链接、构建脚本注释与 `scripts/doctor.mjs` 的提示都算引用。

## 检查

```sh
git grep -n '<被移动的旧路径>' -- . ':!node_modules' ':!harness-web'
git ls-files 'docs/**'
node scripts/validate-manifests.mjs
```

最后一条会读取 `docs/runtime-manifest.example.json` 与 `docs/项目状态.md`，改动 docs 目录结构后顺手跑一次。
