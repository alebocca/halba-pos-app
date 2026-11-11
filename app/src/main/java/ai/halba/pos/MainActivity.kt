package ai.halba.pos

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
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

// App halba sul terminale (docs/progetto-card-present.md). Schermate provvisorie: il design arriva dal design system
// (docs/brief-design-system.md). Flusso:
//   primo avvio: abbinamento → negozio e nome → prova del lettore → schermo a riposo scaricato
//   poi: a riposo (immagine o video del merchant in loop) → pagamento chiesto dalla cassa → esito → di nuovo a riposo
// Il terminale sta in carica e con lo schermo sempre acceso; resta nell'app grazie al kiosk del PSP. Il menu del
// personale si apre tenendo premuto per 3 secondi l'indicatore di stato, con il PIN del negozio.
class MainActivity : AppCompatActivity() {
    private val api = HalbaApi(BuildConfig.HALBA_API)
    private lateinit var reader: CardReader
    private lateinit var secure: SecureStore
    private lateinit var screen: ScreenCache
    private lateinit var container: FrameLayout
    private lateinit var root: LinearLayout
    private var readerInfo = ""
    private var loop: Job? = null
    private var busy = false //        pagamento in corso: il long-poll non apre altri comandi
    private var idle = false //        schermo a riposo visibile
    private var online = false
    private var config: JSONObject? = null
    private var configVersion: String? = null
    private var statusView: TextView? = null
    private var chargingBanner: TextView? = null

    private val labels = mapOf("card" to "Card", "alipay" to "Alipay", "wechatpay" to "WeChat Pay", "bizum" to "Bizum", "blik" to "BLIK", "twint" to "TWINT")

