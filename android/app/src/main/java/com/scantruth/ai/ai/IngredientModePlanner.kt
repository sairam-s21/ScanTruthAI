package com.scantruth.ai.ai

import com.scantruth.ai.model.AiMode

/** One engine a single analysis run is allowed to try, in order. */
internal enum class InferenceAttempt { ON_DEVICE, CLOUD }

/**
 * The ingredient engine policy, in one testable place, the same as on iOS and the web:
 *
 *  - FLASH uses the cloud model only.
 *  - NANO uses the on-device model only. It never falls back to the cloud, so the
 *    approved health profile never leaves the device in that mode.
 *  - AUTO uses the cloud model and falls back to the on-device model when the cloud fails
 *    and Nano is ready.
 */
internal object IngredientModePlanner {

    fun plan(mode: AiMode, nanoReady: Boolean): List<InferenceAttempt> = when (mode) {
        AiMode.FLASH -> listOf(InferenceAttempt.CLOUD)
        AiMode.NANO -> listOf(InferenceAttempt.ON_DEVICE)
        AiMode.AUTO -> if (nanoReady) {
            listOf(InferenceAttempt.CLOUD, InferenceAttempt.ON_DEVICE)
        } else {
            listOf(InferenceAttempt.CLOUD)
        }
    }

    /** Only in AUTO is an on-device run a fallback; in NANO it is what the user asked for. */
    fun onDeviceCountsAsFallback(mode: AiMode): Boolean = mode == AiMode.AUTO
}
