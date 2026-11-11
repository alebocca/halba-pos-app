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
}
