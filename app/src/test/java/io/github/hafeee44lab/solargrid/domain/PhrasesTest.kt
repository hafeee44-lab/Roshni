package io.github.hafeee44lab.solargrid.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PhrasesTest {

    @Test
    fun urduIsChosenForUrduLocales() {
        assertSame(Phrases.Urdu, Phrases.of(Locale.forLanguageTag("ur-PK")))
        assertSame(Phrases.English, Phrases.of(Locale.UK))
    }

    @Test
    fun durationsReadNaturallyInUrdu() {
        assertEquals("1 گھنٹہ 20 منٹ", OutageLog.describeDuration(80 * 60_000L, Phrases.Urdu))
        assertEquals("2 گھنٹے", RuntimeEstimator.describe(120, Phrases.Urdu))
        assertEquals("ایک منٹ سے کم", OutageLog.describeDuration(20_000L, Phrases.Urdu))
    }

    @Test
    fun theOutageAlertSpeaksUrduWhenAsked() {
        val body = Phrases.Urdu.outageBody("64", "3 گھنٹے")
        assertTrue(body.contains("64%"))
        assertTrue(body.endsWith("۔"))
    }

    @Test
    fun englishOutputIsUnchanged() {
        assertEquals("1h 20m", OutageLog.describeDuration(80 * 60_000L))
        assertEquals("Mains are back after 2h. Battery at 70%.", Phrases.English.restoredBody("70", "2h"))
    }
}
