package org.polycare.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ArtifactVerifierTest {
    @TempDir
    lateinit var dir: File

    private val content = "polycare".toByteArray()
    private val artifact = Artifact(
        path = "m/model.bin",
        sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) },
        sizeBytes = content.size.toLong(),
    )

    private fun write(bytes: ByteArray) = File(dir, "m").apply { mkdirs() }.resolve("model.bin").writeBytes(bytes)

    @Test
    fun `matching file verifies`() {
        write(content)
        assertEquals(Verification.Ok, ArtifactVerifier.verify(dir, artifact))
    }

    @Test
    fun `missing file is reported, not thrown`() {
        assertEquals(Verification.Missing, ArtifactVerifier.verify(dir, artifact))
    }

    @Test
    fun `tampered file is quarantined`() {
        write("polycarE".toByteArray())
        val result = ArtifactVerifier.verify(dir, artifact)
        assertTrue(result is Verification.Quarantined)
        assertFalse(File(dir, "m/model.bin").exists())
        assertTrue(File(dir, "m/model.bin.quarantine").exists())
    }
}
