package com.scantruth.ai.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Indian lab-report layouts: names and addresses that carry no label, and the report
 * vocabulary that must never be mistaken for a person.
 */
class IndianReportDetectionTest {

    private fun redacted(text: String): String =
        (PrivacyMasker.mask(text) as MaskOutcome.Masked).document.redactedText

    @Test
    fun `name label on its own line masks the name underneath`() {
        val text = redacted(
            """
            CITY DIAGNOSTICS
            Patient Name
            RAHUL SHARMA
            Age/Sex: 34/M
            Hemoglobin 13.2 g/dL
            """.trimIndent()
        )

        assertFalse(text.contains("RAHUL SHARMA"))
        assertTrue(text.contains("[NAME_1]"))
        // The facility letterhead and the clinical content survive.
        assertTrue(text.contains("CITY DIAGNOSTICS"))
        assertTrue(text.contains("Hemoglobin 13.2 g/dL"))
    }

    @Test
    fun `caps identity block beside UHID is masked`() {
        val text = redacted(
            """
            City Diagnostics
            ANJALI NAIR
            UHID: 8891452
            Hemoglobin 12.1 g/dL
            """.trimIndent()
        )

        assertFalse(text.contains("ANJALI NAIR"))
        assertTrue(text.contains("[NAME_1]"))
    }

    @Test
    fun `report headings and test names are not treated as names`() {
        val text = redacted(
            """
            COMPLETE BLOOD COUNT
            Name: CBC
            Hemoglobin 13.2 g/dL
            WBC 6400 /uL
            """.trimIndent()
        )

        assertTrue(text.contains("COMPLETE BLOOD COUNT"))
        assertTrue(text.contains("Name: CBC"))
    }

    @Test
    fun `unlabelled address block with pin code is masked`() {
        val text = redacted(
            """
            Flat 4B, Green Park
            Andheri West, Mumbai 400058
            Hemoglobin 13.2 g/dL
            """.trimIndent()
        )

        assertFalse(text.contains("Green Park"))
        assertFalse(text.contains("400058"))
        assertTrue(text.contains("Hemoglobin 13.2 g/dL"))
    }

    @Test
    fun `street and pin on separate lines stay one address block`() {
        val text = redacted(
            """
            12, MG Road
            Pune 411001
            Hemoglobin 13.2 g/dL
            """.trimIndent()
        )

        assertFalse(text.contains("MG Road"))
        assertFalse(text.contains("411001"))
    }

    @Test
    fun `laboratory address in clinical wording is left alone`() {
        val text = redacted(
            """
            Sample collected at 12 MG Road, Pune 411001
            Hemoglobin 13.2 g/dL
            """.trimIndent()
        )

        // Collection wording marks a facility line, not the patient's address.
        assertTrue(text.contains("Sample collected at"))
        assertTrue(text.contains("Hemoglobin 13.2 g/dL"))
    }

    @Test
    fun `ordinary clinical sentences are not masked as addresses`() {
        val text = redacted(
            """
            Patient advised to repeat the test after 3 weeks
            Hemoglobin 10.8 g/dL (13-17)
            WBC 6400 /uL (4000-11000)
            """.trimIndent()
        )

        assertTrue(text.contains("Patient advised to repeat the test"))
        assertTrue(text.contains("10.8 g/dL"))
        assertTrue(text.contains("13-17"))
    }
}
