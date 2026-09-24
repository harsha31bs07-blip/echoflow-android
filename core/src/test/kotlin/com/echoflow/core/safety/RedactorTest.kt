package com.echoflow.core.safety

import com.echoflow.core.model.CaptureDiagnostics
import com.echoflow.core.model.SnapshotJson
import com.echoflow.core.testing.screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RedactorTest {
    @Test fun `typed text, phone numbers and emails are removed`() {
        var field = -1
        val s = screen {
            field = edit(hint = "Search", typed = "my secret query")
            text("Deliver to Home, +91 98765 43210")
            text("Invoice sent to someone@example.com")
            text("Card ending 4242")
            text("Total ₹349")
        }
        val r = Redactor.redact(s)
        assertNull(r.elements[field].text)
        assertEquals("Search", r.elements[field].hintText)
        val all = r.elements.mapNotNull { it.text }.joinToString("|")
        assertFalse(all.contains("98765"), all)
        assertFalse(all.contains("example.com"), all)
        assertFalse(all.contains("4242"), all)
        assertTrue(all.contains("₹349"), "short prices are kept: $all")
    }

    @Test fun `redacted payment screen still classifies the same`() {
        val s = screen {
            text("Payment Options"); text("UPI"); text("Saved card •••• 4242"); edit(hint = "CVV", typed = "123"); text("Pay ₹1499")
        }
        val c = ScreenSafetyClassifier()
        assertEquals(c.classify(s).kinds, c.classify(Redactor.redact(s)).kinds)
    }

    @Test fun `snapshot JSON round-trips`() {
        val s = screen { text("Hello"); edit(hint = "Search") }
        assertEquals(s, SnapshotJson.decode(SnapshotJson.encode(s)))
        val withDiagnostics = s.copy(diagnostics = CaptureDiagnostics(6, 0, withheldChildren = 3, withheldAt = listOf("android:id/content"), trigger = "dump"))
        assertEquals(withDiagnostics, SnapshotJson.decode(SnapshotJson.encode(withDiagnostics)))
        assertEquals("6 nodes · 0 readable · 3 withheld", withDiagnostics.diagnostics?.summary())
    }
}
