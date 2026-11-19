#!/usr/bin/env bash
set -euo pipefail

OPERIT2_REF="${OPERIT2_REF:?OPERIT2_REF is required}"
CACHE_DIR="${DSH_RUNNER_CACHE_DIR:-$HOME/.cache/operit-android-runtime}"
TMP_WORK_DIR="${DSH_RUNNER_TMP_DIR:-/tmp}"
DOWNLOADS_CACHE="$CACHE_DIR/downloads"
NDK_DIR="$CACHE_DIR/android-ndk-r29-beta4"
NDK_URL="https://dl.google.com/android/repository/android-ndk-r29-beta4-linux.zip"
NDK_SHA="c5db6031a549b5274eed920c45ee3c7da1ac3f1f416cbb512dd9d88417bdf009"

mkdir -p "$DOWNLOADS_CACHE" operit2/tools/android-runtime/downloads operit2/tools/android-runtime/sources

# 每个源允许给一组镜像（英文逗号分隔）并逐个尝试：上游站点会短时不可达，
# 而校验用的 sha256 仍是同一份，缓存命中时仍重算 sha256 再解包，不跳过校验。
fetch_source() {
  local name="$1" url_list="$2" archive="$3" sha="$4"
  local cached="$DOWNLOADS_CACHE/$archive"
  local target="operit2/tools/android-runtime/downloads/$archive"

  if [ -f "$cached" ] && printf '%s  %s\n' "$sha" "$cached" | sha256sum -c - >/dev/null 2>&1; then
    echo "using cached $name ($archive)"
    cp "$cached" "$target"
  else
    echo "downloading $name"
    local ok=0
    local IFS=','
    for url in $url_list; do
      if curl -fL --retry 3 --retry-all-errors --retry-delay 3 --connect-timeout 20 -o "$target" "$url"; then
        ok=1
        break
      fi
      echo "mirror failed: $url" >&2
    done
    [ "$ok" -eq 1 ] || { echo "all mirrors failed for $name" >&2; exit 1; }
    printf '%s  %s\n' "$sha" "$target" | sha256sum -c -
    cp "$target" "$cached"
  fi
  tar -xf "$target" -C operit2/tools/android-runtime/sources
}

fetch_source busybox 'https://busybox.net/downloads/busybox-1.38.0.tar.bz2,https://sources.buildroot.net/busybox/busybox-1.38.0.tar.bz2' busybox-1.38.0.tar.bz2 34f9ea6ff8636f2c9241153b9114eefa9e65674a45318ae1ef95bb5f31c53bb2
fetch_source proot https://github.com/termux/proot/archive/refs/tags/v5.1.107.78.tar.gz termux-proot-v5.1.107.78.tar.gz f3377ba49dcd833370420c34c5081b4c243ecbb85b2a9881a4f4586012599afa
fetch_source talloc 'https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz,https://download.samba.org/pub/talloc/talloc-2.4.3.tar.gz' talloc-2.4.3.tar.gz dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd
fetch_source bash 'https://ftp.gnu.org/gnu/bash/bash-5.2.37.tar.gz,https://mirrors.kernel.org/gnu/bash/bash-5.2.37.tar.gz' bash-5.2.37.tar.gz 9599b22ecd1d5787ad7d3b7bf0c59f312b3396d1e281175dd1f8a4014da621ff

if [ ! -x "$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]; then
  rm -rf "$NDK_DIR"
  curl -fL --retry 3 -o "$TMP_WORK_DIR/ndk.zip" "$NDK_URL"
  printf '%s  %s\n' "$NDK_SHA" "$TMP_WORK_DIR/ndk.zip" | sha256sum -c -
  unzip -q "$TMP_WORK_DIR/ndk.zip" -d "$CACHE_DIR"
  rm -f "$TMP_WORK_DIR/ndk.zip"
else
  echo "using cached NDK r29-beta4 at $NDK_DIR"
fi

(
  cd operit2
  export ANDROID_NDK_HOME="$NDK_DIR"
  export OPERIT_ANDROID_RUNTIME_ABIS="arm64-v8a"
  export OPERIT_ANDROID_RUNTIME_COMPONENTS="proot"
  bash tools/android-runtime/build_android_tools_wsl.sh
)

rm -rf runners
mkdir -p runners
cp operit2/apps/flutter/app/android/app/src/main/jniLibs/arm64-v8a/liboperit_proot.so runners/libdsh_proot.so
cp operit2/apps/flutter/app/android/app/src/main/jniLibs/arm64-v8a/liboperit_loader.so runners/libdsh_proot_loader.so
sha256sum runners/*

rm -rf proot-source "$TMP_WORK_DIR/proot-source.tar"
mkdir -p proot-source/upstream
git -C operit2 archive --format=tar --prefix=operit2/ "$OPERIT2_REF" \
  | gzip -n > proot-source/upstream/operit2-source.tar.gz
cp operit2/tools/android-runtime/downloads/*.tar.* proot-source/upstream/
tar --sort=name --mtime='UTC 1970-01-01' --owner=0 --group=0 --numeric-owner \
  -cf "$TMP_WORK_DIR/proot-source.tar" -C proot-source upstream
gzip -n -c "$TMP_WORK_DIR/proot-source.tar" > proot-corresponding-source.tar.gz
test -s proot-corresponding-source.tar.gz
# 不用 grep -q：pipefail 下提前关闭管道会让 tar 收到 SIGPIPE。
tar -tzf proot-corresponding-source.tar.gz | grep -x 'upstream/operit2-source.tar.gz' >/dev/null
