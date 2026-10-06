cmake_minimum_required(VERSION 3.22.1)
project(montage_native C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# whisper.cpp is extremely slow in Debug builds (no optimization, extra asserts),
# and Android Studio / Gradle debug variants pass Debug here. Always build Release.
set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)

# Everything is linked statically into one small shared library, so the static
# whisper/ggml archives must be position independent.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
# ggml's own thread pool is used; OpenMP would need an extra runtime library on Android.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.7.6
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(whisper)

add_library(montage_whisper SHARED whisper_jni.cpp)
target_link_libraries(montage_whisper PRIVATE whisper)

if (ANDROID)
    target_link_libraries(montage_whisper PRIVATE log android)
    target_compile_options(montage_whisper PRIVATE -O3 -fvisibility=hidden)
    target_link_options(montage_whisper PRIVATE -Wl,--gc-sections)
endif ()
