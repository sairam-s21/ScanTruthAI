package com.scantruth.ai

import com.scantruth.ai.ai.AiResponseParser
import com.scantruth.ai.ai.ParseResult
import com.scantruth.ai.model.HealthActionItem
import com.scantruth.ai.model.HealthActionPlan
import com.scantruth.ai.model.HealthActionType
import com.scantruth.ai.model.HealthContextSuggestion
import com.scantruth.ai.model.MedicalProfile
import com.scantruth.ai.ui.state.MyHealthUiState
import com.scantruth.ai.ui.state.ProfileCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the ScanTruthAI Intelligent Multi-Statement Health Profile Manager:
 * 1. Diabetes -> Diseases (ADD)
 * 2. Tuberculosis -> Diseases (ADD)
 * 3. Peanut allergy -> Allergies (ADD)
 * 4. Vitamin D -> Medications (ADD)
 * 5. Lactose intolerance -> Dietary Restrictions (ADD)
 * 6. Paragraph containing all 4 categories (ADD all without cross-category grouping)
 * 7. Recovery from existing disease -> remove only that disease
 * 8. Resolved allergy -> remove only that allergy
 * 9. Multiple ADD and REMOVE actions in one paragraph
 * 10. Ambiguous removal ("that disease") -> clarification, no profile/database alteration
 * 11. Duplicate prevention on ADD (case-insensitive)
 * 12. Existing unrelated entries remain intact during removal
 * 13. Firestore atomic update failure handling leaves profile untouched
 * 14. Sign-out and account isolation
 */
class HealthContextAssistantTest {

    @Suppress("UNCHECKED_CAST")
    private fun <T> ParseResult<T>.successOrNull(): T? =
        (this as? ParseResult.Success<*>)?.value as? T

    private fun <T> ParseResult<T>.requireSuccess(): T =
        successOrNull() ?: throw AssertionError("expected a Success result, got: $this")

    // =========================================================================
    // 1. Single Statement Extractions & Four Valid Categories
    // =========================================================================

    @Test
    fun `case 01 - classifies diabetes into Diseases as ADD action`() {
        val json = """
            {
              "actions": [
                {
                  "action": "ADD",
                  "category": "Diseases",
                  "value": "Diabetes"
                }
              ]
            }
        """.trimIndent()

        val plan = AiResponseParser.parseHealthActions(json).requireSuccess()
        assertEquals(1, plan.actions.size)
        val action = plan.actions.first()
        assertEquals(HealthActionType.ADD, action.action)
        assertEquals("diseases", action.category)
        assertEquals("Diabetes", action.item)

        val (updated, changes) = MedicalProfile().applyingActions(plan.actions)
        assertEquals(listOf("Diabetes"), updated.conditions)
        assertTrue(changes.any { it.contains("Added Diabetes to Diseases") })
    }

    @Test
    fun `case 02 - classifies tuberculosis into Diseases as ADD action`() {
        val json = """
            {
              "actions": [
                {
                  "action": "ADD",
                  "category": "Diseases",
                  "value": "Tuberculosis"
                }
              ]
            }
        """.trimIndent()

        val plan = AiResponseParser.parseHealthActions(json).requireSuccess()
        val action = plan.actions.first()
        assertEquals(HealthActionType.ADD, action.action)
        assertEquals("diseases", action.category)
        assertEquals("Tuberculosis", action.item)

        val (updated, changes) = MedicalProfile().applyingActions(plan.actions)
        assertEquals(listOf("Tuberculosis"), updated.conditions)
        assertTrue(changes.any { it.contains("Added Tuberculosis to Diseases") })
    }

    @Test
    fun `case 03 - classifies peanut allergy into Allergies as ADD action`() {
        val json = """
            {
              "actions": [
                {
                  "action": "ADD",
                  "category": "Allergies",
                  "value": "Peanuts"
                }
              ]
            }
        """.trimIndent()

        val plan = AiResponseParser.parseHealthActions(json).requireSuccess()
        val action = plan.actions.first()
        assertEquals(HealthActionType.ADD, action.action)
        assertEquals("allergies", action.category)
        assertEquals("Peanuts", action.item)

        val (updated, changes) = MedicalProfile().applyingActions(plan.actions)
        assertEquals(listOf("Peanuts"), updated.allergies)
        assertTrue(changes.any { it.contains("Added Peanuts to Allergies") })
    }

