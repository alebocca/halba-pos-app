package ai.halba.pos

import java.util.Calendar

// Scontrino dei metodi con QR (design system: terminal-receipt.md e terminal/receipt/receipt.json). Due versioni con lo
// stesso contenuto: XHTML con il logo (principale) e solo testo (riserva, 30 caratteri per riga). Righe senza dato non
// si stampano; il codice tecnico del rifiuto mai, solo un motivo per il cliente.
object Receipt {
    const val WIDTH = 30
    const val MAX_LINES = 25

    enum class Outcome { APPROVED, DECLINED, CANCELLED, INCOMPLETE }

    data class Data(
        val lang: String, //            it, en, de (lingua del terminale)
        val merchant: String?,
        val store: String?,
        val address: String? = null,
        val vat: String? = null,
        val at: Calendar,
        val terminal: String?,
        val method: String,
        val order: String?,
        val halbaRef: String,
        val pspRef: String? = null,
        val payer: String? = null,
        val amountMinor: Long,
        val currency: String,
        val outcome: Outcome,
        val reason: String? = null, //  chiave r_* di receipt.json per i rifiuti
    )

    private val STRINGS = mapOf(
        "it" to mapOf(
            "date" to "Data", "terminal" to "Terminale", "method" to "Metodo", "order" to "Ordine", "ref_halba" to "Rif. halba", "ref_psp" to "Rif. PSP", "payer" to "Conto", "vat" to "P. IVA",
            "approved" to "APPROVATO", "declined" to "RIFIUTATO", "cancelled" to "ANNULLATO", "incomplete" to "NON COMPLETATO",
            "copy_customer" to "COPIA CLIENTE", "keep" to "Conservare questo scontrino", "thanks" to "Grazie", "not_fiscal" to "Non costituisce documento fiscale", "no_charge" to "Nessun addebito.",
            "r_insufficient_funds" to "Fondi insufficienti sul conto.", "r_provider_declined" to "Pagamento rifiutato dal fornitore del metodo.", "r_limit_exceeded" to "Limite di spesa superato.",
            "r_method_unavailable" to "Metodo non disponibile al momento.", "r_generic" to "Pagamento non autorizzato.", "r_cancelled" to "Pagamento annullato prima della conferma.", "r_qr_expired" to "Il codice QR è scaduto.",
        ),
        "en" to mapOf(
            "date" to "Date", "terminal" to "Terminal", "method" to "Method", "order" to "Order", "ref_halba" to "halba ref.", "ref_psp" to "PSP ref.", "payer" to "Account", "vat" to "VAT no.",
            "approved" to "APPROVED", "declined" to "DECLINED", "cancelled" to "CANCELLED", "incomplete" to "NOT COMPLETED",
            "copy_customer" to "CUSTOMER COPY", "keep" to "Keep this receipt", "thanks" to "Thank you", "not_fiscal" to "This is not a tax document", "no_charge" to "You were not charged.",
            "r_insufficient_funds" to "Insufficient funds.", "r_provider_declined" to "Payment declined by the payment provider.", "r_limit_exceeded" to "Spending limit exceeded.",
            "r_method_unavailable" to "Payment method unavailable right now.", "r_generic" to "Payment not authorised.", "r_cancelled" to "Payment cancelled before confirmation.", "r_qr_expired" to "The QR code expired.",
        ),
        "de" to mapOf(
            "date" to "Datum", "terminal" to "Terminal", "method" to "Zahlart", "order" to "Bestellung", "ref_halba" to "halba-Ref.", "ref_psp" to "PSP-Ref.", "payer" to "Konto", "vat" to "USt-IdNr.",
            "approved" to "GENEHMIGT", "declined" to "ABGELEHNT", "cancelled" to "ABGEBROCHEN", "incomplete" to "NICHT ABGESCHLOSSEN",
            "copy_customer" to "KUNDENBELEG", "keep" to "Beleg bitte aufbewahren", "thanks" to "Danke", "not_fiscal" to "Kein steuerlicher Beleg", "no_charge" to "Es wurde nichts abgebucht.",
            "r_insufficient_funds" to "Nicht genügend Guthaben.", "r_provider_declined" to "Zahlung vom Anbieter abgelehnt.", "r_limit_exceeded" to "Ausgabenlimit überschritten.",
            "r_method_unavailable" to "Zahlart derzeit nicht verfügbar.", "r_generic" to "Zahlung nicht autorisiert.", "r_cancelled" to "Zahlung vor Bestätigung abgebrochen.", "r_qr_expired" to "Der QR-Code ist abgelaufen.",
        ),
    )
    private fun t(d: Data, k: String) = (STRINGS[d.lang] ?: STRINGS.getValue("en")).getValue(k)

    // Motivo per il cliente dal codice del rifiuto: lista corta e fissa, il resto diventa il generico
    fun reasonKey(code: String?): String {
        val c = code.orEmpty().lowercase()
        return when {
            "insufficient" in c || "funds" in c -> "r_insufficient_funds"
            "limit" in c -> "r_limit_exceeded"
            "unavailable" in c || "not_available" in c -> "r_method_unavailable"
            "declin" in c || "refus" in c -> "r_provider_declined"
            else -> "r_generic"
        }
    }

