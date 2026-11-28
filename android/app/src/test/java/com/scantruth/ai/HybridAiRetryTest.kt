package com.scantruth.ai

import com.google.firebase.ai.type.InvalidAPIKeyException
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.RequestTimeoutException
import com.google.firebase.ai.type.ServerException
import com.scantruth.ai.model.AiEngine
import com.scantruth.ai.ui.state.MyHealthUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HybridAiRetryTest {

    private val delays = mutableListOf<Long>()
    private var originalDelay: suspend (Long) -> Unit = {}

    private fun serverException(message: String): Exception =
        ServerException::class.java.getConstructor(String::class.java, Throwable::class.java)
            .newInstance(message, null) as Exception

    private fun quotaException(message: String): Exception =
        QuotaExceededException::class.java.getConstructor(String::class.java, Throwable::class.java)
            .newInstance(message, null) as Exception

    private fun apiKeyException(message: String): Exception =
        InvalidAPIKeyException::class.java.getConstructor(String::class.java, Throwable::class.java)
            .newInstance(message, null) as Exception

    private fun timeoutException(message: String): Exception =
        RequestTimeoutException::class.java.declaredConstructors[0].apply { isAccessible = true }
            .let { ctor ->
                when (ctor.parameterTypes.size) {
                    2 -> ctor.newInstance(message, null)
                    3 -> ctor.newInstance(message, null, emptyList<Any>())
                    else -> Exception(message)
                }
            } as Exception

    @Before
    fun setUp() {
        delays.clear()
        originalDelay = HybridAIService.backoffDelay
        HybridAIService.backoffDelay = { delayMs ->
            delays.add(delayMs)
        }
    }

    @After
    fun tearDown() {
        HybridAIService.backoffDelay = originalDelay
    }

    @Test
    fun `retry succeeds on attempt 2 after transient 503 ServerException`() = runBlocking {
        var attempts = 0
        val result = HybridAIService.executeWithRetry(AiEngine.CLOUD) {
            attempts++
            if (attempts == 1) {
                throw serverException(
                    "This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later."
                )
            }
            "{\"actions\":[{\"item\":\"Vitamin D\",\"category\":\"medications\",\"action\":\"ADD\"}]}"
        }

        assertEquals(2, attempts)
        assertEquals(1, delays.size)
        assertTrue("Attempt 1 delay should be >= 1000ms", delays[0] >= 1000L)
        assertTrue("Attempt 1 delay should include jitter (<= 1250ms)", delays[0] <= 1250L)
        assertTrue(result.contains("Vitamin D"))
    }

    @Test
    fun `retry succeeds on attempt 3 after transient 429 QuotaExceededException`() = runBlocking {
        var attempts = 0
        val result = HybridAIService.executeWithRetry(AiEngine.CLOUD) {
            attempts++
            if (attempts < 3) {
                throw quotaException("Resource exhausted (429 rate limit exceeded)")
            }
            "{\"actions\":[{\"item\":\"Diabetes\",\"category\":\"diseases\",\"action\":\"ADD\"}]}"
        }

        assertEquals(3, attempts)
        assertEquals(2, delays.size)
        assertTrue("Attempt 1 delay should be >= 1000ms", delays[0] >= 1000L && delays[0] <= 1250L)
        assertTrue("Attempt 2 delay should be >= 2000ms", delays[1] >= 2000L && delays[1] <= 2250L)
        assertTrue(result.contains("Diabetes"))
    }

    @Test
    fun `all retries exhausted throws AiAnalysisException with high demand message`() = runBlocking {
        var attempts = 0
        try {
            HybridAIService.executeWithRetry(AiEngine.CLOUD) {
                attempts++
                throw serverException(
                    "This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later."
                )
            }
            fail("Expected AiAnalysisException to be thrown")
        } catch (e: AiAnalysisException) {
            assertEquals(3, attempts)
            assertEquals(2, delays.size)
            assertEquals(AiFailureKind.CLOUD_UNAVAILABLE, e.kind)
            assertEquals(
                "The AI service is experiencing high demand. Please try again in a moment.",
                e.message
            )
        }
    }

    @Test
    fun `non-retryable error fails immediately on attempt 1 without retry`() = runBlocking {
        var attempts = 0
        try {
            HybridAIService.executeWithRetry(AiEngine.CLOUD) {
                attempts++
                throw apiKeyException("API key not valid")
            }
            fail("Expected AiAnalysisException to be thrown")
        } catch (e: AiAnalysisException) {
            assertEquals(1, attempts)
            assertEquals(0, delays.size)
            assertEquals(AiFailureKind.CLOUD_UNAVAILABLE, e.kind)
            assertEquals(
                "The cloud analysis could not be completed. Check your connection and try again.",
                e.message
            )
        }
    }

    @Test
    fun `permission denied or app check error fails immediately without retry`() = runBlocking {
        var attempts = 0
        try {
            HybridAIService.executeWithRetry(AiEngine.CLOUD) {
                attempts++
                throw Exception("403 Forbidden: AppCheck token verification failed")
            }
            fail("Expected AiAnalysisException to be thrown")
        } catch (e: AiAnalysisException) {
            assertEquals(1, attempts)
            assertEquals(0, delays.size)
            assertEquals(AiFailureKind.CLOUD_UNAVAILABLE, e.kind)
        }
    }

    @Test
    fun `CancellationException is rethrown immediately and not wrapped or retried`() = runBlocking {
        var attempts = 0
        try {
            HybridAIService.executeWithRetry(AiEngine.CLOUD) {
                attempts++
                throw CancellationException("Coroutine was cancelled")
            }
            fail("Expected CancellationException to be thrown")
        } catch (e: CancellationException) {
            assertEquals("Coroutine was cancelled", e.message)
            assertEquals(1, attempts)
            assertEquals(0, delays.size)
        }
    }

    @Test
    fun `on-device engine does not retry on failure`() = runBlocking {
        var attempts = 0
        try {
            HybridAIService.executeWithRetry(AiEngine.ON_DEVICE) {
                attempts++
                throw serverException("On-device model failure")
            }
            fail("Expected AiAnalysisException to be thrown")
        } catch (e: AiAnalysisException) {
            assertEquals(1, attempts)
            assertEquals(0, delays.size)
            assertEquals(AiFailureKind.UNKNOWN, e.kind)
        }
    }

    @Test
    fun `isRetryable accurately classifies transient and permanent errors`() {
        // Retryable
        assertTrue(HybridAIService.isRetryable(serverException("This model is currently experiencing high demand.")))
        assertTrue(HybridAIService.isRetryable(quotaException("Quota exceeded 429")))
        assertTrue(HybridAIService.isRetryable(timeoutException("Timed out")))
        assertTrue(HybridAIService.isRetryable(Exception("HTTP 503 Service Unavailable")))
        assertTrue(HybridAIService.isRetryable(Exception("429 Too Many Requests: resource exhausted")))

        // Non-retryable
        assertFalse(HybridAIService.isRetryable(CancellationException("Cancelled")))
        assertFalse(HybridAIService.isRetryable(apiKeyException("Bad key")))
        assertFalse(HybridAIService.isRetryable(Exception("401 Unauthorized")))
        assertFalse(HybridAIService.isRetryable(Exception("403 PERMISSION_DENIED: AppCheck failed")))
        assertFalse(HybridAIService.isRetryable(Exception("models/gemini-invalid is not found")))
    }

    @Test
    fun `MyHealthUiState clears loading state on cancellation`() {
        val state = MyHealthUiState()
        val job = Job()
        val scope = CoroutineScope(Dispatchers.Unconfined + job)

        state.updateAiAssistantInput("I have diabetes")
        // Cancel the job immediately so the launched coroutine is cancelled
        job.cancel()
        state.analyzeHealthStatement(scope)

        // Loading state should be cleared (false)
        assertFalse("Loading state must be cleared on cancellation", state.isAiAssistantAnalyzing)
    }
}
