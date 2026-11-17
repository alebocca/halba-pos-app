package ai.halba.pos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

// Vettori calcolati a parte con il crypto di Node (pbkdf2Sync sha1, aes-256-cbc, hmac sha256), come da documentazione Adyen
class NexoCryptoTest {
    private val msg = """{"SaleToPOIRequest":{"MessageHeader":{"ServiceID":"ABC"}}}"""
    private fun crypto(id: String = "halba-test") = NexoCrypto(id, "halba-pass€", 1) { it.fill(7) }

    @Test fun derivesAdyenKeyMaterial() {
        val k = NexoCrypto.pbkdf2HmacSha1("halba-pass€".toByteArray(Charsets.UTF_8), "AdyenNexoV1Salt".toByteArray(), 4000, 80)
        assertEquals("e8e6935d2640f29a0a290c7cc646a88c1108b7998855d3b18021e3d37687b9642c2f3574d1f222dccb8f3692cb0cc19e16f45846b5a506352f261d16a5b1106b34bd4c591ad15e5d273346d6dad3258e", k.joinToString("") { "%02x".format(it) })
    }

    @Test fun encryptsLikeAdyen() {
        val s = crypto().encrypt(JSONObject(msg)).getJSONObject("SaleToPOIRequest")
        assertEquals("hId9mcAI7KI4RnzEOmCMWz4rchUtPhSKqoFuQWfeuAoyeyqLJEnCTi9lnp8Nq1ZWTwG6LvcoS4sYLFiRwK+tlw==", s.getString("NexoBlob"))
        val t = s.getJSONObject("SecurityTrailer")
        assertEquals("IT2oLT4lwGeU5VMGUyb/wUfOTT/K8DmBYQugTpiigrs=", t.getString("Hmac"))
        assertEquals(1, t.getInt("AdyenCryptoVersion"))
        assertEquals("halba-test", t.getString("KeyIdentifier"))
        assertEquals("ABC", s.getJSONObject("MessageHeader").getString("ServiceID"))
    }

    @Test fun decryptsAndChecks() {
        val secured = crypto().encrypt(JSONObject(msg))
        assertEquals("ABC", crypto().decrypt(secured).getJSONObject("SaleToPOIRequest").getJSONObject("MessageHeader").getString("ServiceID"))
        // Intestazione esterna cambiata: rifiutato
        val tampered = JSONObject(secured.toString()).apply { getJSONObject("SaleToPOIRequest").getJSONObject("MessageHeader").put("ServiceID", "XYZ") }
        assertThrows(NexoCrypto.CryptoError::class.java) { crypto().decrypt(tampered) }
        // Altra chiave: rifiutato
        assertThrows(NexoCrypto.CryptoError::class.java) { crypto("other").decrypt(secured) }
        // HMAC alterato: rifiutato
        val badHmac = JSONObject(secured.toString()).apply { getJSONObject("SaleToPOIRequest").getJSONObject("SecurityTrailer").put("Hmac", "AAAA") }
        assertThrows(NexoCrypto.CryptoError::class.java) { crypto().decrypt(badHmac) }
    }

    @Test fun readsAdditionalResponse() {
        assertEquals("Refused", AdyenReader.parseAdditional("refusalReason=Refused&tid=1")["refusalReason"])
    }
}
