package com.scantruth.ai.privacy

import com.scantruth.ai.model.IdentifierCategory

/**
 * Identifier rules for the documents this app actually sees: Indian hospital printouts,
 * diagnostic lab reports, pharmacy bills and insurance paperwork.
 *
 * [PrivacyMasker]'s base rules cover the international formats (email, SSN, patient
 * name, phone, DOB, labelled addresses). This adds two passes:
 *
 *  - [maskLongNumbers] runs *before* the base rules so a long numeric identifier
 *    (Aadhaar, ABHA, policy or account number) is masked as one unit instead of being
 *    partially swallowed by the phone rule and leaving fragments behind.
 *  - [maskLabelled] runs *after* them and handles the labels Indian reports use — PAN,
 *    IP/OP/admission/bill numbers, permanent/correspondence addresses and PIN codes.
 *
 * [hasResiduals] is the final gate: it refuses to release text when something
 * identifier-shaped survived every rule.
 *
 * Everything here runs on-device only: text in, tokens out.
 */
internal object IndianIdentifiers {

    private class Rule(
        val category: IdentifierCategory,
        val regex: Regex,
        val valueGroup: Int = 1,
        val minDigits: Int = 0
    )

    /** A value already replaced by a token, so a later rule cannot re-token it. */
    private val TOKEN = Regex("""^\[[A-Z_]+_[0-9]+]$""")

    /**
     * Runs of more than 15 digits, optionally grouped with spaces, tabs, hyphens or
     * parentheses. The phone rule already covers 7-15 digits and classifies them; longer
     * runs exist only as identifiers (policy, account, insurance) and must be masked as
     * one unit instead of being partially matched.
     */
    private val LONG_NUMBER = Rule(
        IdentifierCategory.OTHER,
        Regex("""(?<![\d.])(\d[\d \t()\-]{8,}\d)(?![\d.])"""),
        minDigits = 16
    )

    private val LABELLED_RULES: List<Rule> = listOf(
        // Aadhaar, ABHA / health ID and Ayushman card numbers.
        Rule(
            IdentifierCategory.PATIENT_ID,
            Regex(
                """(?i)\b(?:aadhaar|aadhar|uidai|abha|health\s*id|ayushman|pmjay)\b\s*(?:card)?\s*(?:no\.?|number|id)?\s*[:#\-]?\s*([0-9][0-9 \t\-]{7,22}[0-9])"""
            )
        ),
        // Aadhaar grouped with dots or hyphens: 1234.5678.9012 / 1234-5678-9012.
        // The phone rule rejects dotted values, so this shape needs its own rule.
        Rule(
            IdentifierCategory.PATIENT_ID,
            Regex("""(?<![\d.])\d{4}[ .\-]\d{4}[ .\-]\d{4}(?![\d.])"""),
            valueGroup = 0
        ),
        // Permanent Account Number, e.g. ABCDE1234F.
        Rule(
            IdentifierCategory.PATIENT_ID,
            Regex("""(?i)\bpan\b[^\n:]{0,12}[:#\-]?\s*([A-Z]{5}[0-9]{4}[A-Z])""")
        ),
        // Indian hospital record numbers: IP/OP/admission/bill/receipt/visit/case numbers.
        Rule(
            IdentifierCategory.PATIENT_ID,
            Regex(
                """(?i)\b(?:ip|op|i\.p|o\.p|admission|admit|bill|invoice|receipt|visit|encounter|case|cr|mr)\s*(?:no\.?|number|num|id)\b\s*[:#\-]?\s*([A-Za-z0-9][A-Za-z0-9/\-]{1,})"""
            )
        ),
        // Lab reference / accession numbers written without the base rule's labels.
        Rule(
            IdentifierCategory.HOSPITAL_ID,
            Regex(
                """(?i)\b(?:lab\s*ref(?:\s*(?:no\.?|id))?|ref(?:erence)?\s*(?:no\.?|number|id)|(?:mci|nmc|kmc|mmc|dmc)\s*(?:reg\.?\s*(?:no\.?|id)?))\b\s*[:#\-]?\s*([A-Za-z0-9][A-Za-z0-9/\-]{2,})"""
            )
        ),
        // Addresses in the label forms Indian reports use.
        Rule(
            IdentifierCategory.ADDRESS,
            Regex(
                """(?im)^[ \t]*(?:permanent\s*address|correspondence\s*address|postal\s*address|current\s*address|residential\s*address|home\s*address|address|residence)\s*[:\-][ \t]*(\S.*)$"""
            )
        ),
        // PIN / postal code.
        Rule(
            IdentifierCategory.ADDRESS,
            Regex("""(?i)\b(?:pin|pincode|pin\s*code|postal\s*code|zip)\b\s*[:#\-]?\s*([0-9]{5,6})""")
        )
    )

