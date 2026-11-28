package com.scantruth.ai.ui.state

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.scantruth.ai.AiAnalysisException
import com.scantruth.ai.AiFailureKind
import com.scantruth.ai.FirestoreRepository
import com.scantruth.ai.FirestoreRepository.StoreResult
import com.scantruth.ai.HybridAIService
import com.scantruth.ai.HybridAIService.NanoAvailability
import com.scantruth.ai.OcrException
import com.scantruth.ai.domain.IngredientWorkflow
import com.scantruth.ai.model.AiMode
import com.scantruth.ai.model.IngredientAnalysis
import com.scantruth.ai.model.MedicalProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

sealed interface IngredientStep {
    data object Idle : IngredientStep

    /** Reading a label with OCR or waiting on the AI call. */
    data class Working(val message: String) : IngredientStep

    /** An ingredient list is on screen and can be edited or analysed. */
    data object Detected : IngredientStep

    /** An analysis result is ready to show. */
    data object Ready : IngredientStep
}

/**
 * The Scan flow, as on the website: read a label (or type the list) → edit the detected
 * ingredients → choose the engine → analyse → result sheet → save to scan history.
 *
 * Work runs in a scope owned by this holder rather than by the screen, so leaving the Scan
 * tab does not cancel the AI call or the Firestore save.
 */
