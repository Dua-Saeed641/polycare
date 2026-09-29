cmake_minimum_required(VERSION 3.22)
project(polycare_llm)

# AGP sets CMAKE_BUILD_TYPE to match the Gradle variant (Debug/Release), but an unoptimized (-O0)
# ggml is 10-50x slower. Inference math always wants -O3, even in an app debug build.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

if(NOT DEFINED LLAMA_CPP_DIR)
    message(FATAL_ERROR "LLAMA_CPP_DIR not set — run: bash native/fetch-llama-cpp.sh")
endif()

# ============================================================================
# PERFORMANCE OPTIMIZATIONS - Fixed 2026-09-29
# ============================================================================

# Vulkan GPU offload is opt-in: `-PpolycareVulkan=true` (see core-llm/build.gradle.kts). The default
# build stays CPU-only, which is the configuration that has been built and measured. The NDK ships
# the Vulkan headers and the glslc shader compiler that ggml-vulkan needs.
option(POLYCARE_VULKAN "Build ggml's Vulkan backend (experimental)" OFF)
if(POLYCARE_VULKAN)
    set(GGML_VULKAN ON CACHE BOOL "Vulkan GPU acceleration (opt-in)" FORCE)
else()
    set(GGML_VULKAN OFF CACHE BOOL "Vulkan GPU acceleration is off unless -PpolycareVulkan=true" FORCE)
endif()

# Disable CPU_ALL_VARIANTS to avoid BACKEND_DL requirement
# Instead, use optimized armv8.2-a+dotprod for better performance
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "Disable all CPU variants" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "Disable dynamic backend loading" FORCE)

# Disable -march=native to ensure compatibility across all arm64 devices
set(GGML_NATIVE OFF CACHE BOOL "Disable -march=native for fleet compatibility" FORCE)

# Leave GGML_CPU_ARM_ARCH empty so ggml builds for Android's arm64 baseline.
# Force the empty cache value as well; otherwise an earlier dotprod configure could persist.
set(GGML_CPU_ARM_ARCH "" CACHE STRING "ggml: CPU architecture for ARM" FORCE)

# Disable LTO for now (NDK 27 doesn't support gold linker)
set(GGML_LTO OFF CACHE BOOL "Disable Link Time Optimization" FORCE)

# ============================================================================

# Core llama.cpp build settings
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)

# Use ggml's pthread-based thread pool (Android NDK libomp packaging is fragile)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama_cpp)

add_library(polycare_llm SHARED jni_bridge.cpp)
target_link_libraries(polycare_llm llama ggml log android)

# whisper.cpp integration
if(DEFINED WHISPER_CPP_DIR)
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

    add_subdirectory(${WHISPER_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/whisper_cpp)

    add_library(polycare_whisper SHARED whisper_bridge.cpp)
    target_link_libraries(polycare_whisper whisper ggml log android)
endif()
