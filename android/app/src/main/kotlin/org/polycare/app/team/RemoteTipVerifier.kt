package org.polycare.app.team

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import org.polycare.common.Hlc
import org.polycare.common.sync.SignalCodec
import org.polycare.common.sync.WireCodec
import java.util.Base64

/**
 * Checks a tip another phone pushed, as the gateway relays it, before it enters this phone's team
 * memory. The op carries its author's Ed25519 public key and signature over the canonical bytes
 * (the same [WireCodec] the phone signs with), so what is checked here is that the text, the
 * embedding and the author were not altered on the way. The public key itself comes from the
 * gateway, which verified the signature against the key the device registered; a key the gateway
 * lied about would still verify, so this protects integrity in transit, not against a malicious
 * gateway.
 */
object RemoteTipVerifier {

    /** The verified tip, or null if it is malformed, unsigned, or not a tip. [me] marks the phone's own tips. */
    fun verify(item: JSONObject, me: String): TeamTip? = runCatching {
        if (item.getString("kind") != "TIP") return null
        val opId = item.getString("op_id")
        val device = item.getString("device_id")
        val h = item.getJSONObject("hlc")
        val hlc = Hlc(h.getLong("wall_ms"), h.getInt("logical"), h.getString("node"))
        if (hlc.node != device) return null

        val p = item.getJSONObject("payload")
        val payload = LinkedHashMap<String, Any?>()
        p.keys().forEach { k -> payload[k] = p.get(k).let { v -> if (v is Number) v.toLong() else v.toString() } }

        val dec = Base64.getDecoder()
        val prev = dec.decode(item.getString("prev_hash"))
        val signature = dec.decode(item.getString("signature"))
        val publicKey = dec.decode(item.getString("public_key"))
        if (prev.size != 32 || signature.size != 64 || publicKey.size != 32) return null

        val signed = WireCodec.signedBytes(opId, device, hlc, "TIP", payload, prev)
        val verifier = Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(publicKey, 0)); update(signed, 0, signed.size) }
        if (!verifier.verifySignature(signature)) return null

        val embedding = SignalCodec.decodeDenseF16(payload["dense_f16"] as String) ?: return null
        TeamTip(
            id = opId, text = payload["text"] as String, embedding = embedding, modelId = payload["model_id"] as String,
            simhash = (payload["simhash"] as Long).toInt(), village = payload["village_code"] as String, author = device,
            wallMs = hlc.wallMs, logical = hlc.logical, mine = device == me,
        )
    }.getOrNull()
}
