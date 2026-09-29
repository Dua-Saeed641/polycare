package org.polycare.app.households

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated encryption for the local household database using a non-exportable Keystore key. */
internal class HouseholdStoreCipher {
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext)
        return MAGIC + byteArrayOf(iv.size.toByte()) + iv + encrypted
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > MAGIC.size + 1 && blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "Invalid encrypted household store header"
        }
        val ivSize = blob[MAGIC.size].toInt() and 0xff
        val ivStart = MAGIC.size + 1
        val encryptedStart = ivStart + ivSize
        require(ivSize == GCM_IV_BYTES && encryptedStart < blob.size) { "Invalid encrypted household store" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(GCM_TAG_BITS, blob.copyOfRange(ivStart, encryptedStart)),
        )
        return cipher.doFinal(blob.copyOfRange(encryptedStart, blob.size))
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "org.polycare.household_store.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_IV_BYTES = 12
        val MAGIC = byteArrayOf('P'.code.toByte(), 'C'.code.toByte(), 'H'.code.toByte(), 'H'.code.toByte(), 1)
    }
}
