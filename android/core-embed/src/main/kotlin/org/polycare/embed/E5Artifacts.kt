package org.polycare.embed

import org.polycare.common.Artifact

/**
 * Files the embedder needs, pinned by hash. Produced by tools/models/fetch_models.sh; on the
 * phone they live under `<models root>/multilingual-e5-small/`.
 */
object E5Artifacts {
    const val DIR = "multilingual-e5-small"

    val model = Artifact(
        path = "$DIR/model_quantized.onnx",
        sha256 = "f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193",
        sizeBytes = 118_308_185,
    )

    val tokenizer = Artifact(
        path = "$DIR/e5_tokenizer.bin",
        sha256 = "2d05ac6cf3d7c7702e77844ee44400d6bf4a601c95253d17e48494ac4b2ab15b",
        sizeBytes = 4_318_928,
    )

    val all = listOf(model, tokenizer)
}
