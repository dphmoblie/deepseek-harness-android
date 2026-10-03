#!/bin/sh
# dsh-mobile 沙箱运行器（Android + PRoot 专用）。
#
# 为什么需要它：上游 `dsh-sandbox-local` 的 landlock 档把授权根写成“访客根 `/`”，
# 而被 PRoot 真正 execve 的是 **APK 的 nativeLibraryDir 里的 `libdsh_proot_loader.so`**
# （`PROOT_LOADER`；loader 形态见 RuntimeStore.installGuestLoader，真机实测胜出的是
# “根内符号链接 → nativeLibraryDir”）。该真实路径不在访客根之下，Landlock 允许列表
# 因此拒绝这次 exec，内核报的却是用户要运行的程序名，界面上表现为
# `proot error: execve("..."): Permission denied`。
#
# 上游没有提供“追加授权”的通道：`landlock-run` 只接受 argv（源码里没有任何 getenv），
# 而 `LocalSandboxProvider` 在配置了 `runnerCommand` 时会把 bwrap 形态的档参数
# 原样交给这个运行器。于是 App 侧的做法是：
#   1. 每次启动把 APK 的 nativeLibraryDir 绑定到访客固定路径 `/.dsh-native`；
#   2. 用本脚本把 bwrap 形态的参数翻译成 landlock-run 的授权参数；
#   3. 追加 `--ro /.dsh-native`，让 loader 的 exec 获得授权；
#   4. 任何无法识别的参数**失败关闭**（打印 `dsh-sandbox-runner: ...` 并以 125 退出），
#      绝不静默降级成“看起来跑过了”。
#
# 与上游 landlock 档的等价性：两者都只做 Landlock 文件效果限制，
# 都没有 PID / 挂载隔离（上游 landlock 档本来就没有 `--unshare-pid`、`--proc`），
# 因此这里忽略 bwrap 档里的隔离类参数是等价的，不是缩水。
#
# 授权路径只允许绝对路径、且不含空格与 `..`：landlock-run 的 argv 以空格分隔无法表达空格，
# 而“表达不了就失败”比“猜一个”安全。`--ro-bind` / `--bind` 要求源与目标相同：
# 本运行器按真实路径授权，无法表达“把 A 绑到 B”，改名绑定一律失败关闭。

set -u

LAUNCHER="${DSH_MOBILE_LANDLOCK_RUN:-}"
if [ -z "$LAUNCHER" ]; then
    # pnpm 隔离布局：目录名带精确版本号，所以用通配而不是写死版本。
    # 注意：通配必须发生在 `set -f` 之前，否则模式不会展开（会变成“找不到启动器”）。
    for candidate in /opt/dsh/node_modules/.pnpm/@deepseek-ai+node-addon-system-*/node_modules/@deepseek-ai/node-addon-system-*/bin/landlock-run; do
        if [ -x "$candidate" ]; then
            LAUNCHER="$candidate"
            break
        fi
    done
fi

# 授权参数逐个追加：landlock-run 的解析是 `--ro <path>` / `--rw <path>` 重复出现
# （见 node-addon-system 的 src/main.c），一个 flag 只能带一个路径。
#
# 固定授权与上游 landlock 档 `grantArgs({readOnly:["/"], readWrite:["/dev/null", ...]})` 的**效果**对齐：
#   - `--ro /`：访客根（PRoot 视图里的 `/`）——上游档的同名授权；
#   - `--ro /dev`、`--ro /proc`：App 把宿主的 /dev、/proc 绑进访客（RuntimeLaunchResolver 的必需绑定），
#     它们的**真实路径在访客根之外**，不额外授权的话沙箱内读 /dev/urandom、/proc/cpuinfo 都会被拒；
#     桌面上这两处被 `--ro /` 覆盖，所以这里是为移动端布局补齐等价效果；
#   - `--rw /dev/null`：上游档的原样保留；
#   - `--rw /dev/ptmx`、`--rw /dev/pts`：PTY 需要（打开 ptmx、读写从设备）。桌面上 bwrap 档给的是
#     全新的 devtmpfs+devpts，landlock 档本身不覆盖 PTY；移动端没有 bwrap，只能按路径授权。
#
# 除访客根与 loader 目录外的固定授权**都先判存在**：`landlock-run` 打不开授权路径就直接失败，
# 而不同 rootfs 里 `/dev/ptmx`、`/dev/pts` 不一定都在——少一个设备节点不该让每条沙箱命令都失败。
GRANT_ARGS="--ro /"

