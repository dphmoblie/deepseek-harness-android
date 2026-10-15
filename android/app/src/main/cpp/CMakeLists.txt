cmake_minimum_required(VERSION 3.22.1)
project(dsh_pty LANGUAGES C CXX)

# 检测并启用 ccache 编译启动器加速本地重复构建
find_program(CCACHE_PROGRAM ccache)
if(CCACHE_PROGRAM)
    set(CMAKE_C_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
    set(CMAKE_CXX_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
endif()

add_library(dsh_pty SHARED pty_bridge.c)
target_compile_features(dsh_pty PRIVATE c_std_17)
set_target_properties(dsh_pty PROPERTIES
    C_VISIBILITY_PRESET hidden
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
# 16KB 页面兼容（Android 15+/荣耀 A16 等）：打包 ELF 必须 16KB 对齐（p_align 0x4000）。
# 外部预编译的 PRoot runner/loader 需按发布检查清单独立核验。
target_link_options(dsh_pty PRIVATE "-Wl,-z,relro,-z,now" "-Wl,--no-undefined" "-Wl,-z,max-page-size=16384")
