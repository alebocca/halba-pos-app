package ai.halba.pos

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Currency

// App halba sul terminale: abbinamento → attesa dei comandi di halba → pagamento (carta o QR) → esito.
// La cassa del merchant chiede il pagamento a halba (POST /v1/terminal_payments); halba lo passa a questa app.
class MainActivity : AppCompatActivity() {
    private val api = HalbaApi(BuildConfig.HALBA_API)
    private lateinit var reader: CardReader
    private var readerInfo = ""
    private lateinit var secure: SecureStore
    private lateinit var root: LinearLayout
    private var loop: Job? = null
    private var busy = false //   pagamento in corso: il long-poll non apre altri comandi

    // Nomi dei metodi con QR (stessi id di halba)
    private val labels = mapOf("card" to "Card", "alipay" to "Alipay", "wechatpay" to "WeChat Pay", "bizum" to "Bizum", "blik" to "BLIK", "twint" to "TWINT")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        setContentView(root)
        reader = Psp.cardReader(api, lifecycleScope)
        // Chiave del dispositivo cifrata con l'Android Keystore (SecureStore)
        secure = SecureStore(this)
        api.deviceKey = secure.deviceKey()
        api.terminalId = if (api.deviceKey != null) secure.terminalId else null
        // Permessi chiesti dall'SDK del PSP (Stripe: posizione e Bluetooth)
        val needed = reader.permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1) else start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        start()
    }

    private fun start() {
        if (api.deviceKey == null) pair() else connectAndWait()
    }

    // --- Abbinamento: il merchant inserisce il codice in halba (dashboard o POST /v1/terminals) ---
    private fun pair(): Job = lifecycleScope.launch {
        show("Pair this terminal", "Starting…")
        try {
            val p = api.startPairing()
            show("Pair this terminal", "Enter this code in halba", big = p.getString("code"))
            while (isActive) {
                delay(3000)
                val s = api.pairingStatus(p.getString("id"), p.getString("secret"))
                when (s.optString("status")) {
                    "paired" -> {
                        api.terminalId = s.getString("terminal")
                        api.deviceKey = s.getString("device_key")
                        secure.save(api.terminalId!!, api.deviceKey!!)
                        connectAndWait()
                        return@launch
                    }
                    "expired" -> { pair(); return@launch }
                }
            }
        } catch (e: Exception) {
            show("Pairing failed", e.message ?: "Error", action = "Retry") { pair() }
        }
    }

    // --- Lettore e attesa dei comandi ---
    private fun connectAndWait(): Job = lifecycleScope.launch {
        show("halba POS", "Connecting the card reader…", sub = reader.label)
        try {
            reader.init(this@MainActivity)
            if (!reader.connected) readerInfo = reader.connect()
        } catch (e: Exception) {
            show("Card reader unavailable", e.message ?: "Error", action = "Retry") { connectAndWait() }
            return@launch
        }
        waitForCommands()
    }

    private fun waitForCommands() {
        loop?.cancel()
        show("halba POS", "Ready. Waiting for the till…", sub = listOf(readerInfo, "Terminal ${api.terminalId}").filter { it.isNotEmpty() }.joinToString("\n"))
        loop = lifecycleScope.launch {
            while (isActive) {
                try {
                    val cmds = api.commands().optJSONArray("commands")
                    val first = cmds?.optJSONObject(0)
                    if (first != null && first.optString("type") == "payment.start" && !busy) {
                        busy = true
                        showPayment(first.getJSONObject("payment"))
                        return@launch
                    }
                } catch (e: HalbaApi.ApiError) {
                    if (e.status == 401) { forget(); return@launch }
                    delay(3000)
                } catch (e: Exception) {
                    delay(3000) // rete assente: si riprova
                }
            }
        }
    }

    private fun forget() {
        secure.clear()
        api.terminalId = null
        api.deviceKey = null
        pair()
    }

    // --- Pagamento ---
    private fun amountOf(p: JSONObject): String {
        val a = p.getJSONObject("amount")
        val cur = Currency.getInstance(a.getString("currency"))
        val f = NumberFormat.getCurrencyInstance().apply { currency = cur }
        return f.format(a.getLong("value") / Math.pow(10.0, cur.defaultFractionDigits.toDouble()))
    }

    private fun showPayment(p: JSONObject) {
        root.removeAllViews()
        root.addView(text(p.optString("merchant_reference"), 16f, Color.parseColor("#5B5B66")))
        root.addView(text(amountOf(p), 44f, Color.BLACK, bold = true))
        val methods = p.optJSONArray("methods")
        for (i in 0 until (methods?.length() ?: 0)) {
            val m = methods!!.getString(i)
            if (m == "card" && !reader.canPayByCard(p)) continue
            root.addView(button(labels[m] ?: m) { if (m == "card") payByCard(p) else payByQr(p, m) })
        }
    }

    private fun payByCard(p: JSONObject): Job = lifecycleScope.launch {
        show(amountOf(p), "Tap, insert or swipe the card", sub = reader.cardHint)
        val id = p.getString("id")
        try {
            reader.collect(p)
            val r = api.result(id, "confirmed")
            finish(r)
        } catch (e: Exception) {
            runCatching { api.result(id, "failed", "card_error", e.message) }
            finish(JSONObject().put("status", "failed").put("failure", JSONObject().put("message", e.message ?: "Card error")))
        }
    }

    private fun payByQr(p: JSONObject, method: String): Job = lifecycleScope.launch {
        val id = p.getString("id")
        try {
            val r = api.qr(id, method)
            root.removeAllViews()
            root.addView(text(amountOf(p), 36f, Color.BLACK, bold = true))
            root.addView(text("Scan with ${labels[method] ?: method}", 18f, Color.parseColor("#5B5B66")))
            root.addView(ImageView(this@MainActivity).apply { setImageBitmap(qrBitmap(r.getString("qr"), 720)); adjustViewBounds = true })
            while (isActive) {
                delay(2000)
                val s = api.payment(id)
                if (s.optString("status") in listOf("succeeded", "failed", "canceled")) { finish(s); return@launch }
            }
        } catch (e: Exception) {
            finish(JSONObject().put("status", "failed").put("failure", JSONObject().put("message", e.message ?: "QR error")))
        }
    }

    private fun finish(r: JSONObject) {
        val ok = r.optString("status") == "succeeded"
        val card = r.optJSONObject("card")
        val detail = when {
            ok && card?.optString("last4").orEmpty().isNotEmpty() -> "${card!!.optString("brand")} •••• ${card.optString("last4")} · ${card.optString("entry_mode")}"
            ok -> "Paid"
            else -> r.optJSONObject("failure")?.optString("message") ?: r.optString("status")
        }
        show(if (ok) "Payment approved" else "Payment not completed", detail, color = if (ok) Color.parseColor("#0F766E") else Color.parseColor("#B42318"))
        lifecycleScope.launch { delay(4000); busy = false; waitForCommands() }
    }

    // --- Interfaccia ---
    private fun show(title: String, body: String, big: String? = null, sub: String? = null, color: Int = Color.BLACK, action: String? = null, onAction: (() -> Unit)? = null) {
        root.removeAllViews()
        root.addView(text(title, 24f, color, bold = true))
        root.addView(text(body, 18f, Color.parseColor("#5B5B66")))
        if (big != null) root.addView(text(big, 48f, Color.parseColor("#3E37D6"), bold = true, mono = true))
        if (sub != null) root.addView(text(sub, 12f, Color.parseColor("#8A8A94")))
        if (action != null && onAction != null) root.addView(button(action, onAction))
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(0, 16, 0, 16)
        typeface = when { mono -> Typeface.MONOSPACE; bold -> Typeface.DEFAULT_BOLD; else -> Typeface.DEFAULT }
        if (mono) letterSpacing = 0.15f
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 18f
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 }
        visibility = View.VISIBLE
    }

    private fun qrBitmap(content: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        return bmp
    }
}
