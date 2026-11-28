package com.scantruth.ai.privacy

import com.scantruth.ai.model.AlertArea
import com.scantruth.ai.model.AlertSeverity
import com.scantruth.ai.model.PrivacyAlert

/**
 * Final safety check on already-masked text.
 *
 * It answers two questions for the review screen: is anything still identifier-shaped
 * (which must block approval), and does anything merely *look* like an identifier (which
 * deserves a human look but should not stop a report whose numbers are clinical values).
 *
 * Every finding is derived from the masked text and carries only a line number and a
 * description — never the matched value — so alerts are safe to display and store.
 */
internal object ResidualScanner {

    private const val MAX_ALERTS = 12

    /** Lines that are describing a measurement, assay, or standard accreditation — not a person. */
    private val CLINICAL_CONTEXT = Regex(
        """(?i)\b(?:hb|haemoglobin|hemoglobin|wbc|rbc|platelets?|creatinine|bilirubin|""" +
            """cholesterol|triglycerides?|ldl|hdl|vldl|glucose|hba1c|vitamin|tsh|t3|t4|""" +
            """alt|ast|alp|ggt|ldh|crp|ferritin|folate|insulin|cortisol|albumin|globulin|""" +
            """ige|igg|igm|iga|psa|esr|sgpt|sgot|urea|sodium|potassium|calcium|""" +
            """range|reference|normal|result|mg|g/dl|mg/dl|ug/dl|ug/l|ng/dl|pg/ml|""" +
            """mmol|umol/l|nmol/l|pmol/l|iu/l|iu/ml|miu/ml|uiu/ml|ng/ml|u/l|u/ml|meq/l|""" +
            """g/l|k/ul|m/ul|x10|cells|cmm|cumm|mm/hr|copies/ml|fasting|postprandial|""" +
            """method|assay|analyzer|instrument|elisa|clia|eclia|hplc|iso|nabl|accredited)\b"""
    )

    private val MONTH_YEAR_CODE = Regex(
        """^(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|SEPT|OCT|NOV|DEC)[ \-/]?(?:19|20)\d{2}$"""
    )

    private val HARMLESS_STANDARD_CODE = Regex(
        """^(?:ISO|CAP|ICD|CPT|CLIA)[ \-/]?\d{4,}$"""
    )

    private class Pattern(
        val area: AlertArea,
        val severity: AlertSeverity,
        val regex: Regex,
        val title: String,
        val detail: String,
        val ignoreMatch: (String) -> Boolean = { false }
    )

    private val HIGH_RISK: List<Pattern> = listOf(
        Pattern(
            AlertArea.RESIDUAL,
            AlertSeverity.HIGH,
            Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"""),
            "Email address still visible",
            "An email address survived masking. Add it below before approving."
        ),
        Pattern(
            AlertArea.RESIDUAL,
            AlertSeverity.HIGH,
            Regex(
                """(?i)\b(?:patient\s*(?:id|no\.?|number)|mrn|uhid|aadhaar|aadhar|uidai|""" +
                    """abha|health\s*id|ayushman|pan|ip\s*no\.?|op\s*no\.?|""" +
                    """admission\s*no\.?|bill\s*no\.?|case\s*no\.?|""" +
                    """date\s*of\s*birth|d\.?o\.?b\.?|phone|mobile|""" +
                    """contact\s*(?:no\.?|number)?|tel)\b[^\n]{0,18}?[:#\-][ \t]*+(?!\[)[A-Za-z0-9]"""
            ),
            "Labelled identifier is not masked",
            "A labelled personal identifier still shows its value. Add it below or pick " +
                "another report."
        ),
        Pattern(
            AlertArea.RESIDUAL,
            AlertSeverity.HIGH,
            Regex("""(?<![\d.])\d{10,15}(?![\d.])"""),
            "Long number still visible",
            "A 10-15 digit number (phone or ID) survived masking."
        ),
        Pattern(
            AlertArea.RESIDUAL,
            AlertSeverity.HIGH,
            Regex("""\b[A-Z]{5}\d{4}[A-Z]\b"""),
            "PAN still visible",
            "A PAN-shaped identifier survived masking."
        ),
        Pattern(
            AlertArea.RESIDUAL,
            AlertSeverity.HIGH,
            Regex("""(?<![\d.])\d{4}[ .\-]\d{4}[ .\-]\d{4}(?![\d.])"""),
            "Aadhaar-shaped number still visible",
            "A 12-digit grouped number survived masking."
        )
    )

