package ai.halba.pos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Client dell'API dei dispositivi di halba (halba-orchestrator src/terminal/devices.ts).
// Tutte le chiamate partono dal terminale: niente porte aperte in negozio.
class HalbaApi(private val base: String) {
    var terminalId: String? = null
    var deviceKey: String? = null
    // Il terminale dice a halba se è in carica (la dashboard avvisa il merchant quando non lo è)
    var charging: () -> Boolean? = { null }

    class ApiError(val status: Int, message: String) : Exception(message)

    private suspend fun call(method: String, path: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15_000): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = URL(base + path).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-App-Version", BuildConfig.VERSION_NAME)
            conn.setRequestProperty("X-Device-Model", android.os.Build.MODEL)
            val id = terminalId
            val key = deviceKey
            if (id != null && key != null) conn.setRequestProperty("Authorization", "Device $id.$key")
            charging()?.let { conn.setRequestProperty("X-Charging", it.toString()) }
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = conn.responseCode
            val text = (if (status < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            conn.disconnect()
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (status >= 400) throw ApiError(status, json.optJSONObject("error")?.optString("message") ?: "HTTP $status")
            json
        }

    // Abbinamento: codice da dare al merchant, poi ritiro della chiave
    suspend fun startPairing(): JSONObject = call("POST", "/v1/devices/pairings", JSONObject().put("provider", BuildConfig.PSP).put("model", android.os.Build.MODEL).put("app_version", BuildConfig.VERSION_NAME))
    suspend fun pairingStatus(id: String, secret: String): JSONObject = call("GET", "/v1/devices/pairings/$id", headers = mapOf("X-Pairing-Secret" to secret))

    // Long-poll: risponde appena c'è un pagamento da incassare, al più dopo 25 secondi
    suspend fun commands(): JSONObject = call("GET", "/v1/devices/commands?wait=25", timeoutMs = 40_000)
    suspend fun connectionToken(): JSONObject = call("POST", "/v1/devices/stripe/connection_token", JSONObject())
    suspend fun payment(id: String): JSONObject = call("GET", "/v1/devices/payments/$id")
    suspend fun result(id: String, status: String, code: String? = null, message: String? = null): JSONObject {
        val body = JSONObject().put("status", status)
        if (code != null || message != null) body.put("failure", JSONObject().put("code", code ?: status).put("message", message ?: ""))
        return call("POST", "/v1/devices/payments/$id/result", body)
    }
    suspend fun qr(id: String, method: String): JSONObject = call("POST", "/v1/devices/payments/$id/qr", JSONObject().put("method", method))

    // Configurazione: negozio, nome, PIN impostato, schermo a riposo (link temporaneo al file)
    suspend fun config(): JSONObject = call("GET", "/v1/devices/config")
    suspend fun stores(): JSONObject = call("GET", "/v1/devices/stores")
    suspend fun configure(store: String?, label: String?, pin: String?): JSONObject {
        val body = JSONObject()
        if (store != null) body.put("store", store)
        if (label != null) body.put("label", label)
        if (pin != null) body.put("pin", pin)
        return call("POST", "/v1/devices/terminal", body)
    }
    suspend fun verifyPin(pin: String): JSONObject = call("POST", "/v1/devices/staff/verify", JSONObject().put("pin", pin))
    suspend fun ackMedia(id: String): JSONObject = call("POST", "/v1/devices/media/$id/ack", JSONObject())

    // Scarica il file dello schermo a riposo (link firmato dello Storage, senza chiave del dispositivo) e ne dà lo SHA-256
    suspend fun download(url: String, dest: java.io.File): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 60_000
        if (conn.responseCode >= 400) throw ApiError(conn.responseCode, "Screen download failed")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        conn.inputStream.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        }
        conn.disconnect()
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