    fun amount(d: Data): String {
        val digits = runCatching { java.util.Currency.getInstance(d.currency).defaultFractionDigits }.getOrDefault(2).coerceAtLeast(0)
        var div = 1L; repeat(digits) { div *= 10 }
        val whole = d.amountMinor / div
        val frac = (d.amountMinor % div).toString().padStart(digits, '0')
        val (dec, thou) = if (d.lang == "en") "." to "," else "," to "."
        val w = whole.toString().reversed().chunked(3).joinToString(thou).reversed()
        val n = if (digits > 0) "$w$dec$frac" else w
        val sym = runCatching { java.util.Currency.getInstance(d.currency).getSymbol(java.util.Locale.ITALY) }.getOrDefault(d.currency)
        val symbol = if (d.currency == "EUR") "€" else sym
        return if (d.lang == "en") "$symbol$n" else "$n $symbol"
    }

    fun date(d: Data): String {
        val c = d.at
        val dd = "%02d".format(c.get(Calendar.DAY_OF_MONTH)); val mm = "%02d".format(c.get(Calendar.MONTH) + 1); val y = c.get(Calendar.YEAR)
        val time = "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        return if (d.lang == "de") "$dd.$mm.$y $time" else "$dd/$mm/$y $time"
    }

    // Accorciato al centro, tenendo inizio e fine (riferimenti)
    fun middle(v: String, max: Int): String = if (v.length <= max) v else { val keep = max - 3; v.take((keep + 1) / 2) + "..." + v.takeLast(keep / 2) }

    // A capo sugli spazi, al più maxLines righe; l'ultima finisce con "..." se non basta
    fun wrap(text: String, maxLines: Int, width: Int = WIDTH): List<String> {
        val words = text.trim().split(Regex("\\s+")).flatMap { w -> if (w.length > width) w.chunked(width) else listOf(w) }
        val out = mutableListOf<String>()
        var cur = ""
        for (w in words) {
            if (cur.isEmpty()) cur = w
            else if (cur.length + 1 + w.length <= width) cur += " $w"
            else { out += cur; cur = w }
        }
        if (cur.isNotEmpty()) out += cur
        if (out.size <= maxLines) return out
        val kept = out.take(maxLines).toMutableList()
        val last = kept.last()
        kept[kept.size - 1] = (if (last.length > width - 3) last.take(width - 3) else last) + "..."
        return kept
    }

    // Riga etichetta-valore larga esattamente 30; se non sta, etichetta sopra e valore sotto a destra (al più 2 righe)
    fun row(label: String, value: String): List<String> {
        if (label.length + 1 + value.length <= WIDTH) return listOf(label + " ".repeat(WIDTH - label.length - value.length) + value)
        return listOf(label.padEnd(WIDTH)) + wrap(value, 2).map { it.padStart(WIDTH) }
    }

    private const val SEP = "------------------------------"

    // Versione solo testo (riserva): righe con stile, al più 25
    fun text(d: Data): List<ReceiptLine> {
        val out = mutableListOf<ReceiptLine>()
        fun c(s: String, bold: Boolean = false, w2: Boolean = false, h2: Boolean = false) { out += ReceiptLine(s, bold = bold, centred = true, doubleWidth = w2, doubleHeight = h2) }
        // Righe del pagamento in ordine di sacrificio se si superano le 25 righe (conto, ordine, terminale, P. IVA)
        val header = mutableListOf<Pair<String, Boolean>>()
        d.merchant?.let { wrap(it, 2).forEach { l -> header += l to true } }
        d.store?.let { header += wrap(it, 1).first() to false }
        d.address?.let { wrap(it, 2).forEach { l -> header += l to false } }
        val vatLine = d.vat?.let { "${t(d, "vat")} $it" }
        val rows = mutableListOf<Pair<String, List<String>>>()
        rows += "date" to row(t(d, "date"), date(d))
        d.terminal?.let { rows += "terminal" to row(t(d, "terminal"), it) }
        rows += "method" to row(t(d, "method"), d.method)
        d.order?.let { rows += "order" to row(t(d, "order"), middle(it, WIDTH - t(d, "order").length - 1)) }
        rows += "ref" to row(t(d, "ref_halba"), middle(d.halbaRef, 16))
        d.pspRef?.let { rows += "psp" to row(t(d, "ref_psp"), middle(it, WIDTH - t(d, "ref_psp").length - 1)) }
        d.payer?.let { rows += "payer" to row(t(d, "payer"), middle(it.replace("•", "*"), WIDTH - t(d, "payer").length - 1)) }
        val result = mutableListOf<ReceiptLine>()
        val amt = amount(d)
        result += ReceiptLine(amt, bold = true, centred = true, doubleWidth = amt.length <= 15, doubleHeight = true)
        val outcomeKey = when (d.outcome) { Outcome.APPROVED -> "approved"; Outcome.DECLINED -> "declined"; Outcome.CANCELLED -> "cancelled"; Outcome.INCOMPLETE -> "incomplete" }
        result += ReceiptLine(t(d, outcomeKey), bold = true, centred = true, doubleHeight = true)
        val reasonKey = when (d.outcome) { Outcome.DECLINED -> d.reason ?: "r_generic"; Outcome.CANCELLED -> "r_cancelled"; Outcome.INCOMPLETE -> "r_qr_expired"; else -> null }
        reasonKey?.let { k -> wrap(t(d, k), 2).forEach { result += ReceiptLine(it, centred = true) } }
        if (d.outcome != Outcome.APPROVED) result += ReceiptLine(t(d, "no_charge"), bold = true, centred = true)
        val closing = mutableListOf(ReceiptLine(t(d, "copy_customer"), bold = true, centred = true))
        if (d.outcome == Outcome.APPROVED) { closing += ReceiptLine(t(d, "keep"), centred = true); closing += ReceiptLine(t(d, "thanks"), centred = true) }
        if (d.lang == "it") wrap(t(d, "not_fiscal"), 2).forEach { closing += ReceiptLine(it, centred = true) }

        var dropVat = false
        val drop = mutableSetOf<String>()
        fun count() = header.size + (if (vatLine != null && !dropVat) 1 else 0) + 1 + rows.filter { it.first !in drop }.sumOf { it.second.size } + 1 + result.size + 1 + closing.size
        for (k in listOf("payer", "order", "terminal")) { if (count() <= MAX_LINES) break; drop += k }
        if (count() > MAX_LINES) dropVat = true

        header.forEach { (l, b) -> c(l, bold = b) }
        if (vatLine != null && !dropVat) c(vatLine)
        c(SEP)
        rows.filter { it.first !in drop }.forEach { (_, ls) -> ls.forEach { c(it) } }
        c(SEP)
        out += result
        c(SEP)
        out += closing
        return out
    }

