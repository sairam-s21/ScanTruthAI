package com.scantruth.ai.privacy

import com.scantruth.ai.model.IdentifierCategory

/**
 * Patient-name detection for Indian lab reports and hospital printouts.
 *
 * The base rules in [PrivacyMasker] cover "Patient Name: ..." and honorifics. What they
 * miss is the layout Indian reports actually use: the label on its own line with the
 * name underneath, "Pt. Name" abbreviations, and the all-caps identity block beside
 * UHID/age/sex fields.
 *
 * Nothing here masks a word just because it is capitalised. A candidate must be a short
 * alphabetic name-shaped phrase, free of report vocabulary (test names, departments,
 * facility names, months), and either labelled or sitting next to an identity field.
 */
internal object IndianNames {

    private const val MAX_NAME_TOKENS = 4

    private val INLINE_LABEL = Regex(
        """(?im)^[ \t]*(?:patient'?s?[ \t]*name|name[ \t]*of[ \t]*(?:the[ \t]*)?patient|""" +
            """pt\.?[ \t]*name|patient[ \t]*name|name)[ \t]*[:\-][ \t]*(\S.*)$"""
    )

    private val LABEL_ONLY = Regex(
        """(?i)^[ \t]*(?:patient'?s?[ \t]*name|name[ \t]*of[ \t]*(?:the[ \t]*)?patient|""" +
            """pt\.?[ \t]*name|patient[ \t]*name|name)[ \t]*:?[ \t]*$"""
    )

    private val IDENTITY_NEARBY = Regex(
        """(?i)\b(?:patient|uhid|mrn|reg(?:istration)?|age|sex|gender|dob|date)\b"""
    )

    private val NAME_WORD = Regex("""^[A-Za-z][A-Za-z.'\-]*$""")

    private val CAPS_WORD = Regex("""^[A-Z][A-Z.'\-]*$""")

    /** Words that look name-shaped but describe the report, never a person. */
    private val DENY: Set<String> = setOf(
        // Titles and labels
        "male", "female", "unknown", "self", "patient", "name", "address", "residence",
        "phone", "mobile", "email", "date", "birth", "dob", "age", "sex", "gender",
        "uhid", "mrn", "reg", "registration", "ip", "op", "ward", "bed", "unit",
        "number", "no", "report", "sample", "collection", "collected", "received",
        // Facility and document vocabulary
        "hospital", "clinic", "centre", "center", "diagnostic", "diagnostics",
        "laboratory", "lab", "pathology", "institute", "medical", "health", "care",
        "scan", "imaging", "complete", "blood", "count", "profile", "panel", "routine",
        "screening", "test", "tests", "result", "results", "reference", "range",
        "cbc", "lft", "kft", "esr", "hba1c", "tsh", "wbc", "rbc", "alt", "ast",
        "normal", "high", "low", "abnormal", "pvt", "private", "limited", "ltd",
        "doctor", "dr", "consultant", "department", "dept", "page", "copy",
        // Small words
        "the", "of", "and", "plus", "new", "old", "not", "given", "none",
        // Months and weekdays
        "january", "february", "march", "april", "may", "june", "july", "august",
        "september", "october", "november", "december", "jan", "feb", "mar", "apr",
        "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"
    )

    /** Masks labelled names and the unlabelled caps identity block. */
    fun mask(text: String, tokenFor: (IdentifierCategory, String) -> String): String {
        val lines = text.split('\n').toMutableList()

        // 1. "Patient Name: RAHUL SHARMA" / "Name - Rahul Sharma"
        for (index in lines.indices) {
            val match = INLINE_LABEL.find(lines[index]) ?: continue
            val value = match.groupValues[1].trim()
            if (!isNameCandidate(value)) continue
            lines[index] = lines[index].replaceFirst(value, tokenFor(IdentifierCategory.PATIENT_NAME, value))
        }

        // 2. Label on its own line, value underneath (common in Indian lab printouts).
        for (index in lines.indices) {
            if (!LABEL_ONLY.matches(lines[index])) continue
            val target = (index + 1..minOf(index + 2, lines.lastIndex))
                .firstOrNull { lines[it].isNotBlank() } ?: continue
            val value = lines[target].trim().trimEnd(',', '.', ';', ':')
            if (!isNameCandidate(value)) continue
            lines[target] = lines[target].replaceFirst(
                value,
                tokenFor(IdentifierCategory.PATIENT_NAME, value)
            )
        }

        // 3. All-caps identity block, only beside an identity field and never the
        //    letterhead line at the very top.
        for (index in 1 until minOf(lines.size, 10)) {
            val value = lines[index].trim()
            if (value.isEmpty() || value.startsWith("[")) continue
            if (!isCapsNameBlock(value)) continue
            val nearby = (maxOf(0, index - 2)..minOf(lines.lastIndex, index + 2))
                .any { it != index && IDENTITY_NEARBY.containsMatchIn(lines[it]) }
            if (!nearby) continue
            lines[index] = lines[index].replaceFirst(
                value,
                tokenFor(IdentifierCategory.PATIENT_NAME, value)
            )
        }

        return lines.joinToString("\n")
    }

    private fun isNameCandidate(raw: String): Boolean {
        val value = raw.trim().trimEnd(',', '.', ';', ':')
        if (value.length !in 2..60) return false
        if (value.startsWith("[")) return false
        if (value.any { it.isDigit() }) return false

        val tokens = value.split(WHITESPACE, limit = MAX_NAME_TOKENS + 1)
        if (tokens.size !in 1..MAX_NAME_TOKENS) return false
        if (tokens.any { !NAME_WORD.matches(it) }) return false
        if (tokens.any { it.lowercase() in DENY }) return false

        // A bare short acronym ("CBC", "ESR") after a label is far more likely to be a
        // test name than a person. A two-word name is still accepted.
        if (tokens.size == 1 && tokens.first().length <= 4 &&
            tokens.first().all { it.isUpperCase() || it == '.' }
        ) {
            return false
        }

        return tokens.any { it.length >= 3 }
    }

    private fun isCapsNameBlock(value: String): Boolean {
        if (value.any { it.isDigit() }) return false
        val tokens = value.split(WHITESPACE, limit = MAX_NAME_TOKENS + 1)
        if (tokens.size !in 2..MAX_NAME_TOKENS) return false
        if (tokens.any { !CAPS_WORD.matches(it) }) return false
        if (tokens.any { it.replace(".", "").lowercase() in DENY }) return false
        return tokens.any { token -> token.count { it.isLetter() } >= 3 }
    }

    private val WHITESPACE = Regex("""\s+""")
}
