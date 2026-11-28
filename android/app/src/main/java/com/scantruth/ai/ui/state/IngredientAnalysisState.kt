package com.scantruth.ai.ui.state

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.scantruth.ai.AiAnalysisException
import com.scantruth.ai.AiFailureKind
import com.scantruth.ai.AppPreferences
import com.scantruth.ai.FirestoreRepository
import com.scantruth.ai.FirestoreRepository.StoreResult
import com.scantruth.ai.HybridAIService
import com.scantruth.ai.HybridAIService.NanoAvailability
import com.scantruth.ai.OcrException
import com.scantruth.ai.domain.ExtractionConfidence
import com.scantruth.ai.domain.IngredientParser
import com.scantruth.ai.domain.IngredientWorkflow
import com.scantruth.ai.domain.LabelScan
import com.scantruth.ai.model.AiMode
import com.scantruth.ai.model.IngredientAnalysis
import com.scantruth.ai.model.MedicalProfile
import com.scantruth.ai.model.ScanPerformanceMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

enum class WorkStage {
    CAPTURING,
    READING_OCR,
    EXTRACTING,
    LOADING_PROFILE,
    ANALYZING
}

sealed interface IngredientStep {
    data object Idle : IngredientStep

    /** Capturing, reading a label with on-device OCR, or waiting on the AI call. */
    data class Working(
        val message: String,
        val stage: WorkStage = WorkStage.ANALYZING
    ) : IngredientStep

    /** OCR finished: the ingredient list is on screen and ready for review. */
    data object Detected : IngredientStep

    data object Ready : IngredientStep
    data class Failed(val message: String) : IngredientStep
}

/**
 * The ingredient flow: camera capture -> label OCR -> ingredient list the user can
 * review & edit -> AI analysis -> save to scan history.
 *
 * Work runs in a scope owned by this holder rather than by the screen, so navigating to
 * the result screen does not cancel the AI call or Firestore save.
 */
