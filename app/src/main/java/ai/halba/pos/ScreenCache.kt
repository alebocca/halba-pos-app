package ai.halba.pos

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException

// Schermo a riposo: immagine o video del merchant salvato sul terminale. Si scarica una volta (halba poi toglie il file
// dal suo Storage), si controlla lo SHA-256 e si conferma a halba; resta dopo il riavvio notturno.
class ScreenCache(private val context: Context, private val api: HalbaApi) {
    private val prefs = context.getSharedPreferences("halba_screen", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "screen").apply { mkdirs() }

    val kind: String? get() = prefs.getString("kind", null)
    val file: File? get() = prefs.getString("file", null)?.let { File(dir, it) }?.takeIf { it.exists() }

    // Allinea il file a quello della configurazione; true se lo schermo è cambiato
    suspend fun sync(screen: JSONObject?): Boolean {
        if (screen == null) {
            val had = file != null
            clear()
            return had
        }
        val id = screen.getString("id")
        if (id == prefs.getString("id", null) && file != null) return false
        // Senza link il file non è più nello Storage: si tiene quello che c'è (o lo schermo halba)
        val url = screen.optString("url").takeIf { it.isNotEmpty() && it != "null" } ?: return false
        val ext = when (screen.optString("mime")) { "video/mp4" -> "mp4"; "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
        val tmp = File(dir, "$id.part")
        val sha = api.download(url, tmp)
        if (sha != screen.getString("sha256")) {
            tmp.delete()
            throw IOException("The screen file arrived damaged")
        }
        val dest = File(dir, "$id.$ext")
        if (!tmp.renameTo(dest)) throw IOException("The screen file could not be saved")
        file?.takeIf { it != dest }?.delete()
        prefs.edit().putString("id", id).putString("kind", screen.getString("kind")).putString("file", dest.name).apply()
        api.ackMedia(id)
        return true
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        prefs.edit().clear().apply()
    }
}