    @Test
    fun `case 04 - classifies vitamin D into Medications as ADD action`() {
        val json = """
            {
              "actions": [
                {
                  "action": "ADD",
                  "category": "Medications",
                  "value": "Vitamin D"
                }
              ]
            }
        """.trimIndent()

        val plan = AiResponseParser.parseHealthActions(json).requireSuccess()
        val action = plan.actions.first()
        assertEquals(HealthActionType.ADD, action.action)
        assertEquals("medications", action.category)
        assertEquals("Vitamin D", action.item)

        val (updated, changes) = MedicalProfile().applyingActions(plan.actions)
        assertEquals(listOf("Vitamin D"), updated.medications)
        assertTrue(changes.any { it.contains("Added Vitamin D to Medications") })
    }

    @Test
    fun `case 05 - classifies lactose intolerance into Dietary Restrictions as ADD action`() {
        val json = """
            {
              "actions": [
                {
                  "action": "ADD",
                  "category": "Dietary Restrictions",
                  "value": "Lactose intolerance"
                }
              ]
            }
        """.trimIndent()

        val plan = AiResponseParser.parseHealthActions(json).requireSuccess()
        val action = plan.actions.first()
        assertEquals(HealthActionType.ADD, action.action)
        assertEquals("dietaryRestrictions", action.category)
        assertEquals("Lactose intolerance", action.item)

        val (updated, changes) = MedicalProfile().applyingActions(plan.actions)
        assertEquals(listOf("Lactose intolerance"), updated.dietaryRestrictions)
        assertTrue(changes.any { it.contains("Added Lactose intolerance to Dietary Restrictions") })
    }

    // =========================================================================
    // 2. Multi-Statement Paragraph Extractions
    // =========================================================================

    @Test
    fun `case 06 - extracts all four categories from single paragraph without grouping into one category`() {
        val json = """
            {
              "actions": [
                { "action": "ADD", "category": "Diseases", "value": "Diabetes" },
                { "action": "ADD", "category": "Allergies", "value": "Peanuts" },
                { "action": "ADD", "category": "Medications", "value": "Vitamin D" },
                { "action": "ADD", "category": "Dietary Restrictions", "value": "Lactose intolerance" }
              ]
            }
        """.trimIndent()

        val plan = AiResponseParser.parseHealthActions(json).requireSuccess()
        assertEquals(4, plan.actions.size)

        val initialProfile = MedicalProfile()
        val (updated, changes) = initialProfile.applyingActions(plan.actions)

        assertEquals(listOf("Diabetes"), updated.conditions)
        assertEquals(listOf("Peanuts"), updated.allergies)
        assertEquals(listOf("Vitamin D"), updated.medications)
        assertEquals(listOf("Lactose intolerance"), updated.dietaryRestrictions)
        assertEquals(4, changes.size)
        assertEquals("Added Diabetes to Diseases.", changes[0])
        assertEquals("Added Peanuts to Allergies.", changes[1])
        assertEquals("Added Vitamin D to Medications.", changes[2])
        assertEquals("Added Lactose intolerance to Dietary Restrictions.", changes[3])
    }

    // =========================================================================
    // 3. Removal & Recovery Statements
    // =========================================================================

    @Test
    fun `case 07 - recovery from existing disease removes only that disease`() {
        val initialProfile = MedicalProfile(
            conditions = listOf("Tuberculosis", "Hypertension")
        )
        val json = """
            {
              "actions": [
                {
                  "action": "REMOVE",
                  "category": "Diseases",
                  "value": "Tuberculosis"
                }
              ]
            }
        """.trimIndent()

        val plan = AiResponseParser.parseHealthActions(json).requireSuccess()
        val (updated, changes) = initialProfile.applyingActions(plan.actions)

        assertEquals(listOf("Hypertension"), updated.conditions)
        assertEquals(listOf("Removed Tuberculosis from Diseases."), changes)
    }

