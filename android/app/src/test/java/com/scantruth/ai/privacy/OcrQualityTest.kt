package com.scantruth.ai.privacy

import com.scantruth.ai.model.AlertArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR plausibility hints: they must catch damaged identifiers without flagging clean
 * ones, and they must never carry the value they matched.
 */
class OcrQualityTest {

    @Test
    fun `labelled phone with too few digits is flagged`() {
        val alerts = OcrQuality.assess("Mobile: 98765 4321\nHemoglobin 13.2 g/dL")

        assertTrue(alerts.any { it.area == AlertArea.OCR && it.title.contains("Phone") })
        assertTrue(alerts.all { it.lineNumber == 1 })
    }

    @Test
    fun `a well formed indian mobile is not flagged`() {
        val alerts = OcrQuality.assess("Mobile: 9876543210\nHemoglobin 13.2 g/dL")

        assertTrue(alerts.none { it.title.contains("Phone") })
    }

    @Test
    fun `impossible calendar date is flagged`() {
        val alerts = OcrQuality.assess("DOB: 31/02/1985\nHemoglobin 13.2 g/dL")

        assertTrue(alerts.any { it.title.contains("Date") })
    }

    @Test
    fun `plausible dates are not flagged`() {
        val alerts = OcrQuality.assess(
            "DOB: 31/03/1985\nReport date: 2025-11-21\nHemoglobin 13.2 g/dL"
        )

        assertTrue(alerts.none { it.title.contains("Date") })
    }

    @Test
    fun `aadhaar that fails its checksum is flagged`() {
        val alerts = OcrQuality.assess("Aadhaar No: 1234 5678 9012\nHemoglobin 13.2 g/dL")

        assertTrue(alerts.any { it.title.contains("Aadhaar") })
    }

    @Test
    fun `a valid aadhaar keeps its checksum quiet`() {
        val alerts = OcrQuality.assess("Aadhaar No: 2345 6789 0124\nHemoglobin 13.2 g/dL")

        assertTrue(alerts.none { it.title.contains("Aadhaar") })
    }

    @Test
    fun `letters inside a number are flagged as a likely misread`() {
        val alerts = OcrQuality.assess("Mobile: 98O6543210\nHemoglobin 13.2 g/dL")

        assertTrue(alerts.any { it.title.contains("letters") || it.title.contains("Phone") })
    }

    @Test
    fun `findings never contain the identifier itself`() {
        val alerts = OcrQuality.assess("Aadhaar No: 1234 5678 9012\nDOB: 31/02/1985")

        val rendered = alerts.joinToString { "${it.title} ${it.detail}" }
        assertFalse(rendered.contains("1234"))
        assertFalse(rendered.contains("5678"))
        assertFalse(rendered.contains("31/02"))
        // Line numbers are the only locator exposed.
        assertTrue(alerts.all { it.lineNumber != null })
        assertEquals(AlertArea.OCR, alerts.first().area)
    }
}
