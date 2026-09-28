cmake_minimum_required(VERSION 3.22)
project(polycare_llm)

# AGP sets CMAKE_BUILD_TYPE to match the Gradle variant (Debug/Release), but an unoptimized (-O0)
# ggml is 10-50x slower — measured on-device: a Debug build needed 350s to prefill 150 tokens
# and ran generation at 0.3 tok/s. Inference math always wants -O3, even in an app debug build;
# only our own thin bridge below would ever need debug symbols, and it has none to speak of.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# LLAMA_CPP_DIR is passed by Gradle (see :core-llm build.gradle.kts) and points at
# native/llama.cpp, fetched to a pinned tag by native/fetch-llama-cpp.sh. It lives outside
# this module, the same way native/qdrant does for :qdrant-edge (see build-qdrant-edge.sh).
if(NOT DEFINED LLAMA_CPP_DIR)
    message(FATAL_ERROR "LLAMA_CPP_DIR not set — run: bash native/fetch-llama-cpp.sh")
endif()

# We only need the core `llama`/`ggml` libraries, not llama.cpp's CLI tools, server, examples
# or its `common` utility lib (which pulls in curl/nlohmann-json we have no use for on-device;
# our own thin C++ bridge below replaces what `common` would otherwise provide).
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)

# The Android NDK ships libomp, but packaging + loading it correctly from an app is fragile;
# ggml's own pthread-based thread pool is the well-trodden path for Android ports.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# No explicit -march: for a phone fleet we cannot assume dotprod/i8mm are present, so this
# builds the safe arm64-v8a (NEON) baseline. Revisit with GGML_CPU_ALL_VARIANTS (runtime
# dispatch) once we need the extra throughput and can measure it against real devices.

add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama_cpp)

add_library(polycare_llm SHARED jni_bridge.cpp)
target_link_libraries(polycare_llm llama ggml log android)

# whisper.cpp (native/whisper.cpp, pinned tag — native/fetch-whisper-cpp.sh) vendors its own
# copy of ggml. Its own CMakeLists checks `if (NOT TARGET ggml)` before adding it, so because
# llama.cpp's ggml is already defined above, whisper.cpp reuses that exact target instead of
# building a second one — this is the difference between one `libggml*.so` set in the APK and a
# same-named, likely ABI-incompatible second copy silently shadowing (or shadowed by) the first.
if(DEFINED WHISPER_CPP_DIR)
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

    add_subdirectory(${WHISPER_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/whisper_cpp)

    add_library(polycare_whisper SHARED whisper_bridge.cpp)
    target_link_libraries(polycare_whisper whisper ggml log android)
endif()
