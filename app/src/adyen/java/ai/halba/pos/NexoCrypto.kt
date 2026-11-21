package ai.halba.pos

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// Protezione dei messaggi della Terminal API locale di Adyen (docs.adyen.com, "Protect local communications"):
// chiave derivata dalla passphrase con PBKDF2-HMAC-SHA1 (sale "AdyenNexoV1Salt", 4000 iterazioni, 80 byte: 32 per
// l'HMAC, 32 per AES, 16 di IV), AES-256-CBC con IV = IV derivato XOR nonce casuale, HMAC-SHA256 del testo in chiaro.
// La passphrase è quella impostata dal merchant in Customer Area (Device settings > Integrations > Terminal API).
class NexoCrypto(private val keyIdentifier: String, passphrase: String, private val keyVersion: Int, private val random: (ByteArray) -> Unit = { SecureRandom().nextBytes(it) }) {
    class CryptoError(message: String) : Exception(message)

    private val hmacKey: ByteArray
    private val cipherKey: ByteArray
    private val iv: ByteArray

    init {
        val k = pbkdf2HmacSha1(passphrase.toByteArray(Charsets.UTF_8), SALT, 4000, 80)
        hmacKey = k.copyOfRange(0, 32)
        cipherKey = k.copyOfRange(32, 64)
        iv = k.copyOfRange(64, 80)
    }

    // body: "SaleToPOIRequest" o "SaleToPOIResponse"; message: il messaggio completo { body: { MessageHeader, … } }
    fun encrypt(message: JSONObject, body: String = "SaleToPOIRequest"): JSONObject {
        val plain = message.toString().toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(16).also(random)
        val blob = crypt(Cipher.ENCRYPT_MODE, plain, nonce)
        val header = message.getJSONObject(body).getJSONObject("MessageHeader")
        return JSONObject().put(body, JSONObject()
            .put("MessageHeader", header)
            .put("NexoBlob", b64(blob))
            .put("SecurityTrailer", JSONObject()
                .put("AdyenCryptoVersion", 1)
                .put("KeyIdentifier", keyIdentifier)
                .put("KeyVersion", keyVersion)
                .put("Nonce", b64(nonce))
                .put("Hmac", b64(hmac(plain)))))
    }

    // Messaggio protetto → messaggio in chiaro, dopo i controlli di Adyen: versione, chiave, HMAC, stessa intestazione
    fun decrypt(secured: JSONObject): JSONObject {
        val body = secured.keys().asSequence().firstOrNull { it == "SaleToPOIResponse" || it == "SaleToPOIRequest" } ?: throw CryptoError("Not a Terminal API message")
        val outer = secured.getJSONObject(body)
        val trailer = outer.optJSONObject("SecurityTrailer") ?: throw CryptoError("Message not protected")
        if (trailer.optInt("AdyenCryptoVersion") != 1) throw CryptoError("Unsupported AdyenCryptoVersion")
        if (trailer.optString("KeyIdentifier") != keyIdentifier || trailer.optInt("KeyVersion") != keyVersion) throw CryptoError("Message protected with another key")
        val nonce = unb64(trailer.getString("Nonce"))
        if (nonce.size != 16) throw CryptoError("Invalid nonce")
        val plain = try { crypt(Cipher.DECRYPT_MODE, unb64(outer.getString("NexoBlob")), nonce) } catch (e: Exception) { throw CryptoError("Cannot decrypt the message") }
        if (!MessageDigest.isEqual(hmac(plain), unb64(trailer.getString("Hmac")))) throw CryptoError("HMAC validation failed")
        val msg = JSONObject(String(plain, Charsets.UTF_8))
        val inner = msg.optJSONObject(body)?.optJSONObject("MessageHeader")
        if (inner == null || !sameJson(inner, outer.getJSONObject("MessageHeader"))) throw CryptoError("Message header mismatch")
        return msg
    }

    private fun crypt(mode: Int, data: ByteArray, nonce: ByteArray): ByteArray {
        val actual = ByteArray(16) { (iv[it].toInt() xor nonce[it].toInt()).toByte() }
        return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(mode, SecretKeySpec(cipherKey, "AES"), IvParameterSpec(actual))
            doFinal(data)
        }
    }

    private fun hmac(data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(hmacKey, "HmacSHA256")); doFinal(data) }

    companion object {
        private val SALT = "AdyenNexoV1Salt".toByteArray(Charsets.US_ASCII)
        private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
        private fun unb64(s: String) = try { Base64.getDecoder().decode(s) } catch (e: IllegalArgumentException) { throw CryptoError("Invalid Base64") }

        // PBKDF2 scritto qui (RFC 8018), uguale su ogni versione di Android: i byte della passphrase sono UTF-8
        fun pbkdf2HmacSha1(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(password.takeIf { it.isNotEmpty() } ?: ByteArray(1), "HmacSHA1")) }
            if (password.isEmpty()) throw CryptoError("Empty passphrase")
            val out = ByteArray(length)
            var block = 1
            var pos = 0
            while (pos < length) {
                mac.update(salt)
                mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
                var u = mac.doFinal()
                val t = u.copyOf()
                repeat(iterations - 1) {
                    u = mac.doFinal(u)
                    for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
                }
                val n = minOf(t.size, length - pos)
                System.arraycopy(t, 0, out, pos, n)
                pos += n
                block++
            }
            return out
        }

        private fun sameJson(a: JSONObject, b: JSONObject): Boolean {
            if (a.length() != b.length()) return false
            return a.keys().asSequence().all { k -> b.has(k) && a.get(k).toString() == b.get(k).toString() }
        }
    }
}
