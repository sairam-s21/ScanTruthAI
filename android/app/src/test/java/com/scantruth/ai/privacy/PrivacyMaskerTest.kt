package com.scantruth.ai.privacy

import com.scantruth.ai.model.IdentifierCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The masking layer is the app's privacy guarantee, so it is tested directly rather
 * than through the UI: text in, tokens out, and a rejection when it cannot be sure.
 */
class PrivacyMaskerTest {

    private val sampleReport = """
        City Diagnostics — Complete Blood Count
        Patient Name: Jane Doe
        Date of Birth: 12/03/1985
        Phone: +1 415 555 0132
        Email: jane.doe@example.com
        Hemoglobin 13.2 g/dL
        WBC 6.4 x10^9/L
    """.trimIndent()

    @Test
    fun `masks name date of birth phone and email`() {
        val outcome = PrivacyMasker.mask(sampleReport)

        assertTrue("expected a masked document, got $outcome", outcome is MaskOutcome.Masked)
        val document = (outcome as MaskOutcome.Masked).document

        assertFalse(document.redactedText.contains("Jane Doe"))
        assertFalse(document.redactedText.contains("jane.doe@example.com"))
        assertFalse(document.redactedText.contains("415 555"))
        assertFalse(document.redactedText.contains("12/03/1985"))

        assertTrue(document.redactedText.contains("[NAME_1]"))
        assertTrue(document.redactedText.contains("[DOB_1]"))
        assertTrue(document.redactedText.contains("[PHONE_1]"))
        assertTrue(document.redactedText.contains("[EMAIL_1]"))

        // Clinical values survive: masking must not destroy the medical content.
        assertTrue(document.redactedText.contains("Hemoglobin 13.2 g/dL"))

        assertTrue(document.totalReplacements >= 4)
    }

    @Test
    fun `detected identifiers carry tokens and counts but never original values`() {
        val outcome = PrivacyMasker.mask(sampleReport) as MaskOutcome.Masked
        val document = outcome.document

        val categories = document.detectedIdentifiers.map { it.category }
        assertTrue(categories.contains(IdentifierCategory.PATIENT_NAME))
        assertTrue(categories.contains(IdentifierCategory.EMAIL))

        val rendered = document.detectedIdentifiers.joinToString { "${it.token} ${it.occurrences}" }
        assertFalse(rendered.contains("Jane", ignoreCase = true))
        assertFalse(rendered.contains("example.com", ignoreCase = true))
        assertFalse(document.detectedIdentifiers.any { it.occurrences < 1 })
    }

    @Test
    fun `same value masks to the same token and counts each occurrence`() {
        val outcome = PrivacyMasker.mask(
            "Patient Name: Jane Doe\nPatient: Jane Doe\nWard: 4B"
        ) as MaskOutcome.Masked

        assertEquals(1, outcome.document.detectedIdentifiers.size)
        val identifier = outcome.document.detectedIdentifiers.first()
        assertEquals(IdentifierCategory.PATIENT_NAME, identifier.category)
        assertEquals(2, identifier.occurrences)
        assertEquals(2, outcome.document.totalReplacements)
    }

    @Test
    fun `social security number is masked`() {
        val outcome = PrivacyMasker.mask("SSN: 123-45-6789\nDiagnosis: stable") as MaskOutcome.Masked
        assertFalse(outcome.document.redactedText.contains("123-45-6789"))
        assertTrue(outcome.document.detectedIdentifiers.isNotEmpty())
    }

    @Test
    fun `additional values from on-device detection are masked too`() {
        val outcome = PrivacyMasker.mask(
            rawText = "Seen by A. Reyes at the clinic. No identifiers on the lab printout.",
            additionalValues = listOf("A. Reyes" to IdentifierCategory.PATIENT_NAME)
        ) as MaskOutcome.Masked

        assertFalse(outcome.document.redactedText.contains("A. Reyes"))
        assertTrue(outcome.document.redactedText.contains("[NAME_1]"))
    }

    @Test
    fun `text without identifiers is kept intact`() {
        val outcome = PrivacyMasker.mask("Hemoglobin 13.2 g/dL\nVitamin D 32 ng/mL")
        assertTrue(outcome is MaskOutcome.Masked)
        val document = (outcome as MaskOutcome.Masked).document
        assertTrue(document.detectedIdentifiers.isEmpty())
        assertEquals(0, document.totalReplacements)
        assertTrue(document.redactedText.contains("Vitamin D 32 ng/mL"))
    }

    @Test
    fun `blank text is rejected instead of producing an empty document`() {
        val outcome = PrivacyMasker.mask("   \n\t ")
        assertTrue(outcome is MaskOutcome.Rejected)
        assertEquals(MaskFailure.EMPTY_TEXT, (outcome as MaskOutcome.Rejected).failure)
    }

