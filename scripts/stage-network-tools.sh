#!/usr/bin/env bash
# 在 ubuntu-24.04-arm runner 上把「网络工具」组件组预置成与 rootfs 同构的目录树。
#
#   stage-network-tools.sh <目标目录> [ubuntu-base 归档] [包清单输出路径]
#
# 包清单用于 scripts/legal-notices.py collect：每个落进镜像的文件由哪个包提供
# （dpkg -S 反查），据此收集发行版版权文件，传递依赖库也不会漏登记。
#
# 为什么这样做：
#   * runner 与镜像**同发行版同架构**（ubuntu-24.04-arm），包在 runner 上取，
#     绝不在镜像里跑 apt —— 沿用 scripts/build-embedded-runtime.py 既有的取工具模式；
#   * `cp -a --parents` 保留归档内绝对路径与硬链接：Ubuntu 的 git 把上百个内建
#     子命令硬链接到同一个 git 二进制（arm64 安装体积 21.8 MB，而 .deb 只有
#     3.6 MB）；硬链接一旦丢失，rootfs 会凭空膨胀数百 MB
#     （build-embedded-runtime.py 有 96 MB 的体积上限兜底）；
#   * 只复制需要的路径，不复制 /usr/share/doc、/usr/share/man、/usr/share/locale
#     （git 的 man page 由单独的 git-man 包提供，不取）；
#   * 共享库按 ldd 传递闭包收集，并跳过 ubuntu-base 已带的路径：rootfs 里出现
#     重复路径会被 SafeRootfsExtractor 以 ARCHIVE_DUPLICATE_ENTRY 拒绝；
#   * CA 信任库**重新生成**，不直接搬 runner 上的 /etc/ssl/certs：runner 上那份
#     可能已包含本机额外注入的 CA（企业代理的中间人证书），照搬等于把额外的
#     信任锚带进用户设备。
#
# 环境变量：DSH_SKIP_OPENSSH=1 时不预置 openssh-client（该组件是可选件）。
set -euo pipefail

DEST="${1:?用法: stage-network-tools.sh <目标目录> [ubuntu-base 归档] [包清单输出路径]}"
BASE_ARCHIVE="${2:-}"
PACKAGE_LIST="${3:-${DEST%/}-packages.txt}"
SKIP_OPENSSH="${DSH_SKIP_OPENSSH:-0}"
CA_KEY_DIR="/usr/share/ca-certificates/mozilla"
CA_MIN_FILES=64

log() { printf '[stage-network-tools] %s\n' "$*"; }

# ---- 1. 在 runner 上安装组件（不在镜像里跑 apt）--------------------------------
PACKAGES=(git curl ca-certificates libexpat1)
if [[ "$SKIP_OPENSSH" != "1" ]]; then
  PACKAGES+=(openssh-client)
fi
sudo apt-get update -qq
sudo apt-get install -y -qq --no-install-recommends "${PACKAGES[@]}"
log "已安装: ${PACKAGES[*]}"

# ---- 2. 基线镜像已有路径（过滤掉，避免 rootfs 重复条目）------------------------
BASE_PATHS="$(mktemp)"
ALL_ENTRIES="$(mktemp)"
COPY_LIST="$(mktemp)"
NEW_LIBS="$(mktemp)"
STAGED_PATHS="$(mktemp)"
trap 'rm -f "$BASE_PATHS" "$ALL_ENTRIES" "$COPY_LIST" "$NEW_LIBS" "$NEW_LIBS.filtered" "$STAGED_PATHS"' EXIT
if [[ -n "$BASE_ARCHIVE" && -f "$BASE_ARCHIVE" ]]; then
  # **必须归一化成与候选清单同一种写法（前导 `/`）**：tar 列出的是 `./usr/…` 或 `usr/…`，
  # 而候选清单来自 `find /usr/bin/...` 的绝对路径。写法不一致时 `comm` 永远不匹配，
  # 过滤等于没做——CI 日志里「候选条目: 357，去掉基线镜像已有后: 357」就是它的表现。
  # 后果不只是白复制：把基线已有的文件再放进增量树，会在最终 rootfs 里形成重复条目。
  tar -tzf "$BASE_ARCHIVE" | sed 's:/$::; s:^\./::; s:^:/:' | LC_ALL=C sort -u > "$BASE_PATHS"
  log "基线镜像路径数: $(wc -l < "$BASE_PATHS")"
else
  : > "$BASE_PATHS"
  log "警告: 未提供 ubuntu-base 归档，无法过滤基线镜像已有路径"
fi

