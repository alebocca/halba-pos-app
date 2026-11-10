package ai.halba.pos

import android.content.Context
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.external.callable.Callback
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Carta con lo Stripe Terminal SDK. In sviluppo: lettore simulato dell'SDK (nessun hardware). Sui lettori Stripe
// con Apps on Devices si usa lo stesso SDK in modalità handoff, cambia solo la discovery.
class StripeReader(private val api: HalbaApi, private val scope: CoroutineScope) {
    private var location: String? = null

    fun init(context: Context) {
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
        val listener = object : TerminalListener {}
        Terminal.init(context.applicationContext, LogLevel.WARNING, tokens, listener, null, LocaleConfig.CardLanguagePreferenceIfAvailable)
    }

    val connected: Boolean get() = Terminal.isInitialized() && Terminal.getInstance().connectedReader != null

    suspend fun connectSimulated(): Reader {
        val reader = suspendCancellableCoroutine { cont ->
            var done = false
            Terminal.getInstance().discoverReaders(
                DiscoveryConfiguration.BluetoothDiscoveryConfiguration(timeout = 0, isSimulated = true),
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
        }
        return suspendCancellableCoroutine { cont ->
            Terminal.getInstance().connectReader(
                reader,
                ConnectionConfiguration.BluetoothConnectionConfiguration(
                    locationId = reader.location?.id ?: location.orEmpty(),
                    autoReconnectOnUnexpectedDisconnect = true,
                    bluetoothReaderListener = object : MobileReaderListener {},
                ),
                object : ReaderCallback {
                    override fun onSuccess(reader: Reader) = cont.resume(reader)
                    override fun onFailure(e: TerminalException) = cont.resumeWithException(e)
                },
            )
        }
    }

    // Il pagamento lo ha creato halba (client secret nel comando): l'SDK chiede la carta e conferma
    suspend fun collect(clientSecret: String): PaymentIntent {
        val pi = suspendCancellableCoroutine { cont ->
            Terminal.getInstance().retrievePaymentIntent(clientSecret, object : PaymentIntentCallback {
                override fun onSuccess(paymentIntent: PaymentIntent) = cont.resume(paymentIntent)
                override fun onFailure(e: TerminalException) = cont.resumeWithException(e)
            })
        }
        return suspendCancellableCoroutine { cont ->
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
