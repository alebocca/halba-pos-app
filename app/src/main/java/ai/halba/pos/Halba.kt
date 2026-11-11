package ai.halba.pos

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser

// Token del gruppo Terminal del design system (artifact "Halba Design System", project/terminal.md, sezione 3).
// Chiaro per configurazione, pagamento, esito e personale; scuro per riposo, avvio e stati di sistema: lo decide la
// schermata, mai il tema del dispositivo.
class Palette(
    val surface: Int, val sunken: Int, val ink: Int, val muted: Int, val border: Int, val borderStrong: Int,
    val brand: Int, val onBrand: Int, val tint: Int,
    val success: Int, val successBg: Int, val danger: Int, val dangerBg: Int, val warn: Int, val warnBg: Int,
)

object Halba {
    val LIGHT = Palette(
        surface = 0xFFFFFFFF.toInt(), sunken = 0xFFF1F1EE.toInt(), ink = 0xFF0E0E10.toInt(), muted = 0xFF4A4A55.toInt(),
        border = 0xFFC9C9C2.toInt(), borderStrong = 0xFF6F6F79.toInt(), brand = 0xFF5B47E0.toInt(), onBrand = 0xFFFFFFFF.toInt(),
        tint = 0xFFD4CEFB.toInt(), success = 0xFF07624A.toInt(), successBg = 0xFFDDF5EA.toInt(), danger = 0xFFA8190F.toInt(),
        dangerBg = 0xFFFDE7E4.toInt(), warn = 0xFF6B5200.toInt(), warnBg = 0xFFFFF1A8.toInt(),
    )
    val DARK = Palette(
        surface = 0xFF0E0E10.toInt(), sunken = 0xFF1C1C22.toInt(), ink = 0xFFF4F4F1.toInt(), muted = 0xFFC4C4CC.toInt(),
        border = 0xFF33333B.toInt(), borderStrong = 0xFF8A8A96.toInt(), brand = 0xFFA99CFF.toInt(), onBrand = 0xFF0E0E10.toInt(),
        tint = 0xFF2A2650.toInt(), success = 0xFF5FD3A0.toInt(), successBg = 0xFF0F2F25.toInt(), danger = 0xFFFF8A80.toInt(),
        dangerBg = 0xFF3A1512.toInt(), warn = 0xFFFFD400.toInt(), warnBg = 0xFF3A3000.toInt(),
    )

    // Colori fissi dello schermo a riposo (3.1)
    const val PILL_BG = 0xC70E0E10.toInt() //    #0E0E10 al 78 %
    const val PILL_OFFLINE = 0xFFB42318.toInt()
    const val PILL_POWER = 0xFFFFD400.toInt()
    const val DOT_ONLINE = 0xFF5FD3A0.toInt()
    const val IDLE_BG = 0xFF0E0E10.toInt()

    // Misure (3.4), in dp
    const val MARGIN = 24f
    const val GAP = 16f
    const val ROW_GAP = 8f
    const val R_CONTROL = 16f
    const val R_TILE = 20f
    const val R_PANEL = 24f
    const val MAIN_BUTTON = 64f
    const val BUTTON = 56f
    const val CHOICE_ROW = 72f
    const val MENU_ROW = 64f
    const val INPUT = 64f
    const val HEADER = 72f
    const val METHOD_TILE = 88f
    const val QR = 288f
    const val PIN_KEY = 72f
    const val MAX_WIDTH = 400f

    // Tipografia (3.2), in sp: dimensione e interlinea
    enum class Type(val size: Float, val line: Float, val weight: Int) {
        AMOUNT(52f, 60f, 700), AMOUNT_RESULT(48f, 56f, 700), CODE(72f, 80f, 500), TITLE(28f, 34f, 700),
        HEADLINE(24f, 30f, 600), TEXT(20f, 28f, 400), LABEL(18f, 24f, 500), CAPTION(16f, 22f, 400),
        BUTTON_MAIN(22f, 28f, 600), BUTTON(20f, 28f, 600),
    }

    fun dp(context: Context, v: Float) = v * context.resources.displayMetrics.density
    fun px(context: Context, v: Float) = dp(context, v).toInt()