# ---- 3. 候选源路径 --------------------------------------------------------------
# 注意：git 的 perl 依赖（git add -p / git send-email / git svn 等）不复制，
# 核心的 clone/commit/diff/push/status/log 不依赖 perl。
SOURCES=(
  /usr/bin/git
  /usr/lib/git-core
  /usr/share/git-core/templates
  /usr/bin/curl
  /usr/lib/ssl
  "$CA_KEY_DIR"
)
if [[ "$SKIP_OPENSSH" != "1" ]]; then
  SOURCES+=(
    /usr/bin/ssh
    /usr/bin/scp
    /usr/bin/sftp
    /usr/bin/ssh-keygen
    /usr/bin/ssh-keyscan
    /usr/bin/ssh-add
    /usr/bin/ssh-agent
    /usr/lib/openssh
  )
fi

enumerate_entries() {
  local source entry
  for source in "$@"; do
    if [[ -d "$source" && ! -L "$source" ]]; then
      while IFS= read -r entry; do
        printf '%s\n' "$entry"
      done < <(find "$source" \( -type f -o -type l \) -print)
    elif [[ -e "$source" || -L "$source" ]]; then
      printf '%s\n' "$source"
    else
      log "跳过不存在的路径: $source"
    fi
  done
}

enumerate_entries "${SOURCES[@]}" | sed 's:/$::' | LC_ALL=C sort -u > "$ALL_ENTRIES"
LC_ALL=C comm -23 "$ALL_ENTRIES" "$BASE_PATHS" > "$COPY_LIST"
log "候选条目: $(wc -l < "$ALL_ENTRIES")，去掉基线镜像已有后: $(wc -l < "$COPY_LIST")"

# ---- 4. 一次性复制（同一次 cp 调用才会保留硬链接）------------------------------
mkdir -p "$DEST"
if [[ -s "$COPY_LIST" ]]; then
  mapfile -t COPY_PATHS < "$COPY_LIST"
  cp -a --parents "${COPY_PATHS[@]}" "$DEST/"
  cat "$COPY_LIST" >> "$STAGED_PATHS"
fi

