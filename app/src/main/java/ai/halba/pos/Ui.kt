package ai.halba.pos

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import ai.halba.pos.Halba.Type

// Componenti del gruppo Terminal costruiti con le View di Android, con le misure di project/terminal.md (3.4).
// Una pagina ha un corpo che scorre (al centro o dall'alto) e le azioni fisse in basso; contenuto largo al più 400 dp.
class Ui(private val ctx: Context, val p: Palette, val accent: Halba.Accent) {
    private fun dp(v: Float) = Halba.px(ctx, v)
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    class Page(val root: FrameLayout, val header: LinearLayout, val body: LinearLayout, val bottom: LinearLayout)

    fun page(center: Boolean = true, background: Int = p.surface): Page {
        val root = FrameLayout(ctx).apply { setBackgroundColor(background) }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (center) Gravity.CENTER else Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(Halba.MARGIN), dp(Halba.MARGIN), dp(Halba.MARGIN), dp(Halba.MARGIN))
        }
        val scroll = ScrollView(ctx).apply { isFillViewport = true; addView(body, FrameLayout.LayoutParams(match, wrap)) }
        val bottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Halba.MARGIN), 0, dp(Halba.MARGIN), dp(Halba.MARGIN))
        }
        column.addView(header, LinearLayout.LayoutParams(match, wrap))
        column.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
        column.addView(bottom, LinearLayout.LayoutParams(match, wrap))
        // Schermi più larghi: colonna di 400 dp al centro
        root.addView(column, FrameLayout.LayoutParams(match, match, Gravity.CENTER_HORIZONTAL).also { lp ->
            val w = ctx.resources.displayMetrics.widthPixels
            if (w > dp(Halba.MAX_WIDTH + 2 * Halba.MARGIN)) lp.width = dp(Halba.MAX_WIDTH + 2 * Halba.MARGIN)
        })
        return Page(root, header, body, bottom)
    }

    // --- Testi ---
    fun text(s: CharSequence, type: Type, color: Int = if (type == Type.TEXT || type == Type.CAPTION) p.muted else p.ink, center: Boolean = true, mono: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, type.size)
        TextViewCompat.setLineHeight(this, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, type.line, resources.displayMetrics).toInt())
        typeface = Halba.typeface(type.weight, mono)
        setTextColor(color)
        gravity = if (center) Gravity.CENTER_HORIZONTAL else Gravity.START
        if (type == Type.TITLE || type == Type.AMOUNT) letterSpacing = -0.02f
        if (type == Type.TITLE || type == Type.HEADLINE) ViewCompat.setAccessibilityHeading(this, true)
    }

    // Importo su una riga: scende fino a 36 sp per starci, non va mai a capo e non si tronca
    fun amount(s: String, result: Boolean = false) = text(s, if (result) Type.AMOUNT_RESULT else Type.AMOUNT).apply {
        maxLines = 1
        fontFeatureSettings = "tnum"
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 36, if (result) 48 else 52, 1, TypedValue.COMPLEX_UNIT_SP)
        layoutParams = LinearLayout.LayoutParams(match, dp(if (result) 56f else 60f))
    }

    fun space(h: Float) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    // Misure: quelle passate, altrimenti quelle già impostate sulla vista (loader, cerchi, spazi), altrimenti tutta la larghezza
    fun <T : View> add(parent: LinearLayout, v: T, top: Float = 0f, width: Int? = null, height: Int? = null, gravity: Int = Gravity.CENTER_HORIZONTAL): T {
        val own = v.layoutParams
        parent.addView(v, LinearLayout.LayoutParams(width ?: own?.width ?: match, height ?: own?.height ?: wrap).apply { topMargin = dp(top); this.gravity = gravity })
        return v
    }

    // --- Pulsanti ---
    // Principale: 64 dp, testo 22 sp, raggio 16, pieno nell'accento (o rosso per le azioni distruttive)
    fun mainButton(label: String, danger: Boolean = false, onClick: () -> Unit) = button(label, Halba.MAIN_BUTTON, Type.BUTTON_MAIN,
        fill = if (danger) p.danger else accent.primary, textColor = if (danger) Color.WHITE else accent.onPrimary, stroke = null, onClick = onClick)

    // Altri: 56 dp, testo 20 sp, bordo forte
    fun button(label: String, onClick: () -> Unit) = button(label, Halba.BUTTON, Type.BUTTON, p.surface, p.ink, p.borderStrong, onClick)

    // Testuale (Annulla): 56 dp, senza bordo
    fun textButton(label: String, onClick: () -> Unit) = button(label, Halba.BUTTON, Type.BUTTON, Color.TRANSPARENT, p.ink, null, onClick)

    private fun button(label: String, h: Float, type: Type, fill: Int, textColor: Int, stroke: Int?, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, type.size)
        typeface = Halba.typeface(600)
        setTextColor(textColor)
        gravity = Gravity.CENTER
        minHeight = dp(h)
        setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        background = Halba.rounded(ctx, fill, Halba.R_CONTROL, stroke)
        isClickable = true
        isFocusable = true
        ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        })
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(Halba.ROW_GAP) }
    }

    fun setEnabled(v: View, on: Boolean) { v.isEnabled = on; v.alpha = if (on) 1f else 0.4f }

    // --- Righe ---
    // Scelta (lingua, negozio): 72 dp, bordo 2 dp; selezionata con fondo tint e bordo nell'accento
    fun choiceRow(title: String, sub: String? = null, selected: Boolean, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(Halba.CHOICE_ROW)
        setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
        background = Halba.rounded(ctx, if (selected) p.tint else p.surface, Halba.R_CONTROL, if (selected) accent.accent else p.border)
        addView(text(title, Type.LABEL, p.ink, center = false))
        if (sub != null) addView(text(sub, Type.CAPTION, center = false))
        isClickable = true
        isSelected = selected
        contentDescription = listOfNotNull(title, sub).joinToString(", ")
        ViewCompat.setStateDescription(this, if (selected) "✓" else null)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(Halba.ROW_GAP) }
    }

    // Menu: 64 dp, icona, testo, freccia
    fun menuRow(icon: String, label: String, danger: Boolean = false, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(Halba.MENU_ROW)
        setPadding(dp(16f), 0, dp(16f), 0)
        background = Halba.rounded(ctx, p.surface, Halba.R_CONTROL, p.border)
        val c = if (danger) p.danger else p.ink
        addView(Icon(ctx, icon, c), LinearLayout.LayoutParams(dp(28f), dp(28f)))
        addView(text(label, Type.LABEL, c, center = false), LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dp(16f) })
        addView(Icon(ctx, "chevron-right", p.muted), LinearLayout.LayoutParams(dp(24f), dp(24f)))
        isClickable = true
        contentDescription = label
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(Halba.ROW_GAP) }
    }

    // Informazione: 64 dp, etichetta e valore
    fun infoRow(label: String, value: String, mono: Boolean = false) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(Halba.MENU_ROW)
        // L'etichetta resta su una riga; un valore lungo (id del terminale) va a capo, senza troncarlo
        addView(text(label, Type.LABEL, p.muted, center = false).apply { maxLines = 1 }, LinearLayout.LayoutParams(wrap, wrap))
        addView(text(value, Type.LABEL, p.ink, center = false, mono = mono).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dp(16f) })
        contentDescription = "$label: $value"
        layoutParams = LinearLayout.LayoutParams(match, wrap)
    }

    fun divider() = View(ctx).apply { setBackgroundColor(p.border); layoutParams = LinearLayout.LayoutParams(match, maxOf(1, dp(1f))) }

    // Nota (info, danger, warn): fondo tenue, icona di 24 dp e testo
    fun note(kind: String, s: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        val (bg, fg, icon) = when (kind) {
            "danger" -> Triple(p.dangerBg, p.danger, "alert")
            "warn" -> Triple(p.warnBg, p.warn, "clock")
            "success" -> Triple(p.successBg, p.success, "check")
            else -> Triple(p.tint, p.ink, "info")
        }
        background = Halba.rounded(ctx, bg, Halba.R_CONTROL)
        setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        addView(Icon(ctx, icon, fg), LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { topMargin = dp(2f) })
        addView(text(s, Type.LABEL, if (kind == "info") p.ink else fg, center = false), LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dp(12f) })
        layoutParams = LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(Halba.GAP) }
    }

    // Cerchio di stato dell'esito: 96 dp (120 per l'approvato), icona 48 dp
    fun badge(kind: String, sizeDp: Float = 96f) = FrameLayout(ctx).apply {
        val (bg, fg, icon) = when (kind) {
            "success" -> Triple(p.successBg, p.success, "check")
            "danger" -> Triple(p.dangerBg, p.danger, "x")
            else -> Triple(p.sunken, p.ink, "minus")
        }
        background = Halba.circle(bg)
        addView(Icon(ctx, icon, fg, 2.5f), FrameLayout.LayoutParams(dp(sizeDp / 2), dp(sizeDp / 2), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }

    fun loader(sizeDp: Float = 72f, dark: Boolean = false) = HalbaMark(ctx, ring = p.ink, dot = if (dark) Halba.DARK.brand else accent.accent, spin = true).apply {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }

    // Campo di testo: 64 dp, testo 24 sp, bordo forte
    @SuppressLint("SetTextI18n")
    fun input(value: String, hint: String) = EditText(ctx).apply {
        setText(value)
        this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        setTextColor(p.ink)
        setHintTextColor(p.muted)
        setSingleLine()
        minHeight = dp(Halba.INPUT) //  dopo setSingleLine, che altrimenti fissa l'altezza a una riga
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(16f), 0, dp(16f), 0)
        background = Halba.rounded(ctx, p.surface, Halba.R_CONTROL, p.borderStrong)
        layoutParams = LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(Halba.ROW_GAP) }
    }
}
