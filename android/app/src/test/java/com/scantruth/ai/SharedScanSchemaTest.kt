package com.scantruth.ai

import com.scantruth.ai.model.AiEngine
import com.scantruth.ai.model.FactorDirection
import com.scantruth.ai.model.HealthFactor
import com.scantruth.ai.model.IngredientAnalysis
import com.scantruth.ai.model.IngredientVerdict
import com.scantruth.ai.model.VerdictLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android scans must be saved in the SPEC.md schema that firestore.rules accepts. */
class SharedScanSchemaTest {

    private val rulesAllowedKeys = setOf(
        "productName", "productCategory", "safetyScore", "badgeLabel", "headline", "aiSummary",
        "healthFactors", "ingredientBreakdown", "recommendedIntake", "rawIngredients", "modelUsed",
        "scannedAt", "kind", "title", "subtitle", "reportId", "ingredientCount", "aiEngine"
    )

    private fun analysis(
        score: Int = 52,
        engine: AiEngine = AiEngine.CLOUD,
        headline: String = "Contains dairy",
        intake: String = "Half a serving"
    ) = IngredientAnalysis(
        productName = "Whey Protein",
        productCategory = "supplement",
        safetyScore = score,
        badgeLabel = "Use with care",
        headline = headline,
        aiSummary = "Whey contains lactose.",
        healthFactors = listOf(
            HealthFactor("Lactose", "Whey concentrate contains lactose.", FactorDirection.CAUTION),
            HealthFactor("Protein", "High quality protein.", FactorDirection.POSITIVE)
        ),
        ingredientBreakdown = listOf(
            IngredientVerdict("Whey protein concentrate", VerdictLevel.AVOID, "Lactose source"),
            IngredientVerdict("Cocoa", VerdictLevel.SAFE, null)
        ),
        recommendedIntake = intake,
        uncertaintyNotes = emptyList(),
        engine = engine
    )

    @Test
    fun usesOnlyFieldsTheRulesAllow() {
        val fields = FirestoreRepository.sharedScanFields(analysis(), listOf("Whey protein concentrate", "Cocoa"))
        assertTrue(rulesAllowedKeys.containsAll(fields.keys))
        assertEquals("Whey Protein", fields["productName"])
        assertEquals(listOf("Whey protein concentrate", "Cocoa"), fields["rawIngredients"])
        assertEquals(mapOf("guidance" to "Half a serving"), fields["recommendedIntake"])
        assertEquals("Gemini 3.5 Flash Lite", fields["modelUsed"])
    }

    @Test
    fun mapsFactorsAndVerdictsToSharedWording() {
        val fields = FirestoreRepository.sharedScanFields(analysis(), emptyList())

        @Suppress("UNCHECKED_CAST")
        val factors = fields["healthFactors"] as List<Map<String, Any>>
        assertEquals("Lactose", factors[0]["title"])
        assertEquals("warning", factors[0]["sentiment"])
        assertEquals("Caution", factors[0]["impactBadge"])
        assertEquals("positive", factors[1]["sentiment"])

        @Suppress("UNCHECKED_CAST")
        val breakdown = fields["ingredientBreakdown"] as List<Map<String, Any>>
        assertEquals("Avoid", breakdown[0]["status"])
        assertEquals("Okay", breakdown[1]["status"])
        assertFalse("A missing note is omitted, not saved as null", breakdown[1].containsKey("note"))
    }

    @Test
    fun badgeFollowsTheSharedRubric() {
        assertEquals("Good match for you", FirestoreRepository.sharedScanFields(analysis(score = 70), emptyList())["badgeLabel"])
        assertEquals("Use with care", FirestoreRepository.sharedScanFields(analysis(score = 40), emptyList())["badgeLabel"])
        assertEquals("Avoid", FirestoreRepository.sharedScanFields(analysis(score = 39), emptyList())["badgeLabel"])
        assertEquals(100, FirestoreRepository.sharedScanFields(analysis(score = 140), emptyList())["safetyScore"])
    }

    @Test
    fun trimsTextAndOmitsBlankFieldsToStayWithinRuleLimits() {
        val fields = FirestoreRepository.sharedScanFields(
            analysis(headline = "x".repeat(900), intake = " "),
            List(700) { "Ingredient $it" }
        )
        assertEquals(500, (fields["headline"] as String).length)
        assertEquals(500, (fields["rawIngredients"] as List<*>).size)
        assertFalse(fields.containsKey("recommendedIntake"))
    }

    @Test
    fun namesTheOnDeviceEngine() {
        val fields = FirestoreRepository.sharedScanFields(analysis(engine = AiEngine.ON_DEVICE), emptyList())
        assertEquals("Gemini Nano (On-Device)", fields["modelUsed"])
    }
}
