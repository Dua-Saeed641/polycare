plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "org.polycare.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.polycare.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // llama.cpp, whisper.cpp and Qdrant Edge are built for arm64 only.
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // On-device parity checks reuse the Python reference fixture from core-embed: in the
    // instrumented test, and in debug builds for the `embed_check` launch extra.
    sourceSets["androidTest"].assets.srcDir("../core-embed/src/test/resources")
    sourceSets["debug"].assets.srcDir("../core-embed/src/test/resources")

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs.pickFirsts += "**/libc++_shared.so"
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        // ggml loads its per-architecture CPU backends (libggml-cpu-android_armv8.*.so) by
        // dlopen()ing a *filesystem path* and enumerating that directory, which cannot work for
        // libs that live only inside the APK. Android 6+ defaults to extractNativeLibs=false
        // (load straight from the APK), so the directory ggml scans would not even exist and
        // model load fails with no CPU backend. Forcing extraction puts the .so files back on
        // disk under nativeLibraryDir, where LlmProvider passes that path to ggml.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":core-common"))
    implementation(project(":core-vector"))
    implementation(project(":core-governor"))
    implementation(project(":qdrant-edge"))
    implementation(project(":core-embed"))
    implementation(project(":core-llm"))
    implementation(project(":ocr-paddle"))
    implementation(libs.onnxruntime.android)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.devanagari)
    implementation(libs.androidx.exifinterface)
    // Ed25519 for signing sync ops (the platform only has Ed25519 from API 33; minSdk is 29).
    implementation(libs.bouncycastle)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit4)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