    // Inter e DM Mono non sono ancora nell'APK (servono i TTF): font di sistema, come previsto dalla guida
    fun typeface(weight: Int, mono: Boolean = false): Typeface {
        val base = if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF
        return if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, weight, false)
        else Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }

    fun rounded(context: Context, fill: Int, radius: Float, stroke: Int? = null, strokeDp: Float = 2f) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(context, radius)
        if (stroke != null) setStroke(px(context, strokeDp), stroke)
    }
    fun topRounded(context: Context, fill: Int, radius: Float) = GradientDrawable().apply {
        setColor(fill)
        val r = dp(context, radius)
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }
    fun circle(fill: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }

    // "Rimuovi animazioni" del sistema (sezione 9): letto a ogni cambio di schermata
    fun reducedMotion(context: Context) =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    // Colore del merchant (3.3): on-primary bianco o nero con il contrasto maggiore (almeno 4.5:1, altrimenti viola
    // di halba); accento = primario se ha 3:1 sulla superficie, altrimenti scurito a passi
    class Accent(val primary: Int, val onPrimary: Int, val accent: Int)

    fun accent(merchant: String?): Accent {
        val brand = LIGHT.brand
        val c = runCatching { merchant?.let { Color.parseColor(it) } }.getOrNull() ?: return Accent(brand, Color.WHITE, brand)
        val white = ColorUtils.calculateContrast(Color.WHITE, c or 0xFF000000.toInt())
        val black = ColorUtils.calculateContrast(Color.BLACK, c or 0xFF000000.toInt())
        if (maxOf(white, black) < 4.5) return Accent(brand, Color.WHITE, brand)
        var a = c or 0xFF000000.toInt()
        var i = 0
        while (ColorUtils.calculateContrast(a, LIGHT.surface) < 3.0 && i++ < 20) a = ColorUtils.blendARGB(a, Color.BLACK, 0.08f)
        return Accent(c or 0xFF000000.toInt(), if (white >= black) Color.WHITE else Color.BLACK, a)
    }
}

// Marchio di halba (project/brand/halba-mark.svg): anello aperto e punto. Con spin = true è il loader "orbit":
// il punto resta al centro e l'anello gira (1,4 s); con movimento ridotto il marchio resta fermo.
class HalbaMark(context: Context, private val ring: Int = Halba.LIGHT.ink, private val dot: Int = Halba.LIGHT.brand, spin: Boolean = false) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ring }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = dot }
    private val box = RectF()
    private var angle = 66f
    private val anim: ValueAnimator? = if (spin && !Halba.reducedMotion(context)) ValueAnimator.ofFloat(66f, 426f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; invalidate() }
    } else null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim?.start() }
    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        // Proporzioni del file SVG: viewBox 683, raggio 280, spessore 122, punto 60, arco di 310°
        val s = minOf(width, height).toFloat()
        val k = s / 683f
        val cx = width / 2f
        val cy = height / 2f
        stroke.strokeWidth = 122f * k
        val r = 280f * k
        box.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(box, angle, 310f, false, stroke)
        canvas.drawCircle(cx, cy, 60f * k, fill)
    }
}

// Icone Lucide (contorno, tratto 2 dp arrotondato) disegnate dai loro percorsi in un riquadro di 24
class Icon(context: Context, name: String, var color: Int, private val strokeDp: Float = 2f) : View(context) {
    private val paths = (LUCIDE[name] ?: emptyList()).map { PathParser.createPathFromPathData(it) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val m = Matrix()
    private val tmp = Path()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height) / 24f
        m.setScale(s, s)
        m.postTranslate((width - 24f * s) / 2f, (height - 24f * s) / 2f)
        paint.color = color
        paint.strokeWidth = Halba.dp(context, strokeDp) * (minOf(width, height) / Halba.dp(context, 28f)).coerceIn(0.8f, 1.8f)
        for (p in paths) { tmp.set(p); tmp.transform(m); canvas.drawPath(tmp, paint) }
    }

    companion object {
        private const val CIRCLE = "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20"
        val LUCIDE = mapOf(
            "check" to listOf("M20 6 9 17l-5-5"),
            "x" to listOf("M18 6 6 18", "m6 6 12 12"),
            "minus" to listOf("M5 12h14"),
            "alert" to listOf(CIRCLE, "M12 8v4", "M12 16h.01"),
            "info" to listOf(CIRCLE, "M12 16v-4", "M12 8h.01"),
            "clock" to listOf(CIRCLE, "M12 6v6l4 2"),
            "chevron-left" to listOf("m15 18-6-6 6-6"),
            "chevron-right" to listOf("m9 18 6-6-6-6"),
            "search" to listOf("M11 3a8 8 0 1 0 0 16a8 8 0 1 0 0-16", "m21 21-4.3-4.3"),
            "card" to listOf("M4 5h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M2 10h20"),
            "qr" to listOf("M4 3h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M16 3h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M4 15h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1z", "M21 16h-3a2 2 0 0 0-2 2v3", "M21 21v.01", "M12 7v3a2 2 0 0 1-2 2H7", "M3 12h.01", "M12 3h.01", "M12 16v.01", "M16 12h1", "M21 12v.01", "M12 21v-1"),
            "wifi-off" to listOf("M12 20h.01", "M8.5 16.429a5 5 0 0 1 7 0", "M5 12.859a10 10 0 0 1 5.17-2.69", "M19 12.859a10 10 0 0 0-2.007-1.523", "M2 8.82a15 15 0 0 1 4.177-2.643", "M22 8.82a15 15 0 0 0-11.288-3.764", "m2 2 20 20"),
            "plug" to listOf("M12 22v-5", "M9 8V2", "M15 8V2", "M18 8v5a4 4 0 0 1-4 4h-4a4 4 0 0 1-4-4V8Z"),
            "delete" to listOf("M10 5a2 2 0 0 0-1.344.519l-6.328 5.74a1 1 0 0 0 0 1.481l6.328 5.741A2 2 0 0 0 10 19h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2z", "m12 9 6 6", "m18 9-6 6"),
            "refresh" to listOf("M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5", "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5"),
            "settings" to listOf("M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6", "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"),
            "unlink" to listOf("m18.84 12.25 1.72-1.71h-.02a5.004 5.004 0 0 0-.12-7.07 5.006 5.006 0 0 0-6.95 0l-1.72 1.71", "m5.17 11.75-1.71 1.71a5.004 5.004 0 0 0 .12 7.07 5.006 5.006 0 0 0 6.95 0l1.71-1.71", "M8 2v3", "M2 8h3", "M16 19v3", "M19 16h3"),
            "log-out" to listOf("M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4", "m16 17 5-5-5-5", "M21 12H9"),
            "power" to listOf("M12 2v10", "M18.4 6.6a9 9 0 1 1-12.77.04"),
        )
    }
}

