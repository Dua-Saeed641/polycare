package org.polycare.whisper

import org.polycare.common.Artifact

/**
 * The speech model, pinned by hash. Produced by `tools/models/fetch_models.sh`.
 *
 * "small" over "base": base's Hindi transcription was tested (`--ez hindi_check true`, an
 * on-device TTS-synthesised round-trip, no field recording needed) and came back wrong-script
 * ("बच्चे को दस्त हो तो क्या करें" → "Bっちy kudas thu to kya kare?"), not merely imperfect. small
 * is ~3.2x the download (181MB vs 57MB) for meaningfully better multilingual accuracy upstream —
 * worth it for a project whose whole premise is serving Hindi-speaking ASHA workers.
 */
object WhisperArtifacts {
    const val DIR = "whisper"
    const val MODEL_ID = "whisper-small-multilingual/q5_1@5359861"

    val model = Artifact(
        path = "$DIR/ggml-small-q5_1.bin",
        sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
        sizeBytes = 190_085_487,
    )
}