@Stable
class IngredientAnalysisState(
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {

    private companion object {
        const val PROFILE_CACHE_TTL_MS = 60_000L
    }

    private val work = StateWorkScope(dispatcher)

    var aiMode by mutableStateOf(
        AiMode.fromWire(AppPreferences.string(AppPreferences.SELECTED_AI_MODE, AiMode.AUTO.name))
    )
        private set

    var step by mutableStateOf<IngredientStep>(IngredientStep.Idle)
        private set

    var isCapturing by mutableStateOf(false)
        private set

    var sourceName by mutableStateOf("")
        private set

    var ingredients by mutableStateOf<List<String>>(emptyList())
        private set

    var allergenDeclarations by mutableStateOf<List<String>>(emptyList())
        private set

    var scanWarnings by mutableStateOf<List<String>>(emptyList())
        private set

    var rawOcrText by mutableStateOf("")
        private set

    var detectedRegionText by mutableStateOf("")
        private set

    var extractionConfidence by mutableStateOf(ExtractionConfidence.HIGH)
        private set

    var imageQualityHint by mutableStateOf<String?>(null)
        private set

    var lastMetrics by mutableStateOf(ScanPerformanceMetrics())
        private set

    var analysis by mutableStateOf<IngredientAnalysis?>(null)
        private set

    /** Notified when an ingredient analysis has been saved to scan history. */
    var onScanSaved: (() -> Unit)? = null

    var saveNote by mutableStateOf<String?>(null)
        private set

    var usedProfile by mutableStateOf(MedicalProfile())
        private set

    var nanoStatus by mutableStateOf<NanoAvailability?>(null)
        private set

    var isCheckingNano by mutableStateOf(false)
        private set

    var isDownloadingNano by mutableStateOf(false)
        private set

    var downloadFraction by mutableStateOf<Float?>(null)
        private set

    private var cachedProfile: MedicalProfile? = null
    private var cachedProfileAtMs: Long = 0L
    private var isPrefetchingProfile = false

    val isBusy: Boolean get() = isCapturing || step is IngredientStep.Working

    val isProcessingOcr: Boolean
        get() = (step as? IngredientStep.Working)?.stage in setOf(
            WorkStage.READING_OCR,
            WorkStage.EXTRACTING
        )

    val isAnalyzingAi: Boolean
        get() = (step as? IngredientStep.Working)?.stage in setOf(
            WorkStage.LOADING_PROFILE,
            WorkStage.ANALYZING
        )

    val hasLabel: Boolean get() = sourceName.isNotBlank()

    /**
     * True when the camera preview and capture controls should remain mounted.
     * Kept true while [isCapturing] is active so CameraX stays bound until takePicture finishes.
     */
    val showCameraViewfinder: Boolean
        get() = isCapturing || (
            !isBusy &&
                ingredients.isEmpty() &&
                step !is IngredientStep.Detected &&
                step !is IngredientStep.Ready
            )

    val hasValidIngredients: Boolean
        get() = ingredients.any { it.isNotBlank() }

    val isSelectedModelUsable: Boolean
        get() = when (aiMode) {
            AiMode.AUTO, AiMode.ON_DEVICE -> true
            AiMode.NANO -> nanoStatus is NanoAvailability.Available
        }

    val canAnalyze: Boolean
        get() = hasValidIngredients && !isBusy && isSelectedModelUsable

    val isNanoSelectedAndUnavailable: Boolean
        get() = aiMode == AiMode.NANO && nanoStatus !is NanoAvailability.Available

    // Testable seams for unit testing without live Firebase / ML Kit.
    internal var nanoAvailabilityProvider: suspend (Boolean) -> NanoAvailability =
        { force -> HybridAIService.nanoAvailability(forceRefresh = force) }
    internal var profileLoader: suspend () -> StoreResult<MedicalProfile?> =
        { FirestoreRepository.loadMedicalProfile(preferCache = aiMode == AiMode.ON_DEVICE) }
    internal var labelReader: suspend (Context, Uri, String?, (String) -> Unit) -> LabelScan =
        { ctx, uri, name, onStage -> IngredientWorkflow.readLabel(ctx, uri, name, onStage) }
    internal var ingredientAnalyzer: suspend (List<String>, MedicalProfile, AiMode) -> IngredientAnalysis =
        { list, profile, mode ->
            HybridAIService.analyzeIngredients(
                ingredients = list,
                profile = profile,
                mode = mode,
                allergenDeclarations = allergenDeclarations,
                rawOcrText = rawOcrText
            )
        }

    fun setMode(mode: AiMode) {
        if (isBusy) return
        aiMode = mode
        AppPreferences.setString(AppPreferences.SELECTED_AI_MODE, mode.name)
        if (step is IngredientStep.Failed) {
            step = if (hasValidIngredients) IngredientStep.Detected else IngredientStep.Idle
        }
    }

    /**
     * Prefetches the signed-in user's health profile in the background so tapping
     * "Analyze Ingredients" does not block sequentially on Firestore.
     */
    fun prefetchProfile() {
        val now = System.currentTimeMillis()
        if (isPrefetchingProfile) return
        if (cachedProfile != null && (now - cachedProfileAtMs) < PROFILE_CACHE_TTL_MS) return
        isPrefetchingProfile = true
        work.launch {
            try {
                when (val stored = profileLoader()) {
                    is StoreResult.Success -> {
                        cachedProfile = stored.value ?: MedicalProfile()
                        cachedProfileAtMs = System.currentTimeMillis()
                    }
                    is StoreResult.Failure -> Unit
                }
            } finally {
                isPrefetchingProfile = false
            }
        }
    }

    fun invalidateProfileCache() {
        cachedProfile = null
        cachedProfileAtMs = 0L
    }

    internal fun updateProfileCache(profile: MedicalProfile) {
        cachedProfile = profile
        cachedProfileAtMs = System.currentTimeMillis()
    }

    fun checkNano(forceRefresh: Boolean = false) {
        if (isCheckingNano) return
        if (!forceRefresh && nanoStatus != null) return
        isCheckingNano = true
        work.launch {
            try {
                nanoStatus = nanoAvailabilityProvider(forceRefresh)
            } finally {
                isCheckingNano = false
            }
        }
    }

    fun downloadNano() {
        if (isDownloadingNano) return
        isDownloadingNano = true
        downloadFraction = null
        work.launch {
            try {
                val result = HybridAIService.downloadNano { progress ->
                    downloadFraction = progress.fraction
                }
                if (result.isFailure) {
                    nanoStatus = NanoAvailability.Unsupported(
                        result.exceptionOrNull()?.message
                            ?: "Gemini Nano could not be downloaded."
                    )
                } else {
                    nanoStatus = nanoAvailabilityProvider(true)
                }
            } finally {
                isDownloadingNano = false
            }
        }
    }

    /**
     * Locks capture immediately to prevent duplicate camera shutter taps and duplicate OCR runs.
     * @return true if capture is allowed to proceed.
     */
    fun beginCapture(): Boolean {
        if (isBusy) return false
        work.cancelChildren()
        analysis = null
        saveNote = null
        ingredients = emptyList()
        allergenDeclarations = emptyList()
        scanWarnings = emptyList()
        rawOcrText = ""
        detectedRegionText = ""
        extractionConfidence = ExtractionConfidence.HIGH
        imageQualityHint = null
        isCapturing = true
        step = IngredientStep.Working("Capturing image…", WorkStage.CAPTURING)
        return true
    }

    fun onCaptureFailed(message: String) {
        isCapturing = false
        step = IngredientStep.Failed(message)
    }

    /** Reopens the live camera viewfinder while resetting the current label scan. */
    fun rescanLabel() {
        if (isBusy) return
        reset()
    }

    /** Reads a label with on-device OCR only. Nothing is sent to any model here. */
    fun readLabel(
        context: Context,
        uri: Uri,
        displayName: String
    ) {
        readLabelInternal(displayName) { onStage ->
            labelReader(context, uri, displayName, onStage)
        }
    }

    internal fun readLabelInternal(
        displayName: String,
        scanRunner: suspend ((String) -> Unit) -> LabelScan
    ) {
        work.cancelChildren()
        isCapturing = false
        analysis = null
        saveNote = null
        usedProfile = MedicalProfile()
        ingredients = emptyList()
        allergenDeclarations = emptyList()
        scanWarnings = emptyList()
        rawOcrText = ""
        detectedRegionText = ""
        extractionConfidence = ExtractionConfidence.HIGH
        imageQualityHint = null
        sourceName = displayName
        step = IngredientStep.Working("Reading ingredients…", WorkStage.READING_OCR)

        // Prefetch health profile concurrently with OCR so Analyze runs immediately after review.
        prefetchProfile()

        work.launch {
            val label = try {
                scanRunner { stageMessage ->
                    val stage = if (stageMessage.contains("Extracting", ignoreCase = true)) {
                        WorkStage.EXTRACTING
                    } else {
                        WorkStage.READING_OCR
                    }
                    step = IngredientStep.Working(stageMessage, stage)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OcrException) {
                step = IngredientStep.Failed(e.error.userMessage)
                return@launch
            } catch (e: Exception) {
                step = IngredientStep.Failed("That label could not be read. Try another photo.")
                return@launch
            }

            rawOcrText = label.rawOcrText
            detectedRegionText = label.detectedRegionText
            extractionConfidence = label.confidence
            allergenDeclarations = label.allergenDeclarations
            scanWarnings = label.warnings
            imageQualityHint = label.qualityHint
            lastMetrics = lastMetrics.copy(
                ocrDurationMs = label.ocrDurationMs,
                parseDurationMs = label.parseDurationMs
            )

            if (label.ingredients.isEmpty()) {
                val hint = label.qualityHint?.let { " $it" }.orEmpty()
                step = IngredientStep.Failed(
                    "No ingredient list was found in that image. Frame the ingredients panel " +
                        "so the whole list is visible.$hint"
                )
                return@launch
            }

            ingredients = label.ingredients
            step = IngredientStep.Detected
        }
    }

    /**
     * Adds ingredients typed or pasted by the user. The same deterministic parser is used
     * as for OCR text, so manual and scanned lists behave identically.
     *
     * @return how many new names were added.
     */
    fun addIngredient(raw: String): Int {
        val parsed = IngredientParser.parse(raw)
        if (parsed.isEmpty()) return 0

        val existing = ingredients.map { it.lowercase() }.toMutableSet()
        val added = parsed.filter { existing.add(it.lowercase()) }
        if (added.isNotEmpty()) {
            ingredients = ingredients + added
            sourceName = sourceName.ifBlank { "Manual ingredient list" }
            if (step is IngredientStep.Idle || step is IngredientStep.Failed || step is IngredientStep.Ready) {
                analysis = null
                saveNote = null
                step = IngredientStep.Detected
            }
        }
        return added.size
    }

    fun removeIngredient(name: String) {
        if (isBusy) return
        ingredients = ingredients.filterNot { it.equals(name, ignoreCase = true) }
        if (step is IngredientStep.Ready) {
            analysis = null
            saveNote = null
        }
        if (ingredients.isEmpty() && step is IngredientStep.Detected) {
            step = IngredientStep.Idle
        }
    }

    /** Runs the chosen engine over the confirmed list. */
    fun analyze() {
        if (isBusy || !hasValidIngredients) return
        val selectedMode = aiMode

        if (selectedMode == AiMode.NANO && nanoStatus !is NanoAvailability.Available) {
            val reason = when (val status = nanoStatus) {
                is NanoAvailability.Downloadable -> "Gemini Nano needs a one-time download before running Nano mode."
                is NanoAvailability.Downloading -> "Gemini Nano is still downloading. Try again once complete."
                is NanoAvailability.Unsupported -> status.reason
                null -> "Checking Gemini Nano availability on this device. Try again in a moment."
                else -> "Gemini Nano is unavailable on this device."
            }
            step = IngredientStep.Failed(reason)
            return
        }

        val list = ingredients
        analysis = null
        saveNote = null

        work.launch {
            val profileStartNs = System.nanoTime()
            val now = System.currentTimeMillis()
            val cached = cachedProfile
            usedProfile = if (cached != null && (now - cachedProfileAtMs) < PROFILE_CACHE_TTL_MS) {
                cached
            } else {
                step = IngredientStep.Working("Preparing your health profile…", WorkStage.LOADING_PROFILE)
                when (val stored = profileLoader()) {
                    is StoreResult.Success -> (stored.value ?: MedicalProfile()).also {
                        cachedProfile = it
                        cachedProfileAtMs = System.currentTimeMillis()
                    }
                    is StoreResult.Failure -> MedicalProfile()
                }
            }
            val profileMs = ((System.nanoTime() - profileStartNs) / 1_000_000L).coerceAtLeast(0L)

            step = IngredientStep.Working(
                when (selectedMode) {
                    AiMode.AUTO -> "Analysing with Gemini Flash (Cloud)…"
                    AiMode.NANO -> "Analysing on this device (Gemini Nano)…"
                    AiMode.ON_DEVICE -> "Analysing offline on this device…"
                },
                WorkStage.ANALYZING
            )

            val aiStartNs = System.nanoTime()
            try {
                val result = ingredientAnalyzer(list, usedProfile, selectedMode)
                val aiMs = ((System.nanoTime() - aiStartNs) / 1_000_000L).coerceAtLeast(1L)
                val postStartNs = System.nanoTime()
                analysis = result
                step = IngredientStep.Ready
                val postMs = ((System.nanoTime() - postStartNs) / 1_000_000L).coerceAtLeast(0L)
                lastMetrics = lastMetrics.copy(
                    profileLoadMs = profileMs,
                    aiInferenceDurationMs = aiMs,
                    postProcessDurationMs = postMs
                )
                persist(result, list)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiAnalysisException) {
                step = IngredientStep.Failed(e.message)
                if (e.kind == AiFailureKind.NANO_UNAVAILABLE) checkNano(forceRefresh = true)
            } catch (e: Exception) {
                step = IngredientStep.Failed("The analysis could not be completed.")
            }
        }
    }

    private fun persist(result: IngredientAnalysis, ingredients: List<String>) {
        work.launch {
            when (val saved = FirestoreRepository.saveIngredientScan(result, ingredients)) {
                is StoreResult.Success -> {
                    saveNote = "Saved to your scan history."
                    onScanSaved?.invoke()
                }
                is StoreResult.Failure ->
                    saveNote = if (saved.notSignedIn) {
                        "Not saved: sign in to keep this scan in your history."
                    } else {
                        saved.message
                    }
            }
        }
    }

    /** Stops any in-flight analysis and drops its state. Safe to call when idle. */
    fun cancel() {
        reset()
    }

    /**
     * Clears the previous analysis result while preserving detected ingredients,
     * leaving the flow in [IngredientStep.Detected] (or [IngredientStep.Idle] if empty)
     * so the user can edit or re-analyze without losing their scanned list.
     */
    fun clearAnalysis() {
        if (isBusy) return
        analysis = null
        saveNote = null
        step = if (ingredients.isNotEmpty()) IngredientStep.Detected else IngredientStep.Idle
    }

    fun reset() {
        work.cancelChildren()
        isCapturing = false
        step = IngredientStep.Idle
        sourceName = ""
        ingredients = emptyList()
        allergenDeclarations = emptyList()
        scanWarnings = emptyList()
        rawOcrText = ""
        detectedRegionText = ""
        extractionConfidence = ExtractionConfidence.HIGH
        imageQualityHint = null
        analysis = null
        saveNote = null
        usedProfile = MedicalProfile()
    }
}
