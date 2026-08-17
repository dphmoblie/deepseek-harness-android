cmake_minimum_required(VERSION 3.22.1)
project(dsh_pty LANGUAGES C)

add_library(dsh_pty SHARED pty_bridge.c)
target_compile_features(dsh_pty PRIVATE c_std_17)
# 16KB 页面兼容（Android 15+/荣耀 A16 等）：打包 ELF 必须 16KB 对齐（p_align 0x4000）。
# 外部预编译的 PRoot runner/loader 需独立检查（docs/review-2026-08-16.md §6）。
target_link_options(dsh_pty PRIVATE "-Wl,-z,relro,-z,now" "-Wl,--no-undefined" "-Wl,-z,max-page-size=16384")
