package com.scantruth.ai.privacy

import com.scantruth.ai.model.AlertSeverity
import com.scantruth.ai.model.DetectedIdentifier
import com.scantruth.ai.model.IdentifierCategory
import com.scantruth.ai.model.RedactedDocument

/** Why masking refused to produce a document. A rejection stops cloud processing. */
enum class MaskFailure { EMPTY_TEXT, TEXT_TOO_LARGE, RESIDUAL_IDENTIFIERS, INTERNAL_ERROR }

sealed interface MaskOutcome {
    data class Masked(val document: RedactedDocument) : MaskOutcome
    data class Rejected(val failure: MaskFailure, val message: String) : MaskOutcome
}

/**
 * Deterministic, rule-based on-device redaction.
 *
 * This is the guaranteed privacy baseline and runs the same way whether or not the
 * device has Gemini Nano. Nothing here talks to the network: text in, redacted text
 * out. Detected original values are reduced to tokens immediately and are never
 * returned, logged or stored.
 */
object PrivacyMasker {

    private const val MAX_TEXT_LENGTH = 40_000

    private class Rule(
        val category: IdentifierCategory,
        val regex: Regex,
        val valueGroup: Int = 0,
        val validator: (String) -> Boolean = { true }
    )

    private val DATE_LIKE = Regex(
        "\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}|\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}"
    )

    private val NAME_STOPWORDS = setOf(
        "male", "female", "unknown", "self", "patient", "na", "n", "a", "adult", "child",
        "mr", "mrs", "ms", "dr", "none", "not", "given",
        // Test and report abbreviations that appear after a "Name:" label.
        "cbc", "esr", "lft", "kft", "wbc", "rbc", "tsh", "hba1c", "alt", "ast",
        "hiv", "hbsag", "ecg", "usg", "ct", "mri"
    )

    /** Two short numeric groups, e.g. a lab reference range "4000-11000". */
    private val NUMERIC_RANGE = Regex("""^\d{1,5}\s*-\s*\d{1,5}$""")

    private fun looksLikePhone(value: String): Boolean {
        if (value.contains('.')) return false
        if (DATE_LIKE.matches(value)) return false
        // A range such as "4000-11000" is a reference interval, not a phone number.
        // Real Indian landline formats ("080-23456789") keep a long second group.
        if (NUMERIC_RANGE.matches(value.trim())) return false
        return value.count { it.isDigit() } in 7..15
    }

    private fun looksLikeName(value: String): Boolean {
        val cleaned = value.trim().trimEnd(',', '.', ';', ':')
        if (cleaned.length < 2) return false
        if (cleaned.lowercase() in NAME_STOPWORDS) return false
        if (!cleaned.any { it.isLetter() }) return false

        // A bare short acronym after a label is a test name far more often than a
        // person ("Name: CBC"), so it is left for the reviewer to mask manually.
        val words = cleaned.split(Regex("""\s+"""))
        if (words.size == 1 && words.first().length <= 4 &&
            words.first().all { it.isUpperCase() || it == '.' }
        ) {
            return false
        }
        return true
    }

