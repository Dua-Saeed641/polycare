plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// On-device inference: llama.cpp (native/llama.cpp, pinned tag — native/fetch-llama-cpp.sh)
// behind a thin JNI bridge (src/main/cpp/jni_bridge.cpp), wrapped by LlamaEngine for Kotlin.
//
// whisper.cpp (native/whisper.cpp, native/fetch-whisper-cpp.sh) lives in this same module and
// CMake project rather than its own — its build reuses llama.cpp's already-built `ggml` target
// instead of vendoring a second, same-named copy (see src/main/cpp/CMakeLists.txt).
val llamaCppDir = rootProject.file("../native/llama.cpp")
val whisperCppDir = rootProject.file("../native/whisper.cpp")

// Opt-in GPU backend: `./gradlew :app:installDebug -PpolycareVulkan=true` builds ggml with Vulkan.
// Off by default; the CPU build is what has been measured. Enabling it also needs the toggle
// System -> Experimental -> "Use the GPU" on the phone, and falls back to CPU if loading fails.
val vulkan = providers.gradleProperty("polycareVulkan").orNull == "true"

android {
    namespace = "org.polycare.llm"
    compileSdk = 35
    ndkVersion = "27.1.12297006"

    defaultConfig {
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments += "-DLLAMA_CPP_DIR=${llamaCppDir.absolutePath}"
                if (whisperCppDir.exists()) arguments += "-DWHISPER_CPP_DIR=${whisperCppDir.absolutePath}"
                arguments += "-DANDROID_STL=c++_shared"
                if (vulkan) arguments += "-DPOLYCARE_VULKAN=ON"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        // llama.cpp builds ggml as a separate shared lib; both are loaded by LlamaEngine.
        jniLibs.keepDebugSymbols += "**/*.so"
    }
}

dependencies {
    api(project(":core-common"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
