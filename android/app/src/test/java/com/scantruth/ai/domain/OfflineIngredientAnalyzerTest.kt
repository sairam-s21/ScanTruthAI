package com.scantruth.ai.domain

import com.scantruth.ai.HybridAIService
import com.scantruth.ai.model.AiEngine
import com.scantruth.ai.model.AiMode
import com.scantruth.ai.model.MedicalProfile
import com.scantruth.ai.model.SafetyBand
import com.scantruth.ai.model.VerdictLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineIngredientAnalyzerTest {

    @Test
    fun `safe recognized ingredients classify as SAFE with good match score`() {
        val result = OfflineIngredientAnalyzer.analyze(
            ingredients = listOf("Water", "Rolled Oats", "Cocoa Powder", "Sunflower Oil"),
            profile = MedicalProfile()
        )

        assertEquals(AiEngine.OFFLINE, result.engine)
        assertFalse(result.usedFallback)
        assertNull(result.fallbackNotice)
        assertEquals(SafetyBand.GOOD, result.band)
        assertTrue(result.safetyScore >= 70)
        assertTrue(result.ingredientBreakdown.all { it.verdict == VerdictLevel.SAFE })
    }

    @Test
    fun `allergy profile match classifies as AVOID and enforces strict score cap`() {
        val result = OfflineIngredientAnalyzer.analyze(
            ingredients = listOf("Refined Wheat Flour (Maida)", "Sugar", "Milk Solids", "Salt"),
            profile = MedicalProfile(allergies = listOf("Milk", "Wheat"))
        )

        assertEquals(AiEngine.OFFLINE, result.engine)
        assertEquals(SafetyBand.AVOID, result.band)
        assertTrue("Allergy conflict must score <= 39", result.safetyScore <= 39)
        val milk = result.ingredientBreakdown.first { it.name.contains("Milk") }
        val wheat = result.ingredientBreakdown.first { it.name.contains("Wheat") }
        assertEquals(VerdictLevel.AVOID, milk.verdict)
        assertEquals(VerdictLevel.AVOID, wheat.verdict)
        assertTrue(result.healthFactors.any { it.label.equals("Allergen", ignoreCase = true) })
    }

    @Test
    fun `ocr allergen declarations catch conflicts even if ingredients omit them`() {
        val result = OfflineIngredientAnalyzer.analyze(
            ingredients = listOf("Cocoa Solids", "Water"),
            profile = MedicalProfile(allergies = listOf("Peanuts")),
            allergenDeclarations = listOf("May contain traces of Peanuts and Tree Nuts")
        )

        assertEquals(SafetyBand.AVOID, result.band)
        assertTrue(result.safetyScore <= 39)
        assertTrue(result.aiSummary.contains("Peanuts", ignoreCase = true))
    }

    @Test
    fun `diabetes and nutrition rules classify sugar and palm oil as CAUTION`() {
        val result = OfflineIngredientAnalyzer.analyze(
            ingredients = listOf("Water", "Sugar", "Palm Oil"),
            profile = MedicalProfile(conditions = listOf("Diabetes"))
        )

        val sugar = result.ingredientBreakdown.first { it.name == "Sugar" }
        val palm = result.ingredientBreakdown.first { it.name == "Palm Oil" }
        val water = result.ingredientBreakdown.first { it.name == "Water" }
        assertEquals(VerdictLevel.SAFE, water.verdict)
        assertEquals(VerdictLevel.CAUTION, sugar.verdict)
        assertEquals(VerdictLevel.CAUTION, palm.verdict)
        assertEquals(SafetyBand.CARE, result.band)
    }

    @Test
    fun `unrecognized ingredients stay UNKNOWN and are never assumed SAFE`() {
        val result = OfflineIngredientAnalyzer.analyze(
            ingredients = listOf("Water", "Xyloprofen-9", "INS 999"),
            profile = MedicalProfile()
        )

        val water = result.ingredientBreakdown.first { it.name == "Water" }
        val unknown1 = result.ingredientBreakdown.first { it.name == "Xyloprofen-9" }
        val unknown2 = result.ingredientBreakdown.first { it.name == "INS 999" }

        assertEquals(VerdictLevel.SAFE, water.verdict)
        assertEquals(VerdictLevel.UNKNOWN, unknown1.verdict)
        assertEquals(VerdictLevel.UNKNOWN, unknown2.verdict)
        assertTrue(result.uncertaintyNotes.any { it.contains("UNKNOWN", ignoreCase = true) })

        val allUnknown = OfflineIngredientAnalyzer.analyze(
            ingredients = listOf("Zeta-Compound-42", "Proprietary Binder X"),
            profile = MedicalProfile()
        )
        assertTrue(allUnknown.ingredientBreakdown.all { it.verdict == VerdictLevel.UNKNOWN })
        assertTrue("All-unknown product must never score >= 70", allUnknown.safetyScore < 70)
    }

    @Test
    fun `on-device mode in HybridAIService never calls Gemini Nano or Gemini Flash`() = runBlocking {
        val result = HybridAIService.analyzeIngredientsWith(
            ingredients = listOf("Wheat Flour", "Unknown Additive 77", "Water"),
            profile = MedicalProfile(allergies = listOf("Gluten")),
            mode = AiMode.ON_DEVICE,
            nanoChecker = { throw AssertionError("ON_DEVICE mode must not check Gemini Nano / AICore") },
            cloudRunner = { _, _ -> throw AssertionError("ON_DEVICE mode must not call Gemini Flash cloud") },
            nanoRunner = { _, _ -> throw AssertionError("ON_DEVICE mode must not invoke Gemini Nano") }
        )

        assertEquals(AiEngine.OFFLINE, result.engine)
        assertEquals(SafetyBand.AVOID, result.band)
        assertEquals(
            VerdictLevel.AVOID,
            result.ingredientBreakdown.first { it.name == "Wheat Flour" }.verdict
        )
        assertEquals(
            VerdictLevel.UNKNOWN,
            result.ingredientBreakdown.first { it.name == "Unknown Additive 77" }.verdict
        )
        assertEquals(
            VerdictLevel.SAFE,
            result.ingredientBreakdown.first { it.name == "Water" }.verdict
        )
    }
}