    /**
     * Ordered rules. Label-bearing identifiers come before structural ones so that
     * "Patient ID: 4471" is classified as a patient ID, not a phone number, and the
     * name rules never see an already-tokenised value.
     */
    private val RULES: List<Rule> = listOf(
        Rule(
            IdentifierCategory.EMAIL,
            Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
        ),
        Rule(IdentifierCategory.OTHER, Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b")),          // SSN
        Rule(IdentifierCategory.OTHER, Regex("\\b\\d{4}\\s\\d{4}\\s\\d{4}\\b")),       // national ID
        Rule(
            IdentifierCategory.PATIENT_ID,
            Regex(
                "(?i)\\b(?:patient\\s*(?:id|no\\.?|number)|mrn|uhid|" +
                    "reg(?:istration)?\\s*(?:no\\.?|number))\\b\\s*[:#\\-]?\\s*" +
                    "([A-Za-z0-9][A-Za-z0-9/\\-]{2,})"
            ),
            valueGroup = 1
        ),
        Rule(
            IdentifierCategory.HOSPITAL_ID,
            Regex(
                "(?i)\\b(?:hospital\\s*(?:id|no\\.?)|lab\\s*(?:id|no\\.?)|" +
                    "accession\\s*(?:no\\.?|number)|report\\s*id|sample\\s*id|" +
                    "barcode)\\b\\s*[:#\\-]?\\s*([A-Za-z0-9][A-Za-z0-9/\\-]{2,})"
            ),
            valueGroup = 1
        ),
        Rule(
            IdentifierCategory.DATE_OF_BIRTH,
            Regex(
                "(?i)(?:date\\s*of\\s*birth|d\\.?o\\.?b\\.?|dob|birth\\s*date)" +
                    "\\s*[:\\-]?\\s*([0-9]{1,4}[/\\-. ][A-Za-z0-9]{1,9}[/\\-. ][0-9]{2,4})"
            ),
            valueGroup = 1
        ),
        Rule(
            IdentifierCategory.ADDRESS,
            Regex(
                "(?im)^[ \\t]*(?:residential\\s*address|home\\s*address|address|residence)" +
                    "\\s*[:\\-][ \\t]*(\\S.*)$"
            ),
            valueGroup = 1
        ),
        Rule(
            IdentifierCategory.PATIENT_NAME,
            Regex(
                "(?im)(?:patient\\s*name|name\\s*of\\s*(?:the\\s*)?patient|patient|^[ \\t]*name)" +
                    "\\s*[:\\-][ \\t]*([A-Za-z][A-Za-z.'\\-]*(?:[ \\t]+[A-Za-z][A-Za-z.'\\-]*){0,3})"
            ),
            valueGroup = 1,
            validator = ::looksLikeName
        ),
        Rule(
            IdentifierCategory.PATIENT_NAME,
            Regex("\\b(?:Mr|Mrs|Ms|Miss|Dr)\\.?\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,2})\\b"),
            valueGroup = 1,
            validator = ::looksLikeName
        ),
        Rule(
            IdentifierCategory.PHONE,
            Regex("(?<![\\d.])(\\+?\\d[\\d\\s().\\-]{5,17}\\d)(?![\\d.])"),
            valueGroup = 1,
            validator = ::looksLikePhone
        )
    )

    /**
     * Redacts [rawText].
     *
     * @param additionalValues extra exact values (for example from on-device Gemini
     *   Nano detection) to mask in addition to the deterministic rules. These stay on
     *   the device exactly like the rule matches.
     */
    fun mask(
        rawText: String,
        additionalValues: List<Pair<String, IdentifierCategory>> = emptyList()
    ): MaskOutcome {
        val text = rawText.replace("\r\n", "\n").trim()
        if (text.isEmpty()) {
            return MaskOutcome.Rejected(
                MaskFailure.EMPTY_TEXT,
                "There is no text to review from this file."
            )
        }
        if (text.length > MAX_TEXT_LENGTH) {
            return MaskOutcome.Rejected(
                MaskFailure.TEXT_TOO_LARGE,
                "This document is too large to review on-device. Try a shorter report."
            )
        }

        return try {
            val tokens = TokenRegistry()
            var current = text

            additionalValues.forEach { (value, category) ->
                if (value.length >= 2) current = maskLiteral(current, value, category, tokens)
            }

            // Long numeric identifiers first: a 16-digit policy number must be masked
            // as a whole, not partially matched by the phone rule.
            current = IndianIdentifiers.maskLongNumbers(current) { category, value ->
                tokens.tokenFor(category, value)
            }

            RULES.forEach { rule ->
                current = rule.regex.replace(current) { match ->
                    val value = match.groupValues[rule.valueGroup]
                    if (value.isBlank() || !rule.validator(value)) {
                        match.value
                    } else {
                        val token = tokens.tokenFor(rule.category, value)
                        if (rule.valueGroup == 0) {
                            token
                        } else {
                            match.value.replaceFirst(value, token)
                        }
                    }
                }
            }

            // Region-specific labels the base rules do not cover: PAN, Indian hospital
            // record numbers, permanent/correspondence addresses and PIN codes.
            current = IndianIdentifiers.maskLabelled(current) { category, value ->
                tokens.tokenFor(category, value)
            }

            // Unlabelled patient-name blocks and header address blocks. These run last
            // so they see the tokens earlier rules produced rather than raw values.
            current = IndianNames.mask(current) { category, value ->
                tokens.tokenFor(category, value)
            }
            current = IndianAddress.mask(current) { category, value ->
                tokens.tokenFor(category, value)
            }

            // Final review pass. High-risk findings block approval (see
            // RedactedDocument.isSafeToApprove); medium findings are hints shown next to
            // the text. The document is still returned so the user can fix it by adding
            // the missed value, instead of hitting a dead end.
            val alerts = (ResidualScanner.scan(current) + OcrQuality.assess(text))
                .distinctBy { it.title to it.lineNumber }
                .sortedBy { if (it.severity == AlertSeverity.HIGH) 0 else 1 }

            MaskOutcome.Masked(
                RedactedDocument(
                    redactedText = current,
                    detectedIdentifiers = tokens.detected(),
                    totalReplacements = tokens.replacements,
                    sourcePages = 0,
                    truncated = false,
                    protectedOriginalText = tokens.protectedText(current),
                    alerts = alerts
                )
            )
        } catch (e: Exception) {
            MaskOutcome.Rejected(
                MaskFailure.INTERNAL_ERROR,
                "Identifier masking could not be completed on this device."
            )
        }
    }

    private val TOKEN_PATTERN = Regex("""\[[A-Z_]+_\d+\]""")

    private fun maskLiteral(
        text: String,
        value: String,
        category: IdentifierCategory,
        tokens: TokenRegistry
    ): String {
        val pattern = Regex(Regex.escape(value), RegexOption.IGNORE_CASE)
        return pattern.replace(text) { tokens.tokenFor(category, value) }
    }

    /** Maps each distinct original value to a stable token, keeping only counters. */
    private class TokenRegistry {
        private val byValue = LinkedHashMap<String, String>()
        private val perCategoryCount = LinkedHashMap<IdentifierCategory, Int>()
        private val occurrences = LinkedHashMap<IdentifierCategory, Int>()
        private val firstToken = LinkedHashMap<IdentifierCategory, String>()
        private val tokenLengths = LinkedHashMap<String, Int>()
        var replacements: Int = 0
            private set

        fun tokenFor(category: IdentifierCategory, value: String): String {
            val key = category.name + '\u0000' + value.trim().lowercase()
            val token = byValue.getOrPut(key) {
                val next = (perCategoryCount[category] ?: 0) + 1
                perCategoryCount[category] = next
                val created = "[${category.tokenPrefix}_$next]"
                if (!firstToken.containsKey(category)) firstToken[category] = created
                // Only the length is kept, never the value: enough to draw the same
                // number of bullets in the protected review copy.
                tokenLengths[created] = value.trim().length
                created
            }
            occurrences[category] = (occurrences[category] ?: 0) + 1
            replacements++
            return token
        }

        /**
         * Review copy of the original layout with every masked value drawn as bullets.
         * Derived from the tokens and their lengths, so no original text is retained.
         */
        fun protectedText(redacted: String): String =
            TOKEN_PATTERN.replace(redacted) { match ->
                "•".repeat((tokenLengths[match.value] ?: 6).coerceIn(3, 12))
            }

        fun detected(): List<DetectedIdentifier> = occurrences.map { (category, count) ->
            DetectedIdentifier(
                category = category,
                token = firstToken[category] ?: "[${category.tokenPrefix}_1]",
                occurrences = count
            )
        }
    }
}
