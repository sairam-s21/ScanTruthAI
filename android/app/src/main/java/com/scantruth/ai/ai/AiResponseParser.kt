package com.scantruth.ai.ai

import com.scantruth.ai.domain.IngredientParser
import com.scantruth.ai.model.AiEngine
import com.scantruth.ai.model.FactorDirection
import com.scantruth.ai.model.HealthFactor
import com.scantruth.ai.model.HealthMarker
import com.scantruth.ai.model.HealthActionItem
import com.scantruth.ai.model.HealthActionPlan
import com.scantruth.ai.model.HealthActionType
import com.scantruth.ai.model.HealthContextSuggestion
import com.scantruth.ai.model.HealthItemValidation
import com.scantruth.ai.model.IngredientAnalysis
import com.scantruth.ai.model.IngredientVerdict
import com.scantruth.ai.model.MarkerStatus
import com.scantruth.ai.model.MedicalExtraction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed interface ParseResult<out T> {
    data class Success<T>(val value: T) : ParseResult<T>
    data class Invalid(val reason: String) : ParseResult<Nothing>
}

// ---------------------------------------------------------------- wire DTOs

@Serializable
internal data class MedicalExtractionDto(
    val reportTitle: String? = null,
    val reportType: String? = null,
    val conditions: List<String> = emptyList(),
    val allergies: List<String> = emptyList(),
    val medications: List<String> = emptyList(),
    val dietaryRestrictions: List<String> = emptyList(),
    val healthNotes: List<String> = emptyList(),
    val markers: List<MarkerDto> = emptyList(),
    // Optional: a response may carry fallback metadata; absence means "no fallback".
    val usedFallback: Boolean? = null,
    val fallbackNotice: String? = null
)

@Serializable
internal data class MarkerDto(
    val name: String = "",
    val value: String = "",
    val unit: String? = null,
    val status: String? = null
)

@Serializable
internal data class IngredientAnalysisDto(
    val productName: String? = null,
    val productCategory: String? = null,
    val safetyScore: Int? = null,
    @SerialName("badgeLabel") val badgeLabel: String? = null,
    val headline: String? = null,
    @SerialName("aiSummary") val aiSummary: String? = null,
    val healthFactors: List<HealthFactorDto> = emptyList(),
    val ingredientBreakdown: List<BreakdownDto> = emptyList(),
    val recommendedIntake: String? = null,
    val uncertaintyNotes: List<String> = emptyList(),
    val confidence: String? = null,
    val usedFallback: Boolean? = null,
    val fallbackNotice: String? = null
)

@Serializable
internal data class HealthFactorDto(
    val label: String = "",
    val detail: String = "",
    val direction: String? = null
)

@Serializable
internal data class BreakdownDto(
    val name: String = "",
    val verdict: String? = null,
    val note: String? = null
)

@Serializable
internal data class HealthActionDto(
    val item: String = "",
    val value: String = "",
    val category: String = "",
    val action: String = "ADD",
    val confidence: String? = null,
    val clarificationNeeded: String? = null
)

@Serializable
internal data class HealthContextDto(
    val actions: List<HealthActionDto> = emptyList(),
    val conditions: List<String> = emptyList(),
    val allergies: List<String> = emptyList(),
    val medications: List<String> = emptyList(),
    val dietaryRestrictions: List<String> = emptyList(),
    val healthNotes: List<String> = emptyList(),
    val clarificationNeeded: String? = null
)

@Serializable
internal data class NanoIdentifierDto(
    val value: String = "",
    val category: String? = null
)

@Serializable
internal data class NanoIdentifierResponseDto(
    val items: List<NanoIdentifierDto> = emptyList()
)

@Serializable
internal data class HealthItemValidationDto(
    val isValid: Boolean? = null,
    val normalizedName: String? = null,
    val reason: String? = null
)

// ---------------------------------------------------------------- parser

/**
 * Validates and normalises every AI response before it can be displayed or saved.
 *
 * The model is treated as untrusted input: JSON may arrive fenced in markdown, with
 * extra keys, numbers as strings or fields missing entirely. Anything that cannot be
 * turned into a coherent result becomes [ParseResult.Invalid] so the UI can say the
 * analysis failed instead of showing invented values.
 */
object AiResponseParser {

