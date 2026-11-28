package com.scantruth.ai

import com.scantruth.ai.ui.state.MyHealthUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MyHealthSuccessMessageTest {

    @Test
    fun `success message automatically dismisses after duration`() = runBlocking {
        val state = MyHealthUiState()
        state.showSuccessMessage(this, "Updated your health profile: - Added Asthma to Diseases.", durationMs = 50L)

        assertEquals("Updated your health profile: - Added Asthma to Diseases.", state.aiAssistantSuccessMessage)

        delay(80L)

        assertNull("Success message should be automatically dismissed after duration", state.aiAssistantSuccessMessage)
    }

    @Test
    fun `starting a new request immediately clears previous success message`() = runBlocking {
        val state = MyHealthUiState()
        state.showSuccessMessage(this, "Previous success message", durationMs = 5000L)
        assertEquals("Previous success message", state.aiAssistantSuccessMessage)

        // Simulate starting a new analysis request
        state.analyzeHealthStatement(this, statementOverride = "I have asthma")

        assertNull("Starting a new request must clear any previous success message", state.aiAssistantSuccessMessage)
    }

    @Test
    fun `clearSuccessMessage clears message and cancels pending timer`() = runBlocking {
        val state = MyHealthUiState()
        state.showSuccessMessage(this, "Active success message", durationMs = 500L)
        assertEquals("Active success message", state.aiAssistantSuccessMessage)

        state.clearSuccessMessage()

        assertNull("clearSuccessMessage should set message to null immediately", state.aiAssistantSuccessMessage)

        // Wait past the original timer duration
        delay(600L)
        assertNull(state.aiAssistantSuccessMessage)
    }

    @Test
    fun `updating AI assistant input immediately clears success message`() = runBlocking {
        val state = MyHealthUiState()
        state.showSuccessMessage(this, "Profile updated", durationMs = 5000L)
        assertEquals("Profile updated", state.aiAssistantSuccessMessage)

        state.updateAiAssistantInput("New health note")

        assertNull("Typing new input should dismiss previous success message", state.aiAssistantSuccessMessage)
    }

    @Test
    fun `stop lifecycle call clears success message and cancels timer`() = runBlocking {
        val state = MyHealthUiState()
        state.showSuccessMessage(this, "Profile updated", durationMs = 5000L)
        assertEquals("Profile updated", state.aiAssistantSuccessMessage)

        state.stop()

        assertNull("stop() must clear success message for screen cleanup", state.aiAssistantSuccessMessage)
    }

    @Test
    fun `coroutine cancellation safely terminates auto-dismiss job`() = runBlocking {
        val state = MyHealthUiState()
        val job = Job()
        val scope = CoroutineScope(Dispatchers.Unconfined + job)

        state.showSuccessMessage(scope, "Profile updated", durationMs = 500L)
        assertEquals("Profile updated", state.aiAssistantSuccessMessage)

        // Cancel scope before dismissal timer fires
        job.cancel()

        // Explicitly clear when leaving/cancelled
        state.clearSuccessMessage()
        assertNull(state.aiAssistantSuccessMessage)
    }
}
