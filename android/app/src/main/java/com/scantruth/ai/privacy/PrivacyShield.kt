package com.scantruth.ai.privacy

import com.scantruth.ai.model.FallbackNotices
import com.scantruth.ai.model.IdentifierCategory

/**
 * What the optional on-device identifier scan produced.
 *
 * [Values] holds raw identifier strings, so it is process-local by construction: nothing
 * in this type is serialised, logged or sent anywhere. The deterministic masker is the
 * baseline whether or not Nano contributes.
 */
sealed interface NanoMaskScan {
    data class Values(val values: List<Pair<String, IdentifierCategory>>) : NanoMaskScan
    data object NoIdentifiers : NanoMaskScan
    data class Unavailable(val reason: String) : NanoMaskScan
}

/** Which engine contributed the identifier hints used for masking. */
enum class PrivacyShieldSource { NANO_ASSISTED, LOCAL_RULES }

/**
 * Typed privacy-shield outcome, so "Nano assisted the masker" can never be confused with
 * "the app fell back to the rule-based shield".
 */
data class PrivacyShield(
    val source: PrivacyShieldSource,
    val usedFallback: Boolean,
    val notice: String?
) {
    val nanoAssisted: Boolean get() = source == PrivacyShieldSource.NANO_ASSISTED

    companion object {
        val Baseline = PrivacyShield(
            source = PrivacyShieldSource.LOCAL_RULES,
            usedFallback = false,
            notice = null
        )
    }
}

/**
 * Decides whether a report used the on-device assist or the local fallback.
 *
 * The rule-based masker always runs. Nano only adds candidate identifiers, so a Nano
 * outage never blocks masking — it only changes the notice the reviewer sees.
 */
object PrivacyShieldPolicy {

    fun assess(nanoRequested: Boolean, scan: NanoMaskScan?): PrivacyShield {
        if (!nanoRequested) return PrivacyShield.Baseline
        return when (scan) {
            is NanoMaskScan.Values, NanoMaskScan.NoIdentifiers -> PrivacyShield(
                source = PrivacyShieldSource.NANO_ASSISTED,
                usedFallback = false,
                notice = null
            )

            is NanoMaskScan.Unavailable, null -> PrivacyShield(
                source = PrivacyShieldSource.LOCAL_RULES,
                usedFallback = true,
                notice = FallbackNotices.PRIVACY_LOCAL_SHIELD
            )
        }
    }

    /** The extra values Nano found, or none. Never contains anything but the raw values. */
    fun valuesOf(scan: NanoMaskScan?): List<Pair<String, IdentifierCategory>> =
        (scan as? NanoMaskScan.Values)?.values.orEmpty()
}
