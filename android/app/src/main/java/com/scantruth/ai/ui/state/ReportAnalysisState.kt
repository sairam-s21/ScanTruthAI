package com.scantruth.ai.ui.state

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.scantruth.ai.AiAnalysisException
import com.scantruth.ai.ExtractedText
import com.scantruth.ai.FirestoreRepository
import com.scantruth.ai.FirestoreRepository.StoreResult
import com.scantruth.ai.HybridAIService
import com.scantruth.ai.OcrException
import com.scantruth.ai.domain.ReportWorkflow
import com.scantruth.ai.model.DetectedIdentifier
import com.scantruth.ai.model.IdentifierCategory
import com.scantruth.ai.model.MedicalExtraction
import com.scantruth.ai.model.PrivacyAlert
import com.scantruth.ai.privacy.MaskOutcome
import com.scantruth.ai.privacy.NanoMaskScan
import com.scantruth.ai.privacy.PrivacyShield
import com.scantruth.ai.privacy.PrivacyShieldPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the medical-report flow currently is. */
sealed interface ReportStep {
    data object Idle : ReportStep
    data class Working(val message: String) : ReportStep
    data object Review : ReportStep
    data object Extracting : ReportStep
    data object ExtractionReady : ReportStep
    data object Saved : ReportStep
    data class Failed(val message: String, val cloudBlocked: Boolean = false) : ReportStep
}

/**
 * Holds the whole medical-report flow for one document: read → mask → user review →
 * cloud extraction → save.
 *
 * Shared between the Scan screen, My Health, the privacy review screen and the result
 * screen, so a document started anywhere finishes in the same flow.
 *
 * The unredacted extraction lives only in this object's memory, is used solely to
 * re-run the on-device masker when the reviewer adds a missed identifier, and is dropped
 * as soon as the flow leaves the review stage, is rejected, or the user signs out.
 */
