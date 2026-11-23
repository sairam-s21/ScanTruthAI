package com.scantruth.ai.privacy

import com.scantruth.ai.model.AlertSeverity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The review copy shown beside the redacted text: same layout, identifiers as bullets,
 * and nothing readable left behind.
 */
class PrivacyReviewCopyTest {

    private val report = """
        City Diagnostics
        Patient Name: RAHUL SHARMA
        Mobile: 9876543210
        Aadhaar No: 2345 6789 0124
        Hemoglobin 13.2 g/dL
    """.trimIndent()

    @Test
    fun `protected copy keeps the layout and hides the identifier`() {
        val document = (PrivacyMasker.mask(report) as MaskOutcome.Masked).document
        val protectedText = document.protectedOriginalText

        assertTrue(protectedText.contains("City Diagnostics"))
        assertTrue(protectedText.contains("Hemoglobin 13.2 g/dL"))
        assertTrue(protectedText.contains("•"))
        assertFalse(protectedText.contains("RAHUL"))
        assertFalse(protectedText.contains("9876543210"))
        assertFalse(protectedText.contains("6789"))
    }

    @Test
    fun `protected copy uses login bullets not tokens`() {
        val document = (PrivacyMasker.mask(report) as MaskOutcome.Masked).document

        assertFalse(document.protectedOriginalText.contains("[NAME_1]"))
        // The redacted payload still uses tokens, which the cloud prompt understands.
        assertTrue(document.redactedText.contains("[NAME_1]"))
    }

    @Test
    fun `a value the reviewer adds is masked and counted`() {
        val text = "Seen by Dr. Meera Iyer at the clinic\nHemoglobin 13.2 g/dL"

        val outcome = PrivacyMasker.mask(
            rawText = text,
            additionalValues = listOf("Meera Iyer" to com.scantruth.ai.model.IdentifierCategory.PATIENT_NAME)
        ) as MaskOutcome.Masked

        assertFalse(outcome.document.redactedText.contains("Meera Iyer"))
        assertFalse(outcome.document.protectedOriginalText.contains("Meera Iyer"))
        assertTrue(outcome.document.totalReplacements >= 1)
    }

    @Test
    fun `an added value is masked everywhere it occurs`() {
        val outcome = PrivacyMasker.mask(
            rawText = "Ward note for Suresh Patil\nSuresh Patil reviewed\nHemoglobin 13.2 g/dL",
            additionalValues = listOf("Suresh Patil" to com.scantruth.ai.model.IdentifierCategory.PATIENT_NAME)
        ) as MaskOutcome.Masked

        assertFalse(outcome.document.redactedText.contains("Suresh Patil"))
        assertTrue(outcome.document.detectedIdentifiers.any { it.occurrences == 2 })
    }

    @Test
    fun `manually hiding an unlabelled name clears its review hint`() {
        val text = "Reviewed by Suresh Patil\nSeen by Suresh Patil at the clinic"
        val outcome = PrivacyMasker.mask(
            rawText = text,
            additionalValues = listOf(
                "Suresh Patil" to com.scantruth.ai.model.IdentifierCategory.PATIENT_NAME
            )
        ) as MaskOutcome.Masked

        assertFalse(outcome.document.redactedText.contains("Suresh Patil"))
        assertTrue(outcome.document.detectedIdentifiers.any { it.occurrences == 2 })
        assertTrue(
            outcome.document.alerts.none { it.title.contains("unlabelled name", ignoreCase = true) }
        )
    }

    @Test
    fun `alerts never carry the original value`() {
        val document = (PrivacyMasker.mask("DOB: 12th March 1985\nMobile: 98765 4321") as MaskOutcome.Masked).document

        val rendered = document.alerts.joinToString { "${it.title} ${it.detail}" }
        assertFalse(rendered.contains("March"))
        assertFalse(rendered.contains("1985"))
        assertFalse(rendered.contains("98765"))
        assertTrue(document.alerts.any { it.severity == AlertSeverity.HIGH })
    }
}
