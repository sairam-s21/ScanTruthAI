package com.scantruth.ai.ai

import com.scantruth.ai.model.AiEngine
import com.scantruth.ai.model.AiMode
import com.scantruth.ai.model.FallbackNotices
import com.scantruth.ai.model.IngredientAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The documented engine policy is pinned here, mode by mode. */
class IngredientModePlannerTest {

    @Test
    fun `auto uses Gemini Flash cloud directly without fallback notice`() {
        assertEquals(
            listOf(InferenceAttempt.CLOUD),
            IngredientModePlanner.plan(AiMode.AUTO)
        )
        assertFalse(IngredientModePlanner.cloudCountsAsFallback(AiMode.AUTO))
    }

    @Test
    fun `nano plans only Gemini Nano and never falls back to cloud`() {
        val plan = IngredientModePlanner.plan(AiMode.NANO)
        assertEquals(listOf(InferenceAttempt.NANO), plan)
        assertFalse("NANO must never fall back to the cloud", plan.contains(InferenceAttempt.CLOUD))
        assertFalse(IngredientModePlanner.cloudCountsAsFallback(AiMode.NANO))
    }

    @Test
    fun `on-device plans independent offline analyzer without Nano or cloud`() {
        val plan = IngredientModePlanner.plan(AiMode.ON_DEVICE)
        assertEquals(listOf(InferenceAttempt.OFFLINE), plan)
        assertFalse(plan.contains(InferenceAttempt.CLOUD))
        assertFalse(plan.contains(InferenceAttempt.NANO))
        assertFalse(IngredientModePlanner.cloudCountsAsFallback(AiMode.ON_DEVICE))
    }

    @Test
    fun `an on-device result carries no fallback metadata`() {
        val analysis = IngredientAnalysis(
            productName = "Oat Bar",
            productCategory = "Snack",
            safetyScore = 74,
            badgeLabel = "Good match",
            headline = "Fine",
            aiSummary = "",
            healthFactors = emptyList(),
            ingredientBreakdown = emptyList(),
            recommendedIntake = "",
            uncertaintyNotes = emptyList(),
            engine = AiEngine.ON_DEVICE
        )

        assertFalse(analysis.usedFallback)
        assertNull(analysis.fallbackNotice)
    }

    @Test
    fun `a cloud fallback carries the cloud notice`() {
        val analysis = IngredientAnalysis(
            productName = "Oat Bar",
            productCategory = "Snack",
            safetyScore = 74,
            badgeLabel = "Good match",
            headline = "Fine",
            aiSummary = "",
            healthFactors = emptyList(),
            ingredientBreakdown = emptyList(),
            recommendedIntake = "",
            uncertaintyNotes = emptyList(),
            engine = AiEngine.CLOUD,
            usedFallback = true,
            fallbackNotice = FallbackNotices.INGREDIENT_CLOUD_FALLBACK
        )

        assertTrue(analysis.usedFallback)
        assertEquals(
            "On-device ingredient analysis was unavailable. Cloud Gemini was used for analysis.",
            analysis.fallbackNotice
        )
    }
}