    @Test
    fun `oversized text is rejected before any processing`() {
        val outcome = PrivacyMasker.mask("a".repeat(40_001))
        assertTrue(outcome is MaskOutcome.Rejected)
        assertEquals(MaskFailure.TEXT_TOO_LARGE, (outcome as MaskOutcome.Rejected).failure)
    }

    @Test
    fun `masked document reports usability`() {
        val document = (PrivacyMasker.mask("Hemoglobin 13.2 g/dL") as MaskOutcome.Masked).document
        assertTrue(document.isUsable)
        assertTrue(document.summaryLine().isNotBlank())
    }

    // ------------------------------------------------- Indian identifier formats

    @Test
    fun `masks a labelled Aadhaar number`() {
        val outcome = PrivacyMasker.mask(
            "Aadhaar No: 1234 5678 9012\nHemoglobin 13.2 g/dL"
        ) as MaskOutcome.Masked

        assertFalse(outcome.document.redactedText.contains("5678"))
        assertTrue(outcome.document.detectedIdentifiers.isNotEmpty())
    }

    @Test
    fun `masks a dotted Aadhaar number`() {
        val outcome = PrivacyMasker.mask(
            "Aadhaar: 1234.5678.9012\nHemoglobin 13.2 g/dL"
        ) as MaskOutcome.Masked

        assertFalse(outcome.document.redactedText.contains("5678"))
        assertFalse(outcome.document.redactedText.contains("9012"))
    }

    @Test
    fun `masks ABHA and PAN identifiers`() {
        val outcome = PrivacyMasker.mask(
            "ABHA Number: 12-3456-7890-1234\nPAN: ABCDE1234F\nHemoglobin 13.2 g/dL"
        ) as MaskOutcome.Masked

        assertFalse(outcome.document.redactedText.contains("7890"))
        assertFalse(outcome.document.redactedText.contains("ABCDE1234F"))
    }

    @Test
    fun `masks Indian hospital record numbers`() {
        val outcome = PrivacyMasker.mask(
            "IP No: 2024/8891\nOP No: 77412\nBill No: H-99213\nHemoglobin 13.2 g/dL"
        ) as MaskOutcome.Masked
        val text = outcome.document.redactedText

        assertFalse(text.contains("2024/8891"))
        assertFalse(text.contains("77412"))
        assertFalse(text.contains("99213"))
    }

    @Test
    fun `masks a long numeric policy number as one identifier`() {
        val outcome = PrivacyMasker.mask(
            "Policy No: 1234567890123456\nHemoglobin 13.2 g/dL"
        ) as MaskOutcome.Masked

        assertFalse(outcome.document.redactedText.contains("1234567890123456"))
    }

    @Test
    fun `masks a hyphenated identifier too long for the phone rule`() {
        val outcome = PrivacyMasker.mask(
            "Ref: 1234-5678-9012-3456-78\nHemoglobin 13.2 g/dL"
        ) as MaskOutcome.Masked
        val text = outcome.document.redactedText

        assertFalse(text.contains("9012"))
        assertFalse(text.contains("3456"))
    }

    @Test
    fun `masks a labelled Indian address and PIN code`() {
        val outcome = PrivacyMasker.mask(
            "Permanent Address: 12, MG Road, Pune 411001\nPIN: 411001"
        ) as MaskOutcome.Masked

        assertFalse(outcome.document.redactedText.contains("MG Road"))
        assertFalse(outcome.document.redactedText.contains("411001"))
    }

    @Test
    fun `masks Indian phone number formats`() {
        val outcome = PrivacyMasker.mask(
            "Phone: +91 98765 43210\nMobile: 9876543210\nClinic: 080-23456789"
        ) as MaskOutcome.Masked
        val text = outcome.document.redactedText

        assertFalse(text.contains("98765 43210"))
        assertFalse(text.contains("9876543210"))
        assertFalse(text.contains("23456789"))
    }

    @Test
    fun `a date of birth that survived masking blocks approval`() {
        val outcome = PrivacyMasker.mask("DOB: 12th March 1985\nHemoglobin 13.2 g/dL")

        assertTrue("expected a reviewable document, got $outcome", outcome is MaskOutcome.Masked)
        val document = (outcome as MaskOutcome.Masked).document

        assertFalse(document.isSafeToApprove)
        assertTrue(document.highRiskAlerts.isNotEmpty())
        assertTrue(document.redactedText.contains("Hemoglobin 13.2 g/dL"))
    }

    @Test
    fun `clinical reference ranges do not block a report`() {
        val outcome = PrivacyMasker.mask(
            "Hemoglobin 10.8 g/dL (13-17)\nWBC 6400 /uL (4000-11000)\nPlatelets 150000"
        )

        assertTrue("expected masked, got $outcome", outcome is MaskOutcome.Masked)
        val text = (outcome as MaskOutcome.Masked).document.redactedText
        // Reference intervals must survive so the extraction can judge the value.
        assertTrue(text.contains("13-17"))
        assertTrue(text.contains("4000-11000"))
        assertTrue(text.contains("150000"))
    }
}