    private const val MAX_LIST_ITEMS = 40
    private const val MAX_TEXT_LENGTH = 600

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * @param sourceText the approved redacted text the model was given. When present,
     *   every reported marker must be traceable to it, so an invented value or unit is
     *   dropped instead of being shown as if it came from the report.
     */
    fun parseMedicalExtraction(
        raw: String,
        engine: AiEngine,
        sourceText: String? = null
    ): ParseResult<MedicalExtraction> {
        val body = extractJsonObject(raw)
            ?: return ParseResult.Invalid("The AI response was empty.")
        val dto = try {
            json.decodeFromString(MedicalExtractionDto.serializer(), body)
        } catch (e: Exception) {
            return ParseResult.Invalid("The AI response could not be read as medical data.")
        }

        val grounding = sourceText
            ?.takeIf { it.isNotBlank() }
            ?.let { Grounding(it) }

        val markers = dto.markers
            .mapNotNull { marker ->
                val name = marker.name.clean() ?: return@mapNotNull null
                val value = marker.value.clean() ?: return@mapNotNull null
                // Ungrounded markers are hallucinations by definition: drop them rather
                // than presenting a lab value the report does not contain.
                if (grounding != null && !grounding.supportsValue(value)) return@mapNotNull null
                HealthMarker(
                    name = name,
                    value = value,
                    unit = marker.unit.clean()?.takeIf { unit ->
                        grounding == null || grounding.supportsUnit(unit)
                    },
                    status = markerStatus(marker.status)
                )
            }
            .take(MAX_LIST_ITEMS)

        val extraction = MedicalExtraction(
            reportTitle = dto.reportTitle.clean() ?: "Medical report",
            reportType = dto.reportType.clean() ?: "Unspecified report",
            conditions = dto.conditions.cleanList(),
            allergies = dto.allergies.cleanList(),
            medications = dto.medications.cleanList(),
            dietaryRestrictions = dto.dietaryRestrictions.cleanList(),
            healthNotes = dto.healthNotes.cleanList(),
            markers = markers,
            engine = engine,
            usedFallback = dto.usedFallback == true,
            fallbackNotice = if (dto.usedFallback == true) dto.fallbackNotice.clean() else null
        )

        return if (extraction.isEmpty) {
            ParseResult.Invalid(
                "The AI did not find any medical details it could confirm in this report."
            )
        } else {
            ParseResult.Success(extraction)
        }
    }

    fun parseIngredientAnalysis(raw: String, engine: AiEngine): ParseResult<IngredientAnalysis> {
        val body = extractJsonObject(raw)
            ?: return ParseResult.Invalid("The AI response was empty.")
        val dto = try {
            json.decodeFromString(IngredientAnalysisDto.serializer(), body)
        } catch (e: Exception) {
            return ParseResult.Invalid("The AI response could not be read as an analysis.")
        }

        val score = dto.safetyScore
            ?: return ParseResult.Invalid("The analysis did not include a safety score.")
        if (score !in 0..100) {
            return ParseResult.Invalid("The analysis returned a safety score outside 0–100.")
        }

        val headline = dto.headline.clean()
            ?: return ParseResult.Invalid("The analysis did not include a headline.")

        val factors = dto.healthFactors
            .mapNotNull { factor ->
                val label = factor.label.clean() ?: return@mapNotNull null
                HealthFactor(
                    label = label,
                    detail = factor.detail.clean().orEmpty(),
                    direction = direction(factor.direction)
                )
            }
            .take(MAX_LIST_ITEMS)

        val breakdown = IngredientParser.sanitiseBreakdown(
            dto.ingredientBreakdown.mapNotNull { item ->
                val name = item.name.clean() ?: return@mapNotNull null
                IngredientVerdict(
                    name = name,
                    verdict = IngredientParser.normaliseVerdict(item.verdict),
                    note = item.note.clean()
                )
            }
        )

        val uncertainty = dto.uncertaintyNotes.cleanList().toMutableList()
        if (dto.confidence.clean()?.lowercase()?.let { it.contains("low") } == true) {
            uncertainty.add("The AI reported low confidence in this analysis.")
        }

        val analysis = IngredientAnalysis(
            productName = dto.productName.clean() ?: "Scanned product",
            productCategory = dto.productCategory.clean() ?: "Uncategorised",
            safetyScore = score,
            badgeLabel = dto.badgeLabel.clean() ?: bandLabel(score),
            headline = headline,
            aiSummary = dto.aiSummary.clean().orEmpty(),
            healthFactors = factors,
            ingredientBreakdown = breakdown,
            recommendedIntake = dto.recommendedIntake.clean().orEmpty(),
            uncertaintyNotes = uncertainty,
            engine = engine,
            usedFallback = dto.usedFallback == true,
            fallbackNotice = if (dto.usedFallback == true) dto.fallbackNotice.clean() else null
        )
        return ParseResult.Success(analysis)
    }

