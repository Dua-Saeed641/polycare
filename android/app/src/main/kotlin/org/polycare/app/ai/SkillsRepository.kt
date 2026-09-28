package org.polycare.app.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import org.polycare.common.Artifact
import org.polycare.common.ArtifactVerifier
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.EventLog.Level
import org.polycare.common.Verification
import org.polycare.llm.LlmArtifacts
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class SkillInfo(val id: String, val title: String, val card: String, val file: File, val artifact: Artifact)

/**
 * LoRA skill adapters available on the phone (M0: "two LoRA adapters loaded and switched per
 * request"). Each entry in `files/models/skills/manifest.json` is verified by sha256 before it
 * is considered loadable — the same rule as the base model and the knowledge package.
 *
 * `tools/skills/train_skill.py` trains and converts these; `tools/models/push_skills.sh` copies
 * the manifest and adapter files to the phone. This repository only proves adapters can be
 * verified, loaded and hot-swapped; choosing which skill(s) answer a given question is
 * [SkillRouter] (ARCHITECTURE.md §5.1: compare the question to each skill's `card` by cosine
 * similarity, blend the top two if close, or fall back to the base model).
 */
@Singleton
class SkillsRepository @Inject constructor(@ApplicationContext context: Context, private val events: EventLog) {
    private val root = File(context.filesDir, "models/skills")
    private val manifest = File(root, "manifest.json")

    fun available(): List<SkillInfo> {
        if (!manifest.exists()) return emptyList()
        return runCatching {
            val o = JSONObject(manifest.readText())
            val baseModel = o.optString("baseModel")
            if (baseModel.isNotEmpty() && baseModel != LlmArtifacts.MODEL_ID) {
                events.record(
                    Category.MODEL, "Skills rejected: different base model",
                    mapOf("skillsBase" to baseModel, "phoneBase" to LlmArtifacts.MODEL_ID), Level.ERROR,
                )
                return emptyList()
            }
            val arr = o.getJSONArray("skills")
            List(arr.length()) { i -> arr.getJSONObject(i) }.mapNotNull { s ->
                val artifact = Artifact(path = "skills/${s.getString("file")}", sha256 = s.getString("sha256"), sizeBytes = s.getLong("sizeBytes"))
                when (val v = ArtifactVerifier.verify(root.parentFile!!, artifact)) {
                    Verification.Ok -> SkillInfo(s.getString("id"), s.getString("title"), s.optString("card"), File(root, s.getString("file")), artifact)
                    Verification.Missing -> null
                    is Verification.Quarantined -> {
                        events.record(Category.MODEL, "Skill quarantined", mapOf("id" to s.getString("id"), "reason" to v.reason), Level.ERROR)
                        null
                    }
                }
            }
        }.getOrElse { e ->
            events.record(Category.MODEL, "Skills manifest unreadable", mapOf("error" to e.javaClass.simpleName), Level.ERROR)
            emptyList()
        }
    }
}
