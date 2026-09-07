cmake_minimum_required(VERSION 3.22.1)
project("ScreenshotFakerNative")
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