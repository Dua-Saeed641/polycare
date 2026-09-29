package org.polycare.llm

import org.polycare.common.Artifact

/**
 * The base model, pinned by hash. Produced by `tools/models/fetch_models.sh`.
 *
 * Swapped from Qwen2.5-1.5B to Qwen2.5-0.5B-Instruct: on this CPU-only phone the 1.5B model
 * decoded at ~4-5.5 tok/s even after thread-count tuning, which read as unacceptably slow for an
 * interactive Q&A product. 0.5B has a third of the parameters to read per token, so decode speed
 * scales roughly proportionally faster. Quality risk is real but bounded: `PromptFormat.ask`'s
 * job is "restate this already-retrieved passage in simple grounded language," not open-ended
 * generation, which a 0.5B instruct model handles far better than free-form knowledge questions
 * would. The old 1.5B GGUF is kept in `tools/models/qwen2.5-1.5b-instruct/` (harmless, unused)
 * rather than deleted, in case quality at 0.5B turns out to be the wrong tradeoff.
 */
object LlmArtifacts {
    const val DIR = "qwen2.5-0.5b-instruct"
    const val MODEL_ID = "Qwen2.5-0.5B-Instruct/Q4_K_M@9217f5d"

    /** For UI display — derived from [MODEL_ID], not a second hand-copied literal that can go
     * stale the next time the shipped model changes (as the "Qwen2.5-1.5B" label in `AskScreen`
     * did the first time it changed, confusingly surviving this exact swap). */
    val shortName: String = MODEL_ID.substringBefore("-Instruct")

    val baseModel = Artifact(
        path = "$DIR/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
        sizeBytes = 491_400_032,
    )
}
