package ai.halba.pos

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import ai.halba.pos.Halba.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.URL
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.random.Random

// App halba sul terminale, con il design del gruppo Terminal del design system (project/terminal.md).
//   A. configurazione guidata (primo avvio): lingua → abbinamento → negozio e nome → lettore → schermo a riposo →
//      pagamento di prova (solo test) → fatto
//   B. a riposo: immagine o video del merchant, indicatore di stato, marchio halba
//   C–F. pagamento chiesto dalla cassa: scelta del metodo (se la cassa non l'ha scelto) → carta o QR → esito (4 s)
//   G. menu del personale: pressione di 3 s sull'indicatore o sul marchio, PIN del negozio
//   H. stati di sistema: terminale disattivato, rete assente, avvio
class MainActivity : AppCompatActivity() {
    private val api = HalbaApi(BuildConfig.HALBA_API)
    private lateinit var reader: CardReader
    private lateinit var secure: SecureStore
    private lateinit var screen: ScreenCache
    private lateinit var container: FrameLayout
    private val prefs by lazy { getSharedPreferences("halba_app", MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())

    private var loop: Job? = null
    private var busy = false //                  pagamento in corso: il long-poll non apre altri comandi
    private var online = false
    private var config: JSONObject? = null
    private var configVersion: String? = null
    private var readerInfo = ""
    private var lastContact = 0L //              ultimo giro riuscito del long-poll (menu del personale)
    private var idleView: FrameLayout? = null //  schermo a riposo, sotto il foglio del pagamento
    private var player: ExoPlayer? = null
    private var logo: Bitmap? = null
    private var collecting = false //            carta passata al lettore del PSP (handoff)
    private var onReturn: (() -> Unit)? = null
    private var timeout: Runnable? = null //      inattività (PIN 30 s, menu 120 s)
    private var timeoutMs = 0L
    private var statusPill: View? = null
    private var powerPill: View? = null

    private val labels = mapOf("card" to "Card", "alipay" to "Alipay", "wechatpay" to "WeChat Pay", "bizum" to "Bizum", "blik" to "BLIK", "twint" to "TWINT")
    // Istruzione del QR nella lingua del metodo (8.3)
    private val localLine = mapOf("alipay" to "请使用支付宝扫码", "wechatpay" to "请使用微信扫码", "bizum" to "Escanea con Bizum", "blik" to "Zeskanuj w aplikacji banku")

    private val power = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateStatus()
    }

    // --- Lingua: scelta nella configurazione, tenuta fino a una nuova configurazione (4) ---
    private var lang: String
        get() = prefs.getString("lang", null) ?: Locale.getDefault().language.takeIf { it in listOf("en", "it", "de") } ?: "en"
        set(v) { prefs.edit().putString("lang", v).apply(); strings = localized(v) }
    private var strings: Context? = null
    private fun localized(l: String): Context = createConfigurationContext(Configuration(resources.configuration).apply { setLocale(Locale(l)) })
    private fun s(id: Int, vararg args: Pair<String, Any>): String {
        var t = (strings ?: localized(lang).also { strings = it }).getString(id)
        for ((k, v) in args) t = t.replace("{$k}", v.toString())
        return t
    }

    // Importi nella lingua dello schermo (8.1): €49.90, 49,90 €, 49,90 CHF; spazio indivisibile prima del simbolo
    private fun money(value: Long, currency: String): String {
        val cur = Currency.getInstance(currency)
        val loc = when (lang) { "it" -> Locale.ITALY; "de" -> Locale.GERMANY; else -> Locale("en", "IE") }
        val f = NumberFormat.getCurrencyInstance(loc).apply { this.currency = cur; minimumFractionDigits = cur.defaultFractionDigits; maximumFractionDigits = cur.defaultFractionDigits }
        return f.format(value / Math.pow(10.0, cur.defaultFractionDigits.toDouble())).replace(' ', ' ')
    }
    private fun amountOf(p: JSONObject) = p.getJSONObject("amount").let { money(it.getLong("value"), it.getString("currency")) }

