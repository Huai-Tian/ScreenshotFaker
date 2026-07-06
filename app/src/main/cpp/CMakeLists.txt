cmake_minimum_required(VERSION 3.22.1)
project("ScreenshotFakerNative")
set(OPENSSL_DIR ${CMAKE_SOURCE_DIR}/openssl)
include_directories(${OPENSSL_DIR}/include)
add_executable(daemon daemon.cpp auxiliary.h)
set_target_properties(daemon PROPERTIES SUFFIX ".so")
target_compile_options(daemon PRIVATE -fPIE)
target_link_options(daemon PRIVATE -pie -s)
target_link_libraries(daemon
        ${OPENSSL_DIR}/lib/${ANDROID_ABI}/libcrypto.a
)