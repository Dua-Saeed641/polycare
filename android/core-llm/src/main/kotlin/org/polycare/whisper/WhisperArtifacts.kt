package org.polycare.whisper

import org.polycare.common.Artifact

/** The speech model, pinned by hash. Produced by `tools/models/fetch_models.sh`. */
object WhisperArtifacts {
    const val DIR = "whisper"
    const val MODEL_ID = "whisper-base-multilingual/q5_1@5359861"

    val model = Artifact(
        path = "$DIR/ggml-base-q5_1.bin",
        sha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
        sizeBytes = 59_707_625,
    )
}
