#!/usr/bin/env bash
# 只扫「本次推送区间」的凭据扫描包装脚本（CI 的 gates 作业调用）。
#
# 为什么不用 gitleaks 的默认模式：
#   * 全历史扫描（git 模式不带 --log-opts）会把仓库早期已经清理掉的诱饵值再报一遍，
#     门禁一上线就是红的，人就开始无视它；
#   * 目录模式（dir）会把未跟踪的构建产物一起扫进来，本仓库里那是几十条误报；
#   * 只扫 tip 提交（--log-opts=-1）又太窄，一次推送多个提交时中间的提交根本没过扫描。
# 所以这里显式按区间扫，并且**不允许**「一个提交都没扫到」当成通过：gitleaks 在区间取错时
# 会打印 0 commits scanned 并以 0 退出，不自己验就会变成一道永远绿灯的门禁。
#
# 用法：
#   scripts/ci-gitleaks-range-scan.sh --gitleaks <可执行文件> --base <基线提交> [--head <提交>]
# 退出码：0 通过；1 区间内有凭据或区间扫不到提交；2 用法/环境问题。
set -euo pipefail

gitleaks_bin=""
base=""
head=""

用法() {
  cat <<'用法结束'
用法：scripts/ci-gitleaks-range-scan.sh --gitleaks <可执行文件> --base <基线提交> [--head <提交>]

  --gitleaks   gitleaks 可执行文件路径（CI 里是刚下载并校验过摘要的那份）
  --base       本次推送的基线提交（PR 的 base sha 或 push 事件的 before）；不可用时退回 HEAD^
  --head       本次推送的头提交，默认 HEAD
  -h, --help   打印本说明
用法结束
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --gitleaks)
      gitleaks_bin="${2:-}"
      shift 2
      ;;
    --base)
      base="${2:-}"
      shift 2
      ;;
    --head)
      head="${2:-}"
      shift 2
      ;;
    -h | --help)
      用法
      exit 0
      ;;
    *)
      echo "未知参数：$1" >&2
      用法 >&2
      exit 2
      ;;
  esac
done

if [ -z "${gitleaks_bin}" ] || [ ! -f "${gitleaks_bin}" ]; then
  echo "找不到 gitleaks 可执行文件：${gitleaks_bin:-（未指定）}" >&2
  exit 2
fi
if [ ! -f .gitleaks.toml ]; then
  echo "当前目录没有 .gitleaks.toml，请在仓库根目录运行本脚本" >&2
  exit 2
fi

if [ -z "${head}" ]; then
  head="$(git rev-parse HEAD)"
fi
if ! git cat-file -e "${head}^{commit}" 2>/dev/null; then
  echo "头提交不存在于本仓库：${head}" >&2
  exit 2
fi

# 基线不可用时（新建分支、强推、手工触发、仓库只有一个提交）逐级回退，
# 绝不因为「取不到基线」就直接放行成「什么都不扫」。
if [ -n "${base}" ] && git cat-file -e "${base}^{commit}" 2>/dev/null; then
  :
else
  echo "基线提交 ${base:-（空）} 不在本仓库或不可用，退回 HEAD 上一提交"
  base="$(git rev-parse HEAD^ 2>/dev/null || true)"
fi

if [ -n "${base}" ]; then
  range="${base}..${head}"
  echo "扫描区间：${range}"
else
  range="-1"
  echo "仓库只有 ${head} 一个提交，只扫这一个提交"
fi

set +e
log="$("${gitleaks_bin}" git --no-banner --redact --config .gitleaks.toml --log-opts="${range}" 2>&1)"
status="$?"
set -e
printf '%s\n' "${log}"

if ! printf '%s\n' "${log}" | grep -Eq '[1-9][0-9]* commits? scanned'; then
  echo "::error::gitleaks 没有扫到任何提交，判定为门禁失效（区间 ${range} 取错？）" >&2
  exit 1
fi
if [ "${status}" != "0" ]; then
  echo "::error::区间 ${range} 内发现疑似凭据（已 --redact，日志里不会打印明文），请改用环境变量或密钥库" >&2
  exit 1
fi

echo "凭据扫描通过：区间 ${range} 内没有发现凭据"
