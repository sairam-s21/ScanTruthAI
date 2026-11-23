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
    fun `flash uses the cloud only`() {
        assertEquals(
            listOf(InferenceAttempt.CLOUD),
            IngredientModePlanner.plan(AiMode.FLASH, nanoReady = true)
        )
        assertEquals(
            listOf(InferenceAttempt.CLOUD),
            IngredientModePlanner.plan(AiMode.FLASH, nanoReady = false)
        )
    }

    @Test
    fun `nano never plans a cloud attempt`() {
        assertEquals(
            listOf(InferenceAttempt.ON_DEVICE),
            IngredientModePlanner.plan(AiMode.NANO, nanoReady = true)
        )
        val unavailable = IngredientModePlanner.plan(AiMode.NANO, nanoReady = false)
        assertEquals(listOf(InferenceAttempt.ON_DEVICE), unavailable)
        assertFalse("NANO must never fall back to the cloud", unavailable.contains(InferenceAttempt.CLOUD))
    }

    @Test
    fun `auto prefers nano and falls back to the cloud`() {
        assertEquals(
            listOf(InferenceAttempt.ON_DEVICE, InferenceAttempt.CLOUD),
            IngredientModePlanner.plan(AiMode.AUTO, nanoReady = true)
        )
        assertEquals(
            listOf(InferenceAttempt.CLOUD),
            IngredientModePlanner.plan(AiMode.AUTO, nanoReady = false)
        )
    }

    @Test
    fun `only auto counts a cloud run as a fallback`() {
        assertTrue(IngredientModePlanner.cloudCountsAsFallback(AiMode.AUTO))
        assertFalse(IngredientModePlanner.cloudCountsAsFallback(AiMode.FLASH))
        assertFalse(IngredientModePlanner.cloudCountsAsFallback(AiMode.NANO))
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
