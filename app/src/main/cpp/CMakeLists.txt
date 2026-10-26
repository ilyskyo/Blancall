# Copyright (c) 2026 ilyskyo
# SPDX-License-Identifier: MIT
#
# 手写识别 native 模块。
#
# 依赖 ncnn（腾讯开源，BSD-3-Clause）Android 预编译包，已解压到
# third_party/ncnn-android/<abi>/。模型为 Ismantic/Handwritten (Apache-2.0)，
# 以 assets 形式随 APK 分发，运行时解包到 filesDir 后由 NCNN 读取。
#
# 注意 ncnn 预编译包分两类：
#   * ncnn-<ver>-android-vulkan.zip  —— 含 Vulkan GPU 支持，链接时需要
#     glslang / SPIRV 静态库；本模块只用 CPU，但预编译包里 GPU 目标文件
#     仍会被拉入，故必须一并链接上图库，否则报大量 undefined symbol。
#   * ncnn-<ver>-android.zip         —— 纯 CPU，无 glslang 依赖。
# 本工程使用 vulkan 包，并按 ncnn 自带的 ncnnConfig.cmake 提供的
# imported target 链接，避免手工拼库列表遗漏依赖。

cmake_minimum_required(VERSION 3.22.1)
project("blancall_hccr")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- ncnn：优先用官方 CMake 配置（会带出 glslang/OpenMP 等传递依赖） ----
set(NCNN_ROOT "${CMAKE_SOURCE_DIR}/third_party/ncnn-android/${ANDROID_ABI}")

if(NOT EXISTS "${NCNN_ROOT}/lib/libncnn.a")
    message(FATAL_ERROR
        "ncnn static lib not found for ABI '${ANDROID_ABI}' at ${NCNN_ROOT}/lib/libncnn.a. "
        "Download the ncnn Android prebuilt package and extract it into "
        "app/src/main/cpp/third_party/ncnn-android/.")
endif()

list(APPEND CMAKE_PREFIX_PATH "${NCNN_ROOT}")
set(ncnn_DIR "${NCNN_ROOT}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)

# ---- 手写识别模块 ----
add_library(blancall_hccr SHARED
    hccr_jni.cpp
    preprocess.c)

target_include_directories(blancall_hccr PRIVATE "${CMAKE_SOURCE_DIR}")

# ncnn 的 imported target 已通过 INTERFACE_LINK_LIBRARIES 带出
# -fopenmp / Threads / dl / glslang / SPIRV / android / jnigraphics / log，
# 无需在此重复声明。
target_link_libraries(blancall_hccr ncnn)

# 16 KB page size：NDK r27 默认仍按 4 KB 对齐 LOAD 段；targetSdk 37 起
# 16KB 设备要求 native 库可加载，显式指定最大/公共页大小。
target_link_options(blancall_hccr PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")

# ncnn 静态库里含 Vulkan 相关目标文件，其中引用了 dlopen 等 libdl 符号；
# Android 上 libdl 已并入 libc，但显式链接可避免部分 NDK 版本的可见性问题。
find_library(dl_lib dl)
if(dl_lib)
    target_link_libraries(blancall_hccr ${dl_lib})
endif()

# ARMv7 上启用 NEON（ncnn 预编译包已按 NEON 编译，保持一致的编译标志）
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set_target_properties(blancall_hccr PROPERTIES
        COMPILE_FLAGS "-mfpu=neon")
endif()
