package org.polycare.app.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import org.polycare.app.knowledge.KnowledgeRepository
import org.polycare.app.settings.AppSettings
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.EventLog.Level
import org.polycare.common.sync.WireCodec
import org.polycare.embed.E5Embedder
import org.polycare.llm.LlmArtifacts
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** One update the gateway offers: a LoRA skill or a knowledge package. */
data class ArtifactEntry(
    val kind: String,
    val name: String,
    val version: String,
    val file: String,
    val size: Long,
    val sha256: String,
    val signature: String,
    val title: String,
    val card: String,
    val baseModel: String,
    /** For a knowledge package: its manifest, exactly as published. */
    val manifestJson: String?,
) {
    val label: String get() = if (kind == "skill") "Skill: ${title.ifBlank { name }}" else "Knowledge update $version"
}

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class Available(val entries: List<ArtifactEntry>, val refused: Int) : UpdateStatus
    data class Downloading(val label: String, val done: Long, val total: Long) : UpdateStatus
    data class Installed(val label: String) : UpdateStatus
    data class Failed(val reason: String) : UpdateStatus
}

/**
 * Artifact delivery (ARCHITECTURE.md §6.4). A download is accepted only after:
 *
 *  1. its **signature** verifies against the publisher key pinned on this phone (the gateway holds no
 *     signing key, so a compromised gateway cannot push a model);
 *  2. its **size and sha256** match the signed entry;
 *  3. it is **compatible**: a skill must have been trained on this phone's base model, a knowledge
 *     package must use this phone's embedding model (invariant 4).
 *
 * Downloads resume with HTTP `Range` into a `.part` file, are fsynced, and only then renamed into
 * place. A file that fails a check is renamed `*.quarantine` and the phone keeps what it has
 * (invariant 5: verify before load; on failure quarantine and fall back, never crash).
 */
