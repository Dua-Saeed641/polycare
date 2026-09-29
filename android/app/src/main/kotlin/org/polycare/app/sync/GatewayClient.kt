package org.polycare.app.sync

import org.json.JSONArray
import org.json.JSONObject
import org.polycare.app.settings.AppSettings
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** The gateway refused a request; [code] is the HTTP status, [message] its `detail` if it sent one. */
class GatewayException(val code: Int, message: String) : IOException(message)

data class PushAck(val accepted: List<String>, val duplicates: List<String>, val chainHead: ByteArray)

/**
 * Talks the gateway's protocol (`proto/sync.proto`, `cloud/gateway/app/main.py`):
 *
 *  1. `POST /v1/devices/register`   enrollment token + this phone's Ed25519 public key (idempotent)
 *  2. `POST /v1/auth/challenge`     a one-use 2-minute nonce
 *  3. `POST /v1/auth/verify`        the nonce signed with the device key -> short-lived bearer token
 *  4. `POST /v1/ops/push`           signed, hash-chained ops
 *
 * plus two team endpoints that extend the gateway (`cloud/gateway/app/team.py`): outbreak alerts
 * and supervisor answers. All calls are blocking; callers run them on `Dispatchers.IO`.
 */
@Singleton
class GatewayClient @Inject constructor(
    private val settings: AppSettings,
    private val identity: DeviceIdentity,
) {
    private var token: String? = null
    private var tokenAtMs = 0L

    private fun base(): String = settings.gatewayUrl.value.also { require(it.isNotBlank()) { "Enter the gateway address first" } }

    fun health(): String = JSONObject(get("${base()}/healthz", auth = false)).optString("status", "ok")

    /** Registers this phone's key (a second call with the same key is a no-op) and returns a bearer token. */
    fun authenticate(): String {
        token?.let { if (System.currentTimeMillis() - tokenAtMs < TOKEN_REUSE_MS) return it }
        val b64 = Base64.getEncoder()
        post(
            "${base()}/v1/devices/register",
            JSONObject().put("device_id", identity.deviceId).put("public_key", b64.encodeToString(identity.publicKey)),
            auth = false,
            extraHeaders = mapOf("X-Enrollment-Token" to settings.enrollmentToken.value),
        )
        val ch = JSONObject(post("${base()}/v1/auth/challenge", JSONObject().put("device_id", identity.deviceId), auth = false))
        val nonce = Base64.getDecoder().decode(ch.getString("nonce"))
        val verified = JSONObject(
            post(
                "${base()}/v1/auth/verify",
                JSONObject().put("challenge_id", ch.getString("challenge_id")).put("device_id", identity.deviceId)
                    .put("signature", b64.encodeToString(identity.sign(nonce))),
                auth = false,
            ),
        )
        return verified.getString("access_token").also { token = it; tokenAtMs = System.currentTimeMillis() }
    }

    fun push(ops: JSONArray): PushAck {
        val body = JSONObject(post("${base()}/v1/ops/push", JSONObject().put("ops", ops), auth = true))
        val dec = Base64.getDecoder()
        return PushAck(
            accepted = body.optJSONArray("accepted_op_ids").toStrings(),
            duplicates = body.optJSONArray("duplicate_op_ids").toStrings(),
            chainHead = dec.decode(body.getString("chain_head")),
        )
    }

    fun radarAlerts(): JSONArray = JSONObject(get("${base()}/v1/radar/alerts", auth = true)).optJSONArray("alerts") ?: JSONArray()

    fun answers(afterMs: Long): JSONObject = JSONObject(get("${base()}/v1/answers?after_ms=$afterMs", auth = true))

    // ---- team memory ---------------------------------------------------------------------

    fun merkleChildren(prefix: String): List<String> {
        val a = JSONObject(get("${base()}/v1/merkle?prefix=$prefix", auth = true)).getJSONArray("children")
        return List(a.length()) { a.getString(it) }
    }

    /** Op ids the gateway holds in a 4-digit SimHash region. */
    fun merkleLeaf(region: String): List<String> {
        val a = JSONObject(get("${base()}/v1/merkle/leaf?region=$region", auth = true)).getJSONArray("ops")
        return List(a.length()) { a.getJSONObject(it).getString("op_id") }
    }

    /** Tip ops by id, as the gateway stored them (with signer keys, so the phone can verify). */
    fun fetchOps(opIds: List<String>): JSONArray =
        JSONObject(post("${base()}/v1/ops/fetch", JSONObject().put("op_ids", JSONArray(opIds)), auth = true)).optJSONArray("ops") ?: JSONArray()

    /** Distinct-device vote count per tip id. */
    fun votes(): Map<String, Int> {
        val v = JSONObject(get("${base()}/v1/votes", auth = true)).optJSONObject("votes") ?: return emptyMap()
        return v.keys().asSequence().associateWith { v.getInt(it) }
    }

    fun guidance(afterMs: Long, villageCode: String): JSONObject =
        JSONObject(get("${base()}/v1/guidance?after_ms=$afterMs&village_code=${java.net.URLEncoder.encode(villageCode, "UTF-8")}", auth = true))

    // ---- artifacts -----------------------------------------------------------------------

    fun artifacts(): JSONArray = JSONObject(get("${base()}/v1/artifacts", auth = true)).optJSONArray("artifacts") ?: JSONArray()

    /**
     * Downloads an artifact into [part], **resuming** from whatever is already there with an HTTP
     * `Range` request. If the server ignores the range (200 instead of 206) the file restarts from
     * zero. Returns when the file is complete; [onProgress] gets (bytes so far, total).
     */
    fun download(name: String, version: String, part: File, total: Long, onProgress: (Long, Long) -> Unit) {
        org.polycare.app.chaos.Chaos.beforeGatewayCall()
        var have = if (part.exists()) part.length() else 0L
        if (have > total) { part.delete(); have = 0L }
        if (have == total) return
        val url = "${base()}/v1/artifacts/${java.net.URLEncoder.encode(name, "UTF-8")}/${java.net.URLEncoder.encode(version, "UTF-8")}/file"
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Authorization", "Bearer ${authenticate()}")
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code == 401) token = null
            if (code != 200 && code != 206) throw GatewayException(code, "Download refused ($code)")
            val append = code == 206 && have > 0
            if (!append) have = 0L
            java.io.FileOutputStream(part, append).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        onProgress(have, total)
                    }
                }
                out.fd.sync()
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Drops the cached token, e.g. after a 401, so the next call re-authenticates. */
    fun forgetToken() { token = null }

    // ---------------------------------------------------------------------------------------

    private fun get(url: String, auth: Boolean): String = call("GET", url, null, auth, emptyMap())

    private fun post(url: String, body: JSONObject, auth: Boolean, extraHeaders: Map<String, String> = emptyMap()): String =
        call("POST", url, body.toString(), auth, extraHeaders)

    private fun call(method: String, url: String, body: String?, auth: Boolean, extraHeaders: Map<String, String>): String {
        org.polycare.app.chaos.Chaos.beforeGatewayCall()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            if (auth) conn.setRequestProperty("Authorization", "Bearer ${authenticate()}")
            extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                if (code == 401) token = null
                val detail = runCatching { JSONObject(text).get("detail").toString() }.getOrNull()
                throw GatewayException(code, detail ?: "Gateway answered $code")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONArray?.toStrings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 20_000
        /** Gateway tokens live 15 minutes; reuse for 12 so one never expires mid-request. */
        const val TOKEN_REUSE_MS = 12L * 60 * 1000
    }
}
