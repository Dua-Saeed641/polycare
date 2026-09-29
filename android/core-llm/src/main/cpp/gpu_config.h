// GPU-accelerated inference configuration for llama.cpp
// Enables Vulkan backend for 3-10x speedup on compatible devices

#ifndef POLYCARE_GPU_CONFIG_H
#define POLYCARE_GPU_CONFIG_H

// Enable Vulkan backend (fallback to CPU if unavailable)
#define GGML_USE_VULKAN 1

// GPU memory management
#define GPU_MEMORY_POOL_SIZE_MB 512
#define GPU_BATCH_SIZE 512

// Performance tuning
#define ENABLE_GPU_OFFLOAD 1
#define GPU_LAYERS_AUTO -1  // Auto-detect optimal layer count

// Fallback behavior
#define CPU_FALLBACK_ENABLED 1
#define MIN_GPU_MEMORY_MB 256

#endif // POLYCARE_GPU_CONFIG_H
