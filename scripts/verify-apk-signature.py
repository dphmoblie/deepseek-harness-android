#!/usr/bin/env python3
"""校验 APK 签名证书与团队统一密钥库一致，防止签名不一致的包流入分发。

用法：python scripts/verify-apk-signature.py --apk path/to/app.apk
返回 0 表示签名证书 SHA-256 指纹与固定团队指纹一致；否则退出 1。

优先使用 apksigner（支持 v2/v3 签名；本项目 minSdk 26 下 AGP 默认关闭 v1
JAR 签名，keytool 读不了），无 Android SDK 时回退 keytool（仅能读 v1 签名）。
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

# 团队密钥库 dsh-mobile-team.jks 的证书指纹（apksigner/keytool 输出的 SHA-256 值）。
# 指纹是公开信息（APK 内可见），固定于此用于签名一致性校验。
TEAM_CERT_SHA256 = '6D:1B:5F:23:1D:9B:46:95:61:B8:C3:B7:06:A6:30:5E:A7:29:E9:72:37:4D:F1:F2:10:F5:67:57:3D:3C:09:00'

APKSIGNER_DIGEST_PATTERN = re.compile(r'certificate SHA-256 digest:\s*([0-9a-fA-F:]+)')
KEYTOOL_DIGEST_PATTERN = re.compile(r'SHA256:\s*([0-9A-Fa-f:]+)')


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(1)


def find_java() -> str:
    java_home = os.environ.get('JAVA_HOME')
    if java_home:
        for name in ('java.exe', 'java'):
            candidate = Path(java_home) / 'bin' / name
            if candidate.is_file():
                return str(candidate)
    resolved = shutil.which('java')
    if resolved:
        return resolved
    fail('未找到 java：请安装 JDK 或设置 JAVA_HOME')


def sdk_roots() -> list[Path]:
    roots: list[Path] = []
    for name in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        value = os.environ.get(name)
        if value:
            roots.append(Path(value))
    # 本地工程约定：android/local.properties 的 sdk.dir（相对脚本所在仓库根）
    local_properties = Path(__file__).resolve().parent.parent / 'android' / 'local.properties'
    if local_properties.is_file():
        for line in local_properties.read_text(encoding='utf-8').splitlines():
            if line.startswith('sdk.dir='):
                roots.append(Path(line.split('=', 1)[1].replace('\\\\', '\\')))
    return roots


def find_apksigner_jar() -> str | None:
    for root in sdk_roots():
        build_tools = root / 'build-tools'
        if not build_tools.is_dir():
            continue
        jars = sorted(
            (d / 'lib' / 'apksigner.jar' for d in build_tools.iterdir() if (d / 'lib' / 'apksigner.jar').is_file()),
            key=lambda p: p.parts[-3],
            reverse=True,
        )
        if jars:
            return str(jars[0])
    return None


def digest_via_apksigner(java: str, apksigner_jar: str, apk: Path) -> list[str]:
    completed = subprocess.run(
        [java, '-jar', apksigner_jar, 'verify', '--print-certs', str(apk)],
        capture_output=True,
        text=True,
        timeout=120,
    )
    if completed.returncode != 0:
        fail(f'apksigner 校验失败（{apk}）：{(completed.stdout + completed.stderr).strip()[-500:]}')
    return APKSIGNER_DIGEST_PATTERN.findall(completed.stdout)


def find_keytool() -> str | None:
    java_home = os.environ.get('JAVA_HOME')
    if java_home:
        for name in ('keytool.exe', 'keytool'):
            candidate = Path(java_home) / 'bin' / name
            if candidate.is_file():
                return str(candidate)
    return shutil.which('keytool')


def digest_via_keytool(keytool: str, apk: Path) -> list[str]:
    completed = subprocess.run(
        [keytool, '-printcert', '-jarfile', str(apk), '-J-Duser.language=en'],
        capture_output=True,
        text=True,
        timeout=60,
    )
    if completed.returncode != 0:
        fail(f'keytool 解析 APK 签名失败（{apk}）：{(completed.stdout + completed.stderr).strip()[-500:]}')
    return KEYTOOL_DIGEST_PATTERN.findall(completed.stdout + completed.stderr)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True, type=Path, help='待校验的 APK 路径')
    args = parser.parse_args()
    apk: Path = args.apk
    if not apk.is_file():
        fail(f'APK 不存在：{apk}')

    apksigner_jar = find_apksigner_jar()
    if apksigner_jar is not None:
        fingerprints = digest_via_apksigner(find_java(), apksigner_jar, apk)
    else:
        keytool = find_keytool()
        if keytool is None:
            fail('未找到 apksigner 与 keytool：请安装 Android SDK 或 JDK')
        fingerprints = digest_via_keytool(keytool, apk)

    if not fingerprints:
        fail(f'{apk} 未找到签名证书指纹（APK 可能未签名）')
    expected = TEAM_CERT_SHA256.replace(':', '').lower()
    for fingerprint in fingerprints:
        if fingerprint.replace(':', '').lower() == expected:
            print(f'签名校验通过：{TEAM_CERT_SHA256}')
            return
    fail(
        f'签名校验失败：团队证书指纹应为 {TEAM_CERT_SHA256}\n'
        + '\n'.join(f'  实际指纹：{fingerprint}' for fingerprint in fingerprints)
    )


if __name__ == '__main__':
    main()
