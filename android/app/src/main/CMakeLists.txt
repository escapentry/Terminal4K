cmake_minimum_required(VERSION 3.22.1)
project(terminal_pty LANGUAGES C)

add_library(terminal_pty SHARED
    cpp/pty_jni.c
)

target_link_libraries(terminal_pty
    log
)