# PRoot loader 的真实路径（宿主 nativeLibraryDir）在访客里的绑定点，见文件头说明。
NATIVE_LIB="${DSH_MOBILE_NATIVE_LIB:-/.dsh-native}"

fail() {
    echo "dsh-sandbox-runner: $1" >&2
    exit 125
}

valid_path() {
    case "$1" in
        /*) ;;
        *) return 1 ;;
    esac
    case "$1" in
        *" "*) return 1 ;;
        *".."*) return 1 ;;
    esac
    return 0
}

add_ro() {
    valid_path "$1" || fail "unsupported read-only path"
    GRANT_ARGS="$GRANT_ARGS --ro $1"
}

add_rw() {
    valid_path "$1" || fail "unsupported read-write path"
    GRANT_ARGS="$GRANT_ARGS --rw $1"
}

while [ "$#" -gt 0 ]; do
    argument="$1"
    shift
    case "$argument" in
        --)
            break
            ;;
        --ro-bind)
            [ "$#" -ge 2 ] || fail "missing operands for --ro-bind"
            source_path="$1"
            shift
            target_path="$1"
            shift
            [ "$source_path" = "$target_path" ] || fail "renamed bind is unsupported"
            add_ro "$source_path"
            ;;
        --bind)
            [ "$#" -ge 2 ] || fail "missing operands for --bind"
            source_path="$1"
            shift
            target_path="$1"
            shift
            [ "$source_path" = "$target_path" ] || fail "renamed bind is unsupported"
            add_rw "$source_path"
            ;;
        --tmpfs)
            [ "$#" -ge 1 ] || fail "missing operand for --tmpfs"
            tmpfs_path="$1"
            shift
            # bwrap 会**新建**这个目录；landlock 只能授权已存在的路径。
            # 缺了就跳过这次授权：让「没有临时目录」退化成命令自己的报错，
            # 而不是让整条命令在启动器层失败（那会变成一条误导的“沙箱不可用”）。
            if [ -e "$tmpfs_path" ]; then
                add_rw "$tmpfs_path"
            fi
            ;;
        --dev)
            # bwrap 的 `--dev /dev`：上游 landlock 档只授 `--rw /dev/null`，这里等价处理。
            [ "$#" -ge 1 ] || fail "missing operand for --dev"
            shift
            ;;
        --proc)
            # PRoot 已经在访客里提供 /proc，且上游 landlock 档不做挂载隔离。
            [ "$#" -ge 1 ] || fail "missing operand for --proc"
            shift
            ;;
        --unshare-pid|--die-with-parent)
            # 上游 landlock 档没有这两个隔离；Android 应用也没有创建 PID 命名空间的权限。
            ;;
        *)
            fail "unsupported argument: $argument"
            ;;
    esac
done

[ "$#" -gt 0 ] || fail "missing command"
[ -n "$LAUNCHER" ] || fail "landlock launcher not found"
[ -x "$LAUNCHER" ] || fail "landlock launcher is not executable"
# loader 目录是这条运行器存在的理由：缺了它宁可失败关闭，也不要跑出一条“看起来通过”的命令。
[ -e "$NATIVE_LIB" ] || fail "native library directory not found"
add_ro "$NATIVE_LIB"
for fixed_ro in /dev /proc; do
    [ -e "$fixed_ro" ] && add_ro "$fixed_ro"
done
for fixed_rw in /dev/null /dev/ptmx /dev/pts; do
    [ -e "$fixed_rw" ] && add_rw "$fixed_rw"
done

# 从这里开始关闭通配：授权串要按空格拆分展开，且不允许路径被当成模式匹配。
set -f

# shellcheck disable=SC2086 # 授权串按空格展开是刻意的：每个路径都已校验不含空格，且已关闭通配。
exec "$LAUNCHER" $GRANT_ARGS -- "$@"
