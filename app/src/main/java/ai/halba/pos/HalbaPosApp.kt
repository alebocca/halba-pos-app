package ai.halba.pos

import android.app.Application

// Ogni flavor inizializza l'SDK del suo PSP (Stripe Terminal segue il ciclo di vita dell'app)
class HalbaPosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Psp.onAppCreate(this)
    }
}