    fun parseHealthActions(
        raw: String,
        sourceInput: String? = null
    ): ParseResult<HealthActionPlan> {
        val body = extractJsonObject(raw)
            ?: return ParseResult.Invalid("The AI response was empty.")
        val dto = try {
            json.decodeFromString(HealthContextDto.serializer(), body)
        } catch (e: Exception) {
            return ParseResult.Invalid("The AI response could not be read as health action data.")
        }

        val clarification = dto.clarificationNeeded.clean()
        val actions = mutableListOf<HealthActionItem>()

        dto.actions.forEach { actionDto ->
            val item = actionDto.item.clean() ?: actionDto.value.clean()
            val category = actionDto.category.clean()?.lowercase()?.replace(" ", "")?.replace("_", "") ?: ""
            val actionStr = actionDto.action.clean()?.uppercase() ?: "ADD"
            val itemClarification = actionDto.clarificationNeeded.clean()
            val confidence = actionDto.confidence.clean() ?: "high"

            val actionType = when (actionStr) {
                "REMOVE" -> HealthActionType.REMOVE
                "CLARIFY" -> HealthActionType.CLARIFY
                else -> HealthActionType.ADD
            }

            if (item != null && category.isNotEmpty()) {
                val normalizedCat = when {
                    category.contains("disease") || category.contains("condition") -> "diseases"
                    category.contains("allerg") -> "allergies"
                    category.contains("med") -> "medications"
                    category.contains("diet") -> "dietaryRestrictions"
                    else -> "diseases"
                }
                actions.add(
                    HealthActionItem(
                        item = item,
                        category = normalizedCat,
                        action = actionType,
                        confidence = confidence,
                        clarificationNeeded = itemClarification
                    )
                )
            } else if (itemClarification != null) {
                actions.add(
                    HealthActionItem(
                        item = item.orEmpty(),
                        category = "diseases",
                        action = HealthActionType.CLARIFY,
                        confidence = "low",
                        clarificationNeeded = itemClarification
                    )
                )
            }
        }

        if (actions.isEmpty()) {
            dto.conditions.cleanList().forEach {
                actions.add(HealthActionItem(it, "diseases", HealthActionType.ADD))
            }
            dto.allergies.cleanList().forEach {
                actions.add(HealthActionItem(it, "allergies", HealthActionType.ADD))
            }
            dto.medications.cleanList().forEach {
                actions.add(HealthActionItem(it, "medications", HealthActionType.ADD))
            }
            dto.dietaryRestrictions.cleanList().forEach {
                actions.add(HealthActionItem(it, "dietaryRestrictions", HealthActionType.ADD))
            }
        }

        val distinctActions = actions.distinctBy {
            "${it.item.lowercase()}|${it.category.lowercase()}|${it.action}"
        }

        val effectiveClarification = clarification
            ?: actions.firstOrNull { it.clarificationNeeded != null }?.clarificationNeeded

        val plan = HealthActionPlan(
            actions = distinctActions,
            clarificationNeeded = effectiveClarification
        )

        return if (plan.isEmpty) {
            ParseResult.Invalid(
                "No health details or actions could be identified from your statement."
            )
        } else {
            ParseResult.Success(plan)
        }
    }

    fun parseHealthContext(
        raw: String,
        sourceInput: String? = null
    ): ParseResult<HealthContextSuggestion> {
        val body = extractJsonObject(raw)
            ?: return ParseResult.Invalid("The AI response was empty.")
        val dto = try {
            json.decodeFromString(HealthContextDto.serializer(), body)
        } catch (e: Exception) {
            return ParseResult.Invalid("The AI response could not be read as health context data.")
        }

        var conditions = dto.conditions.cleanList()
        var allergies = dto.allergies.cleanList()
        var medications = dto.medications.cleanList()
        var dietaryRestrictions = dto.dietaryRestrictions.cleanList()
        var healthNotes = dto.healthNotes.cleanList()
        val clarification = dto.clarificationNeeded.clean()

        if (conditions.isEmpty() && allergies.isEmpty() && medications.isEmpty() && dietaryRestrictions.isEmpty()) {
            dto.actions.filter { it.action.uppercase() == "ADD" }.forEach { a ->
                val itm = a.item.clean() ?: return@forEach
                val cat = a.category.lowercase()
                when {
                    cat.contains("disease") || cat.contains("condition") -> conditions = (conditions + itm).distinctBy { it.lowercase() }
                    cat.contains("allerg") -> allergies = (allergies + itm).distinctBy { it.lowercase() }
                    cat.contains("med") -> medications = (medications + itm).distinctBy { it.lowercase() }
                    cat.contains("diet") -> dietaryRestrictions = (dietaryRestrictions + itm).distinctBy { it.lowercase() }
                }
            }
        }

        // Unclassified information should remain in Health notes
        if (conditions.isEmpty() && allergies.isEmpty() && medications.isEmpty() &&
            dietaryRestrictions.isEmpty() && healthNotes.isEmpty() && clarification == null
        ) {
            val fallbackNote = sourceInput?.trim()?.takeIf { it.isNotEmpty() }
            if (fallbackNote != null) {
                healthNotes = listOf(fallbackNote)
            }
        }

        val suggestion = HealthContextSuggestion(
            conditions = conditions,
            allergies = allergies,
            medications = medications,
            dietaryRestrictions = dietaryRestrictions,
            healthNotes = healthNotes,
            clarificationNeeded = clarification
        )

        return if (suggestion.isEmpty && suggestion.clarificationNeeded == null) {
            ParseResult.Invalid(
                "No health details or categories could be identified from your statement."
            )
        } else {
            ParseResult.Success(suggestion)
        }
    }

