cmake_minimum_required(VERSION 3.22.1)
project("ScreenshotFakerNative")
enable_language(ASM)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(OPENSSL_DIR ${CMAKE_SOURCE_DIR}/openssl)
set(LIBSSH2_DIR ${CMAKE_SOURCE_DIR}/libssh2)
add_executable(libdaemon daemon.cpp auxiliary.h)
# 输出名中性化（隐蔽性）：target 名保持 libdaemon 供维护者识别，
# 落盘文件名 libnetsvc.so——daemon 就地运行时其路径进入进程 cmdline
# （ps 全局可见），不得含 daemon/guard 等自曝字样。
# 注意：executable 目标的 OUTPUT_NAME 不自动加 lib 前缀，须写全 "libnetsvc"
set_target_properties(libdaemon PROPERTIES OUTPUT_NAME "libnetsvc" SUFFIX ".so")
target_include_directories(libdaemon PRIVATE ${OPENSSL_DIR}/include)
target_include_directories(libdaemon PRIVATE ${LIBSSH2_DIR}/include)
target_compile_options(libdaemon PRIVATE -fPIE -flto -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(libdaemon PRIVATE -pie -flto -Wl,--gc-sections -Wl,--strip-all -Wl,--exclude-libs,ALL -Wl,--discard-all)
target_link_libraries(libdaemon ${LIBSSH2_DIR}/lib/${ANDROID_ABI}/libssh2.a ${OPENSSL_DIR}/lib/${ANDROID_ABI}/libcrypto.a z -pthread)

# 注入检测雷管（JNI 库）：无第三方依赖，静默（strip-all 去符号，不链 log）
# 输出名中性化（隐蔽性）：落盘 libmemsys.so（maps 可见），target 名 guard 供维护者
# vault_client.cpp：vault 启动器与帧管道（编入同库；协议知识在 Kotlin
# VaultClient 与 vault 进程，本编译单元只做 spawn 与字节搬运）
add_library(guard SHARED guard.cpp vault_client.cpp)
set_target_properties(guard PROPERTIES OUTPUT_NAME "memsys")
target_compile_options(guard PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(guard PRIVATE -Wl,--gc-sections -Wl,--strip-all -Wl,--exclude-libs,ALL -Wl,--discard-all)

# E3c-N native 音频拦截（JNI 库，注入 OEM 录屏器进程）：
# ShadowHook inline hook libaudioclient 的 AudioRecord::obtainBuffer（read/
# 回调/OBTAIN 三路数据出口汇聚点）实现 post-call 内容替换。落盘名中性化
# libmediafx.so（maps 可见，不得含 hook/audio 等自曝字样）。
#
# ShadowHook 接入方式：vendored 源码（cpp/shadowhook/src，v2.0.1 MIT，
# 来源 github.com/bytedance/android-inline-hook）编 STATIC 链入本库——
# 上游 AAR 只带动态 so（无 .a），prefab 方案又对 x86 configure 必败
# （CXX1210）。静态链接收益：单 so 分发，进程 maps 不再出现
# libshadowhook.so 条目（自曝），动态符号表经 mediafx.map 只导出
# Java_*（shadowhook_*/内部符号全 local，即使 SHADOWHOOK_EXPORT 的
# visibility(default) 也被 version script 压回）。上游源码集排除
# sh_jni.c（Java API 桥，非 hook 核心）与 nothing/；-Weverything
# -Werror 弃用（上游 CI 规格）；-Os + section gc 对齐上游。arm64
# 继承上游 -z,max-page-size=16384（16KB 页设备兼容，静态后由宿主
# so 承担）。纯 C 无 STL，与 c++_static 无双重 STL 冲突。
# ABI 守卫：x86 上不出 libmediafx.so，模拟器内 Java 桥 loadLibrary
# 失败即 fail-open（runCatching 静默降级，其余引擎不受影响）
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "armeabi-v7a")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(SH_ARCH arm64)
        set(SH_PAGE_FLAGS "-Wl,-z,max-page-size=16384")
    else()
        set(SH_ARCH arm)
        set(SH_PAGE_FLAGS "")
    endif()
    file(GLOB SHADOWHOOK_SRC
            shadowhook/src/*.c
            shadowhook/src/arch/${SH_ARCH}/*.c
            shadowhook/src/arch/${SH_ARCH}/*.S
            shadowhook/src/common/*.c
            shadowhook/src/third_party/xdl/*.c)
    add_library(shadowhook STATIC ${SHADOWHOOK_SRC})
    target_compile_features(shadowhook PRIVATE c_std_11)
    target_include_directories(shadowhook PRIVATE
            shadowhook/src
            shadowhook/src/include
            shadowhook/src/arch/${SH_ARCH}
            shadowhook/src/common
            shadowhook/src/third_party/xdl
            shadowhook/src/third_party/bsd
            shadowhook/src/third_party/lss)
    target_compile_options(shadowhook PRIVATE -Os -ffunction-sections -fdata-sections)
    target_link_libraries(shadowhook log)

    add_library(audio_replace SHARED audio_replace.cpp)
    set_target_properties(audio_replace PROPERTIES OUTPUT_NAME "mediafx")
    target_include_directories(audio_replace PRIVATE ${CMAKE_SOURCE_DIR}/shadowhook/src/include)
    target_compile_options(audio_replace PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)
    target_link_options(audio_replace PRIVATE -Wl,--gc-sections -Wl,--strip-all -Wl,--exclude-libs,ALL -Wl,--discard-all ${SH_PAGE_FLAGS} -Wl,--version-script=${CMAKE_SOURCE_DIR}/mediafx.map)
    target_link_libraries(audio_replace shadowhook log)
else()
    # x86/x86_64 不产 libmediafx.so（ABI 守卫见上：ShadowHook 仅支持 ARM，
    # 模拟器内 Java 桥 loadLibrary 失败即 fail-open）。但 audio_replace
    # OBJECT 目标仍在 x86 配置真实编译该文件——IDE 的 C/C++ 语言服务
    # 按编译数据库判定文件归属，无编译命令 = "不属于任何项目目标"误报
    # （含 HEADER_FILE_ONLY）。文件内部按架构分流 include（x86 走文件内
    # 桩声明，见 audio_replace.cpp），EXCLUDE_FROM_ALL 保证产物不被使用
    add_library(audio_replace OBJECT EXCLUDE_FROM_ALL audio_replace.cpp)
endif()

# vault 独立密钥保管进程（可执行 so，与 libnetsvc.so 同法打包）：
# DK 唯一持有者与全部 DK/CK 密码学操作的执行者——无 ART，Java hook
# 框架（LSPlant/自研）无处下钩；dumpable=0 + TracerPid 自检挡同 uid
# ptrace。Argon2id 为 vendored 参考实现（CC0，KAT 验证见宿主测试）。
# fill 实现按 ABI 选择（上游 Makefile 同规则）：opt.c 含 x86 intrinsics
#（blamka-round-opt.h 无条件 include emmintrin.h），ARM 编 ref.c
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "armeabi-v7a")
    set(ARGON2_FILL argon2/ref.c)
else()
    set(ARGON2_FILL argon2/opt.c)
endif()
add_executable(libvault vault.cpp
        argon2/argon2.c argon2/core.c argon2/encoding.c argon2/thread.c
        ${ARGON2_FILL}
        argon2/blake2/blake2b.c)
set_target_properties(libvault PROPERTIES OUTPUT_NAME "libsyncsvc" SUFFIX ".so")
target_include_directories(libvault PRIVATE ${OPENSSL_DIR}/include ${CMAKE_CURRENT_SOURCE_DIR}/argon2)
target_compile_options(libvault PRIVATE -fPIE -flto -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(libvault PRIVATE -pie -flto -Wl,--gc-sections -Wl,--strip-all -Wl,--exclude-libs,ALL -Wl,--discard-all)
target_link_libraries(libvault ${OPENSSL_DIR}/lib/${ANDROID_ABI}/libcrypto.a z -pthread)