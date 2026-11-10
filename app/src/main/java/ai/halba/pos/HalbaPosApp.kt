package ai.halba.pos

import android.app.Application
import com.stripe.stripeterminal.TerminalApplicationDelegate

// L'SDK di Stripe Terminal segue il ciclo di vita dell'app
class HalbaPosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TerminalApplicationDelegate.onCreate(this)
    }
}
