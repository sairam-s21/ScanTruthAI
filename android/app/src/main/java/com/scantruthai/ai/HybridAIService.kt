package com.scantruthai.ai

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InferenceMode
import com.google.firebase.ai.type.OnDeviceConfig
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig

object HybridAIService {

    // Stage 2: Strictly On-Device Privacy Gate (Gemini Nano via AICore)
    private val privacyGateModel = Firebase.ai(backend = GenerativeBackend.googleAI())
        .generativeModel(
            modelName = "gemini-3.5-flash-lite",
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_ON_DEVICE)
        )

    // Stage 3: Cloud Medical Profile & Typed Health Note Extractor
    private val profileExtractorModel = Firebase.ai(backend = GenerativeBackend.googleAI())
        .generativeModel(
            modelName = "gemini-3.5-flash-lite",
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_IN_CLOUD),
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = Schema.obj(
                    mapOf(
                        "reportTitle" to Schema.string(),
                        "markersCount" to Schema.integer(),
                        "conditions" to Schema.array(Schema.string()),
                        "allergies" to Schema.array(Schema.string()),
                        "medications" to Schema.array(Schema.string()),
                        "dietaryRestrictions" to Schema.array(Schema.string())
                    )
                )
            }
        )

    // Stage 4: Hybrid Ingredient Analyzer ("auto" | "flash" | "nano")
    fun getIngredientAnalyzerModel(mode: String = "auto") =
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = "gemini-3.5-flash-lite",
            onDeviceConfig = OnDeviceConfig(
                mode = when (mode) {
                    "nano" -> InferenceMode.ONLY_ON_DEVICE
                    "flash" -> InferenceMode.ONLY_IN_CLOUD
                    else -> InferenceMode.PREFER_IN_CLOUD
                }
            )
        )

    suspend fun maskSensitiveDetailsOnDevice(rawMedicalText: String): String {
        val prompt = """
            Identify and mask all personal identifiers (names, dates of birth, phone numbers, emails, addresses, patient/hospital IDs) in the text below.
            Return ONLY valid JSON with keys: "redactedText", "redactedItemsCount", and "detectedItems" (array of { "label", "originalValue", "maskedPreview", "category" }).

            Medical text:
            $rawMedicalText
        """.trimIndent()
        return privacyGateModel.generateContent(prompt).text ?: "{}"
    }

    suspend fun extractMedicalContext(sanitizedText: String): String {
        val prompt = """
            Extract structured conditions, allergies, medications, and dietary restrictions from this anonymized health text.
            Anonymized text:
            $sanitizedText
        """.trimIndent()
        return profileExtractorModel.generateContent(prompt).text ?: "{}"
    }

    suspend fun analyzeIngredients(
        ingredients: List<String>,
        medicalProfileJson: String,
        mode: String = "auto"
    ): String {
        val prompt = """
            Evaluate these ingredients against the user's health profile and return ONLY valid JSON with safetyScore (0-100: 70-100 Good match, 40-69 Use with care, 0-39 Avoid), badgeLabel, headline, aiSummary, healthFactors, ingredientBreakdown, and recommendedIntake.
            User Health Profile: $medicalProfileJson
            Ingredients: ${ingredients.joinToString(", ")}
        """.trimIndent()
        return getIngredientAnalyzerModel(mode).generateContent(prompt).text ?: "{}"
    }
}