package com.scantruth.ai.ai

import com.scantruth.ai.model.AiMode

/** One engine a single analysis run is allowed to try, in order. */
internal enum class InferenceAttempt { ON_DEVICE, CLOUD }

/**
 * The ingredient engine policy, in one testable place:
 *
 *  - FLASH uses the cloud model only; that is the user's explicit choice, not a fallback.
 *  - NANO uses the on-device model only. It never falls back to the cloud, so the
 *    approved health profile never leaves the device in that mode.
 *  - AUTO prefers the on-device model and falls back to the cloud when Nano is not
 *    available or fails — exactly what the Scan screen promises.
 */
internal object IngredientModePlanner {

    fun plan(mode: AiMode, nanoReady: Boolean): List<InferenceAttempt> = when (mode) {
        AiMode.FLASH -> listOf(InferenceAttempt.CLOUD)
        AiMode.NANO -> listOf(InferenceAttempt.ON_DEVICE)
        AiMode.AUTO -> if (nanoReady) {
            listOf(InferenceAttempt.ON_DEVICE, InferenceAttempt.CLOUD)
        } else {
            listOf(InferenceAttempt.CLOUD)
        }
    }

    /**
     * In AUTO any cloud call is a fallback. In FLASH the cloud is what the user asked
     * for, and in NANO the cloud is never called at all.
     */
    fun cloudCountsAsFallback(mode: AiMode): Boolean = mode == AiMode.AUTO
}