    private fun x(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // Versione XHTML con il logo (PNG a 1 bit in Base64), come i modelli del design system (terminal/receipt/xhtml)
    fun xhtml(d: Data, logoPngBase64: String?): String {
        val sep = """<p style="margin:8px 0;border-top:2px solid #000;font-size:2px;line-height:2px;height:2px">&#160;</p>"""
        fun p(s: String, style: String = "") = """<p style="text-align:center;margin:0;$style">${x(s)}</p>"""
        fun tr(label: String, value: String) = """<tr><td style="text-align:left;vertical-align:top">${x(label)}</td><td style="text-align:right">${x(value)}</td></tr>"""
        val outcomeKey = when (d.outcome) { Outcome.APPROVED -> "approved"; Outcome.DECLINED -> "declined"; Outcome.CANCELLED -> "cancelled"; Outcome.INCOMPLETE -> "incomplete" }
        val reasonKey = when (d.outcome) { Outcome.DECLINED -> d.reason ?: "r_generic"; Outcome.CANCELLED -> "r_cancelled"; Outcome.INCOMPLETE -> "r_qr_expired"; else -> null }
        val b = StringBuilder()
        b.append("""<html xmlns="http://www.w3.org/1999/xhtml" lang="${d.lang}"><body style="width:384px;margin:0;font-size:20px;line-height:26px">""")
        if (logoPngBase64 != null) b.append("""<p style="text-align:center;margin:0"><img src="data:image/png;base64,$logoPngBase64" width="384" alt=""/></p>""")
        d.merchant?.let { b.append(p(it, "font-weight:bold;font-size:22px")) }
        d.store?.let { b.append(p(it)) }
        d.address?.let { b.append(p(it)) }
        d.vat?.let { b.append(p("${t(d, "vat")} $it")) }
        b.append(sep)
        b.append("""<table width="100%" style="font-size:20px"><tbody>""")
        b.append(tr(t(d, "date"), date(d)))
        d.terminal?.let { b.append(tr(t(d, "terminal"), it)) }
        b.append(tr(t(d, "method"), d.method))
        d.order?.let { b.append(tr(t(d, "order"), middle(it, 20))) }
        b.append(tr(t(d, "ref_halba"), middle(d.halbaRef, 16).replace("...", "…")))
        d.pspRef?.let { b.append(tr(t(d, "ref_psp"), middle(it, 20))) }
        d.payer?.let { b.append(tr(t(d, "payer"), it)) }
        b.append("</tbody></table>")
        b.append(sep)
        b.append(p(amount(d), "font-weight:bold;font-size:48px;line-height:56px"))
        b.append("""<p style="text-align:center;font-weight:bold;font-size:30px;line-height:38px;border:3px solid #000;margin:6px 24px 8px">${x(t(d, outcomeKey))}</p>""")
        reasonKey?.let { b.append(p(t(d, it))) }
        if (d.outcome != Outcome.APPROVED) b.append(p(t(d, "no_charge"), "font-weight:bold"))
        b.append(sep)
        b.append(p(t(d, "copy_customer"), "font-weight:bold"))
        if (d.outcome == Outcome.APPROVED) { b.append(p(t(d, "keep"))); b.append(p(t(d, "thanks"))) }
        if (d.lang == "it") b.append(p(t(d, "not_fiscal"), "font-size:18px"))
        b.append("</body></html>")
        return b.toString()
    }
}
