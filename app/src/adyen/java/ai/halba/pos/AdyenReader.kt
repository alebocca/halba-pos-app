package ai.halba.pos

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

// Carta sui terminali Android di Adyen (S1F2, S1E…): l'app halba gira sul terminale accanto all'app di pagamento di
// Adyen e le manda la richiesta con la Terminal API locale (https://localhost:8443/nexo). Niente SDK e niente Cloud
// Device API: halba prepara la PaymentRequest, l'app aggiunge l'intestazione con il POIID del terminale, protegge il
// messaggio con la chiave di cifratura del merchant e verifica il certificato del terminale (radice Adyen, nome comune
// del terminale). L'esito che conta per halba è la notifica AUTHORISATION di Adyen: quello dell'app serve allo schermo.
class AdyenReader(private val api: HalbaApi) : CardReader {
    class Declined(message: String) : Exception(message)

    private lateinit var context: Context
    private var crypto: NexoCrypto? = null
    private var environment = "test"
    private var poiid: String? = null

    override val label = "Adyen"
    override val permissions = emptyList<String>()
    override val connected: Boolean get() = crypto != null && poiid != null

    override fun init(context: Context) { this.context = context.applicationContext }

    // POIID dal terminale (Adyen: Settings.Global DEVICE_NAME, per esempio S1F2-000158262320202) e chiave da halba
    override suspend fun connect(): String {
        poiid = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { POIID.matches(it) }
            ?: throw IllegalStateException("This is not an Adyen payment terminal")
        loadKey()
        return "Adyen $poiid · payment app"
    }

    private suspend fun loadKey() {
        val cfg = api.config()
        environment = cfg.optString("environment", "test")
        val k = cfg.optJSONObject("adyen")
        crypto = if (k == null) null else NexoCrypto(k.getString("key_identifier"), k.getString("key_passphrase"), k.optInt("key_version", 1))
        if (crypto == null) throw IllegalStateException("Adyen encryption key missing: add it to the Adyen connection in the halba dashboard")
    }

    override fun canPayByCard(payment: JSONObject) = payment.optJSONObject("adyen") != null

    override val cardHint = "Tap, insert or swipe the card on the terminal"

    override suspend fun collect(payment: JSONObject) {
        val a = payment.getJSONObject("adyen")
        if (crypto == null || poiid == null) connect()
        val header = header("Payment", a.getString("service_id"), a.getString("sale_id"))
        val request = JSONObject().put("SaleToPOIRequest", JSONObject().put("MessageHeader", header).put("PaymentRequest", a.getJSONObject("payment_request")))
        val response = try {
            send(request, 170_000)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Annullato dalla cassa, dal cliente o per tempo scaduto: si ferma anche l'app di pagamento
            withContext(NonCancellable) { runCatching { abort(a) } }
            throw e
        }
        val pr = response.optJSONObject("SaleToPOIResponse")?.optJSONObject("PaymentResponse") ?: throw IllegalStateException("No payment response from the terminal")
        val res = pr.optJSONObject("Response") ?: JSONObject()
        when (res.optString("Result")) {
            "Success", "Partial" -> return
            else -> {
                val add = parseAdditional(res.optString("AdditionalResponse"))
                val why = add["refusalReason"] ?: add["message"] ?: res.optString("ErrorCondition").ifEmpty { "Declined" }
                throw Declined(if (res.optString("ErrorCondition") in setOf("Aborted", "Cancel")) "canceled: $why" else "declined: $why")
            }
        }
    }

    private suspend fun abort(a: JSONObject) {
        val body = JSONObject()
            .put("MessageHeader", header("Abort", serviceId(), a.getString("sale_id")))
            .put("AbortRequest", JSONObject().put("AbortReason", "MerchantAbort").put("MessageReference", JSONObject()
                .put("MessageCategory", "Payment").put("SaleID", a.getString("sale_id")).put("ServiceID", a.getString("service_id"))))
        send(JSONObject().put("SaleToPOIRequest", body), 15_000)
    }

