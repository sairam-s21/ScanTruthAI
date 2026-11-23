package com.scantruth.ai.privacy

import com.scantruth.ai.model.AlertArea
import com.scantruth.ai.model.AlertSeverity
import com.scantruth.ai.model.PrivacyAlert

/**
 * Local plausibility checks for the numbers OCR produced.
 *
 * OCR never reports per-character confidence for this pipeline, so instead of pretending
 * to measure it, these checks validate the *shape* of the identifiers that matter: a
 * phone with the wrong number of digits, an Aadhaar that fails its Verhoeff checksum, an
 * impossible date, or a number where letters stand in for digits. A hit does not prove a
 * misread, so it is always a review hint, never an automatic correction.
 *
 * Runs on the original text on-device only. Findings carry a line number and a
 * description; the value itself never leaves this object.
 */
internal object OcrQuality {

    private const val MAX_ALERTS = 6

    private val PHONE_LABEL = Regex("""(?i)\b(?:phone|mobile|contact|tel|telephone|whatsapp|cell)\b""")

    private val NUMBER = Regex("""(?<![\d.])\+?\d[\d \t().\-]{4,18}\d(?![\d.])""")

    private val DATE = Regex("""(?<![\d.])(\d{1,4})[/\-.](\d{1,2})[/\-.](\d{2,4})(?![\d.])""")

    private val REPEATED_DIGITS = Regex("""(\d)\1{5,}""")

    private val PAN = Regex("""^[A-Z]{5}\d{4}[A-Z]$""")

    private val ID_LABEL = Regex(
        """(?i)\b(aadhaar|aadhar|uidai|abha|health\s*id|ayushman|pan|patient\s*id|mrn|uhid|""" +
            """ip\s*no\.?|op\s*no\.?|admission\s*no\.?)\b[^\n:]{0,12}[:#\-][ \t]*""" +
            """([A-Za-z0-9][A-Za-z0-9 \-./]{1,24})"""
    )

    private val TOKEN = Regex("""\b[A-Za-z0-9]{5,}\b""")

    fun assess(rawText: String): List<PrivacyAlert> {
        val alerts = ArrayList<PrivacyAlert>()

        rawText.split('\n').forEachIndexed { index, line ->
            val lineNumber = index + 1

            if (PHONE_LABEL.containsMatchIn(line)) {
                NUMBER.findAll(line).forEach { match ->
                    val digits = match.value.filter { it.isDigit() }
                    if (digits.length !in 10..12) {
                        alerts += alert(
                            lineNumber,
                            "Phone number may be misread",
                            "A phone number on line $lineNumber has ${digits.length} digits. " +
                                "OCR may have dropped or added one — compare it with the paper copy."
                        )
                    } else if (REPEATED_DIGITS.containsMatchIn(digits)) {
                        alerts += alert(
                            lineNumber,
                            "Phone number has repeated digits",
                            "A phone number on line $lineNumber repeats the same digit. That " +
                                "often means a scan artefact; check it against the paper copy."
                        )
                    }
                }
            }

            ID_LABEL.findAll(line).forEach { match ->
                val label = match.groupValues[1].lowercase()
                val value = match.groupValues[2].trim()
                val digits = value.filter { it.isDigit() }

                val isAadhaar = label.contains("aadhaar") || label.contains("aadhar") ||
                    label.contains("uidai") || label == "abha" || label.contains("health id")
                if (isAadhaar) {
                    if (digits.length != 12) {
                        alerts += alert(
                            lineNumber,
                            "Aadhaar may be misread",
                            "An Aadhaar number on line $lineNumber has ${digits.length} digits " +
                                "instead of 12."
                        )
                    } else if (!Verhoeff.isValid(digits)) {
                        alerts += alert(
                            lineNumber,
                            "Aadhaar checksum failed",
                            "An Aadhaar number on line $lineNumber failed its checksum, so a " +
                                "digit was probably misread."
                        )
                    }
                }

                if (label == "pan" && !PAN.matches(value.take(10).uppercase())) {
                    alerts += alert(
                        lineNumber,
                        "PAN may be misread",
                        "A PAN on line $lineNumber does not match the 5-letters-4-digits-1-letter " +
                            "format."
                    )
                }
            }

            DATE.findAll(line).forEach { match ->
                if (!isPlausibleDate(match.groupValues)) {
                    alerts += alert(
                        lineNumber,
                        "Date may be misread",
                        "A date on line $lineNumber is not a possible calendar date. OCR may " +
                            "have swapped a digit."
                    )
                }
            }

            if (!DATE.containsMatchIn(line)) {
                TOKEN.findAll(line).forEach { match ->
                    if (looksLikeDigitConfusion(match.value)) {
                        alerts += alert(
                            lineNumber,
                            "Number contains letters",
                            "A number on line $lineNumber contains letters such as O, I or l " +
                                "where digits are expected — a common OCR misread."
                        )
                    }
                }
            }
        }

        return alerts.distinctBy { it.title to it.lineNumber }.take(MAX_ALERTS)
    }

    private fun alert(lineNumber: Int, title: String, detail: String) = PrivacyAlert(
        area = AlertArea.OCR,
        severity = AlertSeverity.MEDIUM,
        title = title,
        detail = detail,
        lineNumber = lineNumber
    )

    /**
     * A token that is mostly digits but contains only digit-confusable letters is far
     * more likely a misread number than a word. Real words (BLOOD, COVID) keep letters
     * that are not confusable, or have too few digits, so they are left alone.
     */
    private fun looksLikeDigitConfusion(token: String): Boolean {
        if (token.length < 6) return false
        val digits = token.count { it.isDigit() }
        if (digits < 3) return false
        if (token.any { it.isLetter() && it !in "OoIl" }) return false
        return token.any { it in "OoIl" }
    }

    private fun isPlausibleDate(groups: List<String>): Boolean {
        if (groups.size < 4) return false
        val first = groups[1].toIntOrNull() ?: return false
        val second = groups[2].toIntOrNull() ?: return false
        val third = groups[3].toIntOrNull() ?: return false

        val day: Int
        val month: Int
        val year: Int
        if (groups[1].length == 4) {
            year = first
            month = second
            day = third
        } else {
            day = first
            month = second
            year = if (groups[3].length <= 2) {
                if (third < 30) 2000 + third else 1900 + third
            } else {
                third
            }
        }

        if (month !in 1..12) return false
        if (year !in 1900..2100) return false
        return day in 1..daysIn(month, year)
    }

    private fun daysIn(month: Int, year: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        else -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
    }
}

/** Verhoeff checksum used by Aadhaar, to spot a single misread digit. */
internal object Verhoeff {

    private val D = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
        intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
        intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
        intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8),
        intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
        intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2),
        intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
        intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4),
        intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0)
    )

    private val P = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
        intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
        intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
        intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0),
        intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
        intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5),
        intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8)
    )

    fun isValid(digits: String): Boolean {
        if (digits.isEmpty() || digits.any { !it.isDigit() }) return false
        var checksum = 0
        digits.reversed().forEachIndexed { index, char ->
            checksum = D[checksum][P[index % 8][char - '0']]
        }
        return checksum == 0
    }
}
