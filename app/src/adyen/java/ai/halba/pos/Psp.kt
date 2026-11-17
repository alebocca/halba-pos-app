package ai.halba.pos

import android.app.Application
import kotlinx.coroutines.CoroutineScope

// Flavor Adyen: l'app halba sul terminale Android di Adyen passa la carta all'app di pagamento di Adyen con la
// Terminal API locale (AdyenReader). Si distribuisce dalla Customer Area (Devices > Terminal apps).
object Psp {
    fun onAppCreate(app: Application) {}
    fun cardReader(api: HalbaApi, scope: CoroutineScope): CardReader = AdyenReader(api)
}
