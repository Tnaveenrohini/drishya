cmake_minimum_required(VERSION 3.18)
project(flikkwhisper C CXX)

# AI Subtitles: whisper.cpp (phone lo ne, internet lekunda pani chestundi)
include(FetchContent)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.6.tar.gz
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(whisper)

add_library(flikkwhisper SHARED whisper_jni.cpp)
target_compile_features(flikkwhisper PRIVATE cxx_std_17)
target_compile_options(flikkwhisper PRIVATE -O3 -fvisibility=hidden)
target_link_libraries(flikkwhisper PRIVATE whisper log android)
target_link_options(flikkwhisper PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
