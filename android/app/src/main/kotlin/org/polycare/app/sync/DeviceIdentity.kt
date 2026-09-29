package org.polycare.app.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.polycare.app.security.SecureBox
import org.polycare.common.HlcClock
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This phone's identity toward the gateway: a stable device id and an Ed25519 key pair.
 *
 * - The device id is the HLC node id, because the gateway requires every op's HLC node to equal
 *   its device id.
 * - The 32-byte private seed is generated once and stored sealed with the Android Keystore key
 *   ([SecureBox]); the gateway only ever sees the public key (at registration) and signatures.
 * - Ed25519 comes from BouncyCastle's lightweight API (the platform only offers Ed25519 from API
 *   33, and minSdk is 29). It is deterministic, so re-signing a retried op gives the same bytes.
 */
@Singleton
class DeviceIdentity @Inject constructor(
    @ApplicationContext context: Context,
    clock: HlcClock,
) {
    val deviceId: String = clock.node

    private val file = File(File(context.filesDir, "identity").apply { mkdirs() }, "ed25519.seed")
    private val privateKey: Ed25519PrivateKeyParameters by lazy { loadOrCreate() }

    val publicKey: ByteArray by lazy { privateKey.generatePublicKey().encoded }

    fun sign(message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    private fun loadOrCreate(): Ed25519PrivateKeyParameters {
        val existing = runCatching { SecureBox.open(file.readText())?.let { java.util.Base64.getDecoder().decode(it) } }.getOrNull()
        if (existing != null && existing.size == 32) return Ed25519PrivateKeyParameters(existing, 0)
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        file.writeText(SecureBox.seal(java.util.Base64.getEncoder().encodeToString(seed)))
        return Ed25519PrivateKeyParameters(seed, 0)
    }
}