    private val SUSPICIOUS: List<Pattern> = listOf(
        Pattern(
            AlertArea.SUSPICIOUS,
            AlertSeverity.MEDIUM,
            Regex("""(?<![\d.])\d{1,2}[/\-.]\d{1,2}[/\-.]\d{2,4}(?![\d.])"""),
            "Unmasked date",
            "A date is still in the text. That is fine for a report date, but check it is " +
                "not a date of birth."
        ),
        Pattern(
            AlertArea.SUSPICIOUS,
            AlertSeverity.MEDIUM,
            Regex("""(?<![\d.])\d{6,9}(?![\d.])"""),
            "Unlabelled number",
            "A 6-9 digit number has no unit or label. Check it is a lab value and not an " +
                "unlabelled record number."
        ),
        Pattern(
            AlertArea.SUSPICIOUS,
            AlertSeverity.MEDIUM,
            Regex("""\b[A-Z]{2,4}[ \-/]?\d{4,}\b"""),
            "Identifier-shaped code",
            "A letter-and-digit code survived masking. Check whether it identifies you or " +
                "a visit.",
            ignoreMatch = { matched ->
                val clean = matched.trim().uppercase()
                MONTH_YEAR_CODE.matches(clean) || HARMLESS_STANDARD_CODE.matches(clean)
            }
        ),
        Pattern(
            AlertArea.SUSPICIOUS,
            AlertSeverity.MEDIUM,
            // Narrow context only: a two- or three-word capitalised name directly after
            // "by"/"for". This does not mask every capitalised phrase, it surfaces the
            // one shape that is almost always a person in a report footer.
            Regex(
                """\b(?:by|for)\s+([A-Z][a-z]{1,20}(?:\s+[A-Z][a-z]{1,20}){1,2})\b"""
            ),
            "Possible unlabelled name",
            "A capitalised name after a word like \"by\" is probably a person. If it is " +
                "yours, hide it below."
        )
    )

    /** Grouped runs long enough to be an insurance/policy number. */
    private val GROUPED_RUN = Regex("""(?<![\d.])\d[\d \t()\-]{8,}\d(?![\d.])""")

    fun scan(maskedText: String): List<PrivacyAlert> {
        val findings = ArrayList<PrivacyAlert>()

        HIGH_RISK.forEach { pattern -> collect(maskedText, pattern, findClinicalLine = false, into = findings) }
        SUSPICIOUS.forEach { pattern -> collect(maskedText, pattern, findClinicalLine = true, into = findings) }

        GROUPED_RUN.findAll(maskedText).forEach { match ->
            if (match.value.count { it.isDigit() } >= 16) {
                findings += PrivacyAlert(
                    area = AlertArea.RESIDUAL,
                    severity = AlertSeverity.HIGH,
                    title = "Long grouped number still visible",
                    detail = "A 16+ digit grouped number survived masking.",
                    lineNumber = lineOf(maskedText, match.range.first)
                )
            }
        }

        return findings
            .distinctBy { it.title to it.lineNumber }
            .sortedBy { if (it.severity == AlertSeverity.HIGH) 0 else 1 }
            .take(MAX_ALERTS)
    }

    fun hasHighRisk(alerts: List<PrivacyAlert>): Boolean =
        alerts.any { it.severity == AlertSeverity.HIGH }

    private fun collect(
        text: String,
        pattern: Pattern,
        findClinicalLine: Boolean,
        into: MutableList<PrivacyAlert>
    ) {
        pattern.regex.findAll(text).forEach { match ->
            if (isInsideMaskToken(text, match.range.first, match.range.last)) return@forEach
            if (pattern.ignoreMatch(match.value)) return@forEach
            val lineNumber = lineOf(text, match.range.first)
            if (findClinicalLine) {
                val line = lineAt(text, match.range.first)
                if (CLINICAL_CONTEXT.containsMatchIn(line)) return@forEach
            }
            into += PrivacyAlert(
                area = pattern.area,
                severity = pattern.severity,
                title = pattern.title,
                detail = pattern.detail,
                lineNumber = lineNumber
            )
        }
    }

    private fun isInsideMaskToken(text: String, start: Int, end: Int): Boolean {
        val openBracket = text.lastIndexOf('[', start)
        if (openBracket < 0) return false
        val closeBracket = text.indexOf(']', end)
        if (closeBracket < 0) return false
        val between = text.substring(openBracket, closeBracket + 1)
        return between.matches(Regex("""\[[A-Z_]+_\d+\]"""))
    }

    private fun lineAt(text: String, index: Int): String {
        if (text.isEmpty()) return ""
        val safe = index.coerceIn(0, text.lastIndex)
        val start = text.lastIndexOf('\n', (safe - 1).coerceAtLeast(0)).let {
            if (it < 0 || safe == 0) 0 else it + 1
        }
        val end = text.indexOf('\n', safe).let { if (it < 0) text.length else it }
        return text.substring(start, end)
    }

    private fun lineOf(text: String, index: Int): Int =
        1 + text.take(index.coerceIn(0, text.length)).count { it == '\n' }
}
