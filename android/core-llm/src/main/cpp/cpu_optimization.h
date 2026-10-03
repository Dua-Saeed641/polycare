// Enhanced CPU optimization for ARM big.LITTLE architecture
// Automatically detects and utilizes best CPU features available

#include <jni.h>
#include <android/log.h>
#include <sys/system_properties.h>
#include <cpu-features.h>

#define LOG_TAG "PolyCareOptimize"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace polycare {

struct CpuCapabilities {
    bool has_neon = false;
    bool has_dotprod = false;
    bool has_i8mm = false;
    bool has_fp16 = false;
    int num_cores = 0;
    int fast_cores_start = 0;  // Index where fast cores begin
};

CpuCapabilities detectCpuCapabilities() {
    CpuCapabilities caps;
    
    // Detect ARM features
    uint64_t features = android_getCpuFeatures();
    caps.has_neon = (features & ANDROID_CPU_ARM64_FEATURE_ASIMD) != 0;
    caps.has_dotprod = (features & ANDROID_CPU_ARM64_FEATURE_DOTPROD) != 0;
    caps.has_i8mm = (features & ANDROID_CPU_ARM64_FEATURE_I8MM) != 0;
    caps.has_fp16 = (features & ANDROID_CPU_ARM64_FEATURE_FP16) != 0;
    
    // Get CPU count
    caps.num_cores = android_getCpuCount();
    
    // For big.LITTLE: assume fast cores are the last 2-4 cores
    // This is a heuristic - ideally read /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq
    if (caps.num_cores >= 8) {
        caps.fast_cores_start = 6;  // Xiaomi 2406ERN9CI: cores 6-7 are fast
    } else if (caps.num_cores >= 6) {
        caps.fast_cores_start = 4;
    } else {
        caps.fast_cores_start = 0;
    }
    
    LOGI(\"CPU capabilities: cores=%d, NEON=%d, dotprod=%d, i8mm=%d, fp16=%d, fast_start=%d\",
         caps.num_cores, caps.has_neon, caps.has_dotprod, caps.has_i8mm, 
         caps.has_fp16, caps.fast_cores_start);
    
    return caps;
}

// Optimal thread configuration for Xiaomi 2406ERN9CI (6xA55 + 2xA76)
int getOptimalDecodeThreads(const CpuCapabilities& caps) {
    // Use only fast cores for decode (latency-sensitive)
    return std::min(2, caps.num_cores - caps.fast_cores_start);
}

int getOptimalBatchThreads(const CpuCapabilities& caps) {
    // Use all cores for batch processing (throughput-oriented)
    return caps.num_cores;
}

} // namespace polycare
