package org.polycare.app.ai

import org.polycare.llm.GenerationEvent
import org.polycare.llm.LlamaEngine
import org.polycare.llm.PromptFormat

/**
 * A minimal, real generation on the phone: proves the model loads and answers, and measures
 * tokens/sec (M0: "llama.cpp runs Qwen2.5-1.5B on the phone; tokens/sec measured"). Used by the
 * debug `llm_check` launch extra and the instrumented test.
 */
object LlmSelfCheck {

    data class Result(val answer: String, val promptTokens: Int, val generatedTokens: Int, val promptMs: Long, val decodeMs: Long) {
        val tokensPerSecond: Double get() = if (decodeMs <= 0) 0.0 else generatedTokens * 1000.0 / decodeMs
    }

    suspend fun run(engine: LlamaEngine): Result {
        val prompt = PromptFormat.ask(
            question = "How many antenatal check-ups does a pregnant woman need?",
            passageText = "Four antenatal visits must be completed during a normal pregnancy, at specific intervals.",
            sourceTitle = "ASHA Module 6",
        )
        val text = StringBuilder()
        var promptTokens = 0
        var generatedTokens = 0
        var promptMs = 0L
        var decodeMs = 0L
        engine.generate(prompt, maxTokens = 64).collect { event ->
            when (event) {
                is GenerationEvent.Token -> text.append(event.piece)
                is GenerationEvent.Done -> {
                    promptTokens = event.stats.promptTokens
                    generatedTokens = event.stats.generatedTokens
                    promptMs = event.stats.promptMs
                    decodeMs = event.stats.decodeMs
                }
            }
        }
        return Result(text.toString().trim(), promptTokens, generatedTokens, promptMs, decodeMs)
    }
}
