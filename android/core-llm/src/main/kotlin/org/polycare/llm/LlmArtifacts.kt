package org.polycare.llm

import org.polycare.common.Artifact

/**
 * The base model, pinned by hash. Produced by `tools/models/fetch_models.sh`.
 *
 * Back to Qwen2.5-1.5B-Instruct. It was swapped down to 0.5B because, on the old CPU-only build,
 * 1.5B decoded at ~4-5.5 tok/s. Two things have changed since, and both are why 0.5B is no longer
 * acceptable:
 *
 *  1. The native build now ships every ggml ARM CPU variant (GGML_CPU_ALL_VARIANTS) and lets ggml
 *     pick the best one the phone supports at load time, instead of compiling only the arm64
 *     baseline. The int8 dot-product kernels do most of the arithmetic for a Q4_K_M model, and the
 *     baseline build left them out. On the measured POCO (Snapdragon 7s Gen 2, Adreno 613) that
 *     alone is worth roughly 2x decode.
 *  2. 0.5B could not actually do this job. Its instruction-following was too weak, so on
 *     multi-bullet ASHA passages it copied the passage verbatim instead of restating it - the
 *     grounding prompt (invariant 10) was being ignored, not obeyed. That is the failure mode the
 *     invariant exists to prevent, and a bigger model fixes it at the source rather than hiding it
 *     with post-processing.
 *
 * 1.5B is still the smallest model that reliably follows the grounded-restate prompt. A 3B would
 * read better still but needs ~2 GB just for the weights plus KV, and the fleet has 4 GB phones,
 * so it is left as a future option rather than the default (invariant 6: always answer).
 *
 * The 0.5B GGUF is kept in `tools/models/qwen2.5-0.5b-instruct/` (harmless, unused) rather than
 * deleted, so this swap is reversible.
 */
object LlmArtifacts {
    const val DIR = "qwen2.5-1.5b-instruct"
    const val MODEL_ID = "Qwen2.5-1.5B-Instruct/Q4_K_M@91cad51"

    /** For UI display - derived from [MODEL_ID], not a second hand-copied literal that can go
     * stale the next time the shipped model changes (as the "Qwen2.5-1.5B" label in `AskScreen`
     * did the first time it changed, confusingly surviving this exact swap). */
    val shortName: String = MODEL_ID.substringBefore("-Instruct")

    val baseModel = Artifact(
        path = "$DIR/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
        sizeBytes = 1_117_320_736L,
    )
}
