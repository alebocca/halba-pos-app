package ai.halba.pos

import android.app.Application
import com.stripe.stripeterminal.TerminalApplicationDelegate
import kotlinx.coroutines.CoroutineScope

// Flavor Stripe: lettore integrato dei terminali Stripe (Apps on Devices, handoff) o simulato in sviluppo
object Psp {
    fun onAppCreate(app: Application) = TerminalApplicationDelegate.onCreate(app)
    fun cardReader(api: HalbaApi, scope: CoroutineScope): CardReader = StripeReader(api, scope)
}