// Illustrazione della carta (128 dp): carta con chip e onde del contactless
class CardArt(context: Context, private val p: Palette, private val accent: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cw = minOf(w, h * 1.58f) * 0.92f
        val ch = cw / 1.58f
        val left = (w - cw) / 2f
        val top = (h - ch) / 2f
        val k = cw / 200f
        paint.style = Paint.Style.FILL
        paint.color = accent
        r.set(left, top, left + cw, top + ch)
        canvas.drawRoundRect(r, 14f * k, 14f * k, paint)
        paint.color = 0xFFE9D8A6.toInt() //  chip
        r.set(left + 22 * k, top + 44 * k, left + 58 * k, top + 72 * k)
        canvas.drawRoundRect(r, 5 * k, 5 * k, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 6 * k
        paint.color = 0xE6FFFFFF.toInt()
        val cx = left + 160 * k
        val cy = top + 58 * k
        for (i in 1..3) {
            val rr = (10 + i * 10) * k
            r.set(cx - rr, cy - rr, cx + rr, cy + rr)
            canvas.drawArc(r, -45f, 90f, false, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = 0x66FFFFFF
        r.set(left + 22 * k, top + 98 * k, left + 130 * k, top + 108 * k)
        canvas.drawRoundRect(r, 5 * k, 5 * k, paint)
    }
}

// Barra di avanzamento (12 dp configurazione, 8 dp QR ed esito); fraction da 0 a 1
class Bar(context: Context, private val track: Int, var fill: Int) : View(context) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        paint.color = track
        r.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(r, h / 2, h / 2, paint)
        paint.color = fill
        r.set(0f, 0f, width * fraction, h)
        if (fraction > 0f) canvas.drawRoundRect(r, h / 2, h / 2, paint)
    }
}

// Avanzamento dei passi della configurazione: segmenti di 6 dp con 4 dp di spazio, i finiti nell'accento
class Steps(context: Context, private val total: Int, private val done: Int, private val track: Int, private val fill: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val gap = Halba.dp(context, 4f)
        val w = (width - gap * (total - 1)) / total
        val h = height.toFloat()
        for (i in 0 until total) {
            paint.color = if (i < done) fill else track
            val x = i * (w + gap)
            r.set(x, 0f, x + w, h)
            canvas.drawRoundRect(r, h / 2, h / 2, paint)
        }
    }
}

// Pallini del PIN (24 dp, 16 dp di spazio): pieni quanti le cifre inserite, rossi se il PIN è sbagliato
class PinDots(context: Context, private val p: Palette, private val accent: Int) : View(context) {
    var filled = 0
        set(v) { field = v; invalidate() }
    var error = false
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val d = Halba.dp(context, 24f)
        val gap = Halba.dp(context, 16f)
        val total = 6 * d + 5 * gap
        var x = (width - total) / 2f + d / 2
        val cy = height / 2f
        for (i in 0 until 6) {
            val on = i < filled
            paint.style = if (on) Paint.Style.FILL else Paint.Style.STROKE
            paint.strokeWidth = Halba.dp(context, 2f)
            paint.color = if (error) p.danger else if (on) p.ink else p.borderStrong
            canvas.drawCircle(x, cy, d / 2 - paint.strokeWidth / 2, paint)
            x += d + gap
        }
    }
}

// Anello bianco di 4 dp che si riempie durante la pressione lunga del menu del personale
class HoldRing(context: Context) : View(context) {
    var progress = 0f
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66000000 }
    private val r = RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f) return
        val sw = Halba.dp(context, 4f)
        paint.strokeWidth = sw
        shadow.strokeWidth = sw + Halba.dp(context, 2f)
        r.set(sw, sw, width - sw, height - sw)
        canvas.drawArc(r, -90f, 360f * progress, false, shadow)
        canvas.drawArc(r, -90f, 360f * progress, false, paint)
    }
}
