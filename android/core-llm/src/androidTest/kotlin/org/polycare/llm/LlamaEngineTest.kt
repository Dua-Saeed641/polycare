package org.polycare.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * Runs the real base model (and, when present, real trained skill adapters) on the phone's ARM
 * CPU. Needs `files/models/${LlmArtifacts.baseModel.path}`: `bash tools/models/push_models.sh`.
 * Skills are optional: `bash tools/models/push_skills.sh`.
 */
@RunWith(AndroidJUnit4::class)
class LlamaEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val modelFile = File(context.filesDir, "models/${LlmArtifacts.baseModel.path}")
    private val skillsDir = File(context.filesDir, "models/skills")

    @Test
    fun loadsAndGeneratesAGroundedAnswer() = runTest(timeout = 5.minutes) {
        assumeModelPresent()
        val engine = LlamaEngine.load(modelFile)
        assertNotNull("model failed to load", engine)
        try {
            val prompt = PromptFormat.ask(
                question = "How many antenatal check-ups does a pregnant woman need?",
                passageText = "Four antenatal visits must be completed during a normal pregnancy, at specific intervals.",
                sourceTitle = "ASHA Module 6",
            )
            val events = engine!!.generate(prompt, maxTokens = 48).toList()
            val text = events.filterIsInstance<GenerationEvent.Token>().joinToString("") { it.piece }
            val stats = (events.last() as GenerationEvent.Done).stats

            assertTrue("model produced no text", text.isNotBlank())
            assertTrue("prompt tokens should be well over zero", stats.promptTokens > 20)
            assertTrue("should generate at least one token", stats.generatedTokens > 0)
            android.util.Log.i("PolyCareLlmTest", "answer=\"$text\" tokPerSec=${stats.tokensPerSecond}")
        } finally {
            engine?.close(Unit)
        }
    }

    /** M0: "Two LoRA adapters loaded and switched per request" — proved with real trained adapters. */
    @Test
    fun loadsTwoSkillsAndSwitchesBetweenThem() = runTest(timeout = 5.minutes) {
        assumeModelPresent()
        val manifest = File(skillsDir, "manifest.json")
        org.junit.Assume.assumeTrue("run tools/skills/train_skill.py + push_skills.sh first", manifest.exists())

        val skills = org.json.JSONObject(manifest.readText()).getJSONArray("skills")
        org.junit.Assume.assumeTrue("need at least 2 trained skills for a switch test", skills.length() >= 2)
        val files = List(minOf(2, skills.length())) { i -> File(skillsDir, skills.getJSONObject(i).getString("file")) }

        val engine = LlamaEngine.load(modelFile)
        assertNotNull(engine)
        try {
            files.forEach { f -> assertTrue("failed to load ${f.name}", engine!!.loadSkill(f)) }

            val prompt = PromptFormat.ask(
                "What should an ASHA remember about care during pregnancy?",
                "Care during pregnancy requires regular check-ups and attention to danger signs.",
                "ASHA guidance",
            )

            suspend fun answerWith(active: List<Pair<File, Float>>): String {
                assertTrue(engine!!.setActiveSkills(active))
                return engine.generate(prompt, maxTokens = 24).toList()
                    .filterIsInstance<GenerationEvent.Token>().joinToString("") { it.piece }
            }

            val base = answerWith(emptyList())
            val skillA = answerWith(listOf(files[0] to 1.0f))
            val skillB = answerWith(listOf(files[1] to 1.0f))

            assertTrue("base model produced no text", base.isNotBlank())
            assertTrue("skill A produced no text", skillA.isNotBlank())
            assertTrue("skill B produced no text", skillB.isNotBlank())
            android.util.Log.i("PolyCareLlmTest", "base=\"$base\"\nskillA=\"$skillA\"\nskillB=\"$skillB\"")
        } finally {
            engine?.close(Unit)
        }
    }

    private fun assumeModelPresent() {
        org.junit.Assume.assumeTrue("run tools/models/push_models.sh first", modelFile.exists())
    }
}
