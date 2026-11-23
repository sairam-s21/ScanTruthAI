package com.scantruth.ai.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Saving a report must never replace what the user already stored. These tests pin down
 * the merge semantics used by FirestoreRepository.mergeExtraction(): union, not overwrite.
 */
class MedicalProfileMergeTest {

    private fun extraction(
        conditions: List<String> = emptyList(),
        allergies: List<String> = emptyList(),
        medications: List<String> = emptyList(),
        dietary: List<String> = emptyList(),
        notes: List<String> = emptyList()
    ) = MedicalExtraction(
        reportTitle = "Report",
        reportType = "Lab report",
        conditions = conditions,
        allergies = allergies,
        medications = medications,
        dietaryRestrictions = dietary,
        healthNotes = notes,
        markers = emptyList(),
        engine = AiEngine.CLOUD
    )

    @Test
    fun `merging a report keeps every existing entry`() {
        val existing = MedicalProfile(
            conditions = listOf("Anaemia"),
            medications = listOf("Iron")
        )
        val merged = existing.mergedWith(
            extraction(conditions = listOf("Thyroid"), medications = listOf("Vitamin D"))
        )

        assertEquals(listOf("Anaemia", "Thyroid"), merged.conditions)
        assertEquals(listOf("Iron", "Vitamin D"), merged.medications)
    }

    @Test
    fun `merging never drops a list the report does not mention`() {
        val existing = MedicalProfile(
            allergies = listOf("Penicillin"),
            dietaryRestrictions = listOf("Vegan")
        )
        val merged = existing.mergedWith(extraction())

        assertEquals(listOf("Penicillin"), merged.allergies)
        assertEquals(listOf("Vegan"), merged.dietaryRestrictions)
    }

    @Test
    fun `duplicates are collapsed case insensitively`() {
        val merged = MedicalProfile(conditions = listOf("Diabetes"))
            .mergedWith(extraction(conditions = listOf("diabetes", "Hypertension")))

        assertEquals(listOf("Diabetes", "Hypertension"), merged.conditions)
    }

    @Test
    fun `blank entries are ignored`() {
        val merged = MedicalProfile()
            .mergedWith(extraction(conditions = listOf("  ", "", "Asthma")))

        assertEquals(listOf("Asthma"), merged.conditions)
    }

    @Test
    fun `lists stay capped`() {
        val existing = MedicalProfile(conditions = (1..50).map { "Condition $it" })
        val merged = existing.mergedWith(extraction(conditions = listOf("New condition")))

        assertEquals(MedicalProfile.MAX_ITEMS_PER_LIST, merged.conditions.size)
    }

    @Test
    fun `health notes are appended without replacing earlier notes`() {
        val profile = MedicalProfile(healthNotes = listOf("First note"))
            .withHealthNote("Second note")

        assertEquals(listOf("First note", "Second note"), profile.healthNotes)
    }
}
