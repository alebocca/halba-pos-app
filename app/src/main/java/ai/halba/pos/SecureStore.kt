package ai.halba.pos

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Chiave del dispositivo (ricevuta da halba all'abbinamento) cifrata con una chiave AES dell'Android Keystore:
// la chiave AES non esce mai dal Keystore (TEE o StrongBox sui terminali veri), quindi i file dell'app da soli non
// bastano a impersonare il terminale. Nelle preferenze resta solo il testo cifrato (IV + GCM).
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("halba", Context.MODE_PRIVATE)

    private fun aesKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    val terminalId: String? get() = prefs.getString("terminal", null)

    // Chiave del dispositivo in chiaro solo in memoria. Migra la chiave salvata in chiaro dalle versioni precedenti.
    fun deviceKey(): String? {
        prefs.getString("key", null)?.let { plain ->
            save(terminalId ?: return null, plain)
            return plain
        }
        val enc = prefs.getString("key_enc", null) ?: return null
        return runCatching {
            val bytes = Base64.decode(enc, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, bytes, 0, IV_LEN))
            String(cipher.doFinal(bytes, IV_LEN, bytes.size - IV_LEN), Charsets.UTF_8)
        }.getOrNull() //  Keystore azzerato o dato manomesso: serve un nuovo abbinamento
    }

    fun save(terminalId: String, deviceKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey())
        val out = cipher.iv + cipher.doFinal(deviceKey.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("terminal", terminalId)
            .putString("key_enc", Base64.encodeToString(out, Base64.NO_WRAP))
            .remove("key")
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private companion object {
        const val ALIAS = "halba_device_key_wrap"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
