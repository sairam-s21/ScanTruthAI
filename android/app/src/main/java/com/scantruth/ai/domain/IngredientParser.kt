package com.scantruth.ai.domain

import com.scantruth.ai.model.IngredientVerdict
import com.scantruth.ai.model.VerdictLevel

/**
 * Extracts ingredient names from raw OCR label text.
 *
 * Pure and deterministic so it can be unit tested without Android: no I/O, no AI.
 */
object IngredientParser {

    const val MAX_INGREDIENTS = 60
    private const val MAX_ITEM_LENGTH = 60

    private val INGREDIENT_LABEL = Regex("(?i)\\bingredients?\\b\\s*[:\\-]?")
    private val STOP_SECTION = Regex(
        "(?i)^(nutrition|nutritional|typical values|per 100|storage|store in|" +
            "directions|how to use|manufactured|distributed|best before|expiry|" +
            "allergen advice|net (?:wt|weight)|serving size|reference intake)"
    )
    private val BULLET_PREFIX = Regex("^[\\s•·*\\-–—\\d]+[).:\\-]?\\s*")
    private val PARENTHETICAL = Regex("\\([^)]*\\)")
    private val PERCENTAGE = Regex("\\d+([.,]\\d+)?\\s*%")
    private val WHITESPACE = Regex("\\s+")

    /**
     * @return ingredient names in label order, de-duplicated, capped at
     *   [MAX_INGREDIENTS]. Empty when the text has no usable list.
     */
    fun parse(ocrText: String): List<String> {
        if (ocrText.isBlank()) return emptyList()

        val normalised = ocrText.replace("\r\n", "\n").replace('\r', '\n')
        val labelMatch = INGREDIENT_LABEL.find(normalised)
        val body = if (labelMatch != null) {
            normalised.substring(labelMatch.range.last + 1)
        } else {
            normalised
        }

        val section = truncateAtNextSection(body)
        val seen = LinkedHashMap<String, String>()

        section
            .split(',', ';', '\n', '•')
            .forEach { raw ->
                val cleaned = clean(raw)
                if (cleaned != null) seen.putIfAbsent(cleaned.lowercase(), cleaned)
            }

        return seen.values.take(MAX_INGREDIENTS)
    }

    private fun truncateAtNextSection(body: String): String {
        val lines = body.split('\n')
        val kept = StringBuilder()
        for (line in lines) {
            if (STOP_SECTION.containsMatchIn(line.trim())) break
            kept.append(line).append('\n')
        }
        return kept.toString()
    }

    private fun clean(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        value = BULLET_PREFIX.replace(value, "")
        value = PERCENTAGE.replace(value, " ")
        value = PARENTHETICAL.replace(value, " ")
        value = WHITESPACE.replace(value, " ").trim()
        value = value.trim('.', ',', ':', '-', ' ')

        if (value.length < 2 || value.length > MAX_ITEM_LENGTH) return null
        if (value.none { it.isLetter() }) return null
        if (value.lowercase() in IGNORED_VALUES) return null
        return value
    }

    private val IGNORED_VALUES = setOf(
        "ingredients", "ingredient", "contains", "and", "or", "may contain",
        "see below", "as above", "none"
    )

    /**
     * Turns free-text verdict words from the model into a stable enumeration. Unknown
     * wording stays [VerdictLevel.UNKNOWN] rather than being guessed.
     */
    fun normaliseVerdict(raw: String?): VerdictLevel {
        val value = raw?.trim()?.lowercase() ?: return VerdictLevel.UNKNOWN
        return when {
            value.isEmpty() -> VerdictLevel.UNKNOWN
            value.contains("avoid") || value.contains("harmful") || value.contains("unsafe") ->
                VerdictLevel.AVOID

            value.contains("caution") || value.contains("care") || value.contains("moderate") ||
                value.contains("limit") || value.contains("watch") -> VerdictLevel.CAUTION

            value.contains("safe") || value.contains("fine") || value.contains("good") ||
                value.contains("ok") || value.contains("beneficial") -> VerdictLevel.SAFE

            else -> VerdictLevel.UNKNOWN
        }
    }

    /** Caps and de-duplicates a model-provided breakdown so the UI stays honest. */
    fun sanitiseBreakdown(items: List<IngredientVerdict>): List<IngredientVerdict> =
        items
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name.lowercase() }
            .take(MAX_INGREDIENTS)
}
