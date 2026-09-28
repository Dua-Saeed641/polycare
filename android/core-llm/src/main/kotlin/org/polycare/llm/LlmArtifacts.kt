package org.polycare.llm

import org.polycare.common.Artifact

/**
 * The base model, pinned by hash. Produced by `tools/models/fetch_models.sh` from the official
 * Qwen2.5-1.5B-Instruct-GGUF repository; on the phone it lives under `files/models/`.
 */
object LlmArtifacts {
    const val DIR = "qwen2.5-1.5b-instruct"
    const val MODEL_ID = "Qwen2.5-1.5B-Instruct/Q4_K_M@91cad51"

    val baseModel = Artifact(
        path = "$DIR/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
        sizeBytes = 1_117_320_736,
    )
}
