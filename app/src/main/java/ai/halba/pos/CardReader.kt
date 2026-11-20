package ai.halba.pos

import android.content.Context
import org.json.JSONObject

// Lettore di carte del PSP sul terminale. Un'implementazione per flavor (stripe, adyen, mypos): ognuna usa l'SDK del
// suo PSP e va distribuita dal suo store (Stripe Apps on Devices, Adyen Customer Area, myPOS AppMarket).
// I metodi con QR (Alipay, BLIK, TWINT…) non dipendono dal PSP: li gestisce halba, uguali per tutti i flavor.
interface CardReader {
    val label: String //                      nome del PSP mostrato nell'app
    val permissions: List<String> //          permessi Android da chiedere all'avvio
    val connected: Boolean
    fun init(context: Context)
    suspend fun connect(): String //          cosa è collegato, per lo schermo ("Stripe S700 · handoff")
    fun canPayByCard(payment: JSONObject): Boolean
    val cardHint: String //                   riga sotto l'importo mentre si aspetta la carta
    suspend fun collect(payment: JSONObject) //  carta letta e confermata presso il PSP; eccezione se non va
    // Ultimo passo del lettore (collegamento, invio, risposta, errore), mandato a halba per la diagnosi a distanza
    val diagnostics: String? get() = null
    // Stampa sulla stampante del terminale (scontrino dei metodi con QR, che il PSP non conosce): righe di testo,
    // in grassetto e centrate se richiesto. false se il terminale non stampa
    suspend fun print(lines: List<ReceiptLine>): Boolean = false
}

data class ReceiptLine(val text: String, val bold: Boolean = false, val centred: Boolean = false)
