package com.scantruth.ai.ui.state

import com.scantruth.ai.AiAnalysisException
import com.scantruth.ai.AiFailureKind
import com.scantruth.ai.ExtractionError
import com.scantruth.ai.FirestoreRepository.StoreResult
import com.scantruth.ai.HybridAIService
import com.scantruth.ai.HybridAIService.NanoAvailability
import com.scantruth.ai.OcrException
import com.scantruth.ai.domain.LabelScan
import com.scantruth.ai.model.AiEngine
import com.scantruth.ai.model.AiMode
import com.scantruth.ai.model.FallbackNotices
import com.scantruth.ai.model.IngredientAnalysis
import com.scantruth.ai.model.IngredientVerdict
import com.scantruth.ai.model.MedicalProfile
import com.scantruth.ai.model.VerdictLevel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Tests model selection, camera capture workflow, OCR failure handling,
 * duplicate capture prevention, profile caching, timeout cleanup, and metrics.
 */
class ModelSelectionAndWorkflowTest {

    private var originalDelay: suspend (Long) -> Unit = {}
    private lateinit var originalOfflineDispatcher: CoroutineDispatcher

    @Before
    fun setUp() {
        originalDelay = HybridAIService.backoffDelay
        HybridAIService.backoffDelay = { }
        originalOfflineDispatcher = HybridAIService.offlineDispatcher
        HybridAIService.offlineDispatcher = Dispatchers.Unconfined
    }

    @After
    fun tearDown() {
        HybridAIService.backoffDelay = originalDelay
        HybridAIService.offlineDispatcher = originalOfflineDispatcher
    }

    private fun sampleAnalysis(
        engine: AiEngine,
        usedFallback: Boolean = false
    ) = IngredientAnalysis(
        productName = "Bourbon Biscuits",
        productCategory = "food",
        safetyScore = 38,
        badgeLabel = "Avoid",
        headline = "Contains wheat and milk",
        aiSummary = "Refined flour and dairy conflict with profile.",
        healthFactors = emptyList(),
        ingredientBreakdown = listOf(
            IngredientVerdict("Refined Wheat Flour (Maida)", VerdictLevel.AVOID, "Contains gluten", "Gluten intolerance"),
            IngredientVerdict("Sugar", VerdictLevel.CAUTION, "Added sugar", "Diabetes"),
            IngredientVerdict("E322", VerdictLevel.UNKNOWN, "Emulsifier source unspecified")
        ),
        recommendedIntake = "",
        uncertaintyNotes = emptyList(),
        engine = engine,
        usedFallback = usedFallback,
        fallbackNotice = if (usedFallback) FallbackNotices.INGREDIENT_CLOUD_FALLBACK else null
    )

    // --------------------------------------------------- Model selection

    @Test
    fun `exactly three AI modes exist with expected labels and legacy wire compatibility`() {
        assertEquals(listOf(AiMode.AUTO, AiMode.NANO, AiMode.ON_DEVICE), AiMode.entries)
        assertEquals("Auto", AiMode.AUTO.label)
        assertEquals("Nano", AiMode.NANO.label)
        assertEquals("On-device", AiMode.ON_DEVICE.label)
        assertEquals(AiMode.AUTO, AiMode.fromWire("FLASH"))
        assertEquals(AiMode.NANO, AiMode.fromWire("NANO"))
        assertEquals(AiMode.ON_DEVICE, AiMode.fromWire("ON_DEVICE"))
    }

    @Test
    fun `auto uses Gemini Flash cloud directly without checking nano or counting as fallback`() = runBlocking {
        var nanoChecked = false
        var cloudCalled = false

        val result = HybridAIService.analyzeIngredientsWith(
            ingredients = listOf("Wheat Flour", "Sugar"),
            profile = MedicalProfile(),
            mode = AiMode.AUTO,
            nanoChecker = {
                nanoChecked = true
                NanoAvailability.Available
            },
            cloudRunner = { _, usedFallback ->
                cloudCalled = true
                assertFalse(usedFallback)
                sampleAnalysis(AiEngine.CLOUD, usedFallback = false)
            },
            nanoRunner = { _, _ -> throw AssertionError("AUTO must not invoke Gemini Nano") },
            offlineRunner = { _, _, _, _ -> throw AssertionError("AUTO must use Gemini Flash cloud") }
        )

        assertFalse("AUTO must skip Nano availability check", nanoChecked)
        assertTrue(cloudCalled)
        assertEquals(AiEngine.CLOUD, result.engine)
        assertFalse(result.usedFallback)
    }

