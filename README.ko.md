# DeepSeek Harness 안드로이드판

[English](README.en.md) · [简体中文](README.md) · [繁體中文](README.zh-TW.md) · [日本語](README.ja.md)

[![최신 버전](https://img.shields.io/github/v/release/dphmoblie/deepseek-harness-android?label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC&logo=github)](https://github.com/dphmoblie/deepseek-harness-android/releases)
[![다운로드 총계](https://img.shields.io/github/downloads/dphmoblie/deepseek-harness-android/total?label=%E4%B8%8B%E8%BD%BD%E6%80%BB%E9%87%8F&logo=github)](https://github.com/dphmoblie/deepseek-harness-android/releases)
[![라이선스](https://img.shields.io/github/license/dphmoblie/deepseek-harness-android?label=%E8%AE%B8%E5%8F%AF%E8%AF%81)](LICENSE)
[![최근 커밋](https://img.shields.io/github/last-commit/dphmoblie/deepseek-harness-android?label=%E6%9C%80%E8%BF%91%E6%8F%90%E4%BA%A4)](https://github.com/dphmoblie/deepseek-harness-android/commits)
[![PR 환영](https://img.shields.io/badge/PR-%E6%AC%A2%E8%BF%8E%E8%B4%A1%E7%8C%AE-brightgreen.svg)](https://github.com/dphmoblie/deepseek-harness-android/pulls)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-3DDC84?logo=arm&logoColor=white)](https://developer.android.com/ndk/guides/abis)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)

<p align="center"><img src="docs/images/app-icon-512.png" width="256" alt="DeepSeek Harness 安卓版应用图标"></p>

**Android를 위한 로컬 AI 워크벤치입니다.** [DeepSeek Harness](https://github.com/deepseek-ai/dsh), Ubuntu 실행 환경, 세션, 플러그인 및 파일 관리를 스마트폰 하나에 통합합니다. 해당 권한을 활성화하면 AI가 Shizuku를 통해 기기 Shell(명령줄) 작업을 실행하거나, 접근성 서비스를 통해 지정한 앱을 조작할 수도 있습니다.

Root(최고 관리자 권한)는 필요하지 않습니다. Linux 환경은 [PRoot](https://github.com/proot-me/proot)를 통해 사용자 공간에서 실행되며, 콘솔은 내장 웹뷰로 표시됩니다. 모델 추론에는 사용자가 구성한 서비스를 사용합니다.

<a id="qq-group"></a><a id="community-qq-group"></a>**프로젝트 QQ 그룹:** `1108895375`, 사용 경험 공유와 문제 제보, 개발 참여를 환영합니다.

| | |
| --- | --- |
| 앱 패키지 이름 | `io.deepseekharness.mobile` |
| 최소 시스템 | Android 8.0(API 26) 이상 |
| 대상 앱 보조 화면 | Android 10(API 29) 이상, Shizuku 및 기기 검증 필요 |
| 지원 아키텍처 | `arm64-v8a`(64비트 ARM) 전용 |
| 내장 런타임 | Ubuntu 24.04 ARM64 · Node.js 24.19 · `@deepseek-ai/dsh` 0.2.0-rc.2 |
| 앱 라이선스 | MIT(런타임 구성 요소는 각자의 라이선스를 따르며, [라이선스](#라이선스) 참조) |

## 목차

- [기능](#기능)
- [동작 원리](#동작-원리)
- [설치](#설치)
- [모델 공급자](#모델-공급자)
- [선택적 Shizuku 연동](#선택적-shizuku-연동)
- [대상 앱 보조 화면(실험 기능)](#대상-앱-보조-화면실험-기능)
- [소스에서 빌드](#소스에서-빌드)
- [보안 및 개인정보](#보안-및-개인정보)
- [기여하기](#기여하기)
- [라이선스](#라이선스)
- [관련 문서](#관련-문서)

## 기능

- **스마트폰 안의 완전한 Linux 에이전트 환경.** Ubuntu 24.04가 PRoot를 통해 전적으로 기기 로컬에서 실행되며, 클라우드 서버나 원격 데스크톱에 의존하지 않고 계정 가입도 필요하지 않습니다. 에이전트 런타임과 웹 콘솔이 모두 로컬에서 실행됩니다.
- **스마트폰에 맞춘 Harness 콘솔.** 앱 내부는 홈, 플러그인, 설정의 세 가지 하단 진입점으로 기능을 구성하며 좁은 화면, 가로 화면, 안전 영역에 대응합니다. 세션 관리는 홈에 통합되어 있습니다. 서드파티 웹 플러그인은 화면과 의존성이 제각각이므로 호환성은 플러그인과 버전별로 검증해야 합니다.
- **플러그인 가져오기, 업데이트 및 롤백.** npm 패키지 이름, HTTPS 압축 파일 링크 또는 `git+https` 저장소 주소에서 가져오는 것을 지원하며, 패키지와 하위 플러그인의 활성화/중지를 관리합니다. 업데이트 시 지원되는 디렉터리 규칙에 따라 데이터를 보존하고 이전 버전으로 되돌릴 수 있습니다. 설치 스크립트나 네이티브 컴파일이 필요하거나 다른 코어 SDK를 사용하는 패키지에는 제한이 있으며, 자세한 내용은 [플러그인 관리](docs/插件管理.md)를 참조하세요.
- **파일 및 테마 관리.** 전달 영역은 폴더 분류를 지원하며, 앱 안에서 이미지나 동영상 배경을 선택하고 카드 색상, 투명도, 흐림 정도와 강조 색을 조정할 수 있습니다.
- **Root 없이 실행.** 일반 정품 기기에서도 PRoot를 통한 사용자 공간 컨테이너화를 사용할 수 있습니다. 선택 사항인 [Shizuku](https://shizuku.rikka.app/) 연동은 사용자가 직접 권한을 부여한 뒤 Shell 수준의 기기 터미널(`/system/bin/sh`)을 추가로 제공할 수 있습니다. Shizuku가 제공하는 것은 Android Shell 권한이며 Root 권한이 아닙니다.
- **바로 사용 가능하고 오프라인 설치를 지원.** 정식 APK에는 검증된 `rootfs.bundle`과 매니페스트 파일이 내장되어 있어 네트워크가 없는 환경에서도 런타임 설치를 마칠 수 있습니다. 다이제스트로 고정된(digest-pinned) 원격 런타임 소스도 지원합니다.
- **변조 방지 런타임 배포.** 모든 매니페스트와 루트 파일 시스템 이미지는 사용 전에 정확한 길이와 SHA-256으로 검증됩니다. 다운로드는 HTTPS 대상 주소만 허용하고 사설 주소를 가리키는 DNS 해석 결과를 거부하며, HTTP 범위 요청을 통한 이어받기를 지원하고 압축 해제 시 경로 탈출과 장치 노드를 방어합니다. 환경이 준비되면 원자적으로 전환하여 적용합니다.
- **내장 및 사용자 정의 모델 공급자.** DeepSeek, OpenAI, Anthropic, Google Gemini, OpenRouter, Groq, xAI, Mistral 그리고 자체 구축한 OpenAI 호환 엔드포인트의 자격 증명은 모두 Android Keystore로 암호화하여 저장되며, 런타임 프로세스에만 주입되고 절대 WebView로 되돌려 보내지 않습니다.
- **로컬 콘솔 접근.** Harness는 `127.0.0.1`에만 바인딩됩니다. 시작할 때마다 256비트 전송 토큰을 새로 생성하여 HTTP와 WebSocket 요청을 모두 보호합니다. 토큰은 프로세스 메모리에만 보관되며 영속화되지 않고 URL에도 기록되지 않습니다. 모델 호출과 사용자가 시작한 다운로드, 플러그인 설치 등은 여전히 네트워크에 접속할 수 있습니다.
- **통합 터미널.** 같은 화면에서 PRoot 환경 내의 Ubuntu 터미널과 (선택 사항인) Shizuku 기반 Android 기기 터미널을 사용할 수 있습니다.
- **앱 내 런타임 자가 진단.** 실행 환경에 문제가 생겨도 bash에 의존할 필요가 없습니다. 자가 진단은 shell, Node.js, 샌드박스 런처(실행 비트 포함), Landlock 프로브, 실제 샌드박스 내 실행, PTY의 두 가지 스모크 테스트(순수 PTY와 샌드박스 내 PTY), 게스트 데이터 디렉터리와 첨부 파일 디렉터리 쓰기, ripgrep 실행 비트를 항목별로 점검하고 사용 가능한 공간을 보고합니다. 실행 비트 누락이나 디렉터리 누락을 발견하면 어떤 파일 내용도 변경하지 않은 채 그 자리에서 복구할 수 있습니다.
- **필요할 때 보고 해석하는 로그.** 내부 상태 코드와 카운트만 담긴 진단 로그는 앱 안에서 바로 읽을 수 있습니다(마지막 64 / 256 KB 창). 실행 로그 창은 8 / 64 / 256 KB 중에서 선택할 수 있고 키워드 필터와 레벨별 색상을 지원합니다. 이미 진단된 오류 특징(자격 증명 누락, 모듈 ID 분열, 플러그인 로드 실패, 포트 점유 등)에 해당하면, 원문 한 토막을 던져 놓고 추측하게 하는 대신 화면에 결론과 다음 조치를 제시합니다.
- **백그라운드 유지와 플로팅 버튼(선택 사항).** 포그라운드 서비스는 런타임 프로세스가 백그라운드에서 살아남을 우선순위를 높일 수 있지만, 메모리나 배터리, 제조사 정책에 따라 시스템이 프로세스를 종료하는 것을 **막을 수는 없습니다**. 플로팅 버튼을 짧게 누르면 드래그할 수 있는 AI 대화 미니 창이 펼쳐지고, 길게 누르면 메뉴가 표시됩니다. 버튼 위치는 영속화되며 화면이 회전한 뒤에도 보이는 범위로 돌아옵니다.
- **최초 설정 게이트.** 기기에 저장된 모델 자격 증명이 없으면 Harness를 열지 않고(키가 없으면 대화는 반드시 실패합니다) 곧바로 「모델 및 키」로 안내합니다. 동시에 「Harness 안에서 이미 구성했으며 그래도 열겠다」는 명시적 통과 경로도 유지합니다.

## 동작 원리

앱은 세 개의 계층으로 나뉩니다.

1. **관리 인터페이스(Capacitor + React).** 네이티브 Android 셸로서 런타임 설치, 서비스 제어, 모델 공급자 설정, 터미널, 런타임 소스와 환경 초기화를 담당합니다.
2. **네이티브 런타임 계층(Kotlin).** 루트 파일 시스템의 검증과 압축 해제, 네이티브 라이브러리 형태로 패키지에 함께 제공되는 PRoot 러너와 로더 관리, Harness 프로세스와 PTY 세션 감독, 그리고 사용자가 권한을 부여한 뒤의 Shizuku UserService 연결을 담당합니다.
3. **Ubuntu 런타임(PRoot).** 고정된 허용 목록 진입점을 통해 Ubuntu 24.04 안에서 `dsh web`을 시작하고 루프백 주소만 수신 대기합니다. Node.js 프리로드 모듈은 어떤 요청이 Harness에 도달하기 전에 해당 시작 토큰을 검증하며, 내장 WebView도 같은 루프백 오리진 안으로 제한됩니다.

전체 아키텍처와 보안 경계는 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)를 참조하세요.

## 설치

1. [Releases](https://github.com/dphmoblie/deepseek-harness-android/releases) 페이지에서 최신 버전 APK를 내려받고 해당 버전의 릴리스 노트를 확인합니다.
2. APK를 설치합니다(시스템 안내에 따라 신뢰할 수 있는 출처의 설치를 허용).
3. 앱을 열고 내장 런타임이 읽기, 검증, 설치를 마칠 때까지 기다립니다. 공식 자체 포함 버전은 네트워크가 필요하지 않습니다.
4. **설정 → 모델 및 키**에서 모델 공급자와 API 키를 추가한 뒤 Harness를 시작합니다.

런타임이 준비되면 앱은 Harness 콘솔을 바로 열고 가장 최근 세션을 복원합니다.

### 환경 요구 사항

- Android 8.0 이상, **arm64-v8a**(64비트 ARM) 프로세서를 사용하는 기기.
- 압축을 푼 Ubuntu 환경을 저장할 수 GB 단위의 여유 저장 공간.
- 지원되는 모델 공급자 중 최소 하나의 API 키, 또는 호환되는 사용자 정의 엔드포인트.

## 모델 공급자

내장 공급자: **DeepSeek, OpenAI, Anthropic, Google Gemini, OpenRouter, Groq, xAI, Mistral**.

임의의 OpenAI 호환 엔드포인트를 사용자 정의 공급자로 구성할 수도 있습니다(기본 주소, API 키와 모델 목록). 자격 증명은 Android Keystore로 저장 시 암호화되며 프로세스 환경 변수 형태로만 Harness 런타임에 주입됩니다. 설정을 저장할 때 Harness가 실행 중이면 자동으로 재시작하여 런타임 상태가 항상 화면 표시와 일치하도록 합니다.

## 선택적 Shizuku 연동

Shizuku는 완전히 선택 사항이며 앱과 함께 번들로 설치되지 않습니다.

1. [Shizuku](https://shizuku.rikka.app/)를 직접 설치하고 실행합니다(무선 디버깅 또는 Shizuku 공식 안내 방식 이용).
2. 앱 안에서 권한을 부여한 뒤 명시적인 **Shizuku 연결** 동작 버튼을 누릅니다.
3. 「설정 → Shizuku 및 기기 자동화」에서 연결에 성공하면 「AI의 Shell 호출 허용」을 켭니다. 한 번 켜면 직접 끌 때까지 계속 적용되며 명령마다 매번 확인할 필요가 없습니다.

활성화하면 모델은 기기 Shell을 호출해 파일을 읽고 쓰고, 디렉터리를 만들고, 업로드와 다운로드를 할 수 있으며, 지정한 앱의 프로세스, 서비스와 Activity 요약을 조회할 수도 있습니다. Shell은 Shizuku의 실제 Android 권한을 사용하며 Root와 같지 않습니다. 명령 본문과 출력은 진단 로그에 기록되지 않습니다. 모델이 키, 문자 메시지, 연락처, 토큰 같은 민감한 데이터를 읽거나 그대로 출력하게 하지 마세요.

접근성 자동화를 사용하려면 시스템 설정에서 서비스를 직접 켠 다음, 앱 안에서 설치된 앱 목록 중 대상 패키지 이름을 선택해 허용 목록에 저장해야 합니다. 허용 목록 개수에는 제한이 없으며 일반 제조사 앱도 추가할 수 있지만, 시스템 설정, 권한, 결제, 인증 코드와 비밀번호 화면은 여전히 네이티브 서비스가 거부합니다. 이 서비스는 잠금 화면을 우회할 수 없고 제조사 백그라운드 정책 아래에서 지속 실행을 보장하지도 않습니다.

Shizuku를 사용할 수 없거나 권한이 없거나 연결이 끊긴 경우 기기 도구는 명확히 오류를 보고하며, Ubuntu 런타임과 Harness는 영향을 받지 않습니다.

## 대상 앱 보조 화면(실험 기능)

**설정 → AI Shell → 대상 앱 보조 화면(실험 기능)** 에서 대상 앱을 선택하면, 해당 앱을 별도의 가상 디스플레이에서 실행하고 네이티브 미리보기 페이지나 드래그할 수 있는 작은 창으로 보고 조작할 수 있습니다. 여기에서 표시되는 것은 대상 앱의 화면이며, AI 대화 플로팅 창은 별개의 기능입니다.

진입 조건은 Android 10 이상, Shizuku 권한 부여와 연결 완료, 그리고 사용자의 AI Shell 활성화입니다. 잠금 화면이거나 화면이 꺼져 있으면 이 보조 화면 채널은 읽기와 입력을 일시 중지합니다. 현재 보조 화면 상태, PNG 스크린샷, 클릭, 스와이프, 뒤로 가기와 세션 종료 AI 도구가 연동되어 있습니다. **아직 보조 화면 전용 접근성 노드 트리, 중국어 텍스트 입력, 고프레임 동영상 스트림은 없습니다.**

2026-10-02 기준으로 MuMu Android 15에서 일반 Shell 권한으로 테스트 앱의 가로/세로 화면 스크린샷, 지정 좌표 클릭과 세션 회수를 검증했고 `363×800 dp`와 `800×363 dp`의 네이티브 선택 페이지 레이아웃을 확인했습니다. **전체 Shizuku 권한 부여와 바인딩 경로, 실제 서드파티 앱, 작은 창 상호 전환, 장기 백그라운드와 잠금 화면 복귀는 아직 검증되지 않았습니다.** 에뮬레이터 프로브 결과를 모든 스마트폰이나 모든 앱의 호환 보장으로 볼 수는 없습니다.

이미 실행 환경이 있는 경우에는 새 AI 도구를 사용하려면 `dsh-mobile-shizuku` 플러그인을 업데이트하거나 다시 패키징해야 합니다. 새 APK를 설치하는 것만으로는 기존 플러그인 데이터가 바뀌지 않습니다. 메커니즘, 도구 인터페이스와 전체 검증 범위는 [대상 앱 보조 화면 설명](docs/目标应用副屏.md)을 참조하세요.

## 소스에서 빌드

### 빌드 의존성

- Node.js `^22.19.0` 또는 `>=24.0.0`, 그리고 [pnpm](https://pnpm.io/) 11
- Android SDK 35, NDK, CMake 3.22.1, JDK 21, Gradle 8.11.1
- Operit2 Android 런타임 툴체인에서 제공하며 릴리스 버전에 고정 대응하는 ARM64 PRoot 러너와 로더(`libdsh_proot.so`, `libdsh_proot_loader.so`) — 정확한 업스트림 버전 번호와 해시는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 참조
- 자체 포함 빌드에는 같은 소스 버전에서 생성한 `runtime-manifest.json`과 `rootfs.bundle`도 필요합니다

### Web 및 Android 빌드

```bash
pnpm install --frozen-lockfile
pnpm run build          # TypeScript 检查 + Vite 生产构建
pnpm run android:sync   # 构建并同步到安卓工程
pnpm run android:open   # 在 Android Studio 中打开，或直接使用 Gradle 构建
```

개발 빌드는 런타임을 내장하지 않고 대신 `DSH_RUNTIME_MANIFEST_URL`과
`DSH_RUNTIME_MANIFEST_SHA256`을 함께 설정해 원격 매니페스트를 고정할 수 있습니다. 전체 빌드 설명과 서명 정책은
[android/README.md](android/README.md)를 참조하세요.

### 검사와 테스트

```bash
pnpm test          # Vitest 单元测试
pnpm --dir scripts/runtime-profile install --frozen-lockfile --ignore-scripts # 插件测试依赖
pnpm run test:scripts
pnpm lint          # ESLint，零警告通过
```

## 보안 및 개인정보

- **모델 데이터의 행선지.** 로컬 콘솔은 오프라인 모델과 같지 않습니다. 대화, AI 도구가 반환한 파일 내용과 스크린샷은 현재 세션에 들어가 구성된 모델 서비스로 전송될 수 있습니다. 로컬 보조 화면 미리보기 자체는 이미지를 업로드하지 않습니다. 현재 작업과 관련된 앱과 데이터에만 권한을 부여하세요.
- **루프백 주소만 수신 대기.** Harness는 루프백이 아닌 어떤 네트워크 인터페이스에도 바인딩하지 않습니다. 내장 WebView는 루프백 오리진 밖으로의 탐색과 HTTP 리소스 접근을 차단합니다.
- **임시 전송 자격 증명.** Harness를 시작할 때마다 `SecureRandom`으로 256비트 토큰을 새로 생성합니다. 토큰은 영속화되지 않고 로그나 URL에 기록되지 않으며 JavaScript로 반환되지도 않습니다.
- **자격 증명 저장.** 공급자 API 키는 Android Keystore로 암호화되며 관리 인터페이스를 벗어나면 PRoot 런타임의 프로세스 환경 변수로만 존재합니다.
- **검증 가능한 런타임 공급망.** 매니페스트와 루트 파일 시스템 이미지는 모두 스키마 검증과 다이제스트 고정을 거치고, 압축 해제 시 엄격한 아카이브 경계 검사를 수행합니다. 이어받기가 잘못된 범위나 비정상 응답을 만나면 실패 시 차단(fail-closed)으로 처리합니다.
- **감사 기록.** 네이티브 감사 로그는 앱 전용의 백업 금지 디렉터리에 저장되며, 파일은 소유자만 읽고 쓸 수 있고 UTC 날짜 기준으로 순환하면서 90일간 보존됩니다. 기록에는 고정된 이벤트/결과 열거형만 포함되며 URL, 명령, 토큰이나 터미널 데이터는 절대 포함되지 않습니다.
- **로그인 없음, 추적 없음.** 앱에는 계정이 없고 광고가 포함되지 않으며 원격 측정 데이터를 수집하지 않습니다.

## 기여하기

<https://github.com/dphmoblie/deepseek-harness-android> 에 Issue와 Pull Request를 제출해 주세요.

제출 전에는 변경을 한 가지에 집중시키고 새 동작에 대한 테스트를 추가하며 `pnpm lint`와 `pnpm test`를 실행하세요.
보안과 관련된 변경은 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)에 설명된 경계를 반드시 유지해야 하며,
특히 루프백 접근 제어, 다이제스트 검증, 진입점 허용 목록이나 Shizuku UserService 계약을 약화시켜서는 안 됩니다.

### 기여자

[![贡献者](https://contrib.rocks/image?repo=dphmoblie/deepseek-harness-android)](https://github.com/dphmoblie/deepseek-harness-android/graphs/contributors)

더 많은 개발자의 참여를 환영합니다. 여러분의 이름도 여기에 나타날 수 있습니다.

### 커뮤니티

- **QQ 그룹: 1108895375** — 궁금한 점을 묻고, 의견을 피드백하고, 버전 릴리스 알림을 받아 보세요.

## 라이선스

이 저장소의 앱 코드는 [MIT 라이선스](LICENSE)로 배포됩니다.

정식 APK는 각자의 라이선스에 따라 서드파티 런타임 구성 요소도 재배포합니다. 여기에는 PRoot
(GPL-2.0-or-later), Operit2 런타임 툴체인(AGPL-3.0), Ubuntu 24.04
소프트웨어 패키지, Node.js, 그리고 MIT 라이선스를 사용하는 DeepSeek Harness 런타임과 프런트엔드가 포함됩니다.
구성 요소 출처, 정확한 업스트림 버전, 산출물 해시와 해당 라이선스 전문은
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)에 기록되어 있으며 APK 내
`assets/legal/` 디렉터리에 함께 제공됩니다.

## 관련 문서

- [대상 앱 보조 화면과 검증 범위](docs/目标应用副屏.md)
- [앱 읽기와 접근성 자동화](docs/应用自动化能力.md)
- [플러그인 가져오기, 데이터 보존과 롤백](docs/插件管理.md)
- [전달 영역과 저장 권한](docs/存储权限与导入落点.md)
- [아키텍처와 보안 경계](docs/ARCHITECTURE.md)
- [모바일 플러그인 호환 설계](docs/mobile-plugin-compat.md)
- [릴리스 체크리스트](docs/RELEASE_CHECKLIST.md)
- [Android 플랫폼 빌드 설명](android/README.md)
- [서드파티 고지](THIRD_PARTY_NOTICES.md)