@Stable
class IngredientAnalysisState(
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {

    private val work = StateWorkScope(dispatcher)

    var aiMode by mutableStateOf(AiMode.AUTO)
        private set

    var step by mutableStateOf<IngredientStep>(IngredientStep.Idle)
        private set

    var ingredients by mutableStateOf<List<String>>(emptyList())
        private set

    var analysis by mutableStateOf<IngredientAnalysis?>(null)
        private set

    /** Status of the last label read, shown in the Detected Ingredients card. */
    var ocrMessage by mutableStateOf<String?>(null)
        private set

    /** Shown under the Analyze button: a failed analysis, or a failed save. */
    var analysisError by mutableStateOf<String?>(null)
        private set

    var isReadingLabel by mutableStateOf(false)
        private set

    var isAnalyzing by mutableStateOf(false)
        private set

    /** Notified when an ingredient analysis has been saved to scan history. */
    var onScanSaved: (() -> Unit)? = null

    /**
     * Health profile used for the analysis. The shell points this at My Health, so a
     * signed-out visitor's session-only profile is used too.
     */
    var profileSource: suspend () -> MedicalProfile = {
        when (val stored = FirestoreRepository.loadMedicalProfile()) {
            is StoreResult.Success -> stored.value ?: MedicalProfile()
            is StoreResult.Failure -> MedicalProfile()
        }
    }

    /** Kept for callers that show where a save landed. */
    var saveNote by mutableStateOf<String?>(null)
        private set

    var nanoStatus by mutableStateOf<NanoAvailability?>(null)
        private set

    var isCheckingNano by mutableStateOf(false)
        private set

    var isDownloadingNano by mutableStateOf(false)
        private set

    var downloadFraction by mutableStateOf<Float?>(null)
        private set

    val isBusy: Boolean get() = isReadingLabel || isAnalyzing

    fun setMode(mode: AiMode) {
        aiMode = mode
    }

    fun checkNano() {
        if (isCheckingNano) return
        isCheckingNano = true
        work.launch {
            try {
                nanoStatus = HybridAIService.nanoAvailability()
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
                val result = HybridAIService.downloadNano { progress -> downloadFraction = progress.fraction }
                nanoStatus = if (result.isFailure) {
                    NanoAvailability.Unsupported(
                        result.exceptionOrNull()?.message ?: "Gemini Nano could not be downloaded."
                    )
                } else {
                    HybridAIService.nanoAvailability()
                }
            } finally {
                isDownloadingNano = false
            }
        }
    }

    /** Reads a label photo: OCR on the device, then the ingredient list from its Ingredients section. */
    fun readLabel(context: Context, uri: Uri, displayName: String) {
        if (isReadingLabel) return
        isReadingLabel = true
        ocrMessage = "Analyzing label on-device..."
        step = IngredientStep.Working("Analyzing label on-device...")

        work.launch {
            try {
                val found = IngredientWorkflow.readLabel(context, uri, displayName)
                if (found.isEmpty()) {
                    // Do not guess by turning every line of the label into an "ingredient".
                    ocrMessage = "Could not find an ingredients list in that photo. Keep the word " +
                        "\"Ingredients\" in the frame, or type them below."
                } else {
                    ingredients = found
                    ocrMessage = null
                    analysis = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OcrException) {
                ocrMessage = "Error scanning image: ${e.error.userMessage}"
            } catch (e: Exception) {
                ocrMessage = "Could not read that photo. Try another one."
            } finally {
                isReadingLabel = false
                step = if (ingredients.isEmpty()) IngredientStep.Idle else IngredientStep.Detected
            }
        }
    }

    /**
     * Adds what was typed; several ingredients can be separated by commas.
     *
     * @return how many new names were added.
     */
    fun addIngredient(raw: String): Int {
        val parts = raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val existing = ingredients.toMutableList()
        parts.forEach { part -> if (existing.none { it.equals(part, ignoreCase = true) }) existing += part }
        val added = existing.size - ingredients.size
        if (added > 0) {
            ingredients = existing
            if (step == IngredientStep.Idle) step = IngredientStep.Detected
        }
        return added
    }

    fun removeIngredient(name: String) {
        ingredients = ingredients.filterNot { it == name }
        if (ingredients.isEmpty() && step == IngredientStep.Detected) step = IngredientStep.Idle
    }

    /** Runs the chosen engine over the list. The result sheet opens when [step] is Ready. */
    fun analyze() {
        if (isBusy || ingredients.isEmpty()) return
        val list = ingredients
        isAnalyzing = true
        analysisError = null
        saveNote = null
        step = IngredientStep.Working("Analyzing...")

        work.launch {
            try {
                val profile = profileSource()
                val result = HybridAIService.analyzeIngredients(list, profile, aiMode)
                analysis = result
                step = IngredientStep.Ready
                persist(result, list)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiAnalysisException) {
                analysisError = "Analysis failed: ${e.message}"
                step = IngredientStep.Detected
                if (e.kind == AiFailureKind.NANO_UNAVAILABLE) checkNano()
            } catch (e: Exception) {
                analysisError = "Analysis failed: the analysis could not be completed."
                step = IngredientStep.Detected
            } finally {
                isAnalyzing = false
            }
        }
    }

    private fun persist(result: IngredientAnalysis, ingredients: List<String>) {
        if (!FirestoreRepository.isSignedIn()) return
        work.launch {
            when (val saved = FirestoreRepository.saveIngredientScan(result, ingredients)) {
                is StoreResult.Success -> {
                    saveNote = "Saved to your scan history."
                    onScanSaved?.invoke()
                }
                // Do not fail silently: the scan would be missing from History and the other apps.
                is StoreResult.Failure ->
                    analysisError = "Analysis finished, but saving it to your account failed: ${saved.message}"
            }
        }
    }

    /** Stops any in-flight work and drops its state. Safe to call when idle. */
    fun cancel() {
        reset()
    }

    /** Closes the result sheet; the ingredient list stays for edits and re-analysis. */
    fun clearAnalysis() {
        analysis = null
        saveNote = null
        step = if (ingredients.isNotEmpty()) IngredientStep.Detected else IngredientStep.Idle
    }

    fun reset() {
        work.cancelChildren()
        isReadingLabel = false
        isAnalyzing = false
        step = IngredientStep.Idle
        ingredients = emptyList()
        analysis = null
        saveNote = null
        ocrMessage = null
        analysisError = null
    }
}
