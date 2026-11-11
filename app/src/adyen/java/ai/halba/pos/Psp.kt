package ai.halba.pos

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

// Flavor Adyen: per ora solo i metodi con QR. La carta arriva con l'SDK di Adyen e il suo adattatore in halba,
// quando ci sono un terminale di prova e l'account sviluppatore.
object Psp {
    fun onAppCreate(app: Application) {}
    fun cardReader(api: HalbaApi, scope: CoroutineScope): CardReader = object : CardReader {
        override val label = "Adyen"
        override val permissions = emptyList<String>()
        override val connected = true
        override fun init(context: Context) {}
        override suspend fun connect() = "Adyen · QR codes only, cards coming soon"
        override fun canPayByCard(payment: JSONObject) = false
        override val cardHint = ""
        override suspend fun collect(payment: JSONObject) = throw UnsupportedOperationException("Adyen cards are not available yet")
    }
}
