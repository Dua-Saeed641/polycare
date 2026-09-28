package org.polycare.app

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.polycare.app.ai.EmbedSelfCheck
import org.polycare.app.ai.EmbedderProvider
import org.polycare.app.ai.LlmProvider
import org.polycare.app.ai.LlmSelfCheck
import org.polycare.app.ai.SkillsRepository
import org.polycare.app.ai.WavFile
import org.polycare.app.ai.WhisperProvider
import org.polycare.app.ui.PolyCareRoot
import org.polycare.app.ui.theme.PolyCareTheme
import org.polycare.common.EventLog
import org.polycare.llm.GenerationEvent
import org.polycare.llm.PromptFormat
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var embedderProvider: EmbedderProvider
    @Inject lateinit var llmProvider: LlmProvider
    @Inject lateinit var whisperProvider: WhisperProvider
    @Inject lateinit var skillsRepository: SkillsRepository
    @Inject lateinit var events: EventLog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (isDebuggable() && intent.getBooleanExtra(EXTRA_EMBED_CHECK, false)) runEmbedCheck()
        if (isDebuggable() && intent.getBooleanExtra(EXTRA_LLM_CHECK, false)) runLlmCheck()
        if (isDebuggable() && intent.getBooleanExtra(EXTRA_SKILL_CHECK, false)) runSkillCheck()
        debugWhisperWavPath()?.let(::runWhisperCheck)
        setContent {
            PolyCareTheme {
                PolyCareRoot(
                    autoBenchPoints = debugBenchRequest(),
                    debugSearch = debugSearchRequest(),
                    debugAsk = debugAskRequest(),
                    debugTriage = debugTriageRequest(),
                    debugRoute = debugRouteRequest(),
                    debugOcrImagePath = debugOcrImagePath(),
                )
            }
        }
    }

    /**
     * Debug builds only: `adb shell am start -n org.polycare.app/.MainActivity --ei bench_points 10000`
     * opens System and runs the vector benchmark; results go to logcat tag PolyCareBench.
     */
    /** Debug builds only: `--es search_query "how to prepare ORS"` opens Search with that query. */
    private fun debugSearchRequest(): String? =
        intent.getStringExtra(EXTRA_SEARCH_QUERY)?.takeIf { isDebuggable() && it.isNotBlank() }

    /** Debug builds only: `--es ask_query "baby has fast breathing"` opens Ask with that question. */
    private fun debugAskRequest(): String? =
        intent.getStringExtra(EXTRA_ASK_QUERY)?.takeIf { isDebuggable() && it.isNotBlank() }

    /** Debug builds only: `--ez open_triage true` opens Triage — for verifying it renders without a tap. */
    private fun debugTriageRequest(): Boolean =
        intent.getBooleanExtra(EXTRA_OPEN_TRIAGE, false) && isDebuggable()

    /** Debug builds only: `--es open_route memory` opens any plain (no-argument) screen by route name. */
    private fun debugRouteRequest(): String? =
        intent.getStringExtra(EXTRA_OPEN_ROUTE)?.takeIf { isDebuggable() && it.isNotBlank() }

    /** Debug builds only: `--es ocr_image_path /sdcard/....jpg` opens Scan and OCRs that file directly. */
    private fun debugOcrImagePath(): String? =
        intent.getStringExtra(EXTRA_OCR_IMAGE_PATH)?.takeIf { isDebuggable() && it.isNotBlank() }

    /** Debug builds only: `--es whisper_wav_path /sdcard/....wav` (mono 16-bit 16 kHz PCM). */
    private fun debugWhisperWavPath(): String? =
        intent.getStringExtra(EXTRA_WHISPER_WAV_PATH)?.takeIf { isDebuggable() && it.isNotBlank() }

    private fun debugBenchRequest(): Int? =
        intent.getIntExtra(EXTRA_BENCH_POINTS, 0).takeIf { isDebuggable() && it > 0 }

    /**
     * Debug builds only: `adb shell am start -n org.polycare.app/.MainActivity --ez embed_check true`
     * checks the embedder against the Python reference on this phone; results in logcat tag PolyCareEmbed.
     */
    private fun runEmbedCheck() {
        lifecycleScope.launch(Dispatchers.Default) {
            val ready = embedderProvider.get()
            if (ready == null) {
                Log.w(EMBED_TAG, "embed_check: ${embedderProvider.state.value}")
                return@launch
            }
            val fixture = assets.open("e5_reference.json").bufferedReader().use { it.readText() }
            val r = EmbedSelfCheck.run(ready.embedder, fixture)
            Log.i(
                EMBED_TAG,
                "embed_check ${if (r.passed) "PASS" else "FAIL"} load=${ready.loadMs}ms cases=${r.cases} " +
                    "tokenMismatches=${r.tokenMismatches.size} minCos=${"%.6f".format(r.minCosine)} " +
                    "sameNearestNeighbour=${r.neighbourAgreement}/${r.cases} worst=\"${r.worstText}\" " +
                    "query p50=${"%.1f".format(r.queryP50Ms)}ms p95=${"%.1f".format(r.queryP95Ms)}ms",
            )
            r.tokenMismatches.forEach { Log.w(EMBED_TAG, "token mismatch: $it") }
            events.record(
                EventLog.Category.MODEL,
                "Embedder self-check ${if (r.passed) "passed" else "FAILED"}",
                mapOf(
                    "cases" to r.cases, "tokenMismatches" to r.tokenMismatches.size,
                    "minCos" to "%.4f".format(r.minCosine), "sameNeighbour" to "${r.neighbourAgreement}/${r.cases}",
                    "queryP50Ms" to "%.1f".format(r.queryP50Ms),
                ),
                if (r.passed) EventLog.Level.INFO else EventLog.Level.ERROR,
            )
        }
    }

    /**
     * Debug builds only: `adb shell am start -n org.polycare.app/.MainActivity --ez llm_check true`
     * loads Qwen2.5-1.5B and generates a short grounded answer; results in logcat tag PolyCareLlm.
     */
    private fun runLlmCheck() {
        lifecycleScope.launch(Dispatchers.Default) {
            val ready = llmProvider.get()
            if (ready == null) {
                Log.w(LLM_TAG, "llm_check: ${llmProvider.state.value}")
                return@launch
            }
            val r = LlmSelfCheck.run(ready.engine)
            Log.i(
                LLM_TAG,
                "llm_check load=${ready.loadMs}ms promptTokens=${r.promptTokens} generatedTokens=${r.generatedTokens} " +
                    "promptMs=${r.promptMs} decodeMs=${r.decodeMs} tokPerSec=${"%.2f".format(r.tokensPerSecond)} " +
                    "answer=\"${r.answer.take(200)}\"",
            )
            events.record(
                EventLog.Category.MODEL,
                "LLM self-check",
                mapOf(
                    "loadMs" to ready.loadMs, "promptTokens" to r.promptTokens, "generatedTokens" to r.generatedTokens,
                    "tokensPerSecond" to "%.2f".format(r.tokensPerSecond),
                ),
            )
        }
    }

    /**
     * Debug builds only: `adb shell am start -n org.polycare.app/.MainActivity --ez skill_check true`
     * loads every verified skill from `SkillsRepository` and generates the same prompt with the
     * base model alone, then with each skill active at scale 1.0 (M0: "two LoRA adapters loaded
     * and switched per request" — this is the switch, real trained adapter(s), on a real phone).
     * Logs each answer so the difference between skills can be read directly; logcat PolyCareLlm.
     */
    private fun runSkillCheck() {
        lifecycleScope.launch(Dispatchers.Default) {
            val skills = skillsRepository.available()
            if (skills.isEmpty()) {
                Log.w(LLM_TAG, "skill_check: no verified skills in files/models/skills/manifest.json")
                return@launch
            }
            val ready = llmProvider.get()
            if (ready == null) {
                Log.w(LLM_TAG, "skill_check: ${llmProvider.state.value}")
                return@launch
            }
            val engine = ready.engine
            val prompt = PromptFormat.ask(
                question = "What should an ASHA remember about care during pregnancy?",
                passageText = "Care during pregnancy requires regular check-ups and attention to danger signs.",
                sourceTitle = "ASHA guidance",
            )

            suspend fun generate(): String {
                val text = StringBuilder()
                engine.generate(prompt, maxTokens = 40).collect { e -> if (e is GenerationEvent.Token) text.append(e.piece) }
                return text.toString().trim()
            }

            engine.clearSkills()
            val base = generate()
            Log.i(LLM_TAG, "skill_check base=\"$base\"")

            for (skill in skills) {
                val loaded = engine.loadSkill(skill.file)
                if (!loaded) {
                    Log.w(LLM_TAG, "skill_check: failed to load skill ${skill.id} (${skill.file})")
                    continue
                }
                engine.setActiveSkills(listOf(skill.file to 1.0f))
                val withSkill = generate()
                Log.i(LLM_TAG, "skill_check skill=${skill.id} answer=\"$withSkill\"")
                events.record(
                    EventLog.Category.MODEL, "Skill generation compared",
                    mapOf("skillId" to skill.id, "changedFromBase" to (withSkill != base)),
                )
            }
            engine.clearSkills()
        }
    }

    /**
     * Debug builds only: transcribes a WAV file already on the phone (mono 16-bit 16 kHz PCM) and
     * logs the result to logcat tag PolyCareWhisper. Proves whisper.cpp on real ARM64 hardware
     * without needing microphone capture or a tap (M0: "whisper.cpp transcribes a clip offline").
     */
    private fun runWhisperCheck(path: String) {
        lifecycleScope.launch(Dispatchers.Default) {
            val ready = whisperProvider.get()
            if (ready == null) {
                Log.w(WHISPER_TAG, "whisper_check: ${whisperProvider.state.value}")
                return@launch
            }
            val pcm = runCatching { WavFile.readPcm16Mono16k(File(path)) }.getOrElse { e ->
                Log.e(WHISPER_TAG, "whisper_check: could not read $path", e)
                return@launch
            }
            val t0 = System.nanoTime()
            val text = ready.engine.transcribe(pcm, language = "auto")
            val ms = (System.nanoTime() - t0) / 1_000_000
            Log.i(
                WHISPER_TAG,
                "whisper_check load=${ready.loadMs}ms samples=${pcm.size} transcribeMs=$ms text=\"$text\"",
            )
            events.record(
                EventLog.Category.MODEL, "Whisper self-check",
                mapOf("loadMs" to ready.loadMs, "samples" to pcm.size, "transcribeMs" to ms, "chars" to text.length),
            )
        }
    }

    private fun isDebuggable() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private companion object {
        const val EXTRA_BENCH_POINTS = "bench_points"
        const val EXTRA_EMBED_CHECK = "embed_check"
        const val EXTRA_LLM_CHECK = "llm_check"
        const val EXTRA_SKILL_CHECK = "skill_check"
        const val EXTRA_SEARCH_QUERY = "search_query"
        const val EXTRA_ASK_QUERY = "ask_query"
        const val EXTRA_OPEN_TRIAGE = "open_triage"
        const val EXTRA_OPEN_ROUTE = "open_route"
        const val EXTRA_OCR_IMAGE_PATH = "ocr_image_path"
        const val EXTRA_WHISPER_WAV_PATH = "whisper_wav_path"
        const val EMBED_TAG = "PolyCareEmbed"
        const val LLM_TAG = "PolyCareLlm"
        const val WHISPER_TAG = "PolyCareWhisper"
    }
}
