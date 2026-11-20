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
# 参数传递采用纯 POSIX 位置参数轮转（不依赖 bash 数组、不做无引号空白拆分）：
# 因而工作目录或共享目录包含空格、中文或通配符字符时也能原样传给 `landlock-run`，
# 不再误报 `unsupported read-write path` 导致 PTY 静默秒退。
# 授权路径仍要求绝对路径、不含 `..` 且不含换行/回车控制字符；
# `--ro-bind` / `--bind` 要求源与目标相同，改名绑定一律失败关闭。

set -u

LAUNCHER="${DSH_MOBILE_LANDLOCK_RUN:-}"
if [ -z "$LAUNCHER" ]; then
    # pnpm 隔离布局：目录名带精确版本号，所以用通配而不是写死版本。
    for candidate in /opt/dsh/node_modules/.pnpm/@deepseek-ai+node-addon-system-*/node_modules/@deepseek-ai/node-addon-system-*/bin/landlock-run; do
        if [ -x "$candidate" ]; then
            LAUNCHER="$candidate"
            break
        fi
    done
fi

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
        *".."*|*"
"*|*"$(printf '\r')"*) return 1 ;;
    esac
    return 0
}

# 将原始 argv 拆分为「前置 bwrap 选项」与「`--` 后的命令」，并用位置参数队列把翻译后的
# `--ro <path>` / `--rw <path>` 依次追加到 `"$@"` 尾部，全程保持单参数完整性。
REMAINING="$#"
FOUND_SEP=0

while [ "$REMAINING" -gt 0 ]; do
    argument="$1"
    shift
    REMAINING=$((REMAINING - 1))
    case "$argument" in
        --)
            FOUND_SEP=1
            break
            ;;
        --ro-bind)
            [ "$REMAINING" -ge 2 ] || fail "missing operands for --ro-bind"
            source_path="$1"
            shift
            target_path="$1"
            shift
            REMAINING=$((REMAINING - 2))
            [ "$source_path" = "$target_path" ] || fail "renamed bind is unsupported"
            valid_path "$source_path" || fail "unsupported read-only path"
            set -- "$@" --ro "$source_path"
            ;;
        --bind)
            [ "$REMAINING" -ge 2 ] || fail "missing operands for --bind"
            source_path="$1"
            shift
            target_path="$1"
            shift
            REMAINING=$((REMAINING - 2))
            [ "$source_path" = "$target_path" ] || fail "renamed bind is unsupported"
            valid_path "$source_path" || fail "unsupported read-write path"
            set -- "$@" --rw "$source_path"
            ;;
        --tmpfs)
            [ "$REMAINING" -ge 1 ] || fail "missing operand for --tmpfs"
            tmpfs_path="$1"
            shift
            REMAINING=$((REMAINING - 1))
            # bwrap 会**新建**这个目录；landlock 只能授权已存在的路径。
            # 缺了就跳过这次授权：让「没有临时目录」退化成命令自己的报错，
            # 而不是让整条命令在启动器层失败（那会变成一条误导的“沙箱不可用”）。
            if [ -e "$tmpfs_path" ]; then
                valid_path "$tmpfs_path" || fail "unsupported read-write path"
                set -- "$@" --rw "$tmpfs_path"
            fi
            ;;
        --dev)
            # bwrap 的 `--dev /dev`：上游 landlock 档只授 `--rw /dev/null`，这里等价处理。
            [ "$REMAINING" -ge 1 ] || fail "missing operand for --dev"
            shift
            REMAINING=$((REMAINING - 1))
            ;;
        --proc)
            # PRoot 已经在访客里提供 /proc，且上游 landlock 档不做挂载隔离。
            [ "$REMAINING" -ge 1 ] || fail "missing operand for --proc"
            shift
            REMAINING=$((REMAINING - 1))
            ;;
        --unshare-pid|--die-with-parent)
            # 上游 landlock 档没有这两个隔离；Android 应用也没有创建 PID 命名空间的权限。
            ;;
        *)
            fail "unsupported argument: $argument"
            ;;
    esac
done

[ "$FOUND_SEP" -eq 1 ] && [ "$REMAINING" -gt 0 ] || fail "missing command"
[ -n "$LAUNCHER" ] || fail "landlock launcher not found"
[ -x "$LAUNCHER" ] || fail "landlock launcher is not executable"
# loader 目录是这条运行器存在的理由：缺了它宁可失败关闭，也不要跑出一条“看起来通过”的命令。
[ -e "$NATIVE_LIB" ] || fail "native library directory not found"
valid_path "$NATIVE_LIB" || fail "unsupported read-only path"

# 固定授权与上游 landlock 档对齐（见原注释）：访客根、loader 目录、宿主 /dev 与 /proc，
# 以及 PTY/空设备节点；除访客根与 loader 目录外均先判存在。
set -- "$@" --ro / --ro "$NATIVE_LIB"
for fixed_ro in /dev /proc; do
    if [ -e "$fixed_ro" ]; then
        set -- "$@" --ro "$fixed_ro"
    fi
done
for fixed_rw in /dev/null /dev/ptmx /dev/pts; do
    if [ -e "$fixed_rw" ]; then
        set -- "$@" --rw "$fixed_rw"
    fi
done

# 把仍在队首的 `$REMAINING` 个目标命令参数原样轮转到 `--` 之后。
set -- "$@" --
while [ "$REMAINING" -gt 0 ]; do
    cmd_arg="$1"
    shift
    set -- "$@" "$cmd_arg"
    REMAINING=$((REMAINING - 1))
done

exec "$LAUNCHER" "$@"
