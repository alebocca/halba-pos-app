package ai.halba.pos

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.LinearInterpolator

// Token del design system di halba (artifact "Halba Design System", project/tokens.json), tema chiaro e scuro.
// Le schermate del terminale (gruppo Terminal) arrivano dal design system: qui solo i valori condivisi.
object Halba {
    // Colori
    const val SURFACE = 0xFFF9F9F7.toInt()
    const val SURFACE_RAISED = 0xFFFFFFFF.toInt()
    const val SURFACE_SUNKEN = 0xFFEFEFEB.toInt()
    const val INK = 0xFF0E0E10.toInt()
    const val INK_MUTED = 0xFF5C5C66.toInt()
    const val BORDER = 0xFFDEDED8.toInt()
    const val BORDER_STRONG = 0xFF8A8A93.toInt()
    const val BRAND = 0xFF5B47E0.toInt()
    const val BRAND_HOVER = 0xFF4A38C4.toInt()
    const val ON_BRAND = 0xFFFFFFFF.toInt()
    const val SUCCESS = 0xFF0B7A55.toInt()
    const val SUCCESS_TINT = 0xFFDDF5EA.toInt()
    const val DANGER = 0xFFB42318.toInt()
    const val DANGER_TINT = 0xFFFDE7E4.toInt()
    const val WARNING = 0xFF6B5200.toInt()
    const val WARNING_TINT = 0xFFFFF1A8.toInt()
    // Scuro: schermo a riposo predefinito
    const val DARK_SURFACE = 0xFF0F0F14.toInt()
    const val DARK_INK = 0xFFF4F4F1.toInt()
    const val DARK_INK_MUTED = 0xFFA8A8B3.toInt()
    const val DARK_DANGER = 0xFFFF8A7D.toInt()

    // Raggi e spaziature (dp)
    const val RADIUS_MD = 12f
    const val RADIUS_LG = 20f
    const val SPACE_2 = 8
    const val SPACE_4 = 16
    const val SPACE_5 = 24
    const val SPACE_6 = 32

    fun dp(context: Context, v: Float) = v * context.resources.displayMetrics.density

    // Sfondi dei pulsanti: primario (brand) e secondario (bordo forte), raggio md
    fun primary(context: Context) = GradientDrawable().apply { setColor(BRAND); cornerRadius = dp(context, RADIUS_MD) }
    fun secondary(context: Context) = GradientDrawable().apply {
        setColor(SURFACE_RAISED)
        cornerRadius = dp(context, RADIUS_MD)
        setStroke(dp(context, 1.5f).toInt(), BORDER_STRONG)
    }
    fun pill(context: Context, fill: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(context, 999f) }
}

// Marchio di halba (project/brand/halba-mark.svg): anello aperto e punto. Con spin = true è il loader del marchio
class HalbaMark(context: Context, private val ring: Int = Halba.INK, private val dot: Int = Halba.BRAND, spin: Boolean = false) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ring }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = dot }
    private val box = RectF()
    private var angle = 66f
    private val anim: ValueAnimator? = if (spin) ValueAnimator.ofFloat(66f, 426f).apply {
        duration = 1300
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; invalidate() }
    } else null

    init { contentDescription = "halba" }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim?.start() }
    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        // Proporzioni del file SVG: viewBox 683, raggio 280, spessore 122, punto 60, arco di 310° su 360°
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