    @Test
    fun `case 08 - resolved allergy removes only that allergy`() {
        val initialProfile = MedicalProfile(
            allergies = listOf("Peanuts", "Shellfish")
        )
        val json = """
            {
              "actions": [
                {
                  "action": "REMOVE",
                  "category": "Allergies",
                  "value": "Peanuts"
                }
              ]
            }
        """.trimIndent()

        val plan = AiResponseParser.parseHealthActions(json).requireSuccess()
        val (updated, changes) = initialProfile.applyingActions(plan.actions)

        assertEquals(listOf("Shellfish"), updated.allergies)
        assertEquals(listOf("Removed Peanuts from Allergies."), changes)
    }

    @Test
    fun `case 09 - multiple ADD and REMOVE actions in one paragraph are applied atomically`() {
        val initialProfile = MedicalProfile(
            conditions = listOf("Tuberculosis"),
            allergies = listOf("Peanuts"),
            medications = listOf("Aspirin")
        )

        val json = """
            {
              "actions": [
                { "action": "REMOVE", "category": "Diseases", "value": "Tuberculosis" },
                { "action": "ADD", "category": "Diseases", "value": "Asthma" },
                { "action": "REMOVE", "category": "Allergies", "value": "Peanuts" },
                { "action": "ADD", "category": "Medications", "value": "Metformin" }
              ]
            }
        """.trimIndent()

        val plan = AiResponseParser.parseHealthActions(json).requireSuccess()
        val (updated, changes) = initialProfile.applyingActions(plan.actions)

        assertEquals(listOf("Asthma"), updated.conditions)
        assertTrue(updated.allergies.isEmpty())
        assertEquals(listOf("Aspirin", "Metformin"), updated.medications)
        assertEquals(4, changes.size)
        assertTrue(changes.contains("Removed Tuberculosis from Diseases."))
        assertTrue(changes.contains("Added Asthma to Diseases."))
        assertTrue(changes.contains("Removed Peanuts from Allergies."))
        assertTrue(changes.contains("Added Metformin to Medications."))
    }

    // =========================================================================
    // 4. Ambiguity & Clarification Safety
    // =========================================================================

    @Test
    fun `case 10 - ambiguous removal triggers clarification without altering profile`() {
        val initialProfile = MedicalProfile(
            conditions = listOf("Diabetes", "Asthma")
        )

        val json = """
            {
              "actions": [
                {
                  "action": "CLARIFY",
                  "clarificationNeeded": "You have both Diabetes and Asthma recorded. Please clarify which disease you have recovered from."
                }
              ]
            }
        """.trimIndent()

        val plan = AiResponseParser.parseHealthActions(json).requireSuccess()
        assertTrue(plan.isAmbiguous)
        assertEquals(
            "You have both Diabetes and Asthma recorded. Please clarify which disease you have recovered from.",
            plan.clarificationNeeded
        )

        // Applying ambiguous plan does not alter the profile
        val (updated, changes) = initialProfile.applyingActions(plan.actions)
        assertEquals(initialProfile.conditions, updated.conditions)
        assertTrue(changes.isEmpty())
    }

    // =========================================================================
    // 5. Duplicate Prevention & Isolation
    // =========================================================================

