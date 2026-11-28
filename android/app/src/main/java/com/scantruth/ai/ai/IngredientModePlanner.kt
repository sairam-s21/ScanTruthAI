package com.scantruth.ai.ai

import com.scantruth.ai.model.AiMode

/** One engine a single analysis run is allowed to try. */
internal enum class InferenceAttempt { CLOUD, NANO, OFFLINE }

/**
 * The ingredient engine policy, in one testable place:
 *
 *  - AUTO uses Gemini Flash in the cloud directly.
 *  - NANO uses Gemini Nano via Firebase AI Logic / Google AICore only on supported devices.
 *    It never falls back to the cloud.
 *  - ON_DEVICE uses the independent offline analyzer (local ingredient database, OCR output,
 *    and deterministic safety rules). It never checks or requires Gemini Nano, AICore, or cloud.
 */
internal object IngredientModePlanner {

    fun plan(mode: AiMode): List<InferenceAttempt> = when (mode) {
        AiMode.AUTO -> listOf(InferenceAttempt.CLOUD)
        AiMode.NANO -> listOf(InferenceAttempt.NANO)
        AiMode.ON_DEVICE -> listOf(InferenceAttempt.OFFLINE)
    }

    /** Cloud calls in AUTO are explicit Gemini Flash runs, not silent fallbacks. */
    fun cloudCountsAsFallback(mode: AiMode): Boolean = false
}
