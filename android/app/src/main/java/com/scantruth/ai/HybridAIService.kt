package com.scantruth.ai

import com.google.firebase.Firebase
import com.google.firebase.ai.DownloadStatus
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.OnDeviceModelStatus
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import com.scantruth.ai.ai.AiResponseParser
import com.scantruth.ai.ai.InferenceAttempt
import com.scantruth.ai.ai.IngredientModePlanner
import com.scantruth.ai.ai.ParseResult
import com.scantruth.ai.model.AiEngine
import com.scantruth.ai.model.AiMode
import com.scantruth.ai.model.FallbackNotices
import com.scantruth.ai.model.HealthActionPlan
import com.scantruth.ai.model.HealthContextSuggestion
import com.scantruth.ai.model.HealthItemValidation
import com.scantruth.ai.model.IdentifierCategory
import com.scantruth.ai.model.IngredientAnalysis
import com.scantruth.ai.model.MedicalExtraction
import com.scantruth.ai.model.MedicalProfile
import com.scantruth.ai.privacy.NanoMaskScan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

enum class AiFailureKind {
    NANO_UNAVAILABLE,
    NANO_DOWNLOAD_FAILED,
    CLOUD_UNAVAILABLE,
    INVALID_RESPONSE,
    LOGIN_REQUIRED,
    UNKNOWN
}

/** A failure the UI can present without inventing a fallback result. */
class AiAnalysisException(
    val kind: AiFailureKind,
    override val message: String,
    cause: Throwable? = null
) : Exception(message, cause)

data class NanoDownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long?
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }
            ?.let { (bytesDownloaded.toFloat() / it).coerceIn(0f, 1f) }
}

/**
 * All AI access for the app.
 *
 * Three rules hold throughout:
 *  1. Cloud calls only ever receive redacted text (privacy masking happens earlier).
 *  2. The on-device model is never silently replaced by a cloud call, and vice versa:
 *     every result records the engine that produced it.
 *  3. Every response is parsed and validated before it reaches the UI or Firestore.
 */
object HybridAIService {

    /** Cloud model configured for this Firebase project's AI Logic instance. */
    const val CLOUD_MODEL = "gemini-3.5-flash-lite"

    /**
     * The on-device backend resolves the actual AICore model; the model name is the one
     * the Firebase AI Logic backend exposes for this project.
     */
    private const val ON_DEVICE_MODEL = CLOUD_MODEL

    sealed interface NanoAvailability {
        data object Available : NanoAvailability
        data object Downloadable : NanoAvailability
        data object Downloading : NanoAvailability
        data class Unsupported(val reason: String) : NanoAvailability
    }

    // ------------------------------------------------------------------ models

