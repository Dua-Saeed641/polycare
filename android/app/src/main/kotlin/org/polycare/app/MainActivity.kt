package org.polycare.app

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.polycare.app.ai.EmbedSelfCheck
import org.polycare.app.ai.EmbedderProvider
import org.polycare.app.ai.LlmProvider
import org.polycare.app.ai.LlmSelfCheck
import org.polycare.app.ai.SkillsRepository
import org.polycare.app.ai.VoiceRecorder
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
    @Inject lateinit var householdsRepository: org.polycare.app.households.HouseholdsRepository
    @Inject lateinit var syncRepository: org.polycare.app.sync.SyncRepository
    @Inject lateinit var events: EventLog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (isDebuggable() && intent.getBooleanExtra(EXTRA_EMBED_CHECK, false)) runEmbedCheck()
        if (isDebuggable() && intent.getBooleanExtra(EXTRA_LLM_CHECK, false)) runLlmCheck()
        if (isDebuggable() && intent.getBooleanExtra(EXTRA_SKILL_CHECK, false)) runSkillCheck()
        if (isDebuggable() && intent.getBooleanExtra(EXTRA_VOICE_CHECK, false)) runVoiceCheck()
        if (isDebuggable() && intent.getBooleanExtra(EXTRA_HOUSEHOLD_CHECK, false)) runHouseholdCheck()
        if (isDebuggable() && intent.getBooleanExtra(EXTRA_DUE_LIST_CHECK, false)) runDueListCheck()
        if (isDebuggable() && intent.getBooleanExtra(EXTRA_HINDI_CHECK, false)) runHindiCheck()
        if (isDebuggable() && intent.getBooleanExtra(EXTRA_SYNC_CHECK, false)) runSyncCheck()
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
                    debugOpenDrawer = isDebuggable() && intent.getBooleanExtra(EXTRA_OPEN_DRAWER, false),
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

    /**
     * Debug builds only: `adb shell am start -n org.polycare.app/.MainActivity --ez voice_check true`
     * (grant the mic permission first: `adb shell pm grant org.polycare.app android.permission.RECORD_AUDIO`).
     * Exercises [VoiceRecorder] end to end — permission check, `AudioRecord` open, a real capture
     * window, teardown — independent of Ask's UI (which needs a tap this harness can't send).
     * Logs whether the captured samples look like real audio (non-zero variance) rather than a
     * silent/failed capture, and — if whisper is installed — what it transcribed. Results in
     * logcat tag PolyCareWhisper.
     */
    private fun runVoiceCheck() {
        lifecycleScope.launch(Dispatchers.Default) {
            if (!VoiceRecorder.hasPermission(this@MainActivity)) {
                Log.w(WHISPER_TAG, "voice_check: RECORD_AUDIO not granted — adb shell pm grant org.polycare.app android.permission.RECORD_AUDIO")
                return@launch
            }
            val recorder = VoiceRecorder(this@MainActivity)
            launch(Dispatchers.Default) {
                kotlinx.coroutines.delay(4_000) // stand-in for the UI's "tap again to stop" — no tap available here
                recorder.requestStop()
            }
            val t0 = System.nanoTime()
            val pcm = recorder.recordUntilStopped()
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (pcm.isEmpty()) {
                Log.w(WHISPER_TAG, "voice_check: captured 0 samples in ${ms}ms — AudioRecord failed to open")
                return@launch
            }
            val mean = pcm.map { it.toInt() }.average()
            val variance = pcm.map { (it - mean) * (it - mean) }.average()
            Log.i(WHISPER_TAG, "voice_check captured samples=${pcm.size} durationMs=$ms variance=${"%.1f".format(variance)}")
            events.record(
                EventLog.Category.ASK, "Voice capture self-check",
                mapOf("samples" to pcm.size, "durationMs" to ms, "looksLikeAudio" to (variance > 1.0)),
            )
            val ready = whisperProvider.get() ?: return@launch
            val text = ready.engine.transcribe(pcm, language = "auto")
            Log.i(WHISPER_TAG, "voice_check transcribed text=\"$text\"")
        }
    }

    /**
     * Debug builds only: `adb shell am start -n org.polycare.app/.MainActivity --ez hindi_check true`.
     * whisper.cpp was only ever verified against English audio (no Hindi clip was available on
     * hand); this closes that gap without needing a recording, using Android's own Hindi TTS
     * voice to synthesise a known phrase on-device, then feeding that straight into the same
     * `WhisperEngine.transcribe()` path a real recording would use. Not a substitute for a real
     * human voice (TTS is cleaner audio than a phone mic in the field), but a genuine, reproducible
     * check that Hindi text round-trips through synthesis → 16kHz PCM → whisper.cpp without the
     * pipeline itself breaking on Devanagari. Logs to logcat tag PolyCareWhisper.
     */
    private fun runHindiCheck() {
        lifecycleScope.launch(Dispatchers.Default) {
            val phrase = "बच्चे को दस्त हो तो क्या करें" // "What to do if a child has diarrhoea" — a real Search suggestion already in the app
            val wav = File(cacheDir, "hindi_check.wav")
            val synthesised = runCatching { synthesiseHindiToFile(phrase, wav) }.getOrElse { e ->
                Log.w(WHISPER_TAG, "hindi_check: TTS synthesis failed: ${e.message}")
                return@launch
            }
            if (!synthesised) {
                Log.w(WHISPER_TAG, "hindi_check: Hindi voice data not available on this device/TTS engine")
                return@launch
            }
            val ready = whisperProvider.get()
            if (ready == null) {
                Log.w(WHISPER_TAG, "hindi_check: ${whisperProvider.state.value}")
                return@launch
            }
            val pcm = runCatching { WavFile.readPcm16Mono16k(wav) }.getOrElse { e ->
                Log.e(WHISPER_TAG, "hindi_check: could not read synthesised WAV", e)
                return@launch
            }
            val text = ready.engine.transcribe(pcm, language = "hi")
            Log.i(WHISPER_TAG, "hindi_check original=\"$phrase\" transcribed=\"$text\" samples=${pcm.size}")
            events.record(
                EventLog.Category.MODEL, "Hindi whisper self-check",
                mapOf("samples" to pcm.size, "chars" to text.length),
            )
        }
    }

    /** Synthesises [text] to [file] as a 16-bit PCM WAV using Android's Hindi TTS voice. Returns
     * false (not an exception) if no Hindi voice data is installed — the "needs one online moment
     * to download a script/voice pack" situation already documented for ML Kit's Devanagari model. */
    private suspend fun synthesiseHindiToFile(text: String, file: File): Boolean = suspendCancellableCoroutine { cont ->
        var tts: TextToSpeech? = null
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                cont.resume(false, onCancellation = null)
                return@TextToSpeech
            }
            val engine = tts!!
            val availability = engine.setLanguage(java.util.Locale("hi", "IN"))
            if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.shutdown()
                cont.resume(false, onCancellation = null)
                return@TextToSpeech
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    engine.shutdown()
                    if (cont.isActive) cont.resume(true, onCancellation = null)
                }
                override fun onError(utteranceId: String?) {
                    engine.shutdown()
                    if (cont.isActive) cont.resume(false, onCancellation = null)
                }
            })
            val rc = engine.synthesizeToFile(text, null, file, "hindi_check")
            if (rc != TextToSpeech.SUCCESS) {
                engine.shutdown()
                cont.resume(false, onCancellation = null)
            }
        }
        cont.invokeOnCancellation { tts?.shutdown() }
    }

    /**
     * Debug builds only: `adb shell am start -n org.polycare.app/.MainActivity --ez household_check true`.
     * Exercises [org.polycare.app.households.HouseholdsRepository]'s consent rule directly — no
     * tap needed to prove the one safety-critical piece of M3's first slice: a member cannot be
     * added to a household that hasn't given consent. Logs to logcat tag PolyCareEvent (via the
     * normal event log, not a dedicated tag, since this is plain business logic, not a native/ML
     * pipeline check).
     */
    private fun runHouseholdCheck() {
        val noConsent = householdsRepository.addHousehold("Test (no consent)", "Test village", consentGiven = false)
        val blockedMember = householdsRepository.addMember(noConsent.id, "Should not be added", 5, "Child")
        val withConsent = householdsRepository.addHousehold("Test (consent given)", "Test village", consentGiven = true)
        val allowedMember = householdsRepository.addMember(withConsent.id, "Should be added", 5, "Child")
        Log.i(
            "PolyCareEvent",
            "household_check noConsentBlocked=${blockedMember == null} withConsentAllowed=${allowedMember != null} " +
                "households=${householdsRepository.households.value.size} members=${householdsRepository.members.value.size}",
        )
    }

    /**
     * Debug builds only: `adb shell am start -n org.polycare.app/.MainActivity --ez due_list_check true`.
     * Tests visit recording, due item completion, semantic search over visit notes, and the monthly
     * incentive report computation on real hardware (M3: Due list, visit notes, incentive tracker).
     */
    private fun runDueListCheck() {
        lifecycleScope.launch(Dispatchers.Default) {
            val hh = householdsRepository.addHousehold("Asha Devi", "Rampur", consentGiven = true)
            val member = householdsRepository.addMember(hh.id, "Asha Devi", 22, "Mother")
            val pendingBefore = householdsRepository.dueItems.value.count { !it.completed }
            val firstDue = householdsRepository.dueItems.value.firstOrNull { !it.completed }

            val visit = householdsRepository.recordVisit(
                householdId = hh.id,
                memberId = member?.id,
                memberName = "Asha Devi",
                type = org.polycare.app.households.VisitType.ANC,
                notes = "3rd ANC checkup completed. BP 120/80 normal. Advised IFA tablets.",
                highRisk = false,
                dueItemId = firstDue?.id,
            )

            val pendingAfter = householdsRepository.dueItems.value.count { !it.completed }
            val report = householdsRepository.monthlyReport()
            val searchResults = householdsRepository.searchVisits("blood pressure IFA tablets")

            Log.i(
                "PolyCareEvent",
                "due_list_check visitRecorded=${visit != null} incentiveEarned=₹${report.totalIncentiveRupees} " +
                    "dueCompleted=${pendingAfter < pendingBefore} searchMatches=${searchResults.size}",
            )
        }
    }

    private fun isDebuggable() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Debug builds only: `--ez sync_check true` verifies gateway enrollment/auth and a full sync. */
    private fun runSyncCheck() {
        lifecycleScope.launch {
            val connection = syncRepository.testConnection()
            if (connection.isFailure) {
                Log.e(SYNC_TAG, "sync_check connection FAIL: ${connection.exceptionOrNull()?.message}")
                return@launch
            }
            Log.i(SYNC_TAG, "sync_check connection PASS: ${connection.getOrThrow()}")
            when (val result = syncRepository.syncNow()) {
                is org.polycare.app.sync.SyncState.Done -> Log.i(
                    SYNC_TAG,
                    "sync_check PASS pushed=${result.stats.pushedOps} answers=${result.stats.answersReceived} " +
                        "alerts=${result.stats.alertsReceived} guidance=${result.stats.guidanceReceived}",
                )
                is org.polycare.app.sync.SyncState.Failed -> Log.e(SYNC_TAG, "sync_check sync FAIL: ${result.reason}")
                else -> Log.w(SYNC_TAG, "sync_check ended in ${result.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val EXTRA_BENCH_POINTS = "bench_points"
        const val EXTRA_EMBED_CHECK = "embed_check"
        const val EXTRA_LLM_CHECK = "llm_check"
        const val EXTRA_SKILL_CHECK = "skill_check"
        const val EXTRA_VOICE_CHECK = "voice_check"
        const val EXTRA_HINDI_CHECK = "hindi_check"
        const val EXTRA_HOUSEHOLD_CHECK = "household_check"
        const val EXTRA_DUE_LIST_CHECK = "due_list_check"
        const val EXTRA_SYNC_CHECK = "sync_check"
        const val EXTRA_SEARCH_QUERY = "search_query"
        const val EXTRA_ASK_QUERY = "ask_query"
        const val EXTRA_OPEN_TRIAGE = "open_triage"
        const val EXTRA_OPEN_ROUTE = "open_route"
        const val EXTRA_OCR_IMAGE_PATH = "ocr_image_path"
        const val EXTRA_OPEN_DRAWER = "open_drawer"
        const val EXTRA_WHISPER_WAV_PATH = "whisper_wav_path"
        const val EMBED_TAG = "PolyCareEmbed"
        const val LLM_TAG = "PolyCareLlm"
        const val WHISPER_TAG = "PolyCareWhisper"
        const val SYNC_TAG = "PolyCareSync"
    }
}
