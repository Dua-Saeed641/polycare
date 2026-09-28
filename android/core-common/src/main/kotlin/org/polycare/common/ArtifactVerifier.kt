package org.polycare.common

import java.io.File
import java.security.MessageDigest

/** A file the app loads (model, adapter, snapshot) and the hash it must have. */
data class Artifact(val path: String, val sha256: String, val sizeBytes: Long)

sealed interface Verification {
    data object Ok : Verification
    data object Missing : Verification
    data class Quarantined(val reason: String, val movedTo: File) : Verification
}

/**
 * Invariant 5: artifacts are loaded only after their sha256 matches. A bad file is moved aside
 * (`*.quarantine`) so the caller falls back instead of crashing, and a later download can retry.
 */
object ArtifactVerifier {

    fun verify(root: File, artifact: Artifact): Verification {
        val file = File(root, artifact.path)
        if (!file.isFile) return Verification.Missing
        val reason = when {
            file.length() != artifact.sizeBytes -> "size ${file.length()} != ${artifact.sizeBytes}"
            else -> sha256(file).let { if (it.equals(artifact.sha256, ignoreCase = true)) null else "sha256 $it" }
        } ?: return Verification.Ok
        val quarantine = File(file.parentFile, file.name + ".quarantine")
        quarantine.delete()
        file.renameTo(quarantine)
        return Verification.Quarantined(reason, quarantine)
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 16).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
