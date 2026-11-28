package com.scantruth.ai.model

/**
 * User-facing fallback copy. Kept in one place so the state holders, the Compose
 * notifications and the tests cannot drift apart.
 *
 * Both notices state what actually happened; neither is shown for a run that did not
 * fall back.
 */
object FallbackNotices {

    /** Shown on the privacy review when the on-device model could not assist masking. */
    const val PRIVACY_LOCAL_SHIELD =
        "Gemini Nano is unavailable. ScanTruthAI used its local rule-based privacy shield. " +
            "Review the redacted text before continuing."

    /**
     * Shown on an ingredient result only when a cloud request actually happened as a
     * fallback from the on-device path (Auto mode).
     */
    const val INGREDIENT_CLOUD_FALLBACK =
        "On-device ingredient analysis was unavailable. Cloud Gemini was used for analysis."

    /** Auto mode: the cloud failed and Gemini Nano answered instead (same text as iOS and web). */
    const val INGREDIENT_ON_DEVICE_FALLBACK =
        "Cloud Gemini was unavailable. Switched to Gemini Nano for on-device ingredient analysis."
}