    // Corrente collegata o staccata: l'avviso compare o sparisce subito
    private val power = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Schermo sempre acceso finché l'app è in primo piano (nessun permesso: è un'opzione della finestra)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        container = FrameLayout(this).apply { setBackgroundColor(Halba.SURFACE) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        setContentView(container)
        page()
        reader = Psp.cardReader(api, lifecycleScope)
        secure = SecureStore(this)
        screen = ScreenCache(this, api)
        api.charging = { isCharging() }
        api.deviceKey = secure.deviceKey()
        api.terminalId = if (api.deviceKey != null) secure.terminalId else null
        registerReceiver(power, IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) })
        val needed = reader.permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1) else start()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(power) }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        start()
    }

    private fun isCharging(): Boolean {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private fun start() {
        if (api.deviceKey == null) pair() else setup()
    }

    // --- 1. Abbinamento: il merchant inserisce il codice in halba (Connections > PSP > Add terminal) ---
    private fun pair(): Job = lifecycleScope.launch {
        show("Pair this terminal", "Starting…")
        try {
            val p = api.startPairing()
            show("Pair this terminal", "In halba open Connections, choose your PSP, Add terminal, and enter this code", big = p.getString("code"))
            while (isActive) {
                delay(3000)
                val s = api.pairingStatus(p.getString("id"), p.getString("secret"))
                when (s.optString("status")) {
                    "paired" -> {
                        api.terminalId = s.getString("terminal")
                        api.deviceKey = s.getString("device_key")
                        secure.save(api.terminalId!!, api.deviceKey!!)
                        setup()
                        return@launch
                    }
                    "expired" -> { pair(); return@launch }
                }
            }
        } catch (e: Exception) {
            show("Pairing failed", e.message ?: "Error", action = "Retry") { pair() }
        }
    }

    // --- 2. Configurazione: senza negozio si sceglie qui; poi lettore e schermo ---
    private fun setup(): Job = lifecycleScope.launch {
        show("halba POS", "Loading the configuration…")
        try {
            val cfg = api.config()
            apply(cfg)
            online = true //  halba ha risposto: il terminale è raggiungibile (il long-poll lo conferma poi a ogni giro)
            if (cfg.isNull("store")) chooseStore(cfg, null) else prepare()
        } catch (e: HalbaApi.ApiError) {
            if (e.status == 401) forget() else show("Configuration unavailable", e.message ?: "Error", action = "Retry") { setup() }
        } catch (e: Exception) {
            show("No connection", "Check the network of the terminal", action = "Retry") { setup() }
        }
    }

    // Negozio e nome del terminale, scelti dal terminale (configurazione guidata o menu del personale con il PIN)
    private fun chooseStore(cfg: JSONObject, pin: String?): Job = lifecycleScope.launch {
        show("Which store is this terminal in?", "Loading stores…")
        try {
            val stores = api.stores().optJSONArray("data")
            page()
            root.addView(text("Which store is this terminal in?", 24f, Halba.INK, bold = true))
            val name = EditText(this@MainActivity).apply {
                hint = "Terminal name"
                // All'abbinamento il nome è il modello del dispositivo: si propone un nome da cassa
                setText(cfg.optJSONObject("terminal")?.optString("label")?.takeIf { it.isNotEmpty() && it != "null" && it != android.os.Build.MODEL } ?: "Till 1")
                textSize = 18f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            root.addView(text("Name of this terminal", 14f, Halba.INK_MUTED))
            root.addView(name)
            if (stores == null || stores.length() == 0) {
                root.addView(text("No stores yet: add one in halba (Connections > your PSP > Add store)", 16f, Halba.DANGER))
                root.addView(button("Reload") { chooseStore(cfg, pin) })
                return@launch
            }
            for (i in 0 until stores.length()) {
                val s = stores.getJSONObject(i)
                val label = listOf(s.optString("name"), s.optString("external_ref").takeIf { it.isNotEmpty() && it != "null" }).filterNotNull().joinToString(" · ")
                root.addView(button(label) {
                    lifecycleScope.launch {
                        try {
                            apply(api.configure(s.getString("id"), name.text.toString().trim().ifEmpty { null }, pin))
                            prepare()
                        } catch (e: Exception) {
                            show("Not saved", e.message ?: "Error", action = "Back") { chooseStore(cfg, pin) }
                        }
                    }
                })
            }
        } catch (e: Exception) {
            show("Stores unavailable", e.message ?: "Error", action = "Retry") { chooseStore(cfg, pin) }
        }
    }

    // Lettore di carte, poi schermo a riposo, poi attesa della cassa
    private fun prepare(): Job = lifecycleScope.launch {
        show("halba POS", "Connecting the card reader…", sub = reader.label)
        try {
            reader.init(this@MainActivity)
            if (!reader.connected) readerInfo = reader.connect()
        } catch (e: Exception) {
            show("Card reader unavailable", e.message ?: "Error", action = "Retry") { prepare() }
            return@launch
        }
        syncScreen(showProgress = true)
        waitForCommands()
    }

    private fun apply(cfg: JSONObject) {
        config = cfg
        configVersion = cfg.optString("config_version").ifEmpty { null }
    }

    private suspend fun syncScreen(showProgress: Boolean) {
        if (showProgress) show("halba POS", "Downloading the screen…")
        runCatching { if (screen.sync(config?.optJSONObject("screen")) && idle) showIdle() }
    }

    // --- 3. Attesa della cassa: long-poll; config_version diversa = si rilegge la configurazione ---
    private fun waitForCommands() {
        loop?.cancel()
        showIdle()
        loop = lifecycleScope.launch {
            while (isActive) {
                try {
                    val r = api.commands()
                    if (!online) { online = true; updateStatus() }
                    val v = r.optString("config_version").ifEmpty { null }
                    if (v != null && v != configVersion) {
                        apply(api.config())
                        syncScreen(showProgress = false)
                        if (idle) showIdle()
                    }
                    val first = r.optJSONArray("commands")?.optJSONObject(0)
                    if (first != null && first.optString("type") == "payment.start" && !busy) {
                        busy = true
                        idle = false
                        start(first.getJSONObject("payment"))
                        return@launch
                    }
                } catch (e: HalbaApi.ApiError) {
                    if (e.status == 401) { forget(); return@launch }
                    if (online) { online = false; updateStatus() }
                    delay(3000)
                } catch (e: Exception) {
                    if (online) { online = false; updateStatus() }
                    delay(3000) // rete assente: si riprova
                }
            }
        }
    }

    private fun forget() {
        loop?.cancel()
        secure.clear()
        screen.clear()
        api.terminalId = null
        api.deviceKey = null
        config = null
        pair()
    }

    // --- Schermo a riposo: immagine o video del merchant in loop, o lo schermo halba ---
    @SuppressLint("ClickableViewAccessibility")
    private fun showIdle() {
        idle = true
        container.removeAllViews()
        container.setBackgroundColor(Color.BLACK)
        val file = screen.file
        val full = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        if (file != null && screen.kind == "video") {
            container.addView(VideoView(this).apply {
                setVideoURI(Uri.fromFile(file))
                setOnPreparedListener { it.isLooping = true; it.setVolume(0f, 0f); start() }
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        } else if (file != null) {
            container.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(BitmapFactory.decodeFile(file.path)) }, full)
        } else {
            // Schermo halba predefinito, finché il merchant non carica il suo
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Halba.DARK_SURFACE)
                addView(HalbaMark(this@MainActivity, ring = Halba.DARK_INK, dot = 0xFFA99BFF.toInt()), LinearLayout.LayoutParams(Halba.dp(this@MainActivity, 96f).toInt(), Halba.dp(this@MainActivity, 96f).toInt()))
                addView(text("halba", 40f, Halba.DARK_INK, bold = true))
                addView(text(config?.optJSONObject("store")?.optString("name") ?: "", 18f, Halba.DARK_INK_MUTED))
            }, full)
        }
        chargingBanner = TextView(this).apply {
            text = "Connect the terminal to power: without it the till may not reach it"
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Halba.DANGER)
            setPadding(32, 96, 32, 24) //  sotto la barra di stato
            gravity = Gravity.CENTER
        }
        container.addView(chargingBanner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        statusView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            background = Halba.pill(context, 0x99000000.toInt())
            setPadding(32, 16, 32, 16)
            contentDescription = "Terminal status. Staff: hold for 3 seconds to open the menu"
            // Menu del personale: pressione di 3 secondi sull'indicatore
            val hold = Handler(Looper.getMainLooper())
            val open = Runnable { staffMenu() }
            setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> hold.postDelayed(open, 3000)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> hold.removeCallbacks(open)
                }
                true
            }
        }
        container.addView(statusView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 24, 24) })
        updateStatus()
    }

    private fun updateStatus() {
        statusView?.text = listOfNotNull(if (online) "● Online" else "● Offline", config?.optJSONObject("store")?.optString("name")).joinToString(" · ")
        statusView?.setTextColor(if (online) Color.WHITE else Halba.DARK_DANGER)
        chargingBanner?.visibility = if (isCharging()) View.GONE else View.VISIBLE
    }

    // --- Menu del personale (PIN del negozio, verificato da halba) ---
    private fun staffMenu() {
        if (config?.optBoolean("staff_pin_set") != true) { menu(null); return }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "6-digit PIN"
            textSize = 24f
            gravity = Gravity.CENTER
        }
        AlertDialog.Builder(this).setTitle("Staff PIN").setView(input)
            .setPositiveButton("Open") { _, _ ->
                val pin = input.text.toString()
                lifecycleScope.launch {
                    try {
                        api.verifyPin(pin)
                        menu(pin)
                    } catch (e: HalbaApi.ApiError) {
                        AlertDialog.Builder(this@MainActivity).setMessage(if (e.status == 429) "Too many wrong PINs: try again in 5 minutes" else "Wrong PIN").setPositiveButton("OK", null).show()
                    } catch (e: Exception) {
                        AlertDialog.Builder(this@MainActivity).setMessage("No connection: the PIN is checked by halba").setPositiveButton("OK", null).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun menu(pin: String?) {
        val cfg = config ?: JSONObject()
        val items = arrayOf("Terminal information", "Change store or name", "Refresh the screen", "Unpair this terminal", "Exit the app")
        AlertDialog.Builder(this).setTitle("Staff menu").setItems(items) { _, which ->
            when (which) {
                0 -> AlertDialog.Builder(this).setTitle("Terminal").setMessage(
                    listOf(
                        "Store: ${cfg.optJSONObject("store")?.optString("name") ?: "—"}",
                        "Name: ${cfg.optJSONObject("terminal")?.optString("label") ?: "—"}",
                        "Terminal: ${api.terminalId}",
                        "PSP: ${reader.label}${if (readerInfo.isNotEmpty()) " · $readerInfo" else ""}",
                        "App: ${BuildConfig.VERSION_NAME}",
                        "Status: ${if (online) "online" else "offline"}, ${if (isCharging()) "charging" else "not charging"}",
                    ).joinToString("\n"),
                ).setPositiveButton("OK", null).show()
                1 -> { loop?.cancel(); idle = false; chooseStore(cfg, pin) }
                2 -> lifecycleScope.launch { runCatching { apply(api.config()) }; screen.clear(); syncScreen(showProgress = false); showIdle() }
                3 -> AlertDialog.Builder(this).setMessage("Unpair this terminal? It will need a new code to take payments.")
                    .setPositiveButton("Unpair") { _, _ -> forget() }.setNegativeButton("Cancel", null).show()
                4 -> AlertDialog.Builder(this).setMessage("Exit the app? The till will not reach this terminal until the app is opened again.")
                    .setPositiveButton("Exit") { _, _ -> finishAndRemoveTask() }.setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    // --- Pagamento ---
    private fun amountOf(p: JSONObject): String {
        val a = p.getJSONObject("amount")
        val cur = Currency.getInstance(a.getString("currency"))
        val f = NumberFormat.getCurrencyInstance().apply { currency = cur }
        return f.format(a.getLong("value") / Math.pow(10.0, cur.defaultFractionDigits.toDouble()))
    }

    // Metodo scelto dalla cassa (payment_method nella richiesta): si va dritti a quel pagamento, senza chiederlo
    // di nuovo al cliente; altrimenti si mostra la scelta
    private fun start(p: JSONObject) {
        when (val m = p.optString("method").takeIf { it.isNotEmpty() && it != "null" }) {
            null -> showPayment(p)
            "card" -> if (reader.canPayByCard(p)) payByCard(p) else finish(JSONObject().put("status", "failed").put("failure", JSONObject().put("message", "Cards are not available on this terminal")))
            else -> payByQr(p, m)
        }
    }

    private fun showPayment(p: JSONObject) {
        page()
        root.addView(text(p.optString("merchant_reference"), 16f, Halba.INK_MUTED))
        root.addView(text(amountOf(p), 44f, Halba.INK, bold = true))
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
            finish(api.result(id, "confirmed"))
        } catch (e: Exception) {
            runCatching { api.result(id, "failed", "card_error", e.message) }
            finish(JSONObject().put("status", "failed").put("failure", JSONObject().put("message", e.message ?: "Card error")))
        }
    }

    private fun payByQr(p: JSONObject, method: String): Job = lifecycleScope.launch {
        val id = p.getString("id")
        try {
            val r = api.qr(id, method)
            page()
            root.addView(text(amountOf(p), 36f, Halba.INK, bold = true))
            root.addView(text("Scan with ${labels[method] ?: method}", 18f, Halba.INK_MUTED))
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
        show(if (ok) "Payment approved" else "Payment not completed", detail, color = if (ok) Halba.SUCCESS else Halba.DANGER)
        lifecycleScope.launch { delay(4000); busy = false; waitForCommands() }
    }

    // --- Interfaccia provvisoria ---
    private fun page() {
        idle = false
        statusView = null
        chargingBanner = null
        container.removeAllViews()
        container.setBackgroundColor(Halba.SURFACE)
        root.removeAllViews()
        (root.parent as? android.view.ViewGroup)?.removeView(root) //  la pagina precedente lo teneva nel suo ScrollView
        container.addView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    private fun show(title: String, body: String, big: String? = null, sub: String? = null, color: Int = Halba.INK, action: String? = null, onAction: (() -> Unit)? = null) {
        page()
        // Attesa (nessuna azione, testo che finisce con "…"): loader con il marchio, come nel checkout
        if (action == null && body.endsWith("…")) {
            val size = Halba.dp(this, 56f).toInt()
            root.addView(HalbaMark(this, spin = true), LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = Halba.dp(this@MainActivity, 16f).toInt() })
        }
        root.addView(text(title, 24f, color, bold = true))
        root.addView(text(body, 18f, Halba.INK_MUTED))
        if (big != null) root.addView(text(big, 48f, Halba.BRAND, bold = true, mono = true))
        if (sub != null) root.addView(text(sub, 12f, Halba.INK_MUTED))
        if (action != null && onAction != null) root.addView(button(action, primary = true, onClick = onAction))
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

    // Pulsante del design system: primario (brand) o secondario (bordo forte), raggio 12 dp, almeno 56 dp di altezza
    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 18f
        isAllCaps = false
        stateListAnimator = null
        background = if (primary) Halba.primary(context) else Halba.secondary(context)
        setTextColor(if (primary) Halba.ON_BRAND else Halba.INK)
        typeface = Typeface.DEFAULT_BOLD
        minHeight = 168 //  circa 56 dp: bersagli grandi
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 }
    }

    private fun qrBitmap(content: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        return bmp
    }
}
