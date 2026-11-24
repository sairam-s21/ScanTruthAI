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
import com.scantruth.ai.domain.IngredientParser
import com.scantruth.ai.domain.IngredientWorkflow
import com.scantruth.ai.model.AiMode
import com.scantruth.ai.model.IngredientAnalysis
import com.scantruth.ai.model.MedicalProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

sealed interface IngredientStep {
    data object Idle : IngredientStep

    /** Reading a label with on-device OCR or waiting on the AI call. */
    data class Working(val message: String) : IngredientStep

    /** OCR finished: the ingredient list is on screen and awaiting confirmation. */
    data object Detected : IngredientStep

    data object Ready : IngredientStep
    data class Failed(val message: String) : IngredientStep
}

/**
 * The ingredient flow: label OCR → ingredient list the user can correct → AI analysis
 * → save to scan history.
 *
 * OCR and analysis are separate steps so the Scan screen can show what was detected,
 * accept manual corrections, and let the user choose the engine before anything is sent.
 * Work runs in a scope owned by this holder rather than by the screen, so opening the
 * result screen does not cancel the AI call or the Firestore save.
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

    var sourceName by mutableStateOf("")
        private set

    var ingredients by mutableStateOf<List<String>>(emptyList())
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

    val isBusy: Boolean get() = step is IngredientStep.Working

    val hasLabel: Boolean get() = sourceName.isNotBlank()

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
                val result = HybridAIService.downloadNano { progress ->
                    downloadFraction = progress.fraction
                }
                if (result.isFailure) {
                    nanoStatus = NanoAvailability.Unsupported(
                        result.exceptionOrNull()?.message
                            ?: "Gemini Nano could not be downloaded."
                    )
                } else {
                    nanoStatus = HybridAIService.nanoAvailability()
                }
            } finally {
                isDownloadingNano = false
            }
        }
    }

    /** Reads a label with on-device OCR only. Nothing is sent to any model here. */
    fun readLabel(
        context: Context,
        uri: Uri,
        displayName: String
    ) {
        work.cancelChildren()
        analysis = null
        saveNote = null
        usedProfile = MedicalProfile()
        ingredients = emptyList()
        sourceName = displayName
        step = IngredientStep.Working("Reading the label on this device…")

        work.launch {
            val label = try {
                IngredientWorkflow.readLabel(context, uri, displayName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OcrException) {
                step = IngredientStep.Failed(e.error.userMessage)
                return@launch
            } catch (e: Exception) {
                step = IngredientStep.Failed("That label could not be read. Try another photo.")
                return@launch
            }

            if (label.ingredients.isEmpty()) {
                step = IngredientStep.Failed(
                    "No ingredient list was found in that image. Frame the ingredients panel " +
                        "so the whole list is visible."
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
            if (step is IngredientStep.Failed || step is IngredientStep.Ready) {
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
        if (isBusy || ingredients.isEmpty()) return
        val list = ingredients
        analysis = null
        saveNote = null
        step = IngredientStep.Working("Loading your health profile…")

        work.launch {
            usedProfile = when (val stored = FirestoreRepository.loadMedicalProfile()) {
                is StoreResult.Success -> stored.value ?: MedicalProfile()
                is StoreResult.Failure -> MedicalProfile()
            }

            step = IngredientStep.Working(
                when (aiMode) {
                    AiMode.AUTO -> "Analysing against your profile…"
                    AiMode.FLASH -> "Analysing with Gemini cloud…"
                    AiMode.NANO -> "Analysing on this device…"
                }
            )

            try {
                val result = HybridAIService.analyzeIngredients(list, usedProfile, aiMode)
                analysis = result
                step = IngredientStep.Ready
                persist(result, list)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiAnalysisException) {
                step = IngredientStep.Failed(e.message)
                if (e.kind == AiFailureKind.NANO_UNAVAILABLE) checkNano()
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
        step = IngredientStep.Idle
        sourceName = ""
        ingredients = emptyList()
        analysis = null
        saveNote = null
        usedProfile = MedicalProfile()
    }
}
