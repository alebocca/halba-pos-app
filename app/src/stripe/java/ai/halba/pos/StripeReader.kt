package ai.halba.pos

import android.Manifest
import android.content.Context
import android.os.Build
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.external.callable.AppsOnDevicesListener
import com.stripe.stripeterminal.external.callable.Callback
import com.stripe.stripeterminal.external.callable.Cancelable
import com.stripe.stripeterminal.external.callable.ConnectionTokenCallback
import com.stripe.stripeterminal.external.callable.ConnectionTokenProvider
import com.stripe.stripeterminal.external.callable.DiscoveryListener
import com.stripe.stripeterminal.external.callable.MobileReaderListener
import com.stripe.stripeterminal.external.callable.PaymentIntentCallback
import com.stripe.stripeterminal.external.callable.ReaderCallback
import com.stripe.stripeterminal.external.callable.TerminalListener
import com.stripe.stripeterminal.external.models.CollectPaymentIntentConfiguration
import com.stripe.stripeterminal.external.models.ConfirmPaymentIntentConfiguration
import com.stripe.stripeterminal.external.models.ConnectionConfiguration
import com.stripe.stripeterminal.external.models.ConnectionTokenException
import com.stripe.stripeterminal.external.models.DiscoveryConfiguration
import com.stripe.stripeterminal.external.models.LocaleConfig
import com.stripe.stripeterminal.external.models.PaymentIntent
import com.stripe.stripeterminal.external.models.Reader
import com.stripe.stripeterminal.external.models.TerminalException
import com.stripe.stripeterminal.log.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Carta con lo Stripe Terminal SDK.
// - Terminali Stripe (S700, S710, WisePOS E) con Apps on Devices: l'app halba gira sul terminale e passa la carta al
//   lettore integrato in modalità handoff; il lettore torna all'app quando ha finito. L'app va approvata da Stripe
//   (app review) e distribuita dalla dashboard di Stripe.
// - In sviluppo (build debug, emulatore): lettore simulato dell'SDK, nessun hardware.
class StripeReader(private val api: HalbaApi, private val scope: CoroutineScope) : CardReader {
    private var location: String? = null
    private var simulated = false

    override val label = "Stripe"

    // Posizione: l'SDK la chiede per i pagamenti. Bluetooth: solo per il lettore simulato o esterno
    override val permissions: List<String> = buildList {
        add(if (Build.VERSION.SDK_INT >= 31) Manifest.permission.ACCESS_COARSE_LOCATION else Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN) }
    }

    override fun init(context: Context) {
        if (Terminal.isInitialized()) return
        val tokens = object : ConnectionTokenProvider {
            override fun fetchConnectionToken(callback: ConnectionTokenCallback) {
                scope.launch {
                    try {
                        val t = api.connectionToken()
                        location = t.optString("location").ifEmpty { null }
                        callback.onSuccess(t.getString("secret"))
                    } catch (e: Exception) {
                        callback.onFailure(ConnectionTokenException("halba connection token failed", e))
                    }
                }
            }
        }
        Terminal.init(context.applicationContext, LogLevel.WARNING, tokens, object : TerminalListener {}, null, LocaleConfig.CardLanguagePreferenceIfAvailable)
    }

    override val connected: Boolean get() = Terminal.isInitialized() && Terminal.getInstance().connectedReader != null

    override suspend fun connect(): String {
        // Prima il lettore del terminale stesso (Apps on Devices); fuori da un terminale Stripe non ce n'è
        val own = runCatching { withTimeoutOrNull(6_000) { discover(DiscoveryConfiguration.AppsOnDevicesDiscoveryConfiguration()) } }.getOrNull()
        if (own != null) {
            connect(own, ConnectionConfiguration.AppsOnDevicesConnectionConfiguration(object : AppsOnDevicesListener {}))
            simulated = false
            return "${own.deviceType.name.replace('_', ' ')} · handoff"
        }
        // Lettore simulato solo nella build di sviluppo: in produzione un terminale senza lettore non accetta carte
        if (!BuildConfig.DEBUG) throw IllegalStateException("No Stripe card reader on this device")
        val reader = discover(DiscoveryConfiguration.BluetoothDiscoveryConfiguration(timeout = 0, isSimulated = true))
        connect(reader, ConnectionConfiguration.BluetoothConnectionConfiguration(
            locationId = reader.location?.id ?: location.orEmpty(),
            autoReconnectOnUnexpectedDisconnect = true,
            bluetoothReaderListener = object : MobileReaderListener {},
        ))
        simulated = true
        return "Simulated reader"
    }

    private suspend fun discover(config: DiscoveryConfiguration): Reader = suspendCancellableCoroutine { cont ->
        var done = false
        var job: Cancelable? = null
        job = Terminal.getInstance().discoverReaders(
            config,
            object : DiscoveryListener {
                override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                    if (!done && readers.isNotEmpty()) { done = true; cont.resume(readers.first()) }
                }
            },
            object : Callback {
                override fun onSuccess() {}
                override fun onFailure(e: TerminalException) { if (!done) { done = true; cont.resumeWithException(e) } }
            },
        )
        // Tempo scaduto o connessione trovata: la ricerca si ferma (altrimenti l'SDK rifiuta la connessione)
        cont.invokeOnCancellation { job?.cancel(object : Callback { override fun onSuccess() {}; override fun onFailure(e: TerminalException) {} }) }
    }

    private suspend fun connect(reader: Reader, config: ConnectionConfiguration): Reader = suspendCancellableCoroutine { cont ->
        Terminal.getInstance().connectReader(reader, config, object : ReaderCallback {
            override fun onSuccess(reader: Reader) = cont.resume(reader)
            override fun onFailure(e: TerminalException) = cont.resumeWithException(e)
        })
    }

    override fun canPayByCard(payment: JSONObject) = payment.optJSONObject("stripe")?.optString("client_secret").orEmpty().isNotEmpty()

    override val cardHint: String get() = if (simulated) "Simulated reader: the card is presented automatically" else "Tap, insert or swipe the card on the reader"

    // Il pagamento lo ha creato halba (client secret nel comando): l'SDK chiede la carta e conferma
    override suspend fun collect(payment: JSONObject) {
        val clientSecret = payment.getJSONObject("stripe").getString("client_secret")
        val pi = suspendCancellableCoroutine { cont ->
            Terminal.getInstance().retrievePaymentIntent(clientSecret, object : PaymentIntentCallback {
                override fun onSuccess(paymentIntent: PaymentIntent) = cont.resume(paymentIntent)
                override fun onFailure(e: TerminalException) = cont.resumeWithException(e)
            })
        }
        suspendCancellableCoroutine { cont ->
            Terminal.getInstance().processPaymentIntent(
                pi,
                CollectPaymentIntentConfiguration.Builder().build(),
                ConfirmPaymentIntentConfiguration.Builder().build(),
                object : PaymentIntentCallback {
                    override fun onSuccess(paymentIntent: PaymentIntent) = cont.resume(paymentIntent)
                    override fun onFailure(e: TerminalException) = cont.resumeWithException(e)
                },
            )
        }
    }
}
