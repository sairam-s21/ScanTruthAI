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
                    InferenceAttempt.ON_DEVICE -> onDeviceIngredientAnalysis(prompt).let { result ->
                        if (index > 0 && IngredientModePlanner.onDeviceCountsAsFallback(mode)) {
                            result.copy(
                                usedFallback = true,
                                fallbackNotice = FallbackNotices.INGREDIENT_ON_DEVICE_FALLBACK
                            )
                        } else {
                            result
                        }
                    }
                    InferenceAttempt.CLOUD -> cloudIngredientAnalysis(prompt = prompt, usedFallback = false)
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
    private fun cloudMessage(e: Exception): String {
        // Only the cause chain is inspected; the returned text stays fixed.
        val causes = generateSequence<Throwable>(e) { it.cause }.take(8)
            .joinToString(" ") { "${it::class.java.simpleName} ${it.message.orEmpty()}" }
        return when {
            Regex("(?i)app ?check|attestation").containsMatchIn(causes) ->
                "This app could not verify itself with Firebase App Check, so the cloud AI request " +
                    "was blocked. Try again in a moment; if it keeps happening, the app's App Check " +
                    "setup needs attention."
            Regex("(?i)quota|429|resource.?exhausted").containsMatchIn(causes) ->
                "The cloud AI is busy right now. Try again in a minute."
            else -> "The cloud analysis could not be completed. Check your connection and try again."
        }
    }

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

    // ------------------------------------------------------- label ingredient extraction

    private val ingredientExtractor by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = CLOUD_MODEL,
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_IN_CLOUD),
            generationConfig = generationConfig {
                temperature = 0f
                responseMimeType = "application/json"
                responseSchema = Schema.obj(
                    mapOf(
                        "ingredientsSectionFound" to Schema.boolean(),
                        "ingredients" to Schema.array(Schema.string())
                    )
                )
            }
        )
    }

    /**
     * Cloud extraction of the ingredient list from a label's OCR text, with the same prompt
     * as the website. Throws when the model cannot be reached or answers in the wrong shape.
     */
    suspend fun extractIngredients(ocrText: String): Pair<Boolean, List<String>> {
        val raw = generate({ ingredientExtractor }, ingredientExtractionPrompt(ocrText), AiEngine.CLOUD)
        return AiResponseParser.parseIngredientExtraction(raw)
            ?: throw AiAnalysisException(AiFailureKind.INVALID_RESPONSE, "Unexpected ingredient answer.")
    }

    internal fun ingredientExtractionPrompt(ocrText: String): String = """
You read the ingredient list from the packaging of a food, supplement or medicine product.

The text below was produced by OCR from a phone photo of the pack. Expect OCR damage: words split or joined ("SUNFLOWERSEEDS"), misread letters ("SACILUS" for "BACILLUS", "0" for "O", "5" for "S"), lost or extra punctuation, stray symbols (¥ « | _ ©), lines in the wrong order, and text from other parts of the pack mixed in.

STEP 1 - Find the ingredients section.
- It starts at a heading such as "Ingredients", "Ingredient list", "Composition", "Contents" or "Active ingredients" (the heading itself may be misspelled by OCR, e.g. "INGREDIENIS", "1NGREDIENTS").
- It ends where the next part of the label begins, for example: "Allergen advice", "Allergy information", "Contains:", "May contain", "Commodity", "Nutrition information", a nutrition table, "Storage", "Directions", "How to use", "Best before", "Manufactured by", "FSSAI", "Net weight", "Not for medicinal use", "Do not exceed", "Keep out of reach", or any warning or statement.
- If there is no ingredients section, set "ingredientsSectionFound" to false and return an empty list. Never build a list from other parts of the label.

STEP 2 - List only what that section names as ingredients.
Never include anything from outside the section, even if it sounds like food. In particular, never include:
- nutrition table rows or values ("Total salt", "Sodium", "Energy", "Protein (g)", "Bacillus coagulans (billion CFU) 0.2");
- allergen statements ("Contains oats, soy, nuts & milk", "Processed on machinery that also processes tree nuts");
- declarations and warnings ("Contains non caloric sweetener", "Contains naturally occurring sugars", "Not for medicinal use", "Not to be used as a substitute for a varied diet");
- product category, serving, storage or usage text, brand names, claims, addresses, licence numbers, dates and prices.

STEP 3 - Write each ingredient cleanly.
- Split the list on commas and semicolons that are not inside brackets.
- A group with its parts in brackets is replaced by its parts: "Protein source blend (textured soy protein flakes, whey powder)" gives "Textured soy protein flakes" and "Whey powder"; "Fruits, nuts & seeds (raisins, chia seeds, almond)" gives "Raisins", "Chia seeds", "Almond".
- An additive class with one named substance keeps both, substance first: "Sweetener (steviol glycosides)" gives "Steviol glycosides (sweetener)"; "Emulsifier (INS 322)" gives "INS 322 (emulsifier)".
- Remove percentages, amounts and counts: "Instant oats (33%)" gives "Instant oats"; "Bacillus coagulans SNZ 1969, 15 billion CFU/g" gives "Bacillus coagulans SNZ 1969".
- Repair OCR errors only when the intended word is clear from the surrounding text, e.g. "HIMALAYAN PINK SAL" gives "Himalayan pink salt" and "S¥ TEVIOL GLYCOSIDES" gives "Steviol glycosides". If a fragment is unreadable, leave it out. Never add an ingredient that is not in the section.
- Use sentence case ("Rice bran oil"), but keep codes and strain names as printed ("INS 322", "E330", "SNZ 1969").
- Keep label order and list each ingredient once.

Example
Text: "NUTRITION INFORMATION ENERGY 120 kcal SODIUM 80 mg INGREDIENTS: WHOLE WHEAT FLOUR (62%), SUGAR, EDIBLE VEGETABLE OIL (PALM OIL), RAISING AGENTS (INS 500(ii), INS 503(ii)), IODISED SALT, EMULSIFIER (SOY LECITHIN). ALLERGEN INFORMATION: CONTAINS WHEAT AND SOY. BEST BEFORE 6 MONTHS"
Answer: {"ingredientsSectionFound": true, "ingredients": ["Whole wheat flour", "Sugar", "Edible vegetable oil (palm oil)", "INS 500(ii) (raising agent)", "INS 503(ii) (raising agent)", "Iodised salt", "Soy lecithin (emulsifier)"]}

Label text:
${"\"\"\""}
${ocrText.take(6000)}
${"\"\"\""}
""".trimStart()

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