    @Test
    fun `nano mode uses Gemini Nano when ready and never falls back to cloud when unavailable`() = runBlocking {
        var cloudCalled = false
        val okNano = HybridAIService.analyzeIngredientsWith(
            ingredients = listOf("Sugar"),
            profile = MedicalProfile(),
            mode = AiMode.NANO,
            nanoChecker = { NanoAvailability.Available },
            cloudRunner = { _, _ ->
                cloudCalled = true
                sampleAnalysis(AiEngine.CLOUD)
            },
            nanoRunner = { _, _ -> sampleAnalysis(AiEngine.ON_DEVICE) },
            offlineRunner = { _, _, _, _ -> throw AssertionError("NANO must not call offline rule engine") }
        )
        assertEquals(AiEngine.ON_DEVICE, okNano.engine)
        assertFalse(cloudCalled)

        try {
            HybridAIService.analyzeIngredientsWith(
                ingredients = listOf("Wheat Flour"),
                profile = MedicalProfile(),
                mode = AiMode.NANO,
                nanoChecker = { NanoAvailability.Unsupported("Not supported on device") },
                cloudRunner = { _, _ ->
                    cloudCalled = true
                    sampleAnalysis(AiEngine.CLOUD)
                }
            )
            fail("Expected NANO_UNAVAILABLE exception")
        } catch (e: AiAnalysisException) {
            assertEquals(AiFailureKind.NANO_UNAVAILABLE, e.kind)
            assertFalse("NANO must never fall back to cloud", cloudCalled)
        }
    }

    @Test
    fun `on-device mode runs independent offline analysis without AICore Nano or internet`() {
        val state = IngredientAnalysisState(Dispatchers.Unconfined)
        state.nanoAvailabilityProvider = {
            throw AssertionError("On-device mode must not check Gemini Nano availability")
        }
        // Simulate offline Firestore failure (no network); offline mode must still complete.
        state.profileLoader = { StoreResult.Failure("You are offline") }
        state.setMode(AiMode.ON_DEVICE)

        state.readLabelInternal("bourbon.jpg") {
            LabelScan(
                ingredients = listOf("Refined Wheat Flour (Maida)", "Sugar", "Unknown Stabilizer X"),
                pageCount = 1
            )
        }
        assertFalse(state.isNanoSelectedAndUnavailable)
        assertTrue(state.isSelectedModelUsable)
        assertTrue(state.canAnalyze)

        state.analyze()
        assertEquals(IngredientStep.Ready, state.step)
        val result = state.analysis!!
        assertEquals(AiEngine.OFFLINE, result.engine)
        assertFalse(result.usedFallback)
        assertEquals(
            VerdictLevel.UNKNOWN,
            result.ingredientBreakdown.first { it.name == "Unknown Stabilizer X" }.verdict
        )
    }

    @Test
    fun `selected model is preserved across scan workflow and nano unavailability`() {
        val state = IngredientAnalysisState(Dispatchers.Unconfined)
        state.nanoAvailabilityProvider = { NanoAvailability.Unsupported("Device lacks AICore") }
        state.checkNano(forceRefresh = true)

        state.setMode(AiMode.ON_DEVICE)
        assertEquals(AiMode.ON_DEVICE, state.aiMode)
        assertFalse("On-device must never show Nano unavailable warning", state.isNanoSelectedAndUnavailable)

        state.readLabelInternal("bourbon.jpg") {
            LabelScan(ingredients = listOf("Sugar", "Palm Oil"), pageCount = 1)
        }
        assertEquals(AiMode.ON_DEVICE, state.aiMode)
        assertTrue(state.canAnalyze)

        state.setMode(AiMode.NANO)
        assertEquals(AiMode.NANO, state.aiMode)
        assertTrue(state.isNanoSelectedAndUnavailable)

        // Attempting analyze while Nano is unavailable fails cleanly without reverting aiMode.
        state.analyze()
        assertTrue(state.step is IngredientStep.Failed)
        assertEquals("Nano mode must remain selected without silent reset", AiMode.NANO, state.aiMode)

        // Switching to On-device or Auto clears the stale Nano failure and immediately enables Analyze.
        state.setMode(AiMode.ON_DEVICE)
        assertEquals(AiMode.ON_DEVICE, state.aiMode)
        assertEquals(IngredientStep.Detected, state.step)
        assertTrue(state.canAnalyze)
    }

