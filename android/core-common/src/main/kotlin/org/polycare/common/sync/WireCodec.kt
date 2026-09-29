package org.polycare.common.sync

import org.polycare.common.Hlc
import java.security.MessageDigest

/**
 * The bytes the gateway verifies. `cloud/gateway/app/main.py` signs and hashes *canonical JSON*:
 * `json.dumps(fields, sort_keys=True, separators=(",", ":"), ensure_ascii=False)` over
 * `{op_id, device_id, hlc{wall_ms,logical,node}, kind, payload, prev_hash}` where `prev_hash` is
 * lowercase hex (it is base64 only on the REST wire). The op hash chained into the next op is
 * `sha256(signed_bytes ‖ signature)`. Any difference here, even one escaped character, makes the
 * gateway answer "signature is invalid", so this mirrors Python's encoder exactly for the value
 * types a signal or gap can carry: strings, integers, booleans, maps, lists.
 */
object WireCodec {

    val ZERO_HASH: ByteArray = ByteArray(32)

    fun canonicalJson(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, v: Any?) {
        when (v) {
            null -> out.append("null")
            is Boolean -> out.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> out.append(v.toString())
            is String -> writeString(out, v)
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for (key in v.keys.map { it as String }.sorted()) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, key)
                    out.append(':')
                    write(out, v[key])
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                v.forEachIndexed { i, item -> if (i > 0) out.append(','); write(out, item) }
                out.append(']')
            }
            else -> error("unsupported canonical JSON value: ${v::class}")
        }
    }

    /** Python `ensure_ascii=False` string escaping: quote, backslash and control characters only. */
    private fun writeString(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c < ' ' -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    /** Canonical bytes the device signs for one op. */
    fun signedBytes(opId: String, deviceId: String, hlc: Hlc, kind: String, payload: Map<String, Any?>, prevHash: ByteArray): ByteArray {
        require(prevHash.size == 32) { "prev_hash must be 32 bytes" }
        val fields = mapOf(
            "op_id" to opId,
            "device_id" to deviceId,
            "hlc" to mapOf("wall_ms" to hlc.wallMs, "logical" to hlc.logical, "node" to hlc.node),
            "kind" to kind,
            "payload" to payload,
            "prev_hash" to hex(prevHash),
        )
        return canonicalJson(fields).toByteArray(Charsets.UTF_8)
    }

    /** The value that becomes the next op's `prev_hash`. */
    fun opHash(signed: ByteArray, signature: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(signed + signature)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
