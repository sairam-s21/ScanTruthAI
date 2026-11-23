package com.scantruth.ai.domain

import android.content.Context
import android.net.Uri
import com.scantruth.ai.ExtractedText
import com.scantruth.ai.HybridAIService
import com.scantruth.ai.OcrService
import com.scantruth.ai.model.IdentifierCategory
import com.scantruth.ai.privacy.MaskOutcome
import com.scantruth.ai.privacy.NanoMaskScan
import com.scantruth.ai.privacy.PrivacyMasker
import com.scantruth.ai.privacy.PrivacyShieldPolicy
import kotlinx.coroutines.CancellationException

/**
 * The medical-report pipeline's device-side stages.
 *
 * Order is fixed and non-negotiable: read the document, mask identifiers locally,
 * then (only after the user approves) hand the redacted text to the cloud. Nothing in
 * this object can send unredacted text anywhere.
 */
object ReportWorkflow {

    /** OCR + page-aware text extraction. Runs on the IO dispatcher inside OcrService. */
    suspend fun readDocument(
        context: Context,
        uri: Uri,
        displayName: String?
    ): ExtractedText = OcrService.extract(context, uri, displayName)

    /**
     * Optional on-device second opinion on identifiers. Returns candidate values that
     * stay in this process; the deterministic rules remain the baseline either way.
     */
    suspend fun detectExtras(
        document: ExtractedText,
        allowNanoAssist: Boolean
    ): NanoMaskScan {
        if (!allowNanoAssist) return NanoMaskScan.NoIdentifiers
        return try {
            HybridAIService.detectIdentifiersWithNano(document.plainText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The deterministic rules already ran; a missing assist must not block the
            // report. It is reported as the local shield, not as a silent success.
            NanoMaskScan.Unavailable(
                "Gemini Nano could not check this document on this device."
            )
        }
    }

    /**
     * Runs the on-device masker over the document text.
     *
     * @param extras values found by the optional on-device assist.
     * @param userAdded values the reviewer chose to hide during the privacy review. They
     *   are applied to the original text only, never to the cloud payload.
     */
    fun mask(
        document: ExtractedText,
        extras: List<Pair<String, IdentifierCategory>> = emptyList(),
        userAdded: List<Pair<String, IdentifierCategory>> = emptyList()
    ): MaskOutcome = PrivacyMasker.mask(document.plainText, extras + userAdded)

    /** Convenience for callers that do not offer a manual-correction pass. */
    suspend fun redact(
        document: ExtractedText,
        allowNanoAssist: Boolean
    ): MaskOutcome = mask(
        document,
        PrivacyShieldPolicy.valuesOf(detectExtras(document, allowNanoAssist))
    )
}
