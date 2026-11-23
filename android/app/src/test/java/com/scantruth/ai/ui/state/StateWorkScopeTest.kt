package com.scantruth.ai.ui.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the bug that stalled both flows: OCR/AI work was launched in a
 * screen's `rememberCoroutineScope()`, which Compose cancels as soon as navigation removes
 * that screen. The state holders now own their work, so these tests pin down the two
 * properties that fix relies on: navigation must not cancel work, and an explicit cancel
 * must stop it and swallow the late result.
 */
class StateWorkScopeTest {

    @Test
    fun `work survives the screen scope being cancelled`() = runBlocking {
        val work = StateWorkScope(Dispatchers.Unconfined)
        val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val response = CompletableDeferred<Unit>()
        var analysisFinished = false

        val job = work.launch {
            response.await()
            analysisFinished = true
        }

        // What navigation does to rememberCoroutineScope.
        screenScope.cancel()
        assertFalse("navigation alone must not cancel analysis", job.isCancelled)

        response.complete(Unit)
        job.join()

        assertTrue("analysis started before navigation still finishes", analysisFinished)
        assertTrue(work.isActive)
    }

    @Test
    fun `an explicit cancel stops pending work and drops the late result`() = runBlocking {
        val work = StateWorkScope(Dispatchers.Unconfined)
        val response = CompletableDeferred<Unit>()
        var analysisApplied = false

        val job = work.launch {
            response.await()
            analysisApplied = true
        }

        work.cancelChildren()
        // The cloud answer arrives after the user cancelled.
        response.complete(Unit)
        job.join()

        assertTrue(job.isCancelled)
        assertFalse("a late AI response must not be applied", analysisApplied)
        assertTrue("the holder stays usable for the next scan", work.isActive)
    }

    @Test
    fun `a new run can start after cancellation`() = runBlocking {
        val work = StateWorkScope(Dispatchers.Unconfined)

        work.launch { CompletableDeferred<Unit>().await() }
        work.cancelChildren()

        var secondRunFinished = false
        val second = work.launch { secondRunFinished = true }
        second.join()

        assertTrue(secondRunFinished)
    }

    @Test
    fun `cancelling before anything started is safe`() {
        val work = StateWorkScope(Dispatchers.Unconfined)
        work.cancelChildren()
        assertTrue(work.isActive)
    }

    @Test
    fun `ingredient state owns its work and cancels cleanly`() {
        val state = IngredientAnalysisState(Dispatchers.Unconfined)
        assertFalse(state.isBusy)

        state.cancel()
        assertFalse(state.isBusy)
        assertTrue(state.ingredients.isEmpty())
        assertTrue(state.analysis == null)
        assertTrue(state.saveNote == null)
    }
}
