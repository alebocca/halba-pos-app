package ai.halba.pos

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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

    // Passo corrente, per la diagnosi a distanza (halba lo riceve con ogni chiamata, X-Reader-Status)
    @Volatile private var step: String = "not connected"
    @Volatile private var probe: String = ""
    override val diagnostics: String get() = "adyen ${BuildConfig.VERSION_NAME}: ${if (probe.isEmpty()) "" else "[$probe] "}$step"
    private fun step(s: String) { step = s.take(240) }

    override val label = "Adyen"
    override val permissions = emptyList<String>()
    override val connected: Boolean get() = crypto != null && poiid != null

    override fun init(context: Context) { this.context = context.applicationContext }

    // POIID dal terminale (Adyen: Settings.Global DEVICE_NAME, per esempio S1F2-000158262320202) e chiave da halba
    override suspend fun connect(): String {
        val name = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        poiid = name?.takeIf { POIID.matches(it) } ?: run { step("no POIID (DEVICE_NAME=$name)"); throw IllegalStateException("This is not an Adyen payment terminal") }
        try { loadKey() } catch (e: Exception) { step("key: ${e.message}"); throw e }
        withContext(Dispatchers.IO) {
            val diag = JSONObject().put("SaleToPOIRequest", JSONObject()
                .put("MessageHeader", header("Diagnosis", serviceId(), "halba-diagnosis"))
                .put("DiagnosisRequest", JSONObject().put("HostDiagnosisFlag", false)))
            // Si scrive man mano: anche se l'avvio va in timeout resta quello che si è saputo
            probe = probeLocal()
            probe += " | get " + rawHttp("GET", "/nexo", null)
            probe += " | plain " + rawHttp("POST", "/nexo", diag.toString())
            probe += " | enc " + rawHttp("POST", "/nexo", crypto!!.encrypt(diag).toString())
        }
        // Controllo rapido dell'app di pagamento: DiagnosisRequest sulla Terminal API locale
        step("diagnosis: sending")
        val ok = try {
            val r = kotlinx.coroutines.withTimeout(12_000) {
                send(JSONObject().put("SaleToPOIRequest", JSONObject()
                    .put("MessageHeader", header("Diagnosis", serviceId(), "halba-diagnosis"))
                    .put("DiagnosisRequest", JSONObject().put("HostDiagnosisFlag", false))), 10_000)
            }
            val res = r.optJSONObject("SaleToPOIResponse")?.optJSONObject("DiagnosisResponse")?.optJSONObject("Response")
            step(if (res == null) "diagnosis: unexpected ${r.toString().take(80)}" else "diagnosis: ${res.optString("Result")} ${res.optString("ErrorCondition")}".trim())
            res?.optString("Result") == "Success"
        } catch (e: Exception) {
            step("diagnosis error: ${describe(e)}")
            false
        }
        return "Adyen $poiid · payment app ${if (ok) "ready" else "not reachable"}"
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
        step("payment: sending")
        val response = try {
            send(request, 170_000).also { step("payment: response received") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Annullato dalla cassa, dal cliente o per tempo scaduto: si ferma anche l'app di pagamento
            val at = step
            withContext(NonCancellable) {
                runCatching { abort(a) }.onSuccess { step("$at · canceled, abort sent") }.onFailure { step("$at · canceled, abort error: ${describe(it)}") }
            }
            throw e
        } catch (e: Exception) {
            step("payment error: ${describe(e)}")
            throw e
        }
        val pr = response.optJSONObject("SaleToPOIResponse")?.optJSONObject("PaymentResponse") ?: throw IllegalStateException("No payment response from the terminal")
        val res = pr.optJSONObject("Response") ?: JSONObject()
        step("payment result: ${res.optString("Result")} ${res.optString("ErrorCondition")}".trim())
        when (res.optString("Result")) {
            "Success", "Partial" -> return
            else -> {
                val add = parseAdditional(res.optString("AdditionalResponse"))
                val why = add["refusalReason"] ?: add["message"] ?: res.optString("ErrorCondition").ifEmpty { "Declined" }
                throw Declined(if (res.optString("ErrorCondition") in setOf("Aborted", "Cancel")) "canceled: $why" else "declined: $why")
            }
        }
    }

    // Scontrino stampato dall'app di pagamento di Adyen con la Terminal API locale (PrintRequest, testo): serve per i
    // metodi con QR, che Adyen non conosce. Le carte le stampa Adyen da sola (impostazioni receiptPrinting)
    override suspend fun print(lines: List<ReceiptLine>, xhtml: String?): Boolean {
        if (crypto == null || poiid == null) runCatching { connect() }
        if (crypto == null || poiid == null) return false
        // Prima la versione XHTML con il logo (tutto il documento in Base64, come chiede Adyen); se non stampa, il testo
        if (xhtml != null) {
            val content = JSONObject().put("OutputFormat", "XHTML").put("OutputXHTML", java.util.Base64.getEncoder().encodeToString(xhtml.toByteArray(Charsets.UTF_8)))
            if (printRequest(content, "print xhtml")) return true
        }
        val text = org.json.JSONArray()
        for (l in lines) {
            val o = JSONObject().put("Text", l.text).put("CharacterStyle", if (l.bold) "Bold" else "Normal").put("Alignment", if (l.centred) "Centred" else "Left").put("EndOfLineFlag", true)
            if (l.doubleWidth) o.put("CharacterWidth", "DoubleWidth")
            if (l.doubleHeight) o.put("CharacterHeight", "DoubleHeight")
            text.put(o)
        }
        return printRequest(JSONObject().put("OutputFormat", "Text").put("OutputText", text), "print text")
    }

    private suspend fun printRequest(content: JSONObject, what: String): Boolean {
        val header = JSONObject()
            .put("ProtocolVersion", "3.0").put("MessageClass", "Device").put("MessageCategory", "Print").put("MessageType", "Request")
            .put("ServiceID", serviceId()).put("SaleID", "halba-receipt").put("POIID", poiid)
        val req = JSONObject().put("SaleToPOIRequest", JSONObject().put("MessageHeader", header).put("PrintRequest", JSONObject()
            .put("PrintOutput", JSONObject().put("DocumentQualifier", "CustomerReceipt").put("ResponseMode", "PrintEnd").put("OutputContent", content))))
        step("$what: sending")
        return try {
            val r = send(req, 30_000).optJSONObject("SaleToPOIResponse")?.optJSONObject("PrintResponse")?.optJSONObject("Response")
            step("$what: ${r?.optString("Result") ?: "no response"} ${r?.optString("ErrorCondition").orEmpty()} ${r?.optString("AdditionalResponse").orEmpty().take(60)}".trim())
            r?.optString("Result") == "Success"
        } catch (e: Exception) {
            step("$what error: ${describe(e)}")
            false
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

    // Chiamata bloccante su un thread suo: se la coroutine viene annullata (cassa, cliente, tempo scaduto) si chiude la
    // connessione, così l'attesa finisce subito e parte l'AbortRequest
    private suspend fun post(body: String, timeoutMs: Int): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        val bytes = body.toByteArray(Charsets.UTF_8)
        val conn = URL(ENDPOINT).openConnection() as HttpsURLConnection
        cont.invokeOnCancellation { runCatching { conn.disconnect() } }
        val what = step.substringBefore(":")
        thread(name = "adyen-local", isDaemon = true) {
            try {
                conn.sslSocketFactory = tls().socketFactory
                conn.hostnameVerifier = verifier()
                conn.requestMethod = "POST"
                conn.connectTimeout = 5_000
                conn.readTimeout = timeoutMs
                conn.doOutput = true
                // Lunghezza fissa (Content-Length), niente chunked: come le librerie di Adyen
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                step("$what: connecting to $ENDPOINT")
                conn.connect()
                step("$what: connected (TLS ok), sending ${bytes.size} bytes")
                conn.outputStream.use { it.write(bytes) }
                step("$what: sent, waiting for the payment app")
                val status = conn.responseCode
                step("$what: HTTP $status")
                val text = (if (status < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                if (cont.isActive) cont.resume(status to text)
            } catch (e: Throwable) {
                if (cont.isActive) { step("$step -> ${describe(e)}"); cont.resumeWithException(e) }
            } finally {
                runCatching { conn.disconnect() }
            }
        }
    }

    // TCP e poi TLS verso l'app di pagamento, separati e con tempi brevi: dice se la porta risponde, se l'handshake si
    // chiude e chi presenta il certificato
    private fun probeLocal(): String {
        val out = mutableListOf<String>()
        val socket = java.net.Socket()
        try {
            socket.connect(java.net.InetSocketAddress(HOST, 8443), 3_000)
            out += "tcp ok"
            socket.soTimeout = 6_000
            val ssl = tls().socketFactory.createSocket(socket, HOST, 8443, true) as javax.net.ssl.SSLSocket
            ssl.startHandshake()
            val cert = ssl.session.peerCertificates.firstOrNull() as? X509Certificate
            out += "tls ${ssl.session.protocol} ${if (verifier().verify(HOST, ssl.session)) "cert ok" else "cert rejected"}"
            ssl.close()
        } catch (e: Throwable) {
            out += describe(e)
        } finally {
            runCatching { socket.close() }
        }
        return out.joinToString(", ")
    }

    // Richiesta HTTP/1.1 scritta a mano sul socket TLS: prima riga della risposta e inizio del corpo, o l'errore
    private fun rawHttp(method: String, path: String, body: String?): String {
        val started = System.currentTimeMillis()
        return try {
            val ssl = tls().socketFactory.createSocket() as javax.net.ssl.SSLSocket
            ssl.use {
                it.connect(java.net.InetSocketAddress(HOST, 8443), 3_000)
                it.soTimeout = 5_000
                it.startHandshake()
                val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                val head = buildString {
                    append("$method $path HTTP/1.1\r\nHost: $HOST:8443\r\nConnection: close\r\nAccept: application/json\r\n")
                    if (body != null) append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")
                    append("\r\n")
                }
                it.outputStream.write(head.toByteArray(Charsets.US_ASCII) + bytes)
                it.outputStream.flush()
                val text = it.inputStream.bufferedReader().readText()
                val status = text.lineSequence().firstOrNull().orEmpty().removePrefix("HTTP/1.1 ")
                val payload = text.substringAfter("\r\n\r\n", "").replace(Regex("\\s+"), " ").take(40)
                "$status ${payload}".trim() + " ${System.currentTimeMillis() - started}ms"
            }
        } catch (e: Throwable) {
            "${e.javaClass.simpleName} ${System.currentTimeMillis() - started}ms"
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
        // 127.0.0.1 e non localhost: "localhost" può risolversi prima in ::1, dove l'app di pagamento può non ascoltare
        const val HOST = "127.0.0.1"
        const val ENDPOINT = "https://$HOST:8443/nexo"
        private val POIID = Regex("^[A-Za-z0-9]{3,}-[A-Za-z0-9]{6,}$")
        private val CN = Regex("(?:^|,)CN=([^,]+)")
        private fun describe(e: Throwable): String = generateSequence(e) { it.cause }.take(3).joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message.orEmpty().take(80)}" }
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