    private val light get() = Ui(this, Halba.LIGHT, accent)
    private val dark get() = Ui(this, Halba.DARK, Halba.Accent(Halba.DARK.brand, Halba.DARK.onBrand, Halba.DARK.brand))
    private val accent get() = Halba.accent(config?.optJSONObject("merchant")?.optString("primary_color")?.takeIf { it.startsWith("#") })
    // Prima dell'abbinamento la configurazione non c'è: decide l'API a cui punta la build (sandbox = test)
    private val testMode get() = config?.optString("environment")?.let { it == "test" } ?: BuildConfig.HALBA_API.contains("sandbox")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        container = FrameLayout(this).apply { setBackgroundColor(Halba.IDLE_BG) }
        setContentView(container)
        // Terminale da banco in kiosk: niente barre di sistema (riappaiono con uno scorrimento dal bordo e si richiudono)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, container).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        reader = Psp.cardReader(api, lifecycleScope)
        secure = SecureStore(this)
        screen = ScreenCache(this, api)
        api.charging = { isCharging() }
        api.deviceKey = secure.deviceKey()
        api.terminalId = if (api.deviceKey != null) secure.terminalId else null
        registerReceiver(power, IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) })
        val needed = reader.permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1) else begin()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(power) }
        player?.release()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        begin()
    }

    // Ritorno dall'app del PSP (handoff della carta): "Elaborazione in corso"
    override fun onResume() {
        super.onResume()
        if (collecting) onReturn?.invoke()
    }

    // Ogni tocco rinvia il ritorno a riposo per inattività
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) timeout?.let { main.removeCallbacks(it); main.postDelayed(it, timeoutMs) }
        return super.dispatchTouchEvent(ev)
    }

    private fun inactivity(ms: Long?, action: () -> Unit = { backToIdle() }) {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
        if (ms == null) return
        timeoutMs = ms
        timeout = Runnable { timeout = null; action() }.also { main.postDelayed(it, ms) }
    }

    private fun isCharging(): Boolean {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    // --- Schermate e passaggi (4, 9): dissolvenza di 240 ms, nessun movimento con "rimuovi animazioni" ---
    private fun show(v: View, dark: Boolean = false) {
        inactivity(null)
        statusPill = null
        powerPill = null
        if (idleView != null) { player?.release(); player = null; idleView = null }
        resetBrightness()
        container.setBackgroundColor(if (dark) Halba.IDLE_BG else Halba.LIGHT.surface)
        val old = (0 until container.childCount).map { container.getChildAt(it) }
        container.addView(v, FrameLayout.LayoutParams(-1, -1))
        if (Halba.reducedMotion(this) || old.isEmpty()) { old.forEach { container.removeView(it) }; return }
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(240).withEndAction { old.forEach { container.removeView(it) } }.start()
    }

    // Pagamento sopra lo schermo a riposo (C): il foglio bianco sale dal basso e il riposo si scurisce al 60 %
    private fun sheet(v: View) {
        val under = idleView
        if (under == null || Halba.reducedMotion(this)) { show(v); return }
        inactivity(null)
        statusPill = null
        powerPill = null
        idleView = null
        val dim = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        container.addView(dim, FrameLayout.LayoutParams(-1, -1))
        val wrap = FrameLayout(this).apply {
            background = Halba.topRounded(this@MainActivity, Halba.LIGHT.surface, Halba.R_PANEL)
            clipToOutline = true
            addView(v, FrameLayout.LayoutParams(-1, -1))
        }
        container.addView(wrap, FrameLayout.LayoutParams(-1, -1))
        wrap.translationY = resources.displayMetrics.heightPixels.toFloat()
        dim.animate().alpha(0.6f).setDuration(240).start()
        wrap.animate().translationY(0f).setDuration(240).setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction {
            // Finito il passaggio il pagamento occupa lo schermo: il video si ferma
            container.removeView(under); container.removeView(dim)
            player?.release(); player = null
            wrap.background = null
            container.setBackgroundColor(Halba.LIGHT.surface)
        }.start()
    }

    private fun announce(v: View, text: String, assertive: Boolean = true) {
        ViewCompat.setAccessibilityLiveRegion(v, if (assertive) ViewCompat.ACCESSIBILITY_LIVE_REGION_ASSERTIVE else ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
        v.post { v.announceForAccessibility(text) }
    }

    // --- Avvio ---
    private fun begin() {
        when {
            api.deviceKey == null -> if (prefs.contains("lang")) setupPair() else setupWelcome()
            !prefs.getBoolean("setup_done", false) -> loadThen { setupStore(null) }
            else -> boot()
        }
    }

    // H. Avvio (riavvio notturno): marchio con il loader e "Avvio di halba", poi a riposo
    private fun boot(attempt: Int = 0): Job = lifecycleScope.launch {
        system(s(R.string.terminal_boot), loader = true)
        try {
            apply(api.config())
            online = true
            runCatching { reader.init(this@MainActivity); if (!reader.connected) readerInfo = withTimeout(15_000) { reader.connect() } }
            runCatching { screen.sync(config?.optJSONObject("screen")) }
            loadLogo()
            waitForCommands()
        } catch (e: HalbaApi.ApiError) {
            if (e.status == 401) turnedOff() else noNetwork(attempt) { boot(attempt + 1) }
        } catch (e: Exception) {
            noNetwork(attempt) { boot(attempt + 1) }
        }
    }

    private fun loadThen(next: () -> Unit): Job = lifecycleScope.launch {
        system(s(R.string.terminal_boot), loader = true)
        try { apply(api.config()); online = true; next() }
        catch (e: HalbaApi.ApiError) { if (e.status == 401) turnedOff() else noNetwork(0) { loadThen(next) } }
        catch (e: Exception) { noNetwork(0) { loadThen(next) } }
    }

    private fun apply(cfg: JSONObject) {
        config = cfg
        lastContact = System.currentTimeMillis()
        configVersion = cfg.optString("config_version").ifEmpty { null }
    }

    // Logo del merchant sullo schermo halba predefinito
    private suspend fun loadLogo() {
        val url = config?.optJSONObject("merchant")?.optString("logo_url")?.takeIf { it.startsWith("https://") } ?: run { logo = null; return }
        logo = runCatching { withContext(Dispatchers.IO) { URL(url).openStream().use { BitmapFactory.decodeStream(it) } } }.getOrNull()
    }

    // --- H. Stati di sistema (scuri) ---
    private fun system(title: String, text: String? = null, loader: Boolean = false, small: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
        val u = dark
        val pg = u.page(background = Halba.IDLE_BG)
        if (loader) u.add(pg.body, u.loader(72f, dark = true))
        else u.add(pg.body, HalbaMark(this, ring = Halba.DARK.ink, dot = Halba.DARK.brand), width = Halba.px(this, 72f), height = Halba.px(this, 72f))
        u.add(pg.body, u.text(title, Type.TITLE), top = 24f)
        if (text != null) u.add(pg.body, u.text(text, Type.TEXT), top = 8f)
        if (small != null) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            row.addView(u.loader(32f, dark = true))
            row.addView(u.text(small, Type.LABEL, Halba.DARK.muted), LinearLayout.LayoutParams(-2, -2).apply { marginStart = Halba.px(this@MainActivity, 12f) })
            u.add(pg.body, row, top = 24f)
        }
        if (action != null && onAction != null) pg.bottom.addView(u.mainButton(action, onClick = onAction))
        show(pg.root, dark = true)
        announce(pg.body, title, assertive = false)
    }

    private fun turnedOff() {
        loop?.cancel()
        system(s(R.string.terminal_off_title), s(R.string.terminal_off_text), action = s(R.string.terminal_off_btn)) { forget() }
    }

    // Rete assente: si riprova da soli dopo 2, 4, 8, 15 s, poi ogni 30 s (4)
    private fun noNetwork(attempt: Int, retry: () -> Unit) {
        system(s(R.string.terminal_nonet_title), s(R.string.terminal_nonet_text), small = s(R.string.terminal_nonet_try))
        val wait = listOf(2_000L, 4_000L, 8_000L, 15_000L).getOrElse(attempt) { 30_000L }
        lifecycleScope.launch { delay(wait); retry() }
    }

    // --- A. Configurazione guidata ---
    private val totalSteps get() = if (testMode) 7 else 6

    // Intestazione: indietro (56 × 56 dp), "Passo n di t", barra dei passi
    private fun setupPage(step: Int, title: String, text: String? = null, onBack: (() -> Unit)? = null, center: Boolean = false): Ui.Page {
        val u = light
        val pg = u.page(center = center)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Halba.px(this@MainActivity, Halba.HEADER)
            setPadding(Halba.px(this@MainActivity, 8f), 0, Halba.px(this@MainActivity, Halba.MARGIN), 0)
        }
        if (onBack != null) {
            bar.addView(FrameLayout(this).apply {
                addView(Icon(this@MainActivity, "chevron-left", u.p.ink), FrameLayout.LayoutParams(Halba.px(this@MainActivity, 28f), Halba.px(this@MainActivity, 28f), Gravity.CENTER))
                contentDescription = s(R.string.terminal_back)
                isClickable = true
                setOnClickListener { onBack() }
            }, LinearLayout.LayoutParams(Halba.px(this, 56f), Halba.px(this, 56f)))
        } else bar.addView(View(this), LinearLayout.LayoutParams(Halba.px(this, 16f), 1))
        bar.addView(u.text(s(R.string.terminal_step, "n" to step, "t" to totalSteps), Type.LABEL, u.p.muted, center = false), LinearLayout.LayoutParams(0, -2, 1f))
        pg.header.addView(bar)
        pg.header.addView(Steps(this, totalSteps, if (step == totalSteps) step else step - 1, u.p.sunken, u.accent.accent), LinearLayout.LayoutParams(-1, Halba.px(this, 6f)).apply {
            marginStart = Halba.px(this@MainActivity, Halba.MARGIN); marginEnd = Halba.px(this@MainActivity, Halba.MARGIN)
        })
        u.add(pg.body, u.text(title, Type.TITLE, center = false), gravity = Gravity.START)
        if (text != null) u.add(pg.body, u.text(text, Type.TEXT, center = false), top = 8f)
        return pg
    }

    private fun waitingRow(u: Ui, text: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(u.loader(32f))
        addView(u.text(text, Type.LABEL, u.p.muted, center = false), LinearLayout.LayoutParams(-2, -2).apply { marginStart = Halba.px(this@MainActivity, 12f) })
    }

    // 1. Benvenuto e lingua
    private fun setupWelcome() {
        var chosen = lang
        fun render() {
            val u = light
            val t = localized(chosen)
            val pg = u.page(center = false)
            pg.header.addView(View(this), LinearLayout.LayoutParams(1, Halba.px(this, 24f)))
            u.add(pg.body, HalbaMark(this, ring = u.p.ink, dot = u.accent.accent), top = 24f, width = Halba.px(this, 72f), height = Halba.px(this, 72f), gravity = Gravity.START)
            u.add(pg.body, u.text(t.getString(R.string.terminal_welcome_title), Type.TITLE, center = false), top = 24f, gravity = Gravity.START)
            u.add(pg.body, u.text(t.getString(R.string.terminal_welcome_text), Type.TEXT, center = false), top = 8f, gravity = Gravity.START)
            u.add(pg.body, u.space(Halba.GAP))
            for ((code, name) in listOf("en" to "English", "it" to "Italiano", "de" to "Deutsch")) {
                pg.body.addView(u.choiceRow(name, null, chosen == code) { chosen = code; render() })
            }
            pg.bottom.addView(u.mainButton(t.getString(R.string.terminal_continue)) { lang = chosen; setupPair() })
            show(pg.root)
        }
        render()
    }

    // 2. Abbinamento: codice di 8 caratteri in due righe di quattro; si controlla ogni 3 s
    private var pairing: Job? = null
    private fun setupPair(expired: Boolean = false) {
        pairing?.cancel()
        pairing = lifecycleScope.launch {
            val u = light
            fun page(code: String?, state: String): Ui.Page {
                val pg = setupPage(2, s(R.string.terminal_pair_title), onBack = { pairing?.cancel(); setupWelcome() })
                if (state == "expired") pg.body.addView(u.note("warn", "${s(R.string.terminal_pair_expired)}. ${s(R.string.terminal_pair_new)}"))
                if (code != null) {
                    val box = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        background = Halba.rounded(this@MainActivity, u.p.sunken, Halba.R_PANEL)
                        setPadding(0, Halba.px(this@MainActivity, 20f), 0, Halba.px(this@MainActivity, 20f))
                        for (half in listOf(code.take(4), code.drop(4))) addView(u.text(half, Type.CODE, mono = true).apply { letterSpacing = 0.08f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
                        // Letto carattere per carattere, con una pausa tra i due gruppi (10)
                        contentDescription = code.take(4).toList().joinToString(", ") + ". " + code.drop(4).toList().joinToString(", ")
                        isFocusable = true
                    }
                    u.add(pg.body, box, top = 24f)
                    u.add(pg.body, u.text(s(R.string.terminal_pair_help), Type.TEXT, center = false), top = 16f)
                }
                if (state == "error") {
                    pg.body.addView(u.note("danger", "${s(R.string.terminal_net_title)}. ${s(R.string.terminal_net_text)}"))
                    pg.bottom.addView(u.mainButton(s(R.string.terminal_retry)) { setupPair() })
                } else {
                    val row = u.add(pg.body, waitingRow(u, s(R.string.terminal_pair_wait)), top = 24f, gravity = Gravity.START)
                    announce(row, s(R.string.terminal_pair_wait), assertive = false)
                }
                return pg
            }
            try {
                val p = api.startPairing()
                show(page(p.getString("code"), if (expired) "expired" else "wait").root)
                var failures = 0
                while (isActive) {
                    delay(3000)
                    val st = try { api.pairingStatus(p.getString("id"), p.getString("secret")).also { failures = 0 } } catch (e: Exception) {
                        if (++failures >= 3) { show(page(null, "error").root); delay(15_000); setupPair(); return@launch }
                        continue
                    }
                    when (st.optString("status")) {
                        "paired" -> {
                            api.terminalId = st.getString("terminal")
                            api.deviceKey = st.getString("device_key")
                            secure.save(api.terminalId!!, api.deviceKey!!)
                            loadThen { setupStore(null) }
                            return@launch
                        }
                        "expired" -> { setupPair(expired = true); return@launch }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Errore di rete: nota con "Riprova", e si riprova anche da soli
                show(page(null, "error").root)
                delay(8_000)
                setupPair()
            }
        }
    }

    // 3. Negozio e nome: ricerca, elenco, poi il nome proposto
    private fun setupStore(pin: String?, fromMenu: Boolean = false): Job = lifecycleScope.launch {
        val u = light
        val stores = try { api.stores().optJSONArray("data") } catch (e: Exception) {
            val pg = setupPage(3, s(R.string.terminal_store_title))
            pg.body.addView(u.note("danger", "${s(R.string.terminal_net_title)}. ${s(R.string.terminal_net_text)}"))
            pg.bottom.addView(u.mainButton(s(R.string.terminal_retry)) { setupStore(pin, fromMenu) })
            show(pg.root)
            return@launch
        }
        var selected: String? = config?.optJSONObject("store")?.optString("id")?.takeIf { it.isNotEmpty() && it != "null" }
        var query = ""
        val current = config?.optJSONObject("terminal")?.optString("label")?.takeIf { it.isNotEmpty() && it != "null" && it != android.os.Build.MODEL }
        val pg = setupPage(3, s(R.string.terminal_store_title), onBack = if (fromMenu) ({ backToIdle() }) else null)
        val search = u.input("", s(R.string.terminal_store_search)).apply { imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
        pg.body.addView(search.also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = Halba.px(this@MainActivity, Halba.GAP) })
        val list = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        pg.body.addView(list, LinearLayout.LayoutParams(-1, -2))
        u.add(pg.body, u.text(s(R.string.terminal_name_title), Type.HEADLINE, center = false), top = 24f, gravity = Gravity.START)
        u.add(pg.body, u.text(s(R.string.terminal_name_label), Type.LABEL, u.p.muted, center = false), top = 8f, gravity = Gravity.START)
        val name = u.input(current ?: s(R.string.terminal_name_value), s(R.string.terminal_name_label))
        pg.body.addView(name)
        val next = u.mainButton(s(R.string.terminal_continue)) {}
        fun refresh() {
            list.removeAllViews()
            if (stores == null || stores.length() == 0) list.addView(u.note("warn", "No stores yet: add one in halba (Connections > your PSP > Add store)"))
            for (i in 0 until (stores?.length() ?: 0)) {
                val st = stores!!.getJSONObject(i)
                val ref = st.optString("external_ref").takeIf { it.isNotEmpty() && it != "null" }
                val title = st.optString("name")
                if (query.isNotEmpty() && !title.contains(query, true) && ref?.contains(query, true) != true) continue
                list.addView(u.choiceRow(title, ref?.let { s(R.string.terminal_store_code, "c" to it) }, selected == st.getString("id")) { selected = st.getString("id"); refresh() })
            }
            u.setEnabled(next, selected != null)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) { query = e?.toString()?.trim().orEmpty(); refresh() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        next.setOnClickListener {
            val id = selected ?: return@setOnClickListener
            u.setEnabled(next, false)
            lifecycleScope.launch {
                try {
                    apply(api.configure(id, name.text.toString().trim().ifEmpty { null }, pin))
                    if (fromMenu) { prefs.edit().putBoolean("setup_done", true).apply(); backToIdle() } else setupReader()
                } catch (e: Exception) {
                    pg.body.addView(u.note("danger", e.message ?: s(R.string.terminal_net_title)))
                    u.setEnabled(next, true)
                }
            }
        }
        pg.bottom.addView(next)
        refresh()
        show(pg.root)
    }

    // 4. Prova del lettore: collegamento (al più 15 s), pronto, non trovato
    private fun setupReader(): Job = lifecycleScope.launch {
        val u = light
        val back = { setupStore(null); Unit }
        val pg = setupPage(4, s(R.string.terminal_reader_title), onBack = back)
        u.add(pg.body, u.loader(72f), top = 48f)
        u.add(pg.body, u.text(s(R.string.terminal_reader_connecting), Type.TEXT), top = 16f).also { announce(it, s(R.string.terminal_reader_connecting), false) }
        show(pg.root)
        try {
            reader.init(this@MainActivity)
            if (!reader.connected) readerInfo = withTimeout(15_000) { reader.connect() }
            val ok = setupPage(4, s(R.string.terminal_reader_title), onBack = back)
            u.add(ok.body, u.badge("success"), top = 48f)
            u.add(ok.body, u.text(s(R.string.terminal_reader_ready), Type.HEADLINE), top = 16f).also { announce(it, s(R.string.terminal_reader_ready), false) }
            if (readerInfo.isNotEmpty()) u.add(ok.body, u.text(readerInfo, Type.CAPTION), top = 4f)
            ok.bottom.addView(u.mainButton(s(R.string.terminal_continue)) { setupIdle() })
            show(ok.root)
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            readerMissing(u, back)
        } catch (e: Exception) {
            readerMissing(u, back)
        }
    }

    private fun readerMissing(u: Ui, back: () -> Unit) {
        val ko = setupPage(4, s(R.string.terminal_reader_title), onBack = back)
        ko.body.addView(u.note("danger", s(R.string.terminal_reader_missing)))
        u.add(ko.body, u.text(s(R.string.terminal_reader_help), Type.TEXT, center = false), top = 16f)
        ko.bottom.addView(u.mainButton(s(R.string.terminal_retry)) { setupReader() })
        show(ko.root)
    }

    // 5. Schermo a riposo: download con avanzamento, oppure l'anteprima dello schermo halba
    private fun setupIdle(): Job = lifecycleScope.launch {
        val u = light
        val target = config?.optJSONObject("screen")
        val pg = setupPage(5, s(R.string.terminal_idle_title), onBack = { setupReader() })
        val next = { if (testMode) setupTest() else setupDone() }
        if (screen.pending(target)) {
            u.add(pg.body, u.text(s(R.string.terminal_idle_dl), Type.HEADLINE, center = false), top = 24f, gravity = Gravity.START)
            val bar = u.add(pg.body, Bar(this@MainActivity, u.p.sunken, u.accent.accent), top = 16f, height = Halba.px(this@MainActivity, 12f))
            val pct = u.add(pg.body, u.text("0 %", Type.LABEL, u.p.muted, center = false, mono = true), top = 8f, gravity = Gravity.START)
            val btn = u.mainButton(s(R.string.terminal_continue)) { next() }
            u.setEnabled(btn, false)
            pg.bottom.addView(btn)
            show(pg.root)
            // Il download che non riesce si ritenta poi da solo e non blocca la configurazione
            runCatching {
                screen.sync(target) { read, total ->
                    main.post {
                        if (total > 0) bar.fraction = read.toFloat() / total
                        pct.text = if (total > 0) "${read * 100 / total} % · ${"%.1f".format(read / 1e6)} / ${"%.1f".format(total / 1e6)} MB" else "${"%.1f".format(read / 1e6)} MB"
                    }
                }
            }
            bar.fraction = 1f
            u.setEnabled(btn, true)
        } else {
            u.add(pg.body, u.text(s(R.string.terminal_idle_none_title), Type.HEADLINE, center = false), top = 16f, gravity = Gravity.START)
            u.add(pg.body, u.text(s(R.string.terminal_idle_none_text), Type.TEXT, center = false), top = 8f)
            // Anteprima nella cornice: lo schermo halba predefinito
            val frame = FrameLayout(this@MainActivity).apply {
                background = Halba.rounded(this@MainActivity, Halba.IDLE_BG, Halba.R_TILE, u.p.borderStrong, 4f)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(HalbaMark(this@MainActivity, ring = Halba.DARK.ink, dot = Halba.DARK.brand), LinearLayout.LayoutParams(Halba.px(this@MainActivity, 40f), Halba.px(this@MainActivity, 40f)))
                    addView(dark.text("halba", Type.LABEL).apply { typeface = Halba.typeface(700) })
                }, FrameLayout.LayoutParams(-1, -1))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            u.add(pg.body, frame, top = 24f, width = Halba.px(this@MainActivity, 108f), height = Halba.px(this@MainActivity, 192f))
            pg.bottom.addView(u.mainButton(s(R.string.terminal_continue)) { next() })
            show(pg.root)
        }
    }

    // 6. Pagamento di prova (solo in test, facoltativo): 1,00 nella valuta del merchant
    private fun setupTest() {
        val u = light
        val cur = runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull()?.takeIf { it in listOf("EUR", "CHF", "GBP") } ?: "EUR"
        val pg = setupPage(6, s(R.string.terminal_test_title), s(R.string.terminal_test_text, "amt" to money(100, cur)), onBack = { setupIdle() })
        pg.bottom.addView(u.mainButton(s(R.string.terminal_test_start)) {
            lifecycleScope.launch {
                try {
                    val p = api.testPayment(cur)
                    busy = true
                    payByCard(p, test = true)
                } catch (e: Exception) {
                    pg.body.addView(u.note("danger", e.message ?: s(R.string.terminal_net_title)))
                }
            }
        })
        pg.bottom.addView(u.textButton(s(R.string.terminal_skip)) { setupDone() })
        show(pg.root)
    }

    // 7. Fatto: riepilogo e "Avvia"
    private fun setupDone() {
        val u = light
        val pg = setupPage(totalSteps, s(R.string.terminal_done_title), onBack = { setupIdle() })
        u.add(pg.body, u.badge("success"), top = 24f, gravity = Gravity.START)
        u.add(pg.body, u.space(16f))
        pg.body.addView(u.infoRow(s(R.string.terminal_done_store), config?.optJSONObject("store")?.optString("name") ?: "—"))
        pg.body.addView(u.divider())
        pg.body.addView(u.infoRow(s(R.string.terminal_done_name), config?.optJSONObject("terminal")?.optString("label") ?: "—"))
        pg.body.addView(u.divider())
        pg.body.addView(u.infoRow(s(R.string.terminal_done_psp), reader.label))
        pg.bottom.addView(u.mainButton(s(R.string.terminal_start)) {
            prefs.edit().putBoolean("setup_done", true).apply()
            lifecycleScope.launch { loadLogo(); waitForCommands() }
        })
        show(pg.root)
    }

    // --- Attesa della cassa: long-poll; config_version diversa = si rilegge la configurazione ---
    private fun waitForCommands() {
        loop?.cancel()
        showIdle()
        loop = lifecycleScope.launch {
            while (isActive) {
                try {
                    val r = api.commands()
                    lastContact = System.currentTimeMillis()
                    if (!online) { online = true; updateStatus() }
                    val v = r.optString("config_version").ifEmpty { null }
                    if (v != null && v != configVersion) {
                        apply(api.config())
                        val changed = runCatching { screen.sync(config?.optJSONObject("screen")) }.getOrDefault(false)
                        loadLogo()
                        if (idleView != null && changed) showIdle()
                    }
                    val first = r.optJSONArray("commands")?.optJSONObject(0)
                    if (first != null && first.optString("type") == "payment.start" && !busy) {
                        busy = true
                        start(first.getJSONObject("payment"))
                        return@launch
                    }
                } catch (e: HalbaApi.ApiError) {
                    if (e.status == 401) { turnedOff(); return@launch }
                    if (online) { online = false; updateStatus() }
                    delay(3000)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (online) { online = false; updateStatus() }
                    delay(3000)
                }
            }
        }
    }

    private fun backToIdle() {
        inactivity(null)
        busy = false
        waitForCommands()
    }

    private fun forget() {
        loop?.cancel()
        secure.clear()
        screen.clear()
        prefs.edit().remove("setup_done").apply()
        api.terminalId = null
        api.deviceKey = null
        config = null
        setupPair()
    }

    // --- B. A riposo ---
    private fun showIdle() {
        val view = FrameLayout(this).apply { setBackgroundColor(Halba.IDLE_BG) }
        val file = screen.file
        player?.release(); player = null
        var exo: ExoPlayer? = null
        if (file != null && screen.kind == "video") {
            // ExoPlayer in loop senza audio, pronto in anticipo per non mostrare un fotogramma nero al giro
            exo = ExoPlayer.Builder(this).build().apply {
                setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f
                playWhenReady = true
                prepare()
            }
            view.addView(PlayerView(this).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                this.player = exo
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }, FrameLayout.LayoutParams(-1, -1))
        } else if (file != null) {
            view.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(BitmapFactory.decodeFile(file.path)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, FrameLayout.LayoutParams(-1, -1))
        } else {
            // Schermo halba predefinito: marchio 112 dp, "halba" 40 sp, logo del merchant sotto (176 × 72 dp)
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(HalbaMark(this@MainActivity, ring = Halba.DARK.ink, dot = Halba.DARK.brand), LinearLayout.LayoutParams(Halba.px(this@MainActivity, 112f), Halba.px(this@MainActivity, 112f)))
                addView(TextView(this@MainActivity).apply { text = "halba"; textSize = 40f; typeface = Halba.typeface(700); setTextColor(Halba.DARK.ink); gravity = Gravity.CENTER })
                logo?.let { bmp ->
                    addView(ImageView(this@MainActivity).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER },
                        LinearLayout.LayoutParams(Halba.px(this@MainActivity, 176f), Halba.px(this@MainActivity, 72f)).apply { topMargin = Halba.px(this@MainActivity, 24f) })
                }
            }
            view.addView(content, FrameLayout.LayoutParams(-1, -1))
            // Contro il burn-in: ogni 60 s il contenuto si sposta di al più 4 dp
            val shift = object : Runnable {
                override fun run() {
                    if (!content.isAttachedToWindow) return
                    val d = Halba.dp(this@MainActivity, 4f)
                    content.translationX = Random.nextFloat() * 2 * d - d
                    content.translationY = Random.nextFloat() * 2 * d - d
                    main.postDelayed(this, 60_000)
                }
            }
            main.postDelayed(shift, 60_000)
        }
        // Indicatore di stato in alto a destra (16 dp dai bordi, area di tocco 56 × 56 dp)
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Halba.px(this@MainActivity, 32f)
            setPadding(Halba.px(this@MainActivity, 12f), 0, Halba.px(this@MainActivity, 14f), 0)
        }
        val holder = FrameLayout(this).apply {
            minimumWidth = Halba.px(this@MainActivity, 56f)
            minimumHeight = Halba.px(this@MainActivity, 56f)
            addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        view.addView(holder, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, Halba.px(this@MainActivity, 4f), Halba.px(this@MainActivity, 4f), 0) })
        staffGesture(holder, view)
        // Non in carica: avviso grande in alto a sinistra (56 dp, 18 sp)
        val powerView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Halba.px(this@MainActivity, 56f)
            setPadding(Halba.px(this@MainActivity, 16f), Halba.px(this@MainActivity, 8f), Halba.px(this@MainActivity, 16f), Halba.px(this@MainActivity, 8f))
            background = Halba.rounded(this@MainActivity, Halba.PILL_POWER, 999f)
            addView(Icon(this@MainActivity, "plug", Halba.IDLE_BG), LinearLayout.LayoutParams(Halba.px(this@MainActivity, 24f), Halba.px(this@MainActivity, 24f)))
            addView(TextView(this@MainActivity).apply { text = s(R.string.terminal_ind_power); textSize = 18f; typeface = Halba.typeface(600); setTextColor(Halba.IDLE_BG) },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = Halba.px(this@MainActivity, 8f) })
        }
        view.addView(powerView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(Halba.px(this@MainActivity, 16f), Halba.px(this@MainActivity, 16f), Halba.px(this@MainActivity, 140f), 0)
        })
        // Marchio halba in basso al centro sul contenuto del merchant: cerchio di 40 dp nero al 55 %, marchio bianco 24 dp
        if (file != null) {
            val mark = FrameLayout(this).apply {
                minimumWidth = Halba.px(this@MainActivity, 56f)
                minimumHeight = Halba.px(this@MainActivity, 56f)
                addView(FrameLayout(this@MainActivity).apply {
                    background = Halba.circle(0x8C000000.toInt())
                    addView(HalbaMark(this@MainActivity, ring = Color.WHITE, dot = Color.WHITE), FrameLayout.LayoutParams(Halba.px(this@MainActivity, 24f), Halba.px(this@MainActivity, 24f), Gravity.CENTER))
                }, FrameLayout.LayoutParams(Halba.px(this@MainActivity, 40f), Halba.px(this@MainActivity, 40f), Gravity.CENTER))
                contentDescription = "halba"
            }
            view.addView(mark, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Halba.px(this@MainActivity, 8f) })
            staffGesture(mark, view)
        }
        show(view, dark = true)
        idleView = view
        player = exo
        statusPill = pill
        powerPill = powerView
        updateStatus()
    }

    private fun updateStatus() {
        val pill = statusPill as? LinearLayout ?: return
        pill.removeAllViews()
        if (online) {
            pill.background = Halba.rounded(this, Halba.PILL_BG, 999f)
            pill.addView(View(this).apply { background = Halba.circle(Halba.DOT_ONLINE) }, LinearLayout.LayoutParams(Halba.px(this, 12f), Halba.px(this, 12f)))
        } else {
            pill.background = Halba.rounded(this, Halba.PILL_OFFLINE, 999f)
            pill.addView(Icon(this, "wifi-off", Color.WHITE), LinearLayout.LayoutParams(Halba.px(this, 18f), Halba.px(this, 18f)))
        }
        val label = s(if (online) R.string.terminal_ind_online else R.string.terminal_ind_offline)
        pill.addView(TextView(this).apply { text = label; textSize = 16f; typeface = Halba.typeface(500); setTextColor(Color.WHITE) },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = Halba.px(this@MainActivity, 8f) })
        (pill.parent as? View)?.contentDescription = label
        powerPill?.visibility = if (isCharging()) View.GONE else View.VISIBLE
    }

    // G. Pressione lunga di 3 s sull'indicatore o sul marchio: l'anello compare dopo 0,5 s e si riempie.
    // Con TalkBack: azione "Open staff menu" sull'elemento
    @SuppressLint("ClickableViewAccessibility")
    private fun staffGesture(target: View, parent: FrameLayout) {
        ViewCompat.addAccessibilityAction(target, "Open staff menu") { _, _ -> staffPin(); true }
        val ring = HoldRing(this)
        var startedAt = 0L
        val tick = object : Runnable {
            override fun run() {
                val t = SystemClock.uptimeMillis() - startedAt
                if (t >= 3000) { ring.progress = 0f; parent.removeView(ring); staffPin(); return }
                ring.progress = if (t < 500) 0f else (t - 500) / 2500f
                main.postDelayed(this, 16)
            }
        }
        target.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startedAt = SystemClock.uptimeMillis()
                    val size = Halba.px(this, 72f)
                    val loc = IntArray(2).also { v.getLocationInWindow(it) }
                    val ploc = IntArray(2).also { parent.getLocationInWindow(it) }
                    parent.removeView(ring)
                    parent.addView(ring, FrameLayout.LayoutParams(size, size).apply {
                        leftMargin = loc[0] - ploc[0] + (ev.x - size / 2f).toInt()
                        topMargin = loc[1] - ploc[1] + (ev.y - size / 2f).toInt()
                    })
                    main.post(tick)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { main.removeCallbacks(tick); ring.progress = 0f; parent.removeView(ring) }
            }
            true
        }
    }

    // --- G. Menu del personale ---
    private fun staffPin() {
        if (config?.optBoolean("staff_pin_set") != true) { staffMenu(null); return }
        loop?.cancel()
        val u = light
        val pg = u.page(center = false)
        pg.header.addView(View(this), LinearLayout.LayoutParams(1, Halba.px(this, 24f)))
        u.add(pg.body, u.text(s(R.string.terminal_pin_title), Type.TITLE), top = 8f)
        u.add(pg.body, u.text(s(R.string.terminal_pin_enter), Type.TEXT), top = 8f)
        val dots = u.add(pg.body, PinDots(this, u.p, u.accent.accent), top = 24f, height = Halba.px(this, 32f))
        dots.contentDescription = s(R.string.terminal_pin_digits, "n" to 0)
        val msg = u.add(pg.body, u.text("", Type.LABEL, u.p.danger), top = 12f)
        var digits = ""
        val grid = GridLayout(this).apply { columnCount = 3 }
        val keys = mutableListOf<View>()
        fun setPad(on: Boolean) = keys.forEach { u.setEnabled(it, on) }
        fun locked(until: Long) {
            setPad(false)
            val tick = object : Runnable {
                override fun run() {
                    if (!msg.isAttachedToWindow) return
                    val left = ((until - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                    if (left == 0L) { prefs.edit().remove("pin_lock_until").apply(); msg.text = ""; setPad(true); return }
                    msg.text = "${s(R.string.terminal_pin_locked)}. ${s(R.string.terminal_pin_locked_text, "t" to "%d:%02d".format(left / 60, left % 60))}"
                    main.postDelayed(this, 1000)
                }
            }
            main.post(tick)
        }
        fun press(k: String) {
            msg.text = ""
            dots.error = false
            digits = if (k == "del") digits.dropLast(1) else (digits + k).take(6)
            dots.filled = digits.length
            dots.contentDescription = s(R.string.terminal_pin_digits, "n" to digits.length)
            dots.announceForAccessibility(dots.contentDescription)
            if (digits.length < 6) return
            val pin = digits
            setPad(false)
            lifecycleScope.launch {
                try {
                    api.verifyPin(pin)
                    staffMenu(pin)
                } catch (e: HalbaApi.ApiError) {
                    digits = ""
                    dots.filled = 0
                    if (e.status == 429) {
                        val until = System.currentTimeMillis() + 1000L * e.body.optInt("locked_seconds", 300)
                        prefs.edit().putLong("pin_lock_until", until).apply()
                        locked(until)
                    } else {
                        // PIN sbagliato: pallini rossi e scossa di 240 ms (niente scossa con movimento ridotto)
                        dots.error = true
                        if (!Halba.reducedMotion(this@MainActivity)) {
                            val d = Halba.dp(this@MainActivity, 8f)
                            dots.animate().translationX(d).setDuration(60).withEndAction {
                                dots.animate().translationX(-d).setDuration(60).withEndAction { dots.animate().translationX(0f).setDuration(120).start() }.start()
                            }.start()
                        }
                        msg.text = s(R.string.terminal_pin_wrong, "n" to e.body.optInt("attempts_left", 4))
                        announce(msg, msg.text.toString())
                        setPad(true)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    digits = ""; dots.filled = 0
                    msg.text = s(R.string.terminal_nonet_title)
                    setPad(true)
                }
            }
        }
        val keyH = Halba.px(this, Halba.PIN_KEY)
        val gap = Halba.px(this, 12f)
        val usable = minOf(resources.displayMetrics.widthPixels, Halba.px(this, Halba.MAX_WIDTH + 2 * Halba.MARGIN)) - 2 * Halba.px(this, Halba.MARGIN)
        val keyW = (usable - 2 * gap) / 3
        for ((i, k) in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "del").withIndex()) {
            val key: View = when (k) {
                "" -> View(this)
                "del" -> FrameLayout(this).apply {
                    background = Halba.rounded(this@MainActivity, u.p.surface, Halba.R_CONTROL)
                    addView(Icon(this@MainActivity, "delete", u.p.ink), FrameLayout.LayoutParams(Halba.px(this@MainActivity, 32f), Halba.px(this@MainActivity, 32f), Gravity.CENTER))
                    contentDescription = s(R.string.terminal_pin_delete)
                    isClickable = true
                    setOnClickListener { press("del") }
                }.also { keys += it }
                else -> TextView(this).apply {
                    text = k
                    textSize = 32f
                    typeface = Halba.typeface(600)
                    setTextColor(u.p.ink)
                    gravity = Gravity.CENTER
                    background = Halba.rounded(this@MainActivity, u.p.sunken, Halba.R_CONTROL)
                    isClickable = true
                    setOnClickListener { press(k) }
                }.also { keys += it }
            }
            grid.addView(key, GridLayout.LayoutParams().apply { width = keyW; height = keyH; setMargins(0, 0, if (i % 3 < 2) gap else 0, gap) })
        }
        u.add(pg.body, grid, top = 24f, width = -2)
        pg.bottom.addView(u.textButton(s(R.string.terminal_cancel)) { backToIdle() })
        show(pg.root)
        // Blocco che sopravvive al riavvio (salvato con l'ora)
        prefs.getLong("pin_lock_until", 0L).takeIf { it > System.currentTimeMillis() }?.let { locked(it) }
        inactivity(30_000)
    }

    private fun staffMenu(pin: String?) {
        loop?.cancel()
        val u = light
        val cfg = config ?: JSONObject()
        val pg = u.page(center = false)
        pg.header.addView(View(this), LinearLayout.LayoutParams(1, Halba.px(this, 24f)))
        u.add(pg.body, u.text(s(R.string.terminal_menu_title), Type.TITLE, center = false), gravity = Gravity.START)
        u.add(pg.body, u.space(8f))
        val since = if (lastContact == 0L) "—" else "${((System.currentTimeMillis() - lastContact) / 1000).coerceAtLeast(0)} s"
        val rows = listOf(
            R.string.terminal_i_store to (cfg.optJSONObject("store")?.optString("name") ?: "—"),
            R.string.terminal_i_name to (cfg.optJSONObject("terminal")?.optString("label") ?: "—"),
            R.string.terminal_i_id to (api.terminalId ?: "—"),
            R.string.terminal_i_psp to listOf(reader.label, readerInfo).filter { it.isNotEmpty() }.joinToString(" · "),
            R.string.terminal_i_app to BuildConfig.VERSION_NAME,
            R.string.terminal_i_status to s(if (online) R.string.terminal_ind_online else R.string.terminal_ind_offline),
            R.string.terminal_i_contact to since,
        )
        for ((i, r) in rows.withIndex()) {
            if (i > 0) pg.body.addView(u.divider())
            pg.body.addView(u.infoRow(s(r.first), r.second, mono = r.first == R.string.terminal_i_id))
        }
        u.add(pg.body, u.space(16f))
        pg.body.addView(u.menuRow("settings", s(R.string.terminal_menu_setup)) {
            confirm(pg.root, s(R.string.terminal_c_setup_t), s(R.string.terminal_c_setup_x), s(R.string.terminal_c_setup_ok)) { setupStore(pin, fromMenu = true) }
        })
        pg.body.addView(u.menuRow("refresh", s(R.string.terminal_menu_refresh)) {
            lifecycleScope.launch { runCatching { apply(api.config()) }; screen.clear(); runCatching { screen.sync(config?.optJSONObject("screen")) }; loadLogo(); backToIdle() }
        })
        pg.body.addView(u.menuRow("unlink", s(R.string.terminal_menu_unpair), danger = true) {
            confirm(pg.root, s(R.string.terminal_c_unpair_t), s(R.string.terminal_c_unpair_x), s(R.string.terminal_c_unpair_ok)) { forget() }
        })
        pg.body.addView(u.menuRow("log-out", s(R.string.terminal_menu_exit), danger = true) {
            confirm(pg.root, s(R.string.terminal_c_exit_t), s(R.string.terminal_c_exit_x), s(R.string.terminal_c_exit_ok)) { finishAndRemoveTask() }
        })
        pg.bottom.addView(u.textButton(s(R.string.terminal_menu_close)) { backToIdle() })
        show(pg.root)
        inactivity(120_000)
    }

    // Conferma come foglio dal basso (raggio 24 dp): l'azione distruttiva è il pulsante rosso, "Annulla" sotto
    private fun confirm(over: FrameLayout, title: String, text: String, ok: String, action: () -> Unit) {
        val u = light
        val scrim = View(this).apply { setBackgroundColor(0x99000000.toInt()); isClickable = true }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Halba.topRounded(this@MainActivity, u.p.surface, Halba.R_PANEL)
            val m = Halba.px(this@MainActivity, Halba.MARGIN)
            setPadding(m, m, m, m)
            isClickable = true
        }
        fun close() { over.removeView(scrim); over.removeView(sheet) }
        sheet.addView(u.text(title, Type.HEADLINE, center = false))
        u.add(sheet, u.text(text, Type.TEXT, center = false), top = 8f)
        u.add(sheet, u.space(8f))
        sheet.addView(u.mainButton(ok, danger = true) { close(); action() })
        sheet.addView(u.textButton(s(R.string.terminal_cancel)) { close() })
        scrim.setOnClickListener { close() }
        over.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        over.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        if (!Halba.reducedMotion(this)) { sheet.translationY = 600f; sheet.animate().translationY(0f).setDuration(240).start() }
        announce(sheet, title)
    }

    // --- C. Pagamento chiesto dalla cassa ---
    // Metodo scelto dalla cassa: si va dritti alla carta o al QR, senza la scelta
    private fun start(p: JSONObject) {
        when (val m = p.optString("method").takeIf { it.isNotEmpty() && it != "null" }) {
            null -> choose(p)
            "card" -> if (reader.canPayByCard(p)) payByCard(p) else result(p, "fail", "read")
            else -> payByQr(p, m)
        }
    }

    // Intestazione: nome del merchant (20 sp) e marchio di 28 dp; riferimento dell'ordine (18 sp); importo (52 sp)
    private fun header(u: Ui, pg: Ui.Page, p: JSONObject, amountLabel: Boolean = false, method: String? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Halba.px(this@MainActivity, 16f), Halba.px(this@MainActivity, 16f), Halba.px(this@MainActivity, 16f), 0)
        }
        val merchant = config?.optJSONObject("merchant")?.optString("name")?.takeIf { it.isNotEmpty() && it != "null" } ?: config?.optJSONObject("store")?.optString("name") ?: ""
        row.addView(u.text(merchant, Type.TEXT, u.p.ink, center = false).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(HalbaMark(this, ring = u.p.ink, dot = u.accent.accent), LinearLayout.LayoutParams(Halba.px(this, 28f), Halba.px(this, 28f)))
        pg.header.addView(row)
        val ref = p.optString("merchant_reference").takeIf { it.isNotEmpty() && it != "null" && it != "halba-setup-test" }
        if (ref != null) u.add(pg.body, u.text(s(R.string.terminal_ref, "r" to ref), Type.LABEL, u.p.muted))
        if (amountLabel) u.add(pg.body, u.text(s(R.string.terminal_card_amount_lbl), Type.LABEL, u.p.muted), top = 4f)
        if (method != null) u.add(pg.body, u.text(labels[method] ?: method, Type.HEADLINE), top = 4f)
        u.add(pg.body, u.amount(amountOf(p)), top = 4f)
    }

    private fun choose(p: JSONObject) {
        val u = light
        val pg = u.page(center = false)
        header(u, pg, p)
        val methods = p.optJSONArray("methods")
        val all = (0 until (methods?.length() ?: 0)).map { methods!!.getString(it) }
        if ("card" in all && reader.canPayByCard(p)) {
            // Carta: riquadro di 88 dp con il bordo nell'accento
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = Halba.px(this@MainActivity, Halba.METHOD_TILE)
                setPadding(Halba.px(this@MainActivity, 20f), 0, Halba.px(this@MainActivity, 20f), 0)
                background = Halba.rounded(this@MainActivity, u.p.surface, Halba.R_TILE, u.accent.accent)
                addView(Icon(this@MainActivity, "card", u.p.ink), LinearLayout.LayoutParams(Halba.px(this@MainActivity, 32f), Halba.px(this@MainActivity, 32f)))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(u.text(s(R.string.terminal_m_card), Type.HEADLINE, center = false))
                    addView(u.text(s(R.string.terminal_m_card_sub), Type.CAPTION, center = false))
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Halba.px(this@MainActivity, 16f) })
                isClickable = true
                contentDescription = "${s(R.string.terminal_m_card)}, ${s(R.string.terminal_m_card_sub)}"
                setOnClickListener { payByCard(p) }
            }
            u.add(pg.body, tile, top = 24f)
        }
        val qr = all.filter { it != "card" }
        if (qr.isNotEmpty()) {
            u.add(pg.body, u.text(s(R.string.terminal_or_qr), Type.LABEL, u.p.muted, center = false), top = 24f, gravity = Gravity.START)
            // Metodi con QR su due colonne (8 dp), l'ultimo dispari a tutta larghezza; i marchi ufficiali sono da inserire
            val grid = GridLayout(this).apply { columnCount = 2 }
            val gap = Halba.px(this, 8f)
            val usable = minOf(resources.displayMetrics.widthPixels, Halba.px(this, Halba.MAX_WIDTH + 2 * Halba.MARGIN)) - 2 * Halba.px(this, Halba.MARGIN)
            for ((i, m) in qr.withIndex()) {
                val full = i == qr.size - 1 && qr.size % 2 == 1
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = Halba.rounded(this@MainActivity, u.p.surface, Halba.R_TILE, u.p.borderStrong)
                    addView(Icon(this@MainActivity, "qr", u.p.ink), LinearLayout.LayoutParams(Halba.px(this@MainActivity, 28f), Halba.px(this@MainActivity, 28f)))
                    addView(u.text(labels[m] ?: m, Type.LABEL, u.p.ink))
                    isClickable = true
                    contentDescription = labels[m] ?: m
                    setOnClickListener { payByQr(p, m) }
                }
                grid.addView(tile, GridLayout.LayoutParams().apply {
                    width = if (full) usable else (usable - gap) / 2
                    height = Halba.px(this@MainActivity, Halba.METHOD_TILE)
                    if (full) columnSpec = GridLayout.spec(0, 2)
                    setMargins(0, 0, if (!full && i % 2 == 0) gap else 0, gap)
                })
            }
            u.add(pg.body, grid, top = 8f, width = -2)
        }
        pg.bottom.addView(u.textButton(s(R.string.terminal_cancel)) { cancelByCustomer(p) })
        sheet(pg.root)
    }

    // "Annulla" sul terminale: non chiede nulla, torna a riposo con l'esito "Pagamento annullato"
    private fun cancelByCustomer(p: JSONObject, vararg jobs: Job?) {
        jobs.forEach { it?.cancel() }
        collecting = false
        lifecycleScope.launch { runCatching { api.result(p.getString("id"), "canceled", "canceled", "Canceled on the terminal") } }
        result(p, "fail", "canceled")
    }

    // La cassa annulla mentre il cliente paga con la carta: si controlla ogni 2 s
    private fun watchTill(p: JSONObject, onCanceled: () -> Unit): Job = lifecycleScope.launch {
        while (isActive) {
            delay(2000)
            val st = runCatching { api.payment(p.getString("id")) }.getOrNull() ?: continue
            if (st.optString("status") == "canceled") { onCanceled(); return@launch }
        }
    }

    // --- D. Carta: "Avvicina, inserisci o striscia", passaggio al lettore del PSP, ritorno ed elaborazione ---
    private fun payByCard(p: JSONObject, test: Boolean = false) {
        val u = light
        val pg = u.page()
        header(u, pg, p, amountLabel = true)
        u.add(pg.body, CardArt(this, u.p, u.accent.accent), top = 24f, width = Halba.px(this, 200f), height = Halba.px(this, 128f))
        u.add(pg.body, u.text(s(R.string.terminal_card_tap), Type.TITLE).apply { textSize = 30f }, top = 24f)
        u.add(pg.body, waitingRow(u, s(R.string.terminal_card_open)), top = 16f, width = -2)
        var job: Job? = null
        var watch: Job? = null
        pg.bottom.addView(u.textButton(s(R.string.terminal_cancel)) { cancelByCustomer(p, job, watch) })
        if (idleView != null) sheet(pg.root) else show(pg.root)
        announce(pg.body, "${s(R.string.terminal_card_tap)}. ${amountOf(p)}")
        val id = p.getString("id")
        watch = watchTill(p) { job?.cancel(); collecting = false; result(p, "till", null, test) }
        onReturn = { processing(u, p) }
        job = lifecycleScope.launch {
            collecting = true
            try {
                withTimeout(60_000) { reader.collect(p) }
                collecting = false
                processing(u, p)
                val r = api.result(id, "confirmed")
                watch.cancel()
                if (r.optString("status") == "succeeded") result(p, "ok", null, test, r.optJSONObject("card"))
                else result(p, "fail", reason("${r.optJSONObject("failure")?.optString("code")} ${r.optJSONObject("failure")?.optString("message")}"), test)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                collecting = false; watch.cancel()
                runCatching { api.result(id, "failed", "timeout", "Card wait timed out") }
                result(p, "fail", "timeout", test)
            } catch (e: kotlinx.coroutines.CancellationException) {
                collecting = false
            } catch (e: Exception) {
                collecting = false; watch.cancel()
                runCatching { api.result(id, "failed", "card_error", e.message) }
                result(p, "fail", reason(e.message), test)
            }
        }
    }

    // Motivo per il cliente, senza codici né messaggi dell'acquirer
    private fun reason(raw: String?): String {
        val t = raw.orEmpty().lowercase()
        return when {
            "cancel" in t -> "canceled"
            "timeout" in t || "timed out" in t || "expired" in t -> "timeout"
            "declin" in t || "insufficient" in t || "do_not_honor" in t -> "declined"
            else -> "read"
        }
    }

    private fun processing(u: Ui, p: JSONObject) {
        val pg = u.page()
        header(u, pg, p)
        u.add(pg.body, u.loader(72f), top = 32f)
        u.add(pg.body, u.text(s(R.string.terminal_processing), Type.HEADLINE), top = 16f)
        u.add(pg.body, u.text(s(R.string.terminal_processing_sub), Type.TEXT), top = 8f)
        show(pg.root)
        announce(pg.body, s(R.string.terminal_processing), assertive = false)
    }

    // --- E. QR: 288 dp, nero su bianco, margine bianco di 20 dp; 15 minuti; luminosità al massimo ---
    private fun payByQr(p: JSONObject, method: String) {
        val u = light
        val id = p.getString("id")
        var job: Job? = null
        job = lifecycleScope.launch {
            val r = try { api.qr(id, method) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { result(p, "fail", "read"); return@launch }
            val pg = u.page(center = false)
            header(u, pg, p, method = method)
            val qrName = labels[method] ?: method
            u.add(pg.body, u.text(s(R.string.terminal_qr_scan, "m" to qrName), Type.HEADLINE), top = 8f)
            localLine[method]?.let { u.add(pg.body, u.text(it, Type.LABEL, u.p.muted), top = 4f) }
            val size = Halba.px(this@MainActivity, Halba.QR)
            val margin = Halba.px(this@MainActivity, 20f)
            val box = FrameLayout(this@MainActivity).apply { setBackgroundColor(Color.WHITE); isFocusable = true }
            val img = ImageView(this@MainActivity).apply { setImageBitmap(qrBitmap(r.optString("qr"), size - 2 * margin)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            box.addView(img, FrameLayout.LayoutParams(-1, -1).apply { setMargins(margin, margin, margin, margin) })
            val over = u.text("", Type.HEADLINE, u.p.ink).apply { gravity = Gravity.CENTER; visibility = View.GONE }
            box.addView(over, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            u.add(pg.body, box, top = 16f, width = size, height = size)
            val timeRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val leftLbl = u.text(s(R.string.terminal_qr_left), Type.LABEL, u.p.muted, center = false)
            val clock = u.text("15:00", Type.TEXT, u.p.ink, center = false, mono = true).apply { gravity = Gravity.END }
            timeRow.addView(leftLbl, LinearLayout.LayoutParams(0, -2, 1f))
            timeRow.addView(clock)
            u.add(pg.body, timeRow, top = 16f, width = size)
            val bar = u.add(pg.body, Bar(this@MainActivity, u.p.sunken, u.accent.accent).apply { fraction = 1f }, top = 8f, width = size, height = Halba.px(this@MainActivity, 8f))
            val actions = pg.bottom
            actions.addView(u.textButton(s(R.string.terminal_cancel)) { cancelByCustomer(p, job) })
            sheet(pg.root)
            window.attributes = window.attributes.apply { screenBrightness = 1f }
            val started = SystemClock.elapsedRealtime()
            val total = 15 * 60_000L
            var lastPoll = 0L
            while (isActive) {
                val left = (total - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0)
                val mmss = "%d:%02d".format(left / 60_000, (left / 1000) % 60)
                clock.text = mmss
                bar.fraction = left.toFloat() / total
                box.contentDescription = s(R.string.terminal_qr_alt, "m" to qrName, "t" to mmss)
                if (left in 1..60_000) {
                    // Ultimo minuto: tempo in giallo con "Sta per scadere"
                    leftLbl.text = s(R.string.terminal_qr_soon); leftLbl.setTextColor(u.p.warn); clock.setTextColor(u.p.warn); bar.fill = u.p.warn
                }
                if (left == 0L) {
                    // Scaduto: QR all'8 %, "Nuovo codice QR" con altri 15 minuti
                    img.alpha = 0.08f
                    over.text = s(R.string.terminal_qr_expired)
                    over.visibility = View.VISIBLE
                    announce(over, s(R.string.terminal_qr_expired))
                    actions.addView(u.mainButton(s(R.string.terminal_qr_new)) { payByQr(p, method) }, 0)
                    return@launch
                }
                if (SystemClock.elapsedRealtime() - lastPoll >= 2000) {
                    lastPoll = SystemClock.elapsedRealtime()
                    val st = runCatching { api.payment(id) }.getOrNull()
                    when (st?.optString("status")) {
                        "succeeded" -> { result(p, "ok", null, card = JSONObject().put("entry_mode", "qr").put("method", method)); return@launch }
                        "canceled" -> { result(p, "till", null); return@launch }
                        "failed" -> { result(p, "fail", reason(st.optJSONObject("failure")?.optString("code"))); return@launch }
                    }
                }
                delay(1000)
            }
        }
    }

    private fun resetBrightness() {
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }

    // --- F. Esito: torna a riposo da solo dopo 4 s (barra di 8 dp e conto alla rovescia) ---
    private fun result(p: JSONObject, kind: String, why: String?, test: Boolean = false, card: JSONObject? = null) {
        collecting = false
        onReturn = null
        val u = light
        val pg = u.page()
        val spoken: String
        when (kind) {
            "ok" -> {
                u.add(pg.body, u.badge("success", 120f))
                val title = s(if (test) R.string.terminal_test_ok else R.string.terminal_ok_title)
                u.add(pg.body, u.text(title, Type.TITLE), top = 24f)
                u.add(pg.body, u.amount(amountOf(p), result = true), top = 8f)
                val mode = card?.optString("entry_mode").orEmpty().lowercase()
                val read = when {
                    "qr" in mode -> s(R.string.terminal_ok_qr)
                    "contactless" in mode || "nfc" in mode -> s(R.string.terminal_ok_contactless)
                    mode.isNotEmpty() -> s(R.string.terminal_ok_chip)
                    else -> null
                }
                val what = when {
                    card?.optString("last4").orEmpty().isNotEmpty() -> "${card!!.optString("brand").replaceFirstChar { it.uppercase() }} •••• ${card.optString("last4")}"
                    card?.optString("method").orEmpty().isNotEmpty() -> labels[card!!.optString("method")] ?: card.optString("method")
                    else -> null
                }
                val chip = listOfNotNull(what, read).joinToString(" · ")
                if (chip.isNotEmpty()) {
                    u.add(pg.body, u.text(chip, Type.LABEL, u.p.ink).apply {
                        background = Halba.rounded(this@MainActivity, u.p.sunken, 999f)
                        setPadding(Halba.px(this@MainActivity, 16f), Halba.px(this@MainActivity, 8f), Halba.px(this@MainActivity, 16f), Halba.px(this@MainActivity, 8f))
                    }, top = 16f, width = -2)
                }
                spoken = listOfNotNull(title, amountOf(p), what).joinToString(", ")
            }
            "till" -> {
                u.add(pg.body, u.badge("neutral"))
                u.add(pg.body, u.text(s(R.string.terminal_canceled_till), Type.TITLE), top = 24f)
                u.add(pg.body, u.text(s(R.string.terminal_canceled_till_do), Type.TEXT), top = 8f)
                spoken = s(R.string.terminal_canceled_till)
            }
            else -> {
                val (t, d) = when (why) {
                    "declined" -> R.string.terminal_fail_declined to R.string.terminal_fail_declined_do
                    "canceled" -> R.string.terminal_fail_canceled to R.string.terminal_fail_canceled_do
                    "timeout" -> R.string.terminal_fail_timeout to R.string.terminal_fail_timeout_do
                    else -> R.string.terminal_fail_read to R.string.terminal_fail_read_do
                }
                u.add(pg.body, u.badge("danger"))
                u.add(pg.body, u.text(s(t), Type.TITLE), top = 24f)
                u.add(pg.body, u.text(s(d), Type.TEXT), top = 8f)
                spoken = "${s(t)}. ${s(d)}"
            }
        }
        val bar = Bar(this, u.p.sunken, u.accent.accent).apply { fraction = 1f }
        pg.bottom.addView(bar, LinearLayout.LayoutParams(-1, Halba.px(this, 8f)))
        val back = u.add(pg.bottom, u.text(s(R.string.terminal_ok_back_in, "s" to 4), Type.CAPTION), top = 8f)
        show(pg.root)
        announce(pg.body, spoken)
        val started = SystemClock.elapsedRealtime()
        val tick = object : Runnable {
            override fun run() {
                val left = 4000 - (SystemClock.elapsedRealtime() - started)
                if (left <= 0) {
                    // Dopo il pagamento di prova si torna al passo "Fatto" della configurazione
                    if (test) { busy = false; setupDone() } else backToIdle()
                    return
                }
                bar.fraction = left / 4000f
                back.text = s(R.string.terminal_ok_back_in, "s" to ((left + 999) / 1000))
                main.postDelayed(this, 50)
            }
        }
        main.post(tick)
    }

    // QR senza logo al centro e con moduli quadrati; il margine bianco lo dà il riquadro
    private fun qrBitmap(content: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
        val pixels = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }
}