@Stable
class ReportAnalysisState(
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {

    private companion object {
        const val MAX_USER_REDACTIONS = 20
    }

    var step by mutableStateOf<ReportStep>(ReportStep.Idle)
        private set

    /** Notified when a report and its extraction have been saved to Firestore. */
    var onReportSaved: (() -> Unit)? = null

    var sourceName by mutableStateOf("")
        private set

    var pagesRead by mutableStateOf(0)
        private set

    var truncated by mutableStateOf(false)
        private set

    /** Pages that produced no readable text, so the review is explicitly partial. */
    var unreadablePages by mutableStateOf(0)
        private set

    var unreadablePageNumbers by mutableStateOf<List<Int>>(emptyList())
        private set

    var coverageNote by mutableStateOf<String?>(null)
        private set

    var redactedText by mutableStateOf("")
        private set

    /** Original layout with masked values drawn as bullets; safe to show on screen. */
    var protectedOriginalText by mutableStateOf("")
        private set

    var identifiers by mutableStateOf<List<DetectedIdentifier>>(emptyList())
        private set

    var alerts by mutableStateOf<List<PrivacyAlert>>(emptyList())
        private set

    var replacements by mutableStateOf(0)
        private set

    var extraction by mutableStateOf<MedicalExtraction?>(null)
        private set

    var nanoAssistUsed by mutableStateOf(false)
        private set

    /** Whether masking used Nano's hints or the local rule-based shield. */
    var privacyShield by mutableStateOf(PrivacyShield.Baseline)
        private set

    /** True when the local shield replaced the on-device assist. */
    val usedFallback: Boolean get() = privacyShield.usedFallback

    /** Value-free fallback notice for the review screen, or null. */
    val fallbackNotice: String? get() = privacyShield.notice

    var savedMessage by mutableStateOf<String?>(null)
        private set

    /** How many values the reviewer has chosen to hide manually. */
    var manualRedactions by mutableStateOf(0)
        private set

    /**
     * Owned by the state holder, not by whichever screen started the work. The Scan
     * screen leaves composition (and its rememberCoroutineScope is cancelled) the moment
     * the privacy review opens, which used to abort OCR mid-page and leave the flow
     * stuck on "Reading your report…".
     */
    private val work = StateWorkScope(dispatcher)

    /** Local-only copy of the extracted document, kept alive only for re-masking. */
    private var document: ExtractedText? = null

    /** Review alerts about incomplete extraction; they survive re-masking. */
    private var extractionAlerts: List<PrivacyAlert> = emptyList()
    private var nanoExtras: List<Pair<String, IdentifierCategory>> = emptyList()
    private val userAdded = mutableListOf<Pair<String, IdentifierCategory>>()
    private var job: Job? = null

    val isBusy: Boolean
        get() = step is ReportStep.Working || step == ReportStep.Extracting

    val canReview: Boolean
        get() = step == ReportStep.Review && redactedText.isNotBlank()

    val approveBlocked: Boolean
        get() = alerts.any { it.severity == com.scantruth.ai.model.AlertSeverity.HIGH }

    /** Approval needs a usable redacted text and no high-risk identifier left in it. */
    val canApprove: Boolean
        get() = canReview && !approveBlocked

    /** Reads the document and masks identifiers. Never uploads anything. */
    fun start(
        context: Context,
        uri: Uri,
        displayName: String,
        allowNanoAssist: Boolean = true
    ) {
        reset()
        sourceName = displayName
        step = ReportStep.Working("Reading your report on this device…")

        job = work.launch {
            val extracted = try {
                ReportWorkflow.readDocument(context, uri, displayName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OcrException) {
                step = ReportStep.Failed(e.error.userMessage)
                return@launch
            } catch (e: Exception) {
                step = ReportStep.Failed(
                    "That document could not be read. Try another PDF or a clearer photo."
                )
                return@launch
            }

            document = extracted
            pagesRead = extracted.pageCount
            truncated = extracted.truncated
            unreadablePages = extracted.unreadablePageCount
            unreadablePageNumbers = extracted.skippedPageNumbers
            coverageNote = extracted.coverageNote()
            extractionAlerts = coverageAlerts(extracted)
            step = ReportStep.Working("Masking personal identifiers on this device…")

            val nanoScan = try {
                ReportWorkflow.detectExtras(extracted, allowNanoAssist)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NanoMaskScan.Unavailable(
                    "Gemini Nano could not check this document on this device."
                )
            }
            // The rule-based shield always runs; this records whether Nano contributed.
            privacyShield = PrivacyShieldPolicy.assess(allowNanoAssist, nanoScan)
            nanoExtras = PrivacyShieldPolicy.valuesOf(nanoScan)
            val extrasUsed = nanoExtras.isNotEmpty()

            applyOutcome(withContext(Dispatchers.Default) {
                ReportWorkflow.mask(extracted, nanoExtras, userAdded)
            }, nanoAssistUsed = allowNanoAssist && extrasUsed)
        }
    }

    /**
     * Adds a value the reviewer spotted as unmasked and re-runs the deterministic masker
     * over the original text on this device. Nothing is uploaded.
     */
    fun addRedaction(value: String) {
        if (step != ReportStep.Review) return
        val cleaned = value.trim()
        if (cleaned.length < 2 || cleaned.length > 200) return
        if (userAdded.size >= MAX_USER_REDACTIONS) return
        if (userAdded.any { it.first.equals(cleaned, ignoreCase = true) }) return
        userAdded += cleaned to IdentifierCategory.OTHER
        manualRedactions = userAdded.size

        val currentDocument = document ?: return
        val extras = nanoExtras
        step = ReportStep.Working("Applying your correction on this device…")

        job = work.launch {
            applyOutcome(
                withContext(Dispatchers.Default) {
                    ReportWorkflow.mask(currentDocument, extras, userAdded)
                },
                nanoAssistUsed = nanoAssistUsed
            )
        }
    }

    /**
     * Rejects the document and stops any in-flight work. Everything local about this
     * report is dropped, and no cloud call is ever made.
     */
    fun reject() {
        job?.cancel()
        job = null
        reset()
    }

    /** Sends only the approved redacted text to the cloud extractor. */
    fun approve() {
        if (!canApprove) return
        val approvedText = redactedText
        step = ReportStep.Extracting

        job = work.launch {
            try {
                extraction = HybridAIService.extractMedicalProfile(approvedText)
                // The review copy is no longer needed once extraction is done.
                clearReviewText()
                step = ReportStep.ExtractionReady
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiAnalysisException) {
                clearReviewText()
                step = ReportStep.Failed(e.message)
            } catch (e: Exception) {
                clearReviewText()
                step = ReportStep.Failed("The medical extraction could not be completed.")
            }
        }
    }

    /** Saves the structured result and merges it into the stored profile. */
    fun save() {
        val result = extraction ?: return
        step = ReportStep.Working("Saving to your health profile…")

        job = work.launch {
            val merge = FirestoreRepository.mergeExtraction(result)
            val report = FirestoreRepository.saveReport(
                extraction = result,
                identifiersMasked = replacements,
                pagesScanned = pagesRead,
                truncated = truncated
            )

            when {
                merge is StoreResult.Failure -> step = ReportStep.Failed(merge.message)
                report is StoreResult.Failure -> step = ReportStep.Failed(report.message)
                else -> {
                    // The analysis is finished: no report text stays in memory.
                    clearReviewText()
                    savedMessage = "Report saved and your health profile updated."
                    step = ReportStep.Saved
                    onReportSaved?.invoke()
                }
            }
        }
    }

    /** Drops the review text but keeps the extraction on screen. */
    fun clearReviewText() {
        document = null
        userAdded.clear()
        manualRedactions = 0
                        redactedText = ""
        protectedOriginalText = ""
        identifiers = emptyList()
        alerts = emptyList()
        replacements = 0
        nanoExtras = emptyList()
        nanoAssistUsed = false
        privacyShield = PrivacyShield.Baseline
        extractionAlerts = emptyList()
        unreadablePages = 0
        unreadablePageNumbers = emptyList()
        coverageNote = null
    }

    /**
     * Drops everything this flow held, used on sign-out. Nothing from the previous
     * account can be replayed by the next one.
     */
    fun clearSensitive() {
        job?.cancel()
        job = null
        work.cancelChildren()
        reset()
    }

    fun reset() {
        job?.cancel()
        job = null
        work.cancelChildren()
        clearReviewText()
        step = ReportStep.Idle
        sourceName = ""
        pagesRead = 0
        truncated = false
        extraction = null
        savedMessage = null
    }

    private fun applyOutcome(outcome: MaskOutcome, nanoAssistUsed: Boolean) {
        when (outcome) {
            is MaskOutcome.Masked -> {
                redactedText = outcome.document.redactedText
                protectedOriginalText = outcome.document.protectedOriginalText
                identifiers = outcome.document.detectedIdentifiers
                // Incomplete pages come first: a partial extraction is not a clean report.
                alerts = extractionAlerts + outcome.document.alerts
                replacements = outcome.document.totalReplacements
                this.nanoAssistUsed = nanoAssistUsed
                step = ReportStep.Review
            }

            is MaskOutcome.Rejected -> step = ReportStep.Failed(
                message = outcome.message,
                cloudBlocked = true
            )
        }
    }

    /**
     * Turns unreadable pages into a review alert. The alert is value-free: it carries page
     * numbers only, and the unread pages are never sent anywhere because they have no text.
     */
    private fun coverageAlerts(extracted: ExtractedText): List<PrivacyAlert> {
        if (!extracted.isPartial) return emptyList()
        val count = extracted.unreadablePageCount
        val title = if (count == 1) {
            "1 page could not be read"
        } else {
            "$count pages could not be read"
        }
        return listOf(
            PrivacyAlert(
                area = com.scantruth.ai.model.AlertArea.OCR,
                severity = com.scantruth.ai.model.AlertSeverity.MEDIUM,
                title = title,
                detail = extracted.coverageNote()
                    ?: "Some pages had no readable text and are not part of this review."
            )
        )
    }
}
