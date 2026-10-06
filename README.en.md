# DeepSeek Harness for Android

Project overview · [Usage and build guide](README.zh-CN.md) · [中文首页](README.md)

[![Latest release](https://img.shields.io/github/v/release/dphmoblie/deepseek-harness-android?label=release&logo=github)](https://github.com/dphmoblie/deepseek-harness-android/releases)
[![Total downloads](https://img.shields.io/github/downloads/dphmoblie/deepseek-harness-android/total?label=downloads&logo=github)](https://github.com/dphmoblie/deepseek-harness-android/releases)
[![License](https://img.shields.io/github/license/dphmoblie/deepseek-harness-android?label=license)](LICENSE)
[![Last commit](https://img.shields.io/github/last-commit/dphmoblie/deepseek-harness-android?label=last%20commit)](https://github.com/dphmoblie/deepseek-harness-android/commits)
[![PRs welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](https://github.com/dphmoblie/deepseek-harness-android/pulls)

[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-3DDC84?logo=arm&logoColor=white)](https://developer.android.com/ndk/guides/abis)
[![Ubuntu 24.04](https://img.shields.io/badge/Ubuntu%2024.04-E95420?logo=ubuntu&logoColor=white)](https://ubuntu.com/)
[![Node.js 24](https://img.shields.io/badge/Node.js%2024-5FA04E?logo=node.js&logoColor=white)](https://nodejs.org/)
[![PRoot](https://img.shields.io/badge/PRoot-userspace%20container-4EAA25)](https://github.com/proot-me/proot)
[![Capacitor 7](https://img.shields.io/badge/Capacitor%207-119EFC?logo=capacitor&logoColor=white)](https://capacitorjs.com/)
[![React 18](https://img.shields.io/badge/React%2018-61DAFB?logo=react&logoColor=black)](https://react.dev/)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![QQ group](https://img.shields.io/badge/QQ%20group-1108895375-12B7F5?style=for-the-badge)](#qq-group)

<p align="center"><img src="docs/images/app-icon-512.png" width="256" alt="DeepSeek Harness for Android app icon"></p>

**A local AI workbench for Android.** It brings [DeepSeek Harness](https://github.com/deepseek-ai/dsh), an Ubuntu runtime, sessions, plugins and file management onto your phone. Once you grant the relevant permissions, the AI can also run device shell commands through Shizuku or operate selected apps through the accessibility service.

No root required. The runtime and console execute on the device itself; model inference uses whatever service you configure. Conversations, files returned by tools and screenshots may be sent to that service.

<a id="qq-group"></a><a id="community-qq-group"></a>**Project QQ group:** `1108895375` — for usage questions, feedback and development discussion.

> This page describes the current source tree. The app version declared in source is `0.2.4`; the target-app virtual screen ships as an experimental feature. When downloading an APK, follow the release notes for that specific version — a preview build does not imply device acceptance testing has been completed.

## What it can do

| Capability | Current implementation |
| --- | --- |
| Phone AI workbench | Runs Harness in an on-device Ubuntu userspace, with the bundled web console for sessions and model providers. |
| Mobile UI | Three bottom tabs — Home, Plugins, Settings; session management is merged into Home, with layout adaptation for narrow screens, landscape and system safe areas. |
| Plugin management | Enable/disable plugins and sub-plugins; import from an npm package name, an HTTPS archive or a `git+https` URL; preserve data across updates under supported directory rules and roll back to the previous version. |
| Files and terminals | Organize inbox files by folder and use the Ubuntu terminal; with Shizuku granted, the Android device shell is also available. |
| Device automation | The AI shell reads and writes files, uploads, downloads and queries background tasks within its actual permissions; the accessibility channel observes and operates whitelisted user apps. |
| Themes and mini window | Optional image or video background, tweakable card color, transparency, blur and accent color; the floating ball opens an AI chat mini window. |
| Runtime management | Runtime installation and self-check, log viewing, background keep-alive settings; background survival still depends on Android and vendor policies. |

Plugin compatibility depends on the specific version and dependencies — not every desktop plugin works on mobile. See the [plugin management guide](docs/插件管理.md) (Chinese) for import sources, data migration scope and rollback rules.

## Target-app virtual screen: let the AI observe and operate another display

**Experimental, already wired into the current source.** You can pick a target app, try running it on a separate virtual display, then view and interact with it from a native page or a draggable mini window. What you see there is the target app's screen, separate from the AI chat mini window.

- **Entry point:** Settings → AI Shell → Target-app virtual screen (experimental).
- **Requirements:** Android 10 or later, Shizuku authorized and connected, AI Shell enabled by the user.
- **AI tools:** query virtual-screen state, fetch a PNG screenshot, tap, swipe, back and end the session.
- **Current limits:** reading and input pause while the screen is locked or off; there is no accessibility node tree, Chinese text input or high-frame-rate video stream for the virtual screen yet.

As of **2026-10-02**, portrait and landscape screenshots, targeted taps and session teardown for a test app were verified under MuMu Android 15 with ordinary shell privileges, along with native picker layouts at `363×800 dp` and `800×363 dp`. **The full Shizuku authorization and binding path, real third-party apps, mini-window switching, long-lived background operation and lock-screen recovery remain unverified.**

An existing runtime also needs its `dsh-mobile-shizuku` plugin updated or repackaged before the new AI tools appear; installing a new APK alone does not rewrite existing plugin data. See the [virtual screen guide and verification record](docs/目标应用副屏.md) (Chinese).

## Getting started

1. Pick an APK from the [releases page](https://github.com/dphmoblie/deepseek-harness-android/releases) and read that version's feature notes and known issues.
2. Install and open the app, then complete the runtime installation. Self-contained builds bundle a verified runtime, so the first installation can happen offline.
3. Configure a provider under **Settings → Models & keys**, then start Harness.
4. Enable device capabilities as needed: Shizuku, AI shell, accessibility and overlay each require their own grant. Ordinary conversations need none of them.

| Item | Requirement or note |
| --- | --- |
| App package name | `io.deepseekharness.mobile` |
| Version declared in source | `0.2.4`; does not mean a downloaded build contains every source change |
| Minimum Android for the main app | Android 8.0 (API 26) |
| Official runtime architecture | `arm64-v8a` (64-bit ARM) |
| Target-app virtual screen | Android 10 (API 29) or later; compatibility must be verified per device |
| Runtime | Ubuntu 24.04 ARM64, Node.js and DeepSeek Harness |
| Storage and models | Several GB of free space plus a supported model service |

[Full installation, model configuration and build-from-source guide](README.zh-CN.md) (Chinese)

## Permissions and where data goes

- **Device capabilities are opt-in.** Once AI shell is switched on it stays active until you turn it off, with no per-command confirmation; what it can actually do depends on Shizuku's real privileges. The accessibility service must be enabled separately in system settings, and you add target apps to the whitelist yourself from the installed-app list — there is no cap on the number.
- **The local service and model requests are separate.** The Harness console listens only on loopback with a temporary access credential; model requests, plugin downloads and user-initiated transfers may reach the network.
- **Screenshots and files can contain private data.** Local virtual-screen preview does not upload images; AI screenshots and tool results may enter a conversation and be sent to the configured model service, so grant only the data access a task needs.
- **System limits still apply.** Accessibility and the virtual screen cannot bypass the lock screen; secure windows, per-app multi-display restrictions and vendor background policies may interfere. The project does not promise compatibility with arbitrary apps or permanent survival after the screen locks.

Provider keys are encrypted with the Android Keystore. Native audit records are kept for at least 90 days and never contain command bodies, terminal output or screenshots. See [security and privacy](README.zh-CN.md#安全与隐私) (Chinese) for details.

## Documentation and development

- [Usage, model configuration, build from source](README.zh-CN.md)
- [0.2.4 preview changelog and upgrade notes](docs/发布说明-0.2.4.md)
- [Target-app virtual screen and verification scope](docs/目标应用副屏.md)
- [App reading and accessibility automation](docs/应用自动化能力.md)
- [Plugin import, data retention and rollback](docs/插件管理.md)
- [Inbox and storage permissions](docs/存储权限与导入落点.md)
- [Mobile plugin compatibility design](docs/mobile-plugin-compat.md)
- [Architecture and security boundaries](docs/ARCHITECTURE.md)
- [Android build guide](android/README.md)
- [Release checklist](docs/RELEASE_CHECKLIST.md)

Issue reports and pull requests are welcome. When reporting a compatibility problem, include the device and OS version, the app version, the target app or plugin version and the steps to reproduce — and strip personal information from screenshots and logs first.

[![Contributors](https://contrib.rocks/image?repo=dphmoblie/deepseek-harness-android)](https://github.com/dphmoblie/deepseek-harness-android/graphs/contributors)

More developers are welcome — your name can appear here too.

## License

The application code in this repository is released under the [MIT license](LICENSE). PRoot (GPL-2.0-or-later), the Operit2 toolchain (AGPL-3.0), Ubuntu packages, Node.js and DeepSeek Harness bundled with the runtime keep their own licenses. Component sources, versions, artifact digests and licenses are recorded in the [third-party notices](THIRD_PARTY_NOTICES.md) and inside the APK's `assets/legal/` directory.