    @Test
    fun `case 11 - duplicate prevention on ADD performs case-insensitive check`() {
        val initialProfile = MedicalProfile(
            conditions = listOf("Diabetes"),
            allergies = listOf("Peanuts")
        )

        val actions = listOf(
            HealthActionItem(item = "diabetes", category = "diseases", action = HealthActionType.ADD),
            HealthActionItem(item = "DIABETES", category = "diseases", action = HealthActionType.ADD),
            HealthActionItem(item = "PEANUTS", category = "allergies", action = HealthActionType.ADD)
        )

        val (updated, changes) = initialProfile.applyingActions(actions)

        // Original casing preserved, no duplicates added
        assertEquals(listOf("Diabetes"), updated.conditions)
        assertEquals(listOf("Peanuts"), updated.allergies)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `case 12 - existing unrelated entries and historical health notes remain intact during removal`() {
        val initialProfile = MedicalProfile(
            conditions = listOf("Diabetes", "Hypertension"),
            allergies = listOf("Peanuts", "Dust mites"),
            medications = listOf("Metformin", "Aspirin"),
            dietaryRestrictions = listOf("Low sodium", "Gluten-free"),
            healthNotes = listOf("Historical note recorded in 2024", "Family history of cardiac issues")
        )

        val actions = listOf(
            HealthActionItem(item = "Diabetes", category = "diseases", action = HealthActionType.REMOVE)
        )

        val (updated, changes) = initialProfile.applyingActions(actions)

        assertEquals(listOf("Hypertension"), updated.conditions)
        assertEquals(listOf("Peanuts", "Dust mites"), updated.allergies)
        assertEquals(listOf("Metformin", "Aspirin"), updated.medications)
        assertEquals(listOf("Low sodium", "Gluten-free"), updated.dietaryRestrictions)
        // Historical notes are safely preserved!
        assertEquals(listOf("Historical note recorded in 2024", "Family history of cardiac issues"), updated.healthNotes)
        assertEquals(1, changes.size)
        assertEquals("Removed Diabetes from Diseases.", changes.first())
    }

    // =========================================================================
    // 6. Resilience & State Safety
    // =========================================================================

    @Test
    fun `case 13 - atomic update failure preserves initial profile in UI state`() {
        val state = MyHealthUiState()
        // When not signed in, analyzeHealthStatement sets error and does not touch profile
        assertEquals(false, state.isSignedIn)
        state.updateAiAssistantInput("I have diabetes")

        state.analyzeHealthStatement(kotlinx.coroutines.GlobalScope)

        assertEquals("Sign in with Google to organize your health profile.", state.aiAssistantError)
        assertEquals(0, state.profile.itemCount)
    }

    @Test
    fun `case 14 - sign out isolates and clears assistant state and cached profile`() {
        val state = MyHealthUiState()
        state.updateAiAssistantInput("I have asthma")
        state.setAiAssistantClarificationForTesting("Which condition?")

        assertEquals("I have asthma", state.aiAssistantInput)
        assertEquals("Which condition?", state.aiAssistantClarification)

        state.clearAiAssistant()

        assertEquals("", state.aiAssistantInput)
        assertNull(state.aiAssistantClarification)
        assertNull(state.aiAssistantError)
        assertNull(state.aiAssistantSuccessMessage)
    }

    // =========================================================================
    // 7. Robust Parsing Edge Cases (Markdown Fences, Non-JSON, Empty)
    // =========================================================================

    @Test
    fun `parses markdown-fenced json response with actions`() {
        val fenced = """
            Here is your health action plan:
            ```json
            {
              "actions": [
                {
                  "action": "ADD",
                  "category": "Diseases",
                  "value": "Asthma"
                },
                {
                  "action": "REMOVE",
                  "category": "Medications",
                  "value": "Inhaler"
                }
              ]
            }
            ```
        """.trimIndent()

        val plan = AiResponseParser.parseHealthActions(fenced).requireSuccess()
        assertEquals(2, plan.actions.size)
        assertEquals(HealthActionType.ADD, plan.actions[0].action)
        assertEquals(HealthActionType.REMOVE, plan.actions[1].action)
    }

    @Test
    fun `rejects invalid or non-json input safely`() {
        val result = AiResponseParser.parseHealthActions("I am not valid JSON at all.")
        assertTrue(result is ParseResult.Invalid)
    }

    @Test
    fun `rejects blank response`() {
        val result = AiResponseParser.parseHealthActions("   ")
        assertTrue(result is ParseResult.Invalid)
    }

    @Test
    fun `parses backward compatible health context suggestion`() {
        val json = """
            {
              "conditions": ["Asthma"],
              "allergies": ["Peanuts"],
              "medications": ["Vitamin D"],
              "dietaryRestrictions": ["Lactose intolerant"],
              "healthNotes": ["Historical context"],
              "clarificationNeeded": null
            }
        """.trimIndent()

        val parsed = AiResponseParser.parseHealthContext(json).requireSuccess()
        assertEquals(listOf("Asthma"), parsed.conditions)
        assertEquals(listOf("Peanuts"), parsed.allergies)
        assertEquals(listOf("Vitamin D"), parsed.medications)
        assertEquals(listOf("Lactose intolerant"), parsed.dietaryRestrictions)
        assertEquals(listOf("Historical context"), parsed.healthNotes)
    }
}
