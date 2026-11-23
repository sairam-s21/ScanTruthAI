package com.scantruth.ai.privacy

import com.scantruth.ai.model.FallbackNotices
import com.scantruth.ai.model.IdentifierCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privacy shield must always produce a masked document, and must say honestly whether
 * Nano helped. A Nano outage changes the notice, never the masking guarantee.
 */
class FallbackPolicyTest {

    private val sampleReport = """
        Patient Name: Jane Doe
        Phone: +1 415 555 0132
        Email: jane.doe@example.com
        Aadhaar No: 1234 5678 9012
        Hemoglobin 13.2 g/dL
    """.trimIndent()

    @Test
    fun `nano unavailable falls back to the local rule-based shield`() {
        val shield = PrivacyShieldPolicy.assess(
            nanoRequested = true,
            scan = NanoMaskScan.Unavailable("Gemini Nano is not available on this device.")
        )

        assertEquals(PrivacyShieldSource.LOCAL_RULES, shield.source)
        assertTrue(shield.usedFallback)
        assertEquals(FallbackNotices.PRIVACY_LOCAL_SHIELD, shield.notice)
        assertFalse(shield.nanoAssisted)
    }

    @Test
    fun `a nano failure during the scan also counts as the local fallback`() {
        val shield = PrivacyShieldPolicy.assess(
            nanoRequested = true,
            scan = NanoMaskScan.Unavailable("Gemini Nano could not check this document.")
        )
        assertTrue(shield.usedFallback)
        assertEquals(FallbackNotices.PRIVACY_LOCAL_SHIELD, shield.notice)
    }

    @Test
    fun `a nano assisted scan is not a fallback`() {
        val scan = NanoMaskScan.Values(listOf("Jane Doe" to IdentifierCategory.PATIENT_NAME))
        val shield = PrivacyShieldPolicy.assess(nanoRequested = true, scan = scan)

        assertEquals(PrivacyShieldSource.NANO_ASSISTED, shield.source)
        assertFalse(shield.usedFallback)
        assertNull(shield.notice)
        assertEquals(scan.values, PrivacyShieldPolicy.valuesOf(scan))
    }

    @Test
    fun `nano finding no identifiers is still an assisted run`() {
        val shield = PrivacyShieldPolicy.assess(
            nanoRequested = true,
            scan = NanoMaskScan.NoIdentifiers
        )

        assertEquals(PrivacyShieldSource.NANO_ASSISTED, shield.source)
        assertFalse(shield.usedFallback)
        assertNull(shield.notice)
        assertTrue(PrivacyShieldPolicy.valuesOf(NanoMaskScan.NoIdentifiers).isEmpty())
    }

    @Test
    fun `an assist that was never requested is not announced as a fallback`() {
        val shield = PrivacyShieldPolicy.assess(
            nanoRequested = false,
            scan = NanoMaskScan.Unavailable("not used")
        )
        assertEquals(PrivacyShield.Baseline, shield)
        assertFalse(shield.usedFallback)
        assertNull(shield.notice)
    }

    @Test
    fun `the fallback does not change masking or high-risk approval`() {
        val document = (PrivacyMasker.mask(
            "DOB: 12th March 1985\nPhone: +1 415 555 0132\nHemoglobin 13.2 g/dL"
        ) as MaskOutcome.Masked).document

        val shield = PrivacyShieldPolicy.assess(
            nanoRequested = true,
            scan = NanoMaskScan.Unavailable("unsupported")
        )

        // The shield notice is presentation only: it cannot clear an alert.
        assertTrue(shield.usedFallback)
        assertTrue(document.highRiskAlerts.isNotEmpty())
        assertFalse(document.isSafeToApprove)
        // The phone is masked; the ambiguous date is what keeps approval blocked.
        assertFalse(document.redactedText.contains("415 555"))
    }

    @Test
    fun `no original identifiers reach fallback metadata or alerts`() {
        val document = (PrivacyMasker.mask(sampleReport) as MaskOutcome.Masked).document
        val shield = PrivacyShieldPolicy.assess(
            nanoRequested = true,
            scan = NanoMaskScan.Unavailable("unsupported")
        )

        // Everything the result screen and any metadata could carry.
        val metadata = buildString {
            document.detectedIdentifiers.forEach { identifier ->
                append(identifier.category.name).append(' ')
                append(identifier.token).append(' ')
                append(identifier.occurrences).append(' ')
            }
            document.alerts.forEach { alert ->
                append(alert.area.name).append(' ')
                append(alert.severity.name).append(' ')
                append(alert.title).append(' ')
                append(alert.detail).append(' ')
                append(alert.lineNumber ?: 0).append(' ')
            }
            append(document.summaryLine()).append(' ')
            append(shield.notice).append(' ')
            append(shield.source.name)
        }

        listOf("Jane", "jane.doe@example.com", "415", "555", "0132", "5678", "9012")
            .forEach { value ->
                assertFalse(
                    "metadata leaked '$value': $metadata",
                    metadata.contains(value)
                )
            }
    }
}
