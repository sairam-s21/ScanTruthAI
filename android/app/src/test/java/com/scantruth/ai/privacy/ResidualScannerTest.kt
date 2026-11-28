package com.scantruth.ai.privacy

import com.scantruth.ai.model.AlertSeverity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The approval gate: a high-risk identifier that survived masking must stop the cloud
 * call, while clinical numbers and reference ranges must not.
 */
class ResidualScannerTest {

    @Test
    fun `unmasked email is high risk`() {
        val findings = ResidualScanner.scan("Write to jane.doe@example.com for reports")
        assertTrue(findings.any { it.severity == AlertSeverity.HIGH })
    }

    @Test
    fun `unmasked phone length number is high risk`() {
        val findings = ResidualScanner.scan("Call 9876543210 for the report")
        assertTrue(findings.any { it.severity == AlertSeverity.HIGH })
    }

    @Test
    fun `unmasked labelled patient id is high risk`() {
        val findings = ResidualScanner.scan("Patient ID: 4471\nHemoglobin 13.2 g/dL")
        assertTrue(findings.any { it.severity == AlertSeverity.HIGH })
    }

    @Test
    fun `clinical values and reference ranges are not high risk`() {
        val findings = ResidualScanner.scan(
            "Hemoglobin 10.8 g/dL (13-17)\nWBC 6400 /uL (4000-11000)\nPlatelets 150000"
        )
        assertTrue(findings.none { it.severity == AlertSeverity.HIGH })
    }

    @Test
    fun `clinical report is safe to approve`() {
        val outcome = PrivacyMasker.mask(
            "Hemoglobin 10.8 g/dL (13-17)\nWBC 6400 /uL (4000-11000)\nPlatelets 150000"
        ) as MaskOutcome.Masked

        assertTrue(outcome.document.isSafeToApprove)
    }

    @Test
    fun `medium hints do not block approval`() {
        val outcome = PrivacyMasker.mask(
            "Collected on 12/03/2025\nHemoglobin 13.2 g/dL"
        ) as MaskOutcome.Masked

        assertTrue(outcome.document.alerts.any { it.severity == AlertSeverity.MEDIUM })
        assertTrue(outcome.document.isSafeToApprove)
    }

    @Test
    fun `a high risk residual blocks approval instead of dead ending the review`() {
        // A dotted phone number is rejected by the phone rule, so it survives masking.
        val outcome = PrivacyMasker.mask(
            "Phone: 98.76.54.3210\nHemoglobin 13.2 g/dL"
        ) as MaskOutcome.Masked

        // The document is still returned so the reviewer can add the missed value,
        // but approval is refused.
        assertTrue(outcome.document.redactedText.contains("Hemoglobin"))
        assertFalse(outcome.document.isSafeToApprove)
    }

    @Test
    fun `a capitalised name after by is flagged for manual review`() {
        val findings = ResidualScanner.scan(
            "Reviewed by Suresh Patil\nHaemoglobin 13.2 g/dL"
        )

        assertTrue(findings.any { it.title.contains("unlabelled name", ignoreCase = true) })
        assertTrue(findings.none { it.severity == AlertSeverity.HIGH })
    }

    @Test
    fun `clinical lines without a person are not flagged as names`() {
        val findings = ResidualScanner.scan(
            "Collected by the laboratory at 9 AM\nHaemoglobin 13.2 g/dL"
        )

        assertTrue(findings.none { it.title.contains("unlabelled name", ignoreCase = true) })
    }

    @Test
    fun `findings carry a line number but never the matched value`() {
        val findings = ResidualScanner.scan("Aadhaar No: 2345 6789 0124\nEmail: a@b.com")
        val rendered = findings.joinToString { "${it.title} ${it.detail} ${it.lineNumber}" }

        assertFalse(rendered.contains("2345"))
        assertFalse(rendered.contains("a@b.com"))
        assertTrue(findings.all { it.lineNumber != null })
    }

    @Test
    fun `labelled report and sample codes are masked and leave no identifier-shaped residual`() {
        val report = """
            Report No: RPT-49201
            Sample No: SM-10420
            Order ID: ORD-88210
            Hemoglobin 13.4 g/dL
        """.trimIndent()

        val outcome = PrivacyMasker.mask(report) as MaskOutcome.Masked
        assertTrue(outcome.document.isSafeToApprove)
        assertFalse(outcome.document.redactedText.contains("RPT-49201"))
        assertFalse(outcome.document.redactedText.contains("SM-10420"))
        assertFalse(outcome.document.redactedText.contains("ORD-88210"))
        assertTrue(
            outcome.document.alerts.none { it.title.contains("Identifier-shaped code", ignoreCase = true) }
        )
    }

    @Test
    fun `month year headers ISO accreditation and clinical units do not false positive as identifier codes`() {
        val report = """
            Printed NOV 2025
            Accredited ISO 15189
            Serum IgE 1250 IU/ml
            Ferritin 45.2 ng/ml
            Analyzed on COBAS XN 1000 analyzer
        """.trimIndent()

        val findings = ResidualScanner.scan(report)
        assertTrue(
            "Harmless clinical/date/standard tokens must not trigger Identifier-shaped code: $findings",
            findings.none { it.title.contains("Identifier-shaped code", ignoreCase = true) }
        )
    }

    @Test
    fun `unlabelled letter-and-digit code on a non-clinical line is still flagged`() {
        val findings = ResidualScanner.scan("Visit code XY-98412 confirmed")
        assertTrue(findings.any { it.title.contains("Identifier-shaped code", ignoreCase = true) })
    }
}
