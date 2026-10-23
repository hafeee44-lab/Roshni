package io.github.hafeee44lab.solargrid.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertSettingsTest {

    @Test
    fun ringDurationsReadNaturally() {
        assertEquals("30 s", AlertSettings.describeRing(30))
        assertEquals("1.5 min", AlertSettings.describeRing(90))
        assertEquals("10 min", AlertSettings.describeRing(600))
        assertEquals("Until I stop it", AlertSettings.describeRing(0))
    }

    @Test
    fun defaultRingDurationIsOneOfTheChoices() {
        assertTrue(AlertSettings().ringSeconds in AlertSettings.RING_CHOICES)
    }
}
