plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core-vector"))
    // The Java API is identical in onnxruntime (desktop) and onnxruntime-android; the app
    // supplies the Android runtime, tests run on the desktop one.
    compileOnly(libs.onnxruntime)

    testImplementation(libs.onnxruntime)
    testImplementation(libs.org.json)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Model assets live outside git (tools/models/fetch_models.sh); parity tests skip without them.
    systemProperty("polycare.models", rootProject.file("../tools/models").absolutePath)
    maxHeapSize = "2g"
}