    internal fun parseNanoIdentifiers(raw: String): List<NanoIdentifierDto> {
        val body = extractJsonObject(raw) ?: return emptyList()
        return try {
            json.decodeFromString(NanoIdentifierResponseDto.serializer(), body)
                .items
                .filter { it.value.isNotBlank() }
                .take(50)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Null when the answer is not a usable `{ "isValid": … }` object. */
    fun parseHealthItemValidation(raw: String): HealthItemValidation? {
        val body = extractJsonObject(raw) ?: return null
        return try {
            val dto = json.decodeFromString(HealthItemValidationDto.serializer(), body)
            val valid = dto.isValid ?: return null
            HealthItemValidation(
                isValid = valid,
                normalizedName = dto.normalizedName?.trim()?.takeIf { it.isNotEmpty() },
                reason = dto.reason?.trim()?.takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Pulls the first JSON object out of a response that may be fenced or chatty. */
    fun extractJsonObject(raw: String): String? {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").trim()
        val start = trimmed.indexOf('{')
        if (start < 0) return null
        val end = trimmed.lastIndexOf('}')
        if (end <= start) return null
        val candidate = trimmed.substring(start, end + 1)
        return candidate.ifBlank { null }
    }

    /**
     * Digit and word grounding for reported markers.
     *
     * Values are compared on their digits only, so "13.2", "13,2" and "13.20" all match
     * the same report line, and units are compared on their normalised text so a unit the
     * report never stated is reported as absent instead of being invented.
     */
    private class Grounding(sourceText: String) {
        private val digits = sourceText.filter { it.isDigit() }
        private val lowercased = sourceText.lowercase()
        private val compact = lowercased.replace(" ", "")

        fun supportsValue(value: String): Boolean {
            val valueDigits = value.filter { it.isDigit() }
            if (valueDigits.isEmpty()) return lowercased.contains(value.lowercase())
            return digits.contains(valueDigits)
        }

        fun supportsUnit(unit: String): Boolean {
            val needle = unit.lowercase().replace(" ", "")
            return needle.isNotEmpty() && compact.contains(needle)
        }
    }

    private fun markerStatus(raw: String?): MarkerStatus {
        val value = raw?.trim()?.lowercase().orEmpty()
        return when {
            value.contains("high") || value.contains("elevated") || value.contains("above") ->
                MarkerStatus.HIGH

            value.contains("low") || value.contains("below") ||
                value.contains("deficien") -> MarkerStatus.LOW

            value.contains("normal") || value.contains("within") ||
                value.contains("ok") -> MarkerStatus.NORMAL

            else -> MarkerStatus.UNKNOWN
        }
    }

    private fun direction(raw: String?): FactorDirection {
        val value = raw?.trim()?.lowercase().orEmpty()
        return when {
            value.contains("pos") || value.contains("good") || value.contains("benefit") ->
                FactorDirection.POSITIVE

            value.contains("caution") || value.contains("warn") || value.contains("risk") ||
                value.contains("neg") -> FactorDirection.CAUTION

            value.contains("neutral") -> FactorDirection.NEUTRAL
            else -> FactorDirection.UNKNOWN
        }
    }

    private fun bandLabel(score: Int): String = when {
        score >= 70 -> "Good match"
        score >= 40 -> "Use with care"
        else -> "Avoid"
    }

    private fun String?.clean(): String? = this
        ?.trim()
        ?.take(MAX_TEXT_LENGTH)
        ?.takeIf { it.isNotEmpty() }

    private fun List<String>?.cleanList(): List<String> = this
        .orEmpty()
        .mapNotNull { it.clean() }
        .distinctBy { it.lowercase() }
        .take(MAX_LIST_ITEMS)
}
