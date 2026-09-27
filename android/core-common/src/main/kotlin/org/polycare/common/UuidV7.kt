package org.polycare.common

import java.security.SecureRandom
import java.util.UUID

/** UUIDv7 generator (RFC 9562) used for op_id idempotency keys. */
class UuidV7(
    private val unixMillis: () -> Long,
    private val random: SecureRandom = SecureRandom(),
) {
    fun next(): UUID {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val ms = unixMillis()
        for (i in 0 until 6) bytes[i] = (ms ushr (40 - 8 * i)).toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte() // version 7
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte() // IETF variant
        var msb = 0L
        var lsb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (bytes[i].toLong() and 0xFF)
        for (i in 8 until 16) lsb = (lsb shl 8) or (bytes[i].toLong() and 0xFF)
        return UUID(msb, lsb)
    }
}