    private fun header(category: String, serviceId: String, saleId: String) = JSONObject()
        .put("ProtocolVersion", "3.0").put("MessageClass", "Service").put("MessageCategory", category).put("MessageType", "Request")
        .put("ServiceID", serviceId).put("SaleID", saleId).put("POIID", poiid)

    // Messaggio protetto all'app di pagamento; con la chiave cambiata nel frattempo si rilegge da halba e si riprova
    private suspend fun send(message: JSONObject, timeoutMs: Int, retry: Boolean = true): JSONObject {
        val c = crypto ?: throw IllegalStateException("Adyen encryption key missing")
        val (status, text) = post(c.encrypt(message).toString(), timeoutMs)
        if (status == 401 && retry) { loadKey(); return send(message, timeoutMs, retry = false) }
        if (status >= 400) throw IllegalStateException("Adyen payment app: HTTP $status ${text.take(120)}")
        val json = JSONObject(text)
        val body = json.optJSONObject("SaleToPOIResponse") ?: return json
        // Le risposte di errore del protocollo possono arrivare in chiaro (senza NexoBlob)
        return if (body.has("NexoBlob")) c.decrypt(json) else json
    }

    private suspend fun post(body: String, timeoutMs: Int): Pair<Int, String> = withContext(Dispatchers.IO) {
        val conn = URL(ENDPOINT).openConnection() as HttpsURLConnection
        conn.sslSocketFactory = tls().socketFactory
        conn.hostnameVerifier = verifier()
        conn.requestMethod = "POST"
        conn.connectTimeout = 5_000
        conn.readTimeout = timeoutMs
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            status to ((if (status < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    // Solo i certificati firmati dalla radice Adyen del terminale (test o live, come l'ambiente halba)
    private fun tls(): SSLContext {
        val raw = if (environment == "live") R.raw.adyen_terminalfleet_live else R.raw.adyen_terminalfleet_test
        val root = context.resources.openRawResource(raw).use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("adyen", root) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }

    // localhost non è nel certificato: vale il nome comune del terminale (come TerminalLocalAPIHostnameVerifier di
    // Adyen), che deve essere questo terminale; il nome "legacy" dei terminali più vecchi è accettato
    private fun verifier() = HostnameVerifier { _, session ->
        val cert = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull() ?: return@HostnameVerifier false
        val cn = CN.find(cert.subjectX500Principal.name)?.groupValues?.get(1) ?: return@HostnameVerifier false
        val env = if (environment == "live") "live" else "test"
        cn == "legacy-terminal-certificate.$env.terminal.adyen.com" ||
            (Regex("[a-zA-Z0-9]{3,}-[a-zA-Z0-9]{9,15}\\.$env\\.terminal\\.adyen\\.com").matches(cn) && poiid?.let { cn.startsWith("$it.", ignoreCase = true) } != false)
    }

    companion object {
        const val ENDPOINT = "https://localhost:8443/nexo"
        private val POIID = Regex("^[A-Za-z0-9]{3,}-[A-Za-z0-9]{6,}$")
        private val CN = Regex("(?:^|,)CN=([^,]+)")
        private fun serviceId() = UUID.randomUUID().toString().replace("-", "").take(10).uppercase()

        // AdditionalResponse: coppie chiave=valore come in un form, oppure JSON in Base64
        fun parseAdditional(raw: String): Map<String, String> {
            if (raw.isEmpty()) return emptyMap()
            runCatching {
                val j = JSONObject(String(java.util.Base64.getDecoder().decode(raw), Charsets.UTF_8))
                val o = j.optJSONObject("additionalData") ?: j
                return o.keys().asSequence().associateWith { o.optString(it) }
            }
            return raw.split("&").mapNotNull { kv ->
                val i = kv.indexOf('=')
                if (i <= 0) null else java.net.URLDecoder.decode(kv.substring(0, i), "UTF-8") to java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8")
            }.toMap()
        }
    }
}
