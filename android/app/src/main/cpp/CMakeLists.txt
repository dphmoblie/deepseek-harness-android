cmake_minimum_required(VERSION 3.22.1)
project(dsh_pty LANGUAGES C)

add_library(dsh_pty SHARED pty_bridge.c)
target_compile_features(dsh_pty PRIVATE c_std_17)
target_link_options(dsh_pty PRIVATE "-Wl,-z,relro,-z,now" "-Wl,--no-undefined")