    @Test
    fun `analyze button enable matrix enforces valid ingredients busy lock and selected model usability`() {
        val state = IngredientAnalysisState(Dispatchers.Unconfined)
        state.nanoAvailabilityProvider = { NanoAvailability.Unsupported("No AICore") }
        state.checkNano(forceRefresh = true)

        // Empty ingredients -> disabled in all modes
        state.setMode(AiMode.AUTO)
        assertFalse(state.canAnalyze)
        state.setMode(AiMode.NANO)
        assertFalse(state.canAnalyze)
        state.setMode(AiMode.ON_DEVICE)
        assertFalse(state.canAnalyze)

        // Add ingredients while Nano is unavailable:
        // AUTO and ON_DEVICE must be enabled; NANO must stay disabled.
        state.addIngredient("Wheat Flour, Sugar")
        assertTrue(state.hasValidIngredients)

        state.setMode(AiMode.AUTO)
        assertTrue("AUTO must be usable when Nano is unavailable", state.isSelectedModelUsable)
        assertTrue("Analyze must be enabled for AUTO with valid ingredients", state.canAnalyze)

        state.setMode(AiMode.ON_DEVICE)
        assertTrue("ON_DEVICE must be usable without AICore/Nano", state.isSelectedModelUsable)
        assertFalse("ON_DEVICE must not show Nano warning", state.isNanoSelectedAndUnavailable)
        assertTrue("Analyze must be enabled for ON_DEVICE with valid ingredients", state.canAnalyze)

        state.setMode(AiMode.NANO)
        assertFalse("NANO must not be usable when Nano is unavailable", state.isSelectedModelUsable)
        assertTrue(state.isNanoSelectedAndUnavailable)
        assertFalse("Analyze must remain disabled for NANO when unavailable", state.canAnalyze)

        // When Nano becomes available, NANO becomes usable and Analyze enables.
        state.nanoAvailabilityProvider = { NanoAvailability.Available }
        state.checkNano(forceRefresh = true)
        assertTrue(state.isSelectedModelUsable)
        assertTrue(state.canAnalyze)

        // While capturing / busy, Analyze is disabled regardless of mode.
        state.setMode(AiMode.ON_DEVICE)
        assertTrue(state.beginCapture())
        assertFalse(state.canAnalyze)
    }

    // --------------------------------------------------- Camera & OCR workflow

    @Test
    fun `camera viewfinder stays mounted during capture until OCR begins and duplicate capture is blocked`() {
        val state = IngredientAnalysisState(Dispatchers.Unconfined)

        assertTrue(state.beginCapture())
        assertTrue(state.isBusy)
        assertTrue(state.isCapturing)
        assertTrue(
            "Camera viewfinder must stay mounted during capture so CameraX ImageCapture stays bound",
            state.showCameraViewfinder
        )
        assertFalse(
            "OCR progress card must not replace CameraPreview before takePicture finishes",
            state.isProcessingOcr
        )
        assertFalse("Second shutter press must be ignored", state.beginCapture())

        state.onCaptureFailed("Photo failed")
        assertFalse(state.isCapturing)
        assertFalse(state.isBusy)
        assertTrue(state.step is IngredientStep.Failed)
        assertTrue(state.showCameraViewfinder)
    }

    @Test
    fun `capture handoff to OCR clears isCapturing hides camera and transitions to Detected`() {
        val state = IngredientAnalysisState(Dispatchers.Unconfined)
        state.profileLoader = { StoreResult.Success(null) }

        assertTrue(state.beginCapture())
        assertTrue(state.showCameraViewfinder)

        var sawOcrWhileCameraHidden = false
        state.readLabelInternal("scan-123.jpg") { onStage ->
            onStage("Reading ingredients…")
            sawOcrWhileCameraHidden = state.isProcessingOcr && !state.showCameraViewfinder && !state.isCapturing
            LabelScan(
                ingredients = listOf("Refined Wheat Flour (Maida)", "Sugar"),
                pageCount = 1,
                rawOcrText = "Ingredients: Refined Wheat Flour (Maida), Sugar"
            )
        }

        assertTrue("OCR stage must unmount camera preview once photo is saved", sawOcrWhileCameraHidden)
        assertFalse(state.isCapturing)
        assertFalse(state.isProcessingOcr)
        assertEquals(IngredientStep.Detected, state.step)
        assertEquals(listOf("Refined Wheat Flour (Maida)", "Sugar"), state.ingredients)
    }

