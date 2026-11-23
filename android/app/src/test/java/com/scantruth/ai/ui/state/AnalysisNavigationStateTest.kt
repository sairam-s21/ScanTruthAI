package com.scantruth.ai.ui.state

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying navigation and state lifecycle contracts for Phase 1:
 * - F-1: Ingredient analysis state lifecycle and clearAnalysis() preserving detected ingredients.
 * - F-5: Report analysis job cancellation via reject() and reset().
 * - F-6: Extraction state reset and review text clearing preventing stale navigation loops.
 */
class AnalysisNavigationStateTest {

    @Test
    fun `ingredient state clearAnalysis preserves ingredients but clears analysis result`() {
        val state = IngredientAnalysisState(Dispatchers.Unconfined)
        state.addIngredient("Sugar, Water, Citric Acid")
        assertEquals(3, state.ingredients.size)

        state.clearAnalysis()

        // Ingredients are preserved so user can edit without re-scanning
        assertEquals(3, state.ingredients.size)
        assertEquals(IngredientStep.Detected, state.step)
        assertNull(state.analysis)
        assertNull(state.saveNote)
    }

    @Test
    fun `ingredient state clearAnalysis with empty ingredients returns to Idle`() {
        val state = IngredientAnalysisState(Dispatchers.Unconfined)
        assertEquals(0, state.ingredients.size)

        state.clearAnalysis()

        assertEquals(IngredientStep.Idle, state.step)
        assertNull(state.analysis)
    }

    @Test
    fun `ingredient state cancel resets state to Idle and clears ingredients`() {
        val state = IngredientAnalysisState(Dispatchers.Unconfined)
        state.addIngredient("Sugar, Salt")
        assertEquals(2, state.ingredients.size)

        state.cancel()

        assertEquals(IngredientStep.Idle, state.step)
        assertTrue(state.ingredients.isEmpty())
        assertNull(state.analysis)
        assertFalse(state.isBusy)
    }

    @Test
    fun `report state reject cancels job and resets to Idle`() {
        val state = ReportAnalysisState(Dispatchers.Unconfined)
        assertFalse(state.isBusy)
        assertEquals(ReportStep.Idle, state.step)

        state.reject()

        assertEquals(ReportStep.Idle, state.step)
        assertEquals("", state.sourceName)
        assertEquals(0, state.pagesRead)
        assertNull(state.extraction)
        assertFalse(state.isBusy)
    }

    @Test
    fun `report state reset clears extraction and saved message`() {
        val state = ReportAnalysisState(Dispatchers.Unconfined)
        state.reset()

        assertEquals(ReportStep.Idle, state.step)
        assertNull(state.extraction)
        assertNull(state.savedMessage)
    }

    @Test
    fun `report state clearReviewText clears sensitive texts and alerts`() {
        val state = ReportAnalysisState(Dispatchers.Unconfined)
        state.clearReviewText()

        assertEquals("", state.redactedText)
        assertTrue(state.identifiers.isEmpty())
        assertTrue(state.alerts.isEmpty())
        assertEquals(0, state.replacements)
    }
}