# ---- 5. 共享库传递闭包 ----------------------------------------------------------
# 两种取依赖的方式，**ldd 优先、readelf 兜底**：
#   * `ldd` 直接给出解析后的路径，最省事；
#   * 但它必须能「把这个二进制跑起来」才有输出，一旦失败（缺解释器、被执行策略挡下、
#     ldd 自身不可用），输出为空而错误被 `2>/dev/null` 吞掉 —— **闭包会静默变成空集**。
#     O-7 就是这个后果：已发布的产物里 `usr/bin/curl` / `usr/bin/ssh` / `git-remote-https` 都在，
#     而 libcurl / libgssapi / libkrb5 **一个都没进去**（45,439 个条目里零命中），
#     真机上三个命令全部启动失败，而所有「存在 + 0755 + ELF」的校验都是绿的。
#   * `readelf -d` 只读 DT_NEEDED 的 SONAME，再用 `ldconfig -p` 解析成路径，
#     完全不依赖能否运行文件；某条二进制用 ldd 取不到依赖时会自动改走它。
LIBDEPS_METHOD_COUNTS=""
collect_lib_paths() {
  local binary="$1" lib soname resolved
  local via_ldd=0 via_readelf=0
  while IFS= read -r lib; do
    [[ -n "$lib" ]] || continue
    via_ldd=$((via_ldd + 1))
    printf '%s\n' "$lib"
  done < <(ldd "$binary" 2>/dev/null | awk '/=>/ {print $3} !/=>/ && /^\// {print $1}')
  if [[ "$via_ldd" == "0" ]]; then
    while IFS= read -r soname; do
      [[ -n "$soname" ]] || continue
      # `|| true` 同样是为了不被 `set -e` 打死：`ldconfig -p` 在某些环境需要额外权限，
      # 一旦非零退出，赋值语句就带上非零状态，脚本会**无声退出**（这一整类问题的共同形态）。
      resolved="$( { ldconfig -p 2>/dev/null || true; } | awk -v s="$soname" '$1 == s {print $NF; exit}')"
      [[ -n "$resolved" ]] || continue
      via_readelf=$((via_readelf + 1))
      printf '%s\n' "$resolved"
    done < <(readelf -d "$binary" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
  fi
  LIBDEPS_METHOD_COUNTS="${LIBDEPS_METHOD_COUNTS}${via_ldd}/${via_readelf} "
}

TOTAL_LIBS_COLLECTED=0
# 闭包前诊断：这一步是把「闭包为空」从一句结论变成可定位的事实。
# 实测教训：CI 上第一次加了熔断后只看到「闭包为空」，但看不出是「没复制进文件」、
# 「工具不可用」还是「工具取不到依赖」——三者修法完全不同，所以先把它们分开打出来。
log "闭包前诊断: DEST 文件数=$(find "$DEST" -type f 2>/dev/null | wc -l) 候选清单行数=$(wc -l < "$COPY_LIST")"
for tool in ldd readelf ldconfig; do
  if command -v "$tool" >/dev/null 2>&1; then
    log "  工具 $tool: $(command -v "$tool")"
  else
    log "  工具 $tool: **不可用**"
  fi
done
SAMPLE_BIN="$(find "$DEST" -type f -perm -u+x 2>/dev/null | head -n1 || true)"
if [[ -n "$SAMPLE_BIN" ]]; then
  log "  样例二进制: $SAMPLE_BIN"
  log "    ldd 输出行数=$( { ldd "$SAMPLE_BIN" 2>&1 || true; } | wc -l)  首行=$( { ldd "$SAMPLE_BIN" 2>&1 || true; } | head -n1)"
  log "    readelf -d 行数=$( { readelf -d "$SAMPLE_BIN" 2>&1 || true; } | wc -l)  首行=$( { readelf -d "$SAMPLE_BIN" 2>&1 || true; } | head -n1)"
else
  log "  样例二进制: **DEST 里没有可执行文件**（说明第 4 步的复制没落地）"
fi
for _round in 1 2 3 4 5; do
  : > "$NEW_LIBS"
  while IFS= read -r -d '' binary; do
    while IFS= read -r lib; do
      [[ -n "$lib" && -e "$lib" ]] || continue
      # SONAME 链接与解析后的实体都要在镜像里，动态加载器才找得到。
      for candidate in "$lib" "$(readlink -f "$lib" 2>/dev/null || true)"; do
        [[ -n "$candidate" && -e "$candidate" ]] || continue
        [[ -e "$DEST/${candidate#/}" ]] && continue
        printf '%s\n' "$candidate"
      done
    done < <(collect_lib_paths "$binary")
  done < <(find "$DEST" -type f -print0) > "$NEW_LIBS"
  #                      ^^^^^^^^^^^^^^^^^^^^^^^^^^^^
  # 这个重定向是 O-7 的**真正根因**：原来这里没有它，于是 `printf '%s\n' "$candidate"`
  # 收集到的库路径全部打到了**脚本的标准输出**（进了 CI 日志），而 `$NEW_LIBS` 自始至终是空的
  # —— `sort -u -o "$NEW_LIBS" "$NEW_LIBS"` 排的是一个永远空的文件，闭包从未生效。
  # 表现就是：二进制装进了镜像、运行库一个都没有，而所有「存在 + 0755 + ELF」的校验都是绿的。
  LC_ALL=C sort -u -o "$NEW_LIBS" "$NEW_LIBS"
  LC_ALL=C comm -23 "$NEW_LIBS" "$BASE_PATHS" > "$NEW_LIBS.filtered"
  if [[ ! -s "$NEW_LIBS.filtered" ]]; then
    break
  fi
  mapfile -t ROUND_LIBS < "$NEW_LIBS.filtered"
  cp -a --parents "${ROUND_LIBS[@]}" "$DEST/"
  printf '%s\n' "${ROUND_LIBS[@]}" >> "$STAGED_PATHS"
  TOTAL_LIBS_COLLECTED=$((TOTAL_LIBS_COLLECTED + ${#ROUND_LIBS[@]}))
  log "第 ${_round} 轮补齐共享库: ${#ROUND_LIBS[@]} 个（各二进制的 ldd/readelf 命中数: ${LIBDEPS_METHOD_COUNTS}）"
done

# 熔断：一个共享库都没收集到，说明两种取依赖的方式都失效了。
# 这种情况**绝不能继续**——产物会带着一批「装得上、跑不起来」的命令发布出去（O-7）。
if ((TOTAL_LIBS_COLLECTED == 0)); then
  echo "共享库闭包为空：ldd 与 readelf 都没取到依赖，拒绝产出一个跑不起来的组件组" >&2
  exit 1
fi

# ---- 5b. 反查提供这些文件的包（供法律材料收集使用）-----------------------------
if [[ -s "$STAGED_PATHS" ]]; then
  mapfile -t STAGED_ARRAY < <(LC_ALL=C sort -u "$STAGED_PATHS")
  # dpkg -S 对未登记路径会报错并返回非零：这里只取命中行，遗漏项由包清单的
  # 必需组件校验（legal-notices.py）兜底。
  #
  # **必须显式 `|| true`**：脚本开着 `set -o pipefail`，dpkg 的非零退出会顺着管道
  # 把整个脚本**静默终止**（只留下 exit code 1，没有任何错误信息）。
  # 这个坑一直被上一条问题掩盖着：闭包坏掉时 STAGED_PATHS 里只有二进制、全都能被 dpkg 认出来；
  # 闭包修好后一下子多出两百多个库路径，只要有一个不属于任何包，脚本就在这一步无声死掉。
  log "反查包清单: $(wc -l < "$STAGED_PATHS") 条路径"
  { dpkg -S "${STAGED_ARRAY[@]}" 2>/dev/null || true; } \
    | awk -F': ' '{print $1}' \
    | tr ',' '\n' \
    | sed 's/^[[:space:]]*//; s/[[:space:]]*$//' \
    | grep -v '^$' \
    | LC_ALL=C sort -u > "$PACKAGE_LIST"
  log "包清单: $(wc -l < "$PACKAGE_LIST") 个包 -> $PACKAGE_LIST"
fi

# ---- 6. 重新生成 CA 信任库 ------------------------------------------------------
[[ -d "$CA_KEY_DIR" ]] || { echo "缺少 $CA_KEY_DIR：ca-certificates 未正确安装" >&2; exit 1; }
mkdir -p "$DEST/etc/ssl/certs"
: > "$DEST/etc/ssl/certs/ca-certificates.crt"
CA_COUNT=0
for cert in "$CA_KEY_DIR"/*.crt; do
  [[ -f "$cert" ]] || continue
  name="$(basename "$cert" .crt)"
  cp "$cert" "$DEST/etc/ssl/certs/${name}.pem"
  # 目录式查找用的 <hash>.N 链接（相对目标，guest 内可解析）
  hash="$(openssl x509 -hash -noout -in "$cert")"
  ln -sf "${name}.pem" "$DEST/etc/ssl/certs/${hash}.0"
  cat "$cert" >> "$DEST/etc/ssl/certs/ca-certificates.crt"
  CA_COUNT=$((CA_COUNT + 2))
done
CA_COUNT=$((CA_COUNT + 1))
log "CA 信任库: 证书 ${CA_COUNT} 项（.pem + <hash>.0 + ca-certificates.crt）"

# ---- 7. 失败即失败：必需文件、CA 下限、悬空链接 ---------------------------------
require_file() {
  [[ -f "$DEST/${1#/}" ]] || { echo "缺少必需文件: $1" >&2; exit 1; }
}
require_file usr/bin/git
require_file usr/lib/git-core/git-remote-https
require_file usr/bin/curl
require_file etc/ssl/certs/ca-certificates.crt
if ((CA_COUNT < CA_MIN_FILES)); then
  echo "CA 条目不足: $CA_COUNT < $CA_MIN_FILES" >&2
  exit 1
fi
# 用 -xtype l 而非 -L -type l：预置树里有指向宿主机绝对路径的软链
# （/usr/lib/ssl/private -> /etc/ssl/private，0710 root:ssl-cert），
# -L 会真的走进去，非 root runner 立刻 EACCES、find 以 1 退出，set -e 直接终止；
# -xtype 只 stat 链接目标、不遍历目标目录，同样能判出悬空链接。
#
# **但「在本树里解析不到」不等于「悬空」**：本树是**基线镜像之上的增量**，
# 指向基线提供文件的链接（例如 libc 家族的 SONAME 链接）在这里当然解析不到，
# 而在最终 rootfs 里（base + delta）是完好的。CI 上就撞到过一条：
# `/lib/aarch64-linux-gnu/libffi.so.8` —— 目标由基线镜像提供，被误判成悬空。
# 因此判定改为：宿主上解析得到、且解析结果在基线清单里 ⇒ 放过；否则才算真悬空。
BROKEN_LINK=""
while IFS= read -r link; do
  rel="${link#"$DEST"/}"
  resolved="$(readlink -f "/$rel" 2>/dev/null | sed 's:^/::' || true)"
  if [[ -n "$resolved" ]] && grep -qxF "/$resolved" "$BASE_PATHS"; then
    continue
  fi
  BROKEN_LINK="$link"
  break
done < <(find "$DEST" -xtype l)
if [[ -n "$BROKEN_LINK" ]]; then
  echo "预置树存在悬空符号链接（目标既不在本树也不在基线镜像里）: $BROKEN_LINK" >&2
  exit 1
fi

# 预置的 git/curl 必须真能运行（同架构 runner 上直接跑）
STAGED_GIT="$("$DEST/usr/bin/git" --version)"
STAGED_CURL="$("$DEST/usr/bin/curl" --version | head -n1)"
log "预置结果: ${STAGED_GIT} / ${STAGED_CURL}"

# 体积口径：磁盘占用（du）远小于表观大小（apparent）说明硬链接保住了。
APPARENT_BYTES="$(find "$DEST" -type f -printf '%s\n' | awk '{total += $1} END {print total + 0}')"
log "文件数=$(find "$DEST" -type f | wc -l) 链接数=$(find "$DEST" -type l | wc -l) 表观字节=${APPARENT_BYTES} 磁盘字节=$(du -sb "$DEST" | cut -f1)"
du -sh "$DEST"
