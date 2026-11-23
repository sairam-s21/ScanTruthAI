package com.scantruth.ai.privacy

import com.scantruth.ai.model.IdentifierCategory

/**
 * Unlabelled address blocks in Indian reports.
 *
 * A labelled "Address: ..." line is already handled by the base rules. Indian printouts
 * also put the address in a header block with no label at all, and it rarely fits on one
 * line: "12, MG Road", "Pune 411001", "Maharashtra".
 *
 * A line is only treated as an address when it carries at least two independent address
 * signals (a street word, a house/flat marker, a PIN code or a state/city), so ordinary
 * sentences and clinical lines are not swallowed. Lines with measurement units or
 * collection/referral wording are always skipped.
 */
internal object IndianAddress {

    private val STREET = Regex(
        """(?i)\b(?:road|rd\.?|street|lane|nagar|colony|sector|layout|apartment|apt\.?|""" +
            """flat|plot|house|h\.?\s*no|building|bungalow|chawl|society|complex|enclave|""" +
            """extension|extn|cross|block|phase|village|vill\.?|post|p\.?o\.?|taluk|taluka|""" +
            """tehsil|district|dist\.?|near|opp\.?|opposite|landmark|marg|chowk|gate|ward)\b"""
    )

    private val HOUSE = Regex(
        """(?i)\b(?:h\.?\s*no|house\s*no|flat\s*no|plot\s*no|door\s*no|d\.?\s*no|apt\s*no)\b"""
    )

    private val PIN = Regex("""(?<![\d.])[1-6]\d{5}(?![\d.])""")

    private val REGION = Regex(
        """(?i)\b(?:andhra|arunachal|assam|bihar|chhattisgarh|goa|gujarat|haryana|""" +
            """himachal|jharkhand|karnataka|kerala|madhya\s*pradesh|maharashtra|manipur|""" +
            """meghalaya|mizoram|nagaland|odisha|orissa|punjab|rajasthan|sikkim|tamil\s*nadu|""" +
            """telangana|tripura|uttar\s*pradesh|uttarakhand|west\s*bengal|delhi|mumbai|""" +
            """pune|nagpur|nashik|thane|bengaluru|bangalore|chennai|hyderabad|kolkata|""" +
            """ahmedabad|surat|vadodara|jaipur|lucknow|kanpur|indore|bhopal|patna|kochi|""" +
            """coimbatore|chandigarh|noida|gurugram|gurgaon|faridabad|ghaziabad)\b"""
    )

    /** Clinical or referral wording: never an address block, even with street words. */
    private val NOT_ADDRESS = Regex(
        """(?i)\b(?:haemoglobin|hemoglobin|wbc|rbc|platelets?|creatinine|bilirubin|""" +
            """cholesterol|triglycerides?|glucose|hba1c|vitamin|tsh|t3|t4|alt|ast|sgpt|""" +
            """sgot|urea|sodium|potassium|calcium|range|reference|normal|result|""" +
            """\d+\s*(?:mg|g|ml|dl|mmol|iu|l)\b|g/dl|""" +
            """mmol|iu/l|ng/ml|u/l|x10|cells|cmm|mm/hr|fasting|postprandial|sample|""" +
            """collected|collection|reported|verified|opd|ipd|department|dept|""" +
            """cardiology|orthopaedic|orthopedic|radiology|refer|referred|advice)\b"""
    )

    private val WHITESPACE = Regex("""\s+""")

    fun mask(text: String, tokenFor: (IdentifierCategory, String) -> String): String {
        val lines = text.split('\n').toMutableList()

        for (index in lines.indices) {
            val value = lines[index].trim()
            if (value.length < 8 || value.startsWith("[")) continue
            if (!value.any { it.isLetter() }) continue
            if (NOT_ADDRESS.containsMatchIn(value)) continue

            // A street line can hold the PIN on the next line, which Indian printouts
            // often do; the two lines are then one block.
            val nextLine = lines.getOrNull(index + 1)?.trim().orEmpty()
            val nextCarriesPin = PIN.containsMatchIn(nextLine) || REGION.containsMatchIn(nextLine)
            val isAddress = looksLikeAddress(value) ||
                (STREET.containsMatchIn(value) && nextCarriesPin)
            if (!isAddress) continue

            lines[index] = lines[index].replaceFirst(
                value,
                tokenFor(IdentifierCategory.ADDRESS, value)
            )

            // The block usually continues for a line or two; consume only lines that
            // still carry an address signal and are not clinical content.
            var consumed = 0
            var next = index + 1
            while (next <= lines.lastIndex && consumed < 2) {
                val continuation = lines[next].trim()
                val usable = continuation.length >= 3 &&
                    !continuation.startsWith("[") &&
                    !NOT_ADDRESS.containsMatchIn(continuation) &&
                    looksLikeAddressContinuation(continuation)
                if (!usable) break
                lines[next] = lines[next].replaceFirst(
                    continuation,
                    tokenFor(IdentifierCategory.ADDRESS, continuation)
                )
                consumed++
                next++
            }
        }

        return lines.joinToString("\n")
    }

    private fun looksLikeAddress(value: String): Boolean {
        val streetHits = STREET.findAll(value).count()
        val hasHouse = HOUSE.containsMatchIn(value)
        val hasPin = PIN.containsMatchIn(value)
        val hasRegion = REGION.containsMatchIn(value)
        return when {
            hasPin && (streetHits >= 1 || hasHouse) -> true
            hasHouse && (streetHits >= 1 || hasRegion) -> true
            streetHits >= 2 -> true
            hasPin && hasRegion -> true
            else -> false
        }
    }

    private fun looksLikeAddressContinuation(value: String): Boolean {
        if (value.split(WHITESPACE).size > 6) return false
        return PIN.containsMatchIn(value) ||
            REGION.containsMatchIn(value) ||
            STREET.containsMatchIn(value) ||
            HOUSE.containsMatchIn(value)
    }
}
