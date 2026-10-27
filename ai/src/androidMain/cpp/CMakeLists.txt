# Rebuild libvelum_needle.so from Needle static libs (Apache-2.0).
# Prefetch: scripts/fetch-needle-android.sh
# Output is also checked into jniLibs/ for CI without NDK.
# Android 15+ / Play: link with 16 KB ELF max-page-size (NDK r28+ default; set explicitly).

cmake_minimum_required(VERSION 3.22.1)
project(velum_needle)

add_library(velum_needle SHARED velum_needle_jni.cpp)

set(PREBUILT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI})
add_library(needle STATIC IMPORTED)
set_target_properties(needle PROPERTIES IMPORTED_LOCATION ${PREBUILT_DIR}/libneedle.a)

target_include_directories(velum_needle PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(velum_needle
    -Wl,--whole-archive needle -Wl,--no-whole-archive
    log m dl c++_shared
)
target_compile_features(velum_needle PRIVATE cxx_std_17)

# https://developer.android.com/guide/practices/page-sizes
target_link_options(velum_needle PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
