package org.polycare.common

/** Every tunable threshold on the edge lives here. No magic numbers inline. */
object PolyCareConfig {

    object Routing {
        /** Below this top skill score, answer with the base model and log a gap (τ). */
        const val minSkillScore = 0.60f
        /** If the top two skills are closer than this, blend them (δ). */
        const val blendMargin = 0.10f
        /** Softmax temperature for blend weights (T). */
        const val blendTemperature = 0.05f
        const val skillCandidates = 3
    }

    object Retrieval {
        const val prefetchLimit = 20
        const val resultLimit = 5
        /** Standard RRF constant. */
        const val rrfK = 60
        /** BM25 term-frequency saturation and length normalisation for sparse vectors. */
        const val bm25K1 = 1.2f
        const val bm25B = 0.75f
        const val bm25AvgDocTokens = 120f

        /** Staleness half-life for the confidence badge. */
        const val stalenessHalfLifeMs = 14L * 24 * 60 * 60 * 1000
    }

    object Conflicts {
        const val nearDuplicateCosine = 0.90f
    }

    object Sync {
        const val stableWindowMs = 30_000L
        const val chunkBytes = 256 * 1024
        const val maxClockDriftMs = 5L * 60 * 1000
    }

    object Llm {
        /** Context window: prompt + generated tokens must fit inside this many tokens. */
        const val contextTokens = 2048
        const val maxNewTokens = 256
        const val temperature = 0.7f
        const val topP = 0.9f
        /** Per-request LoRA scale when a single skill is the clear match (no blending). */
        const val singleSkillScale = 1.0f
    }

    object Governor {
        /**
         * Minimum RAM (MB) as reported by the OS, which is always below the advertised size:
         * a "6 GB" phone reports ~5.3 GB, a "4 GB" phone ~3.6 GB, a "3 GB" phone ~2.7 GB.
         */
        const val fullRamMb = 5_000L // 6 GB class: 1.5B model with two adapters
        const val leanRamMb = 3_400L // 4 GB class: 1.5B model, shorter context
        const val baseRamMb = 2_500L // 3 GB class: small model
        const val lowBatteryPct = 15
        /** Free storage (MB) needed to hold a ~1 M point knowledge slice plus models. */
        const val fullStorageMb = 3_000L
    }
}