    private val medicalExtractor by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = CLOUD_MODEL,
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_IN_CLOUD),
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = Schema.obj(
                    mapOf(
                        "reportTitle" to Schema.string(),
                        "reportType" to Schema.string(),
                        "conditions" to Schema.array(Schema.string()),
                        "allergies" to Schema.array(Schema.string()),
                        "medications" to Schema.array(Schema.string()),
                        "dietaryRestrictions" to Schema.array(Schema.string()),
                        "healthNotes" to Schema.array(Schema.string()),
                        "markers" to Schema.array(
                            Schema.obj(
                                mapOf(
                                    "name" to Schema.string(),
                                    "value" to Schema.string(),
                                    "unit" to Schema.string(),
                                    "status" to Schema.string()
                                )
                            )
                        )
                    )
                )
            }
        )
    }

    private val cloudIngredientAnalyzer by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = CLOUD_MODEL,
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_IN_CLOUD),
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = Schema.obj(
                    mapOf(
                        "productName" to Schema.string(),
                        "productCategory" to Schema.string(),
                        "safetyScore" to Schema.integer(),
                        "badgeLabel" to Schema.string(),
                        "headline" to Schema.string(),
                        "aiSummary" to Schema.string(),
                        "healthFactors" to Schema.array(
                            Schema.obj(
                                mapOf(
                                    "label" to Schema.string(),
                                    "detail" to Schema.string(),
                                    "direction" to Schema.string()
                                )
                            )
                        ),
                        "ingredientBreakdown" to Schema.array(
                            Schema.obj(
                                mapOf(
                                    "name" to Schema.string(),
                                    "verdict" to Schema.string(),
                                    "note" to Schema.string()
                                )
                            )
                        ),
                        "recommendedIntake" to Schema.string(),
                        "uncertaintyNotes" to Schema.array(Schema.string())
                    )
                )
            }
        )
    }

    private val healthContextAssistant by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = CLOUD_MODEL,
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_IN_CLOUD),
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = Schema.obj(
                    mapOf(
                        "actions" to Schema.array(
                            Schema.obj(
                                mapOf(
                                    "item" to Schema.string(),
                                    "category" to Schema.string(),
                                    "action" to Schema.string(),
                                    "confidence" to Schema.string(),
                                    "clarificationNeeded" to Schema.string()
                                )
                            )
                        ),
                        "conditions" to Schema.array(Schema.string()),
                        "allergies" to Schema.array(Schema.string()),
                        "medications" to Schema.array(Schema.string()),
                        "dietaryRestrictions" to Schema.array(Schema.string()),
                        "healthNotes" to Schema.array(Schema.string()),
                        "clarificationNeeded" to Schema.string()
                    )
                )
            }
        )
    }

    /**
     * Built without a response schema: the on-device backend does not expose the same
     * structured-output contract, so JSON is requested in the prompt and validated with
     * the same parser the cloud path uses.
     */
    private val nanoModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = ON_DEVICE_MODEL,
            onDeviceConfig = OnDeviceConfig(
                mode = InferenceMode.ONLY_ON_DEVICE,
                temperature = 0.2f,
                maxOutputTokens = 2048
            )
        )
    }

    // ------------------------------------------------------------- on-device state

    /** Checks AICore / Gemini Nano without ever pretending it is available. */
    suspend fun nanoAvailability(): NanoAvailability = withContext(Dispatchers.Default) {
        try {
            val extension = nanoModel.onDeviceExtension
                ?: return@withContext NanoAvailability.Unsupported(UNSUPPORTED_MESSAGE)
            when (extension.checkStatus()) {
                OnDeviceModelStatus.AVAILABLE -> NanoAvailability.Available
                OnDeviceModelStatus.DOWNLOADABLE -> NanoAvailability.Downloadable
                OnDeviceModelStatus.DOWNLOADING -> NanoAvailability.Downloading
                else -> NanoAvailability.Unsupported(UNSUPPORTED_MESSAGE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NanoAvailability.Unsupported(UNSUPPORTED_MESSAGE)
        }
    }

    /** Streams the model download, when this device and account are allowed to. */
    suspend fun downloadNano(
        onProgress: (NanoDownloadProgress) -> Unit
    ): Result<Unit> = withContext(Dispatchers.Default) {
        try {
            val extension = nanoModel.onDeviceExtension
                ?: return@withContext Result.failure(
                    AiAnalysisException(
                        AiFailureKind.NANO_UNAVAILABLE,
                        UNSUPPORTED_MESSAGE
                    )
                )
            var total: Long? = null
            extension.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadStarted -> {
                        total = status.bytesToDownload
                        onProgress(NanoDownloadProgress(0, total))
                    }

                    is DownloadStatus.DownloadInProgress ->
                        onProgress(NanoDownloadProgress(status.totalBytesDownloaded, total))

                    is DownloadStatus.DownloadCompleted ->
                        onProgress(NanoDownloadProgress(total ?: 0, total))

                    is DownloadStatus.DownloadFailed -> throw AiAnalysisException(
                        AiFailureKind.NANO_DOWNLOAD_FAILED,
                        "Gemini Nano could not be downloaded: " +
                            (status.exception.localizedMessage ?: "unknown error") + ".",
                        status.exception
                    )
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiAnalysisException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                AiAnalysisException(
                    AiFailureKind.NANO_DOWNLOAD_FAILED,
                    "Gemini Nano could not be downloaded on this device.",
                    e
                )
            )
        }
    }

    // ---------------------------------------------------------------- medical

    /**
     * Structured medical extraction. Cloud only: this is the step that turns approved,
     * redacted text into the profile the user reviews and saves.
     */
    suspend fun extractMedicalProfile(redactedText: String): MedicalExtraction {
        if (redactedText.isBlank()) {
            throw AiAnalysisException(
                AiFailureKind.INVALID_RESPONSE,
                "There is no approved text to analyse."
            )
        }

        val raw = generate({ medicalExtractor }, medicalExtractionPrompt(redactedText), AiEngine.CLOUD)
        return when (
            val parsed = AiResponseParser.parseMedicalExtraction(
                raw = raw,
                engine = AiEngine.CLOUD,
                sourceText = redactedText
            )
        ) {
            is ParseResult.Success -> parsed.value
            is ParseResult.Invalid -> throw AiAnalysisException(
                AiFailureKind.INVALID_RESPONSE,
                parsed.reason
            )
        }
    }

    /**
     * Structured health statement classification. Cloud only: sends strictly the user's
     * explicitly entered natural language health statement and determines structured actions.
     */
    suspend fun classifyHealthActions(
        statement: String,
        currentProfile: MedicalProfile? = null
    ): HealthActionPlan {
        val cleanInput = statement.trim()
        if (cleanInput.isBlank()) {
            throw AiAnalysisException(
                AiFailureKind.INVALID_RESPONSE,
                "Please enter a health statement first."
            )
        }

        // Local ambiguity check for references like "that disease" when multiple exist
        if (currentProfile != null && currentProfile.conditions.size > 1) {
            val lower = cleanInput.lowercase()
            if (lower.contains("recovered from that disease") || lower.contains("no longer have that disease")) {
                return HealthActionPlan(
                    actions = emptyList(),
                    clarificationNeeded = "Please clarify whether you have recovered from ${currentProfile.conditions.joinToString(" or ")}."
                )
            }
        }

        val prompt = healthContextPrompt(cleanInput, currentProfile)
        val raw = generate({ healthContextAssistant }, prompt, AiEngine.CLOUD)
        return when (val parsed = AiResponseParser.parseHealthActions(raw, cleanInput)) {
            is ParseResult.Success -> parsed.value
            is ParseResult.Invalid -> throw AiAnalysisException(
                AiFailureKind.INVALID_RESPONSE,
                parsed.reason
            )
        }
    }

    /**
     * Structured health statement classification. Cloud only: sends strictly the user's
     * explicitly entered natural language health statement.
     */
    suspend fun classifyHealthContext(statement: String): HealthContextSuggestion {
        val cleanInput = statement.trim()
        if (cleanInput.isBlank()) {
            throw AiAnalysisException(
                AiFailureKind.INVALID_RESPONSE,
                "Please enter a health statement first."
            )
        }

        val prompt = healthContextPrompt(cleanInput)
        val raw = generate({ healthContextAssistant }, prompt, AiEngine.CLOUD)
        return when (val parsed = AiResponseParser.parseHealthContext(raw, cleanInput)) {
            is ParseResult.Success -> parsed.value
            is ParseResult.Invalid -> throw AiAnalysisException(
                AiFailureKind.INVALID_RESPONSE,
                parsed.reason
            )
        }
    }

    // ------------------------------------------------------------- ingredients

    suspend fun analyzeIngredients(
        ingredients: List<String>,
        profile: MedicalProfile,
        mode: AiMode
    ): IngredientAnalysis {
        if (ingredients.isEmpty()) {
            throw AiAnalysisException(
                AiFailureKind.INVALID_RESPONSE,
                "No ingredients were found in that label."
            )
        }
        val prompt = ingredientPrompt(ingredients, profile)

        // IngredientModePlanner holds the documented policy and is unit-tested directly.
        // NANO plans a single on-device attempt, so this loop can never reach the cloud
        // in that mode and the approved profile stays on the device.
        val nanoReady = mode == AiMode.AUTO && nanoAvailability() is NanoAvailability.Available
        val attempts = IngredientModePlanner.plan(mode, nanoReady)

        var lastFailure: Exception? = null
        for ((index, attempt) in attempts.withIndex()) {
            try {
                return when (attempt) {
                    InferenceAttempt.ON_DEVICE -> onDeviceIngredientAnalysis(prompt)
                    InferenceAttempt.CLOUD -> cloudIngredientAnalysis(
                        prompt = prompt,
                        usedFallback = IngredientModePlanner.cloudCountsAsFallback(mode)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // FLASH and NANO have exactly one attempt, so this rethrow is what keeps
                // a failed on-device run from ever becoming a cloud call in NANO mode.
                lastFailure = e
                if (index == attempts.lastIndex) throw e
            }
        }

        throw AiAnalysisException(
            AiFailureKind.UNKNOWN,
            "The ingredient analysis could not be completed.",
            lastFailure
        )
    }

    private suspend fun cloudIngredientAnalysis(
        prompt: String,
        usedFallback: Boolean
    ): IngredientAnalysis {
        val raw = generate({ cloudIngredientAnalyzer }, prompt, AiEngine.CLOUD)
        return when (val parsed = AiResponseParser.parseIngredientAnalysis(raw, AiEngine.CLOUD)) {
            is ParseResult.Success -> parsed.value.copy(
                usedFallback = usedFallback,
                fallbackNotice = if (usedFallback) {
                    FallbackNotices.INGREDIENT_CLOUD_FALLBACK
                } else {
                    null
                }
            )

            is ParseResult.Invalid -> throw AiAnalysisException(
                AiFailureKind.INVALID_RESPONSE,
                parsed.reason
            )
        }
    }

    private suspend fun onDeviceIngredientAnalysis(prompt: String): IngredientAnalysis {
        when (val availability = nanoAvailability()) {
            is NanoAvailability.Available -> Unit
            is NanoAvailability.Downloadable -> throw AiAnalysisException(
                AiFailureKind.NANO_UNAVAILABLE,
                "Gemini Nano needs a one-time download before it can run on this device."
            )

            is NanoAvailability.Downloading -> throw AiAnalysisException(
                AiFailureKind.NANO_UNAVAILABLE,
                "Gemini Nano is still downloading. Try again when it finishes."
            )

            is NanoAvailability.Unsupported -> throw AiAnalysisException(
                AiFailureKind.NANO_UNAVAILABLE,
                availability.reason
            )
        }

        val raw = generate({ nanoModel }, prompt, AiEngine.ON_DEVICE)
        return when (val parsed = AiResponseParser.parseIngredientAnalysis(raw, AiEngine.ON_DEVICE)) {
            is ParseResult.Success -> parsed.value
            is ParseResult.Invalid -> throw AiAnalysisException(
                AiFailureKind.INVALID_RESPONSE,
                parsed.reason
            )
        }
    }

    // ------------------------------------------------------- nano privacy assist

    /**
     * Optional second opinion for identifier detection. The deterministic masker is the
     * baseline; whatever Nano reports here is masked on-device too. Values returned from
     * this function stay in this process and are never uploaded.
     */
    suspend fun detectIdentifiersWithNano(text: String): NanoMaskScan {
        when (val availability = nanoAvailability()) {
            is NanoAvailability.Available -> Unit
            is NanoAvailability.Unsupported -> return NanoMaskScan.Unavailable(availability.reason)
            is NanoAvailability.Downloadable -> return NanoMaskScan.Unavailable(
                "Gemini Nano needs a one-time download before it can check this device's text."
            )

            is NanoAvailability.Downloading -> return NanoMaskScan.Unavailable(
                "Gemini Nano is still downloading."
            )
        }
        val prompt = buildString {
            appendLine("List the personal identifiers in the text below.")
            appendLine(
                "Reply with JSON only, shaped as {\"items\":[{\"value\":\"exact text\"," +
                    "\"category\":\"name|dob|phone|email|address|patient_id|hospital_id|other\"}]}."
            )
            appendLine("If there are none, reply {\"items\":[]}.")
            appendLine()
            append(text.take(6000))
        }
        val raw = try {
            nanoModel.generateContent(prompt).text.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return NanoMaskScan.Unavailable(
                "Gemini Nano could not check this document on this device."
            )
        }
        val values = AiResponseParser.parseNanoIdentifiers(raw).mapNotNull { item ->
            val category = categoryFor(item.category) ?: return@mapNotNull null
            item.value.trim().takeIf { it.length >= 2 }?.let { value -> value to category }
        }
        return if (values.isEmpty()) NanoMaskScan.NoIdentifiers else NanoMaskScan.Values(values)
    }

    // ----------------------------------------------------------------- helpers

    private suspend fun generate(
        model: () -> com.google.firebase.ai.GenerativeModel,
        prompt: String,
        engine: AiEngine
    ): String {
        val response = try {
            model().generateContent(prompt).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (engine == AiEngine.CLOUD) {
                throw AiAnalysisException(AiFailureKind.CLOUD_UNAVAILABLE, cloudMessage(e), e)
            }
            throw AiAnalysisException(
                AiFailureKind.UNKNOWN,
                "Gemini Nano could not complete this analysis on this device.",
                e
            )
        }
        return response?.takeIf { it.isNotBlank() }
            ?: throw AiAnalysisException(
                AiFailureKind.INVALID_RESPONSE,
                if (engine == AiEngine.CLOUD) {
                    "The AI returned an empty response."
                } else {
                    "Gemini Nano returned an empty response."
                }
            )
    }

    /**
     * Deliberately fixed text. Provider exception messages can echo request content —
     * redacted report text, or a label's ingredient list — and that must not reach a UI
     * string that could be screenshotted, logged or shown to another person.
     */
    private fun cloudMessage(e: Exception): String =
        "The cloud analysis could not be completed. Check your connection and try again."

    private fun medicalExtractionPrompt(redactedText: String): String = buildString {
        appendLine("You extract structured data from anonymised medical reports.")
        appendLine(
            "Tokenised identifiers such as [NAME_1], [DOB_1], [PHONE_1] or [PATIENT_ID_1] " +
                "have already been removed. Never try to reconstruct them."
        )
        appendLine(
            "Only report values that appear in the text. Do not invent diagnoses, values or " +
                "dates. If something is absent, return an empty list for it."
        )
        appendLine("Reply with JSON only, matching this shape:")
        appendLine(
            "{\"reportTitle\":\"\",\"reportType\":\"\",\"conditions\":[],\"allergies\":[]," +
                "\"medications\":[],\"dietaryRestrictions\":[],\"healthNotes\":[]," +
                "\"markers\":[{\"name\":\"\",\"value\":\"\",\"unit\":\"\"," +
                "\"status\":\"normal|high|low|unknown\"}]}"
        )
        appendLine()
        appendLine("Report text:")
        append(redactedText)
    }

    private fun healthContextPrompt(statement: String, currentProfile: MedicalProfile? = null): String = buildString {
        appendLine("You are an expert AI Health Profile Assistant for personal wellness management.")
        appendLine("Analyze the user's natural language health input, which may contain multiple sentences or paragraphs, and determine structured ADD or REMOVE actions for their health profile.")
        appendLine()
        appendLine("THE FOUR ALLOWED CATEGORIES:")
        appendLine("1. diseases: ONLY actual diagnosed illnesses and diseases (e.g., 'Diabetes', 'Asthma', 'Tuberculosis', 'Hypertension').")
        appendLine("   CRITICAL EXCLUSIONS: Do NOT classify allergies, symptoms (such as fever, cough, fatigue, headache), food intolerances, medications, or environmental allergens as diseases.")
        appendLine("2. allergies: Allergies or immune responses to foods, substances, drugs, or environmental materials (e.g., 'Peanuts', 'Penicillin', 'Latex', 'Pollen', 'Shellfish').")
        appendLine("3. medications: Active prescription drugs, over-the-counter medications, vitamins, or supplements (e.g., 'Vitamin D', 'Metformin', 'Aspirin', 'Ibuprofen').")
        appendLine("4. dietaryRestrictions: Dietary preferences, habits, or food intolerances (e.g., 'Lactose intolerance', 'Gluten-free', 'Vegan', 'Vegetarian', 'Halal', 'Kosher').")
        appendLine()
        if (currentProfile != null) {
            appendLine("CURRENT USER HEALTH PROFILE:")
            appendLine("- Diseases: " + currentProfile.conditions.ifEmpty { listOf("None recorded") }.joinToString(", "))
            appendLine("- Allergies: " + currentProfile.allergies.ifEmpty { listOf("None recorded") }.joinToString(", "))
            appendLine("- Medications: " + currentProfile.medications.ifEmpty { listOf("None recorded") }.joinToString(", "))
            appendLine("- Dietary Restrictions: " + currentProfile.dietaryRestrictions.ifEmpty { listOf("None recorded") }.joinToString(", "))
            appendLine()
        }
        appendLine("ACTION RULES (ADD, REMOVE, CLARIFY):")
        appendLine("- ADD: When user states they have a condition, allergy, take a medication, or follow a dietary restriction.")
        appendLine("  Extract concise, standardized terms (e.g., 'I have diabetes' -> item: 'Diabetes', category: 'diseases', action: 'ADD').")
        appendLine("- REMOVE: When user states recovery, resolution, cessation, or that they no longer have a condition or allergy (e.g., 'I have recovered from tuberculosis' -> item: 'Tuberculosis', category: 'diseases', action: 'REMOVE'; 'I am no longer allergic to peanuts' -> item: 'Peanuts', category: 'allergies', action: 'REMOVE'). Match against the current profile entries.")
        appendLine("- CLARIFY: When reference is ambiguous (e.g. 'I recovered from that disease' but multiple diseases exist in profile), or wording is too vague to determine which entry resolved. Formulate a specific question in 'clarificationNeeded' and do NOT remove unrelated entries.")
        appendLine()
        appendLine("STRICT CLINICAL & SAFETY RESTRICTIONS:")
        appendLine("- Never infer a diagnosis. Symptoms (cough, fever, pain) MUST NOT become diseases.")
        appendLine("- Allergies and food intolerances MUST NEVER become diseases.")
        appendLine("- Medications MUST NEVER become diseases.")
        appendLine("- Never invent dosages or unstated details.")
        appendLine("- Only explicitly stated information can be added.")
        appendLine("- Only clearly identified existing entries can be removed.")
        appendLine()
        appendLine("OUTPUT FORMAT:")
        appendLine("Reply with JSON only, matching this shape:")
        appendLine("{\"actions\":[{\"item\":\"\",\"category\":\"diseases|allergies|medications|dietaryRestrictions\",\"action\":\"ADD|REMOVE|CLARIFY\",\"confidence\":\"high|medium|low\",\"clarificationNeeded\":\"\"}],\"clarificationNeeded\":\"\"}")
        appendLine()
        appendLine("User input:")
        append(statement)
    }

    private fun ingredientPrompt(ingredients: List<String>, profile: MedicalProfile): String {
        val profileBlock = buildString {
            appendLine("Approved health profile (user-supplied and user-reviewed):")
            appendLine(
                "Conditions: " +
                    profile.conditions.ifEmpty { listOf("none recorded") }.joinToString(", ")
            )
            appendLine(
                "Allergies: " +
                    profile.allergies.ifEmpty { listOf("none recorded") }.joinToString(", ")
            )
            appendLine(
                "Medications: " +
                    profile.medications.ifEmpty { listOf("none recorded") }.joinToString(", ")
            )
            appendLine(
                "Dietary restrictions: " +
                    profile.dietaryRestrictions.ifEmpty { listOf("none recorded") }
                        .joinToString(", ")
            )
            if (profile.healthNotes.isNotEmpty()) {
                appendLine("Health notes: ${profile.healthNotes.joinToString("; ")}")
            }
        }

        return buildString {
            appendLine("You review an ingredient list against a user's own health profile.")
            appendLine(
                "You are not a doctor. Describe general, well-established nutrition and " +
                    "allergen information only. Do not diagnose, prescribe or claim to treat " +
                    "any condition. If the evidence is unclear, say so in uncertaintyNotes."
            )
            appendLine("Score 70-100 for a good match, 40-69 for use with care, 0-39 for avoid.")
            appendLine("Reply with JSON only, matching this shape:")
            appendLine(
                "{\"productName\":\"\",\"productCategory\":\"\",\"safetyScore\":0," +
                    "\"badgeLabel\":\"\",\"headline\":\"\",\"aiSummary\":\"\"," +
                    "\"healthFactors\":[{\"label\":\"\",\"detail\":\"\"," +
                    "\"direction\":\"positive|caution|neutral\"}]," +
                    "\"ingredientBreakdown\":[{\"name\":\"\"," +
                    "\"verdict\":\"safe|caution|avoid\",\"note\":\"\"}]," +
                    "\"recommendedIntake\":\"\",\"uncertaintyNotes\":[]}"
            )
            preferenceRules().takeIf { it.isNotEmpty() }?.let { rules ->
                appendLine("Rules:")
                rules.forEach { appendLine(it) }
            }
            appendLine()
            appendLine(profileBlock)
            appendLine("Ingredients from the label:")
            append(ingredients.joinToString(", "))
        }
    }

    /** Rules from the Profile settings; same options and wording as iOS and web. */
    private fun preferenceRules(): List<String> = buildList {
        if (AppPreferences.bool(AppPreferences.STRICT_SAFETY_PENALTIES, true)) {
            add(
                "- If any ingredient conflicts with an allergy, intolerance or condition in the " +
                    "profile (for example whey protein with lactose intolerance), safetyScore must " +
                    "be 39 or lower (Avoid)."
            )
        }
        if (AppPreferences.bool(AppPreferences.CRITICAL_ALLERGEN_ALERTS, true)) {
            add(
                "- If an ingredient matches a profile allergy such as nuts, dairy or gluten, say " +
                    "so clearly in the headline and add a healthFactor labelled \"Allergen\"."
            )
        }
        if (AppPreferences.bool(AppPreferences.INTOLERANCE_ALERTS, true)) {
            add("- Flag lactose, high sodium and high added sugar whenever they matter for the profile.")
        }
    }

    // ------------------------------------------------------- health item validation

    /**
     * Checks that text typed into an "Add …" dialog really is a `singular` item, e.g. rejects
     * "asdf" or "banana" as a medication. Gemini Nano goes first so nothing leaves the device;
     * the cloud model is used only when Nano is unavailable or fails. Throws if neither decides.
     */
    suspend fun validateHealthItem(text: String, singular: String): HealthItemValidation {
        val prompt = buildString {
            appendLine("A user typed the text below into the \"$singular\" list of their health profile.")
            appendLine(
                "Decide whether it is a real, recognizable $singular (misspellings of real ones " +
                    "are fine) and not gibberish, a random word, or something that belongs in a " +
                    "different list."
            )
            appendLine()
            appendLine("Reply with JSON only, with keys:")
            appendLine("\"isValid\" (boolean),")
            appendLine(
                "\"normalizedName\" (string: properly capitalized and spelled name, keeping any " +
                    "dose such as \"500 mg\"),"
            )
            appendLine("and \"reason\" (short string explaining a rejection, empty if valid).")
            appendLine()
            append("Text: \"").append(text.replace("\"", "'")).append('"')
        }

        if (nanoAvailability() is NanoAvailability.Available) {
            try {
                AiResponseParser.parseHealthItemValidation(
                    nanoModel.generateContent(prompt).text.orEmpty()
                )?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fall through to the cloud model.
            }
        }

        val raw = generate({ validationModel }, prompt, AiEngine.CLOUD)
        return AiResponseParser.parseHealthItemValidation(raw)
            ?: throw AiAnalysisException(
                AiFailureKind.INVALID_RESPONSE,
                "The answer could not be understood."
            )
    }

    private val validationModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = CLOUD_MODEL,
            generationConfig = generationConfig { responseMimeType = "application/json" },
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_IN_CLOUD)
        )
    }

    private fun categoryFor(raw: String?): IdentifierCategory? = when (raw?.trim()?.lowercase()) {
        "name", "patient_name", "person" -> IdentifierCategory.PATIENT_NAME
        "dob", "date_of_birth", "birthdate" -> IdentifierCategory.DATE_OF_BIRTH
        "phone", "phone_number", "mobile" -> IdentifierCategory.PHONE
        "email", "email_address" -> IdentifierCategory.EMAIL
        "address", "postal_address" -> IdentifierCategory.ADDRESS
        "patient_id", "mrn", "uhid" -> IdentifierCategory.PATIENT_ID
        "hospital_id", "lab_id", "accession" -> IdentifierCategory.HOSPITAL_ID
        "other", "id" -> IdentifierCategory.OTHER
        else -> null
    }

    private const val UNSUPPORTED_MESSAGE =
        "Gemini Nano is not available on this device, so on-device analysis cannot run here. " +
            "Choose Auto or Flash to use the cloud model instead."
}