    /** Long numeric identifiers, masked as a whole before the base rules run. */
    fun maskLongNumbers(
        text: String,
        tokenFor: (IdentifierCategory, String) -> String
    ): String = apply(text, listOf(LONG_NUMBER), tokenFor)

    /** Indian labelled identifiers, applied after the base rules. */
    fun maskLabelled(
        text: String,
        tokenFor: (IdentifierCategory, String) -> String
    ): String = apply(text, LABELLED_RULES, tokenFor)

    private fun apply(
        text: String,
        rules: List<Rule>,
        tokenFor: (IdentifierCategory, String) -> String
    ): String {
        var current = text
        rules.forEach { rule ->
            current = rule.regex.replace(current) { match ->
                val value = match.groupValues[rule.valueGroup]
                val enoughDigits = value.count { it.isDigit() } >= rule.minDigits
                if (value.isBlank() || !enoughDigits || TOKEN.matches(value.trim())) {
                    match.value
                } else {
                    val token = tokenFor(rule.category, value)
                    match.value.replaceFirst(value, token)
                }
            }
        }
        return current
    }

    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")

    private val CONTIGUOUS_LONG_DIGITS = Regex("""(?<![\d.])\d{10,}(?![\d.])""")

    /**
     * Labelled values that survived masking. Each pattern requires a raw value after the
     * label — a masked value always starts with "[" — so a hit means an identifier is
     * still in the text and cloud processing must stop.
     */
    private val LABELLED_VALUES: List<Regex> = listOf(
        // Possessive whitespace: a masked value (" [DOB_1]") must not be re-read as a
        // raw value by backtracking into the space before the token.
        Regex("""(?i)\b(?:date\s*of\s*birth|d\.?o\.?b\.?)\s*[:\-][ \t]*+[^\[\s]"""),
        Regex(
            """(?i)\b(?:aadhaar|aadhar|uidai|abha|health\s*id|ayushman|pan|patient\s*id|mrn|uhid|ip\s*no\.?|op\s*no\.?|admission\s*no\.?|bill\s*no\.?|case\s*no\.?)\b[^\n]*[:#\-]\s*+(?!\[)[A-Za-z0-9]"""
        ),
        Regex(
            """(?im)^[ \t]*(?:permanent\s*address|correspondence\s*address|postal\s*address|current\s*address|residential\s*address|home\s*address|address|residence)\s*[:\-][ \t]*+[^\[\s]"""
        )
    )

    /** Grouped runs such as 1234-5678-9012-3456 that survived masking. */
    private val GROUPED_RUN = Regex("""(?<![\d.])\d[\d \t()\-]{8,}\d(?![\d.])""")

    /** True when the masked text still contains something identifier-shaped. */
    fun hasResiduals(text: String): Boolean {
        if (EMAIL.containsMatchIn(text)) return true
        if (CONTIGUOUS_LONG_DIGITS.containsMatchIn(text)) return true
        if (LABELLED_VALUES.any { it.containsMatchIn(text) }) return true
        return GROUPED_RUN.findAll(text).any { match ->
            match.value.count { it.isDigit() } >= 10
        }
    }
}
