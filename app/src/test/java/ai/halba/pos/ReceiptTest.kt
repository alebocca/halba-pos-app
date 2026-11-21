package ai.halba.pos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

// Scontrino dei metodi con QR: regole del design system (terminal-receipt.md, esempi di lines/it.json)
class ReceiptTest {
    private fun at() = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 20, 18, 47) }
    private fun data(o: Receipt.Outcome, lang: String = "it", method: String = "Klarna Pay Over Time") = Receipt.Data(
        lang = lang, merchant = "Northwind Coffee Roasters S.r.l.", store = "Milano Duomo", address = "Via Torino 12, 20123 Milano MI", vat = "IT01234567890",
        at = at(), terminal = "Cassa 1", method = method, order = "ORD-2026-004172", halbaRef = "tp_7Hk2mABCDEFGHIJKLXa3Q9xA",
        pspRef = "KL-8841926710553", payer = "m•••@example.com", amountMinor = 2490, currency = "EUR", outcome = o,
        reason = if (o == Receipt.Outcome.DECLINED) "r_insufficient_funds" else null,
    )

    @Test fun approvedLikeTheDesignSystem() {
        val t = Receipt.text(data(Receipt.Outcome.APPROVED)).map { it.text }
        assertEquals(listOf("Northwind Coffee Roasters", "S.r.l.", "Milano Duomo", "Via Torino 12, 20123 Milano MI", "P. IVA IT01234567890", "------------------------------",
            "Data          20/11/2026 18:47", "Terminale              Cassa 1", "Metodo    Klarna Pay Over Time", "Ordine         ORD-2026-004172",
            "Rif. halba    tp_7Hk2...a3Q9xA", "Rif. PSP      KL-8841926710553", "Conto         m***@example.com", "------------------------------",
            "24,90 €", "APPROVATO", "------------------------------", "COPIA CLIENTE", "Conservare questo scontrino", "Grazie", "Non costituisce documento", "fiscale"), t)
        assertTrue(t.all { it.length <= Receipt.WIDTH })
    }

    @Test fun declinedHasReasonAndNoCharge() {
        val lines = Receipt.text(data(Receipt.Outcome.DECLINED, method = "PayPal"))
        val t = lines.map { it.text }
        assertTrue("Fondi insufficienti sul conto." in t)
        assertTrue("Nessun addebito." in t)
        assertTrue(t.size <= Receipt.MAX_LINES)
        val amount = lines.first { it.text == "24,90 €" }
        assertTrue(amount.doubleWidth && amount.doubleHeight && amount.bold)
    }

    @Test fun englishAmountAndLongMethodOnTwoRows() {
        val d = data(Receipt.Outcome.CANCELLED, lang = "en", method = "A very long payment method name for tests")
        val t = Receipt.text(d).map { it.text }
        assertTrue("€24.90" in t)
        assertTrue(t.any { it.trimEnd() == "Method" })
        assertTrue("not_fiscal solo in Italia", t.none { it.contains("tax document") })
        assertEquals("tp_7Hk2...a3Q9xA", Receipt.middle("tp_7Hk2mABCDEFGHIJKLXa3Q9xA", 16))
    }

    @Test fun xhtmlHasLogoAndOutcome() {
        val x = Receipt.xhtml(data(Receipt.Outcome.INCOMPLETE, lang = "de"), "AAAA")
        assertTrue(x.contains("data:image/png;base64,AAAA"))
        assertTrue(x.contains("NICHT ABGESCHLOSSEN"))
        assertTrue(x.contains("24,90 €"))
    }
}
