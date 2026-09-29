package org.polycare.common.sync

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.IsoFields
import java.util.Base64
import java.util.Random

/**
 * Encodes a de-identified symptom observation into the exact `Signal` shape the gateway accepts
 * (`proto/sync.proto`, `cloud/gateway/app/main.py` `SIGNAL_FIELDS`): a 384-value float16 vector,
 * the embedding model id, a 16-bit SimHash region, a village code, an ISO week, an age band and a
 * sex. No text and no identity ever enters this shape.
 */
object SignalCodec {

    const val DIM = 384

    /**
     * Fleet-wide seed for the 16 Gaussian hyperplanes (ARCHITECTURE.md §5.6). Every phone and the
     * gateway must use the same seed, or the same symptom lands in different regions.
     */
    const val FLEET_SEED = 0x504F4C5943415245L

    private val hyperplanes: Array<FloatArray> by lazy {
        val rnd = Random(FLEET_SEED) // java.util.Random's nextGaussian is specified, so this is stable
        Array(16) { FloatArray(DIM) { rnd.nextGaussian().toFloat() } }
    }

    /** Ages the gateway accepts; anything else is rejected. */
    val ageBands = listOf("0-1", "1-4", "5-9", "10-14", "15-19", "20-29", "30-39", "40-49", "50+")

    /** 16 sign bits, bit 0 = first hyperplane. Each prefix of the hash is a semantic region. */
    fun simhash16(vector: FloatArray): Int {
        require(vector.size == DIM) { "expected $DIM values, got ${vector.size}" }
        var bits = 0
        for (i in 0 until 16) {
            var dot = 0f
            val h = hyperplanes[i]
            for (d in 0 until DIM) dot += h[d] * vector[d]
            if (dot >= 0f) bits = bits or (1 shl i)
        }
        return bits
    }

    /** IEEE 754 binary16 bits of [value] (round to nearest even; overflow becomes infinity). */
    fun toHalfBits(value: Float): Int {
        val f = java.lang.Float.floatToIntBits(value)
        val sign = (f ushr 16) and 0x8000
        var exp = ((f ushr 23) and 0xFF) - 127 + 15
        var mant = f and 0x7FFFFF
        if (((f ushr 23) and 0xFF) == 0xFF) return sign or 0x7C00 or (if (mant != 0) 0x200 else 0) // inf / nan
        if (exp >= 31) return sign or 0x7C00
        if (exp <= 0) {
            if (exp < -10) return sign
            mant = mant or 0x800000
            val shift = 14 - exp
            var half = mant ushr shift
            val rem = mant and ((1 shl shift) - 1)
            val mid = 1 shl (shift - 1)
            if (rem > mid || (rem == mid && (half and 1) == 1)) half++
            return sign or half
        }
        var half = sign or (exp shl 10) or (mant ushr 13)
        val rem = mant and 0x1FFF
        if (rem > 0x1000 || (rem == 0x1000 && (half and 1) == 1)) half++ // may carry into exp: correct
        return half
    }

    /** Base64 of [vector] as 384 little-endian float16 values (768 bytes), the wire `dense_f16`. */
    fun denseF16Base64(vector: FloatArray): String {
        require(vector.size == DIM) { "expected $DIM values, got ${vector.size}" }
        val buf = ByteBuffer.allocate(DIM * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vector) buf.putShort(toHalfBits(v).toShort())
        return Base64.getEncoder().encodeToString(buf.array())
    }

    /** IEEE 754 binary16 bits back to a Float. */
    fun fromHalfBits(bits: Int): Float {
        val sign = if ((bits and 0x8000) != 0) -1f else 1f
        val exp = (bits ushr 10) and 0x1F
        val mant = bits and 0x3FF
        return when (exp) {
            0 -> sign * mant / 16_777_216f // subnormal: mant x 2^-24
            31 -> if (mant == 0) sign * Float.POSITIVE_INFINITY else Float.NaN
            else -> sign * (1f + mant / 1024f) * Math.pow(2.0, (exp - 15).toDouble()).toFloat()
        }
    }

    /** The inverse of [denseF16Base64]; null if [b64] is not exactly 384 float16 values. */
    fun decodeDenseF16(b64: String): FloatArray? {
        val raw = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return null
        if (raw.size != DIM * 2) return null
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(DIM) { fromHalfBits(buf.short.toInt() and 0xFFFF) }
    }

    /** ISO-8601 week number (1..53) of [wallMs] in UTC. */
    fun isoWeek(wallMs: Long): Int =
        Instant.ofEpochMilli(wallMs).atZone(ZoneOffset.UTC).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    /** A coarse, gateway-safe village code: `[A-Za-z0-9_-]{1,64}`, e.g. "Rampur East" -> "rampur_east". */
    fun villageCode(name: String): String {
        val slug = name.trim().lowercase().replace(Regex("\\s+"), "_").replace(Regex("[^a-z0-9_-]"), "")
        return slug.take(64).ifEmpty { "unknown" }
    }
}