    @Test
    fun `ocr success records diagnostics transitions stages and hides camera viewfinder`() {
        val state = IngredientAnalysisState(Dispatchers.Unconfined)
        state.profileLoader = { StoreResult.Success(MedicalProfile(allergies = listOf("Milk"))) }
        val seenStages = mutableListOf<String>()

        state.readLabelInternal("bourbon_back.jpg") { onStage ->
            seenStages += "start"
            onStage("Reading ingredients…")
            seenStages += (state.step as IngredientStep.Working).stage.name
            onStage("Extracting ingredient list…")
            seenStages += (state.step as IngredientStep.Working).stage.name
            LabelScan(
                ingredients = listOf("Refined Wheat Flour (Maida)", "Sugar", "Cocoa Solids"),
                pageCount = 1,
                allergenDeclarations = listOf("Contains Wheat and Milk"),
                warnings = emptyList(),
                rawOcrText = "Ingredients: Refined Wheat Flour (Maida), Sugar, Cocoa Solids.",
                ocrDurationMs = 45L,
                parseDurationMs = 3L
            )
        }

        assertEquals(IngredientStep.Detected, state.step)
        assertFalse("Camera viewfinder must stay hidden after detection", state.showCameraViewfinder)
        assertEquals(3, state.ingredients.size)
        assertEquals(listOf("Contains Wheat and Milk"), state.allergenDeclarations)
        assertEquals(45L, state.lastMetrics.ocrDurationMs)
        assertEquals(3L, state.lastMetrics.parseDurationMs)
        assertEquals(listOf("start", "READING_OCR", "EXTRACTING"), seenStages)

        state.rescanLabel()
        assertTrue("Rescanning reopens camera viewfinder", state.showCameraViewfinder)
        assertTrue(state.ingredients.isEmpty())
    }

    @Test
    fun `ocr failure sets Failed state and clears busy flag`() {
        val state = IngredientAnalysisState(Dispatchers.Unconfined)
        state.profileLoader = { StoreResult.Success(null) }

        state.readLabelInternal("blurry.jpg") {
            throw OcrException(ExtractionError.NoTextFound)
        }

        assertFalse(state.isBusy)
        assertTrue(state.step is IngredientStep.Failed)
        assertTrue(
            (state.step as IngredientStep.Failed).message.contains("No readable text", ignoreCase = true)
        )
    }

    // --------------------------------------------------- Performance & timeout

    @Test
    fun `prefetched profile eliminates blocking load during analyze and records timings`() {
        val state = IngredientAnalysisState(Dispatchers.Unconfined)
        var loadCalls = 0
        state.profileLoader = {
            loadCalls++
            StoreResult.Success(MedicalProfile(conditions = listOf("Diabetes")))
        }
        state.ingredientAnalyzer = { list, profile, mode ->
            assertEquals(listOf("Sugar"), list)
            assertEquals(listOf("Diabetes"), profile.conditions)
            assertEquals(AiMode.AUTO, mode)
            sampleAnalysis(AiEngine.CLOUD)
        }

        state.setMode(AiMode.AUTO)
        state.readLabelInternal("label.jpg") {
            LabelScan(
                ingredients = listOf("Sugar"),
                pageCount = 1,
                ocrDurationMs = 30L,
                parseDurationMs = 2L
            )
        }
        assertEquals("Profile should be prefetched once during OCR", 1, loadCalls)

        state.analyze()
        assertEquals("Cached profile should avoid repeat Firestore fetch", 1, loadCalls)
        assertEquals(IngredientStep.Ready, state.step)
        assertFalse(state.isBusy)
        assertTrue(state.lastMetrics.aiInferenceDurationMs >= 1L)
        assertEquals(30L, state.lastMetrics.ocrDurationMs)
    }

    @Test
    fun `request timeout retries once and clears loading state cleanly`() = runBlocking {
        var attempts = 0
        try {
            HybridAIService.executeWithRetry(AiEngine.CLOUD, timeoutMs = 40L) {
                attempts++
                delay(200L)
                "never reached"
            }
            fail("Expected timeout exception")
        } catch (e: AiAnalysisException) {
            assertEquals(2, attempts)
            assertEquals(AiFailureKind.CLOUD_UNAVAILABLE, e.kind)
            assertTrue(e.message.contains("timed out", ignoreCase = true))
        }
    }
}