@Singleton
class ArtifactsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: GatewayClient,
    private val settings: AppSettings,
    private val knowledge: KnowledgeRepository,
    private val events: EventLog,
) {
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences("polycare_artifacts", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "artifacts").apply { mkdirs() }

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    /** Asks the gateway what is on offer and keeps only what this phone would accept and does not have. */
    suspend fun checkForUpdates(): UpdateStatus = mutex.withLock {
        if (publicKey() == null) {
            return@withLock UpdateStatus.Failed("Paste the publisher's public key first. Updates are refused without one.").also { _status.value = it }
        }
        _status.value = UpdateStatus.Checking
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val arr = client.artifacts()
                var refused = 0
                val offered = ArrayList<ArtifactEntry>()
                for (i in 0 until arr.length()) {
                    val e = parse(arr.getJSONObject(i)) ?: continue
                    when {
                        !signatureValid(e) -> refused++
                        !compatible(e) -> refused++
                        installedVersion(e) == e.version -> Unit
                        else -> offered += e
                    }
                }
                UpdateStatus.Available(offered, refused)
            }
        }.getOrElse { UpdateStatus.Failed(friendly(it)) }
        _status.value = result
        result
    }

    /** Downloads, verifies and installs one entry. Safe to call again after a failure: it resumes. */
    suspend fun install(entry: ArtifactEntry): UpdateStatus = mutex.withLock {
        val part = File(dir, "${entry.name}-${entry.version}.part")
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                check(signatureValid(entry) && compatible(entry)) { "This update is not signed by the pinned publisher key or does not fit this phone." }
                _status.value = UpdateStatus.Downloading(entry.label, part.length(), entry.size)
                client.download(entry.name, entry.version, part, entry.size) { done, total ->
                    _status.value = UpdateStatus.Downloading(entry.label, done, total)
                }
                if (part.length() != entry.size || sha256(part) != entry.sha256) {
                    quarantine(part, "size or sha256 mismatch")
                    error("The download was damaged, so it was thrown away. Try again.")
                }
                when (entry.kind) {
                    "skill" -> installSkill(entry, part)
                    "knowledge" -> installKnowledge(entry, part)
                    else -> error("Unknown update type")
                }
                prefs.edit().putString(installedKey(entry), entry.version).apply()
                events.record(Category.MODEL, "Update installed", mapOf("kind" to entry.kind, "name" to entry.name, "version" to entry.version))
                UpdateStatus.Installed(entry.label)
            }
        }.getOrElse { e ->
            events.record(Category.MODEL, "Update failed", mapOf("kind" to entry.kind, "error" to e.javaClass.simpleName), Level.WARN)
            UpdateStatus.Failed(friendly(e))
        }
        _status.value = outcome
        outcome
    }

    // ---------------------------------------------------------------------------------------

    private fun installSkill(e: ArtifactEntry, part: File) {
        val root = File(context.filesDir, "models/skills").apply { mkdirs() }
        val target = File(root, File(e.file).name)
        val tmp = File(root, target.name + ".tmp")
        part.copyTo(tmp, overwrite = true)
        FileOutputStream(tmp, true).use { it.fd.sync() }
        check(tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))) { "Could not place the skill file" }
        part.delete()

        // Merge into the manifest SkillsRepository verifies against (sha256 of every adapter).
        val manifestFile = File(root, "manifest.json")
        val manifest = runCatching { JSONObject(manifestFile.readText()) }.getOrNull() ?: JSONObject()
        manifest.put("baseModel", e.baseModel)
        val old = manifest.optJSONArray("skills") ?: JSONArray()
        val skills = JSONArray()
        for (i in 0 until old.length()) if (old.getJSONObject(i).optString("id") != e.name) skills.put(old.getJSONObject(i))
        skills.put(
            JSONObject().put("id", e.name).put("title", e.title.ifBlank { e.name }).put("card", e.card)
                .put("file", target.name).put("sha256", e.sha256).put("sizeBytes", e.size),
        )
        manifest.put("skills", skills)
        val mtmp = File(root, "manifest.json.tmp")
        mtmp.writeText(manifest.toString(2))
        check(mtmp.renameTo(manifestFile) || (manifestFile.delete() && mtmp.renameTo(manifestFile))) { "Could not update the skill list" }
    }

    private suspend fun installKnowledge(e: ArtifactEntry, part: File) {
        val manifest = e.manifestJson ?: error("The knowledge update has no manifest")
        check(JSONObject(manifest).optString("file") == e.file) { "The knowledge manifest names a different file" }
        val incoming = knowledge.incomingDirectory
        val zip = File(incoming, File(e.file).name)
        part.copyTo(zip, overwrite = true)
        File(incoming, "${e.version}.json").writeText(manifest)
        part.delete()
        // KnowledgeRepository re-verifies size and sha256 itself, installs atomically, and keeps the old
        // package if anything is wrong.
        knowledge.reloadAfterUpdate()
    }

    private fun quarantine(file: File, why: String) {
        val q = File(file.parentFile, file.name.removeSuffix(".part") + ".quarantine")
        q.delete()
        file.renameTo(q)
        events.record(Category.MODEL, "Download quarantined", mapOf("reason" to why), Level.ERROR)
    }

    private fun parse(o: JSONObject): ArtifactEntry? = runCatching {
        ArtifactEntry(
            kind = o.getString("kind"), name = o.getString("name"), version = o.get("version").toString(), file = o.getString("file"),
            size = o.getLong("size"), sha256 = o.getString("sha256"), signature = o.getString("signature"),
            title = o.optString("title"), card = o.optString("card"), baseModel = o.optString("base_model"),
            manifestJson = o.optJSONObject("manifest")?.toString(),
        )
    }.getOrNull()

    private fun publicKey(): ByteArray? =
        runCatching { Base64.getDecoder().decode(settings.publisherKey.value) }.getOrNull()?.takeIf { it.size == 32 }

    /** Ed25519 over the canonical JSON of `{kind, name, version, sha256, size}`, as `tools/publish_artifact.py` signs it. */
    private fun signatureValid(e: ArtifactEntry): Boolean {
        val key = publicKey() ?: return false
        return runCatching {
            val signed = WireCodec.canonicalJson(
                mapOf("kind" to e.kind, "name" to e.name, "version" to e.version, "sha256" to e.sha256, "size" to e.size),
            ).toByteArray(Charsets.UTF_8)
            val v = Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(key, 0)); update(signed, 0, signed.size) }
            v.verifySignature(Base64.getDecoder().decode(e.signature))
        }.getOrDefault(false)
    }

    private fun compatible(e: ArtifactEntry): Boolean = when (e.kind) {
        "skill" -> e.baseModel == LlmArtifacts.MODEL_ID
        "knowledge" -> runCatching { JSONObject(e.manifestJson!!).let { it.getString("model_id") == E5Embedder.MODEL_ID && it.getInt("dim") == E5Embedder.DIM } }.getOrDefault(false)
        else -> false
    }

    private fun installedKey(e: ArtifactEntry) = "installed.${e.kind}.${e.name}"
    private fun installedVersion(e: ArtifactEntry): String? = prefs.getString(installedKey(e), null)

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun friendly(e: Throwable): String = when {
        e is GatewayException && e.code == 401 -> "The gateway didn't accept this phone. Check the enrollment token."
        e is GatewayException -> e.message ?: "Gateway error ${e.code}"
        e is java.net.UnknownHostException -> "Can't find the gateway. Check the address."
        e is java.net.SocketTimeoutException || e is java.net.ConnectException -> "Gateway not reachable. The download will resume next time."
        else -> e.message ?: "Update failed"
    }
}
