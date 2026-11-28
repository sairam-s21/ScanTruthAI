package com.scantruth.ai.domain

import com.scantruth.ai.model.IngredientVerdict
import com.scantruth.ai.model.VerdictLevel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Overall reliability of OCR + ingredient region extraction. */
enum class ExtractionConfidence { HIGH, MEDIUM, LOW }

/**
 * Rich result of deterministic label parsing: preserves raw OCR text, detected ingredient
 * region, allergen declarations, incomplete-section warnings, extraction confidence,
 * and parsing timings alongside the cleaned ingredient list.
 */
data class IngredientParseResult(
    val ingredients: List<String>,
    val allergenDeclarations: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val rawOcrText: String = "",
    val detectedRegionText: String = "",
    val hasExplicitHeader: Boolean = false,
    val isIncomplete: Boolean = false,
    val confidence: ExtractionConfidence = ExtractionConfidence.HIGH,
    val confidenceReason: String = "",
    val filteredNonIngredients: List<String> = emptyList(),
    val durationMs: Long = 0L
)

/**
 * Extracts ingredient names from raw OCR label text.
 *
 * Pure and deterministic so it can be unit tested without Android: no I/O, no AI.
 */
object IngredientParser {

    const val MAX_INGREDIENTS = 60
    private const val MAX_ITEM_LENGTH = 260

    private val PAGE_MARKER = Regex("""(?im)^\s*page\s+\d+\s*$""")

    // Tolerates common ML Kit misreads AND left-clipped headings (e.g. GREDIENTS:, REDIENTS:)
    private val EXPLICIT_OR_CLIPPED_HEADER = Regex(
        """(?i)(?:^|[\s.;])(?:ingredients?|ingrediants?|ingred[1il]en[t7]s?|[l1]ngredients?|inqredients?|ingredienfs?|ingrediehts?|ingredlents?|ingrediens?|gredients?|redients?|edients?)(?:\s+list)?\s*[:\-–]?"""
    )

    // Line-start headings that always end an ingredient declaration.
    private val STOP_LINE_HEADER = Regex(
        """(?i)^(?:\(?\s*numbers?\s+in\s+brackets\s+as\s+per|nutrition(?:al)?(?:\s+information|\s+facts)?|utrition(?:\s+information|\s+facts)?|typical\s+values|per\s+100\s*g?ml?|""" +
            """serving\s+size|serving\s+suggestion|servings?\s+per|reference\s+intake|""" +
            """storage(?:\s+instructions?)?|store\s+in|tore\s+in|keep\s+(?:in|away)|transfer\s+contents|""" +
            """directions?|how\s+to\s+use|preparation|add\s+approximately|""" +
            """manufactured\s+by|mfd\.?\s*(?:by|on|date)?|mfg\.?\s*(?:by|on|date)?|""" +
            """marketed\s+by|marketepd\s*(?:by|pu)?|packed\s+by|pkd\.?\s*(?:by|on|date)?|distributed\s+by|imported\s+by|""" +
            """regd\.?\s*office|registered\s+office|""" +
            """best\s+before|use\s+by|expiry|exp\.?\s*date|batch\s*(?:no\.?)?|lot\s*(?:no\.?)?|""" +
            """mrp|m\.r\.p|incl\.?\s+of\s+all\s+taxes|net\s*(?:wt|weight|qty|quantity|vol)|""" +
            """fssai|lic\.?\s*(?:no\.?|number)|""" +
            """customer\s+care|consumer\s+care|for\s+feedback|complaints?|toll\s*free|helpline|""" +
            """allergen\s*(?:advice|information|warning|declaration)?|allergy\s*advice|allergens?|""" +
            """may\s+contain|contains\s+(?:added\s+flavour|permitted|wheat|milk|soy|soya|nuts?|peanuts?|gluten|egg|sulphites?)|""" +
            """ontains\s+(?:wheat|milk|soy|soya|nuts?|peanuts?|gluten|egg|sulphites?)|""" +
            """country\s+of\s+origin|made\s+in\b)"""
    )

    // Mid-line boundaries (outside brackets) where packaging/legal copy follows ingredients on the same line.
    private val STOP_INLINE_PATTERN = Regex(
        """(?i)(?:^|[.;])\s*(?:numbers?\s+in\s+brackets\s+as\s+per|nutrition(?:al)?(?:\s+information|\s+facts)?|typical\s+values|""" +
            """serving\s+suggestion|directions?|how\s+to\s+use|storage(?:\s+instructions?)?|store\s+in|""" +
            """manufactured\s+by|mfd\.?\s+by|mfg\.?\s+by|marketed\s+by|packed\s+by|distributed\s+by|imported\s+by|""" +
            """regd\.?\s*office|best\s+before|use\s+by|fssai|lic\.?\s*no|""" +
            """customer\s+care|consumer\s+care|for\s+feedback|toll\s*free|""" +
            """allergen\s*(?:advice|information|warning)?|allergy\s*advice|may\s+contain|""" +
            """contains\s+(?:added\s+flavour|permitted|wheat|milk|soy|soya|nuts?|peanuts?|gluten|egg|sulphites?)|""" +
            """net\s*(?:wt|weight|qty)|mrp\b|www\.|https?://|@[a-z0-9.-]+)"""
    )

    private val ALLERGEN_LINE = Regex(
        """(?i)^(?:allergen\s*(?:advice|information|warning|declaration)?|allergy\s*advice|allergens?)\s*[:\-]?\s*(.+)$"""
    )

    private val CONTAINS_ALLERGEN_SENTENCE = Regex(
        """(?i)\b(?:c?ontains\s+(?:wheat|milk|soy|soya|peanuts?|tree\s+nuts?|nuts?|gluten|egg|eggs|fish|crustaceans?|mustard|sesame|sulphites?|sulfites?|lactose|barley|oats|almonds?|cashews?|hazelnuts?)|may\s+contain(?:\s+traces\s+of)?)[^.\n]*\.?"""
    )

    private val HYPHENATED_WRAP = Regex("""([A-Za-z])-\n([A-Za-z]{2,})""")
    private val BULLET_PREFIX = Regex("""^[\s•·*\-–—\d]+[).:\-]?\s*""")
    private val LEADING_CONJUNCTION = Regex("""(?i)^(?:and|&)\s+""")
    private val PERCENTAGE_ONLY = Regex("""^\d+(?:[.,]\d+)?\s*%$""")
    private val EMPTY_BRACKETS = Regex("""\(\s*\)|\[\s*\]|\{\s*\}""")
    private val WHITESPACE = Regex("""\s+""")

    private val NUTRITION_LINE = Regex(
        """(?i)(?:\b(?:energy|protein|carbohydrates?|total\s+sugars?|added\s+sugars?|""" +
            """total\s+fat|saturated\s+fat|trans\s+fat|mono\s+unsaturated|poly\s+unsaturated|cholesterol|dietary\s+fib(?:er|re)|sodium)\s*[:\-]?\s*\d+(?:[.,]\d+)?\s*(?:g|mg|kcal|kj|%|mcg)?\b|""" +
            """\b(?:nutrition(?:al)?\s+(?:information|facts)|typical\s+values|per\s+100\s*g|per\s+serve|servings?\s+per\s+pack|%?\s*rda|basis\s+average\s+adult|approximate\s+values)\b)"""
    )

    private val PACKAGING_OR_ADDRESS_NOISE = Regex(
        """(?i)(?:\b(?:fssai|lic\.?\s*no|mrp|m\.r\.p|net\s*(?:wt|weight|qty)|batch\s*no|lot\s*no|""" +
            """manufactured\s*by|marketed\s*by|marketepd|packed\s*by|distributed\s*by|mfd|mfg|pkd|""" +
            """regd\.?\s*office|customer\s*care|consumer\s*care|toll\s*free|helpline|""" +
            """best\s*before|use\s+by|store\s+in|keep\s+away|made\s+in\s+india|""" +
            """pvt\.?\s*ltd\.?|industries\s*ltd\.?|r+itannia[a-z]*|britannia|private\s*limited|""" +
            """numbers?\s+in\s+brackets|international\s+numbering\s+system|""" +
            """nutrition(?:al)?|typical\s+values|serving\s+(?:size|suggestion)|""" +
            """hungerford\s+street|prestige\s+shantiniketan|colony|sector|layout|""" +
            """kolkata|mumbai|delhi|new\s+delhi|bengaluru|bangalore|chennai|hyderabad|""" +
            """pune|ahmedabad|surat|jaipur|lucknow|chandigarh|noida|gurugram|gurgaon|""" +
            """west\s+bengal|maharashtra|karnataka|tamil\s+nadu|gujarat|telangana|haryana)\b|""" +
            """^\s*\d+(?:[.,]\d+)?\s*(?:billion|million)?\s*cfu(?:/g)?\s*$|www\.|https?://|@|\b[1-9]\d{5}\b)"""
    )

    private val NUTRITION_MEASUREMENT = Regex("""(?i)\b\d+(?:[.,]\d+)?\s*(?:kcal|kj|mg|mcg|ug)\b""")
    private val MARKETING_CLAIM = Regex(
        """(?i)^(?:100%\s*(?:real|natural|pure|vegetarian)|no\s+added\s+preservatives?|""" +
            """rich\s+source\s+of|source\s+of\s+protein|baked\s+with\s+care|made\s+with\s+love|""" +
            """taste\s+bhi\s+health\s+bhi|trans\s+fat\s+free|(?:creme\s+)?biscu[ei1]ts?\s+w[ei1]th\s+choc[oa0]?[lt]ate).*"""
    )

    private val INGREDIENT_VOCAB_HINT = Regex(
        """(?i)\b(?:refined|wheat|flour|maida|sugar|edible|vegetable|oil|palm|palmolein|""" +
            """starch|cocoa|milk|whey|syrup|glucose|raising|agents?|emulsifiers?|salt|iodised|""" +
            """flavour|flavours|flavouring|flavoring|chocolate|oats|ragi|jowar|millets?|soy|soya|""" +
            """water|citric|ins\s*\d{3}|e\d{3}|500\(ii\)|503\(ii\)|322\(i\)|471|150a|150c|150d|jaggery)\b"""
    )

    private val STANDALONE_CITY_OR_NOISE = setOf(
        "kolkata", "calcutta", "mumbai", "bombay", "delhi", "new delhi", "bengaluru",
        "bangalore", "chennai", "madras", "hyderabad", "pune", "ahmedabad", "surat",
        "vadodara", "jaipur", "lucknow", "kanpur", "indore", "bhopal", "patna", "kochi",
        "coimbatore", "chandigarh", "noida", "gurugram", "gurgaon", "faridabad", "ghaziabad",
        "india", "west bengal", "maharashtra", "karnataka", "tamil nadu", "telangana",
        "gujarat", "uttar pradesh", "haryana", "rajasthan", "kerala",
        "ingredients", "ingredient", "contains", "and", "or", "may contain",
        "see below", "as above", "none", "etc", "nutrition", "nutrition facts",
        "nutrition information", "allergen advice", "allergy advice", "per 100g",
        "numbers in brackets as per international numbering system"
    )

    // Only matches digit-substituted or known OCR typos so already-valid words and singular/plural are untouched.
    private val OCR_WORD_CORRECTIONS: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)\bf1our\b""") to "Flour",
        Regex("""(?i)\boi1\b""") to "Oil",
        Regex("""(?i)\bsa1t\b""") to "Salt",
        Regex("""(?i)\bmi1k\b""") to "Milk",
        Regex("""(?i)\bso1ids\b""") to "Solids",
        Regex("""(?i)\bma1da\b""") to "Maida",
        Regex("""(?i)\baa[1il]da\b""") to "Maida",
        Regex("""(?i)\bsemolina\s*\(\s*suj\s*\)""") to "Semolina (Suji)",
        Regex("""(?i)\bread\s+improver\b""") to "Bread Improver",
        Regex("""(?i)\bpa1m\b""") to "Palm",
        Regex("""(?i)\bpa1mo[1l]ein\b""") to "Palmolein",
        Regex("""(?i)\bpalrn\b""") to "Palm",
        Regex("""(?i)\bcrearn\b""") to "Cream",
        Regex("""(?i)\bemu1sifier\b""") to "Emulsifier",
        Regex("""(?i)\bemu1sifiers\b""") to "Emulsifiers",
        Regex("""(?i)\bf1avouring\b""") to "Flavouring",
        Regex("""(?i)\bf1avours\b""") to "Flavours",
        Regex("""(?i)\bf1avour\b""") to "Flavour",
        Regex("""(?i)\bcoc0a\b""") to "Cocoa",
        Regex("""(?i)\bp0wder\b""") to "Powder",
        Regex("""(?i)\b5ugar\b""") to "Sugar",
        Regex("""(?i)\bs0ya\b""") to "Soya",
        Regex("""(?i)\bs0y\b""") to "Soy",
        Regex("""(?i)\b0ats\b""") to "Oats",
        Regex("""(?i)\bwhea7\b""") to "Wheat",
        Regex("""(?i)\bsuger\b""") to "Sugar",
        Regex("""(?i)\b[1i]od1sed\b""") to "Iodised",
        Regex("""(?i)\b1odised\b""") to "Iodised",
        Regex("""(?i)\bra1s[1i]ng\b""") to "Raising"
    )

    // Words after ')' that belong to the SAME ingredient phrase rather than starting a new item.
    private val SAME_PHRASE_AFTER_PAREN = Regex(
        """(?i)^(?:and\b|or\b|&|\[|\(|oil\b|powder\b|flour\b|solids?\b|syrup\b|extract\b|""" +
            """paste\b|butter\b|starch\b|flavour(?:ing|s)?\b|flavor(?:ing|s)?\b|substances?\b|flakes\b)"""
    )

    // Modifier prefixes where '&' or 'and' joins adjectives for one shared ingredient rather than two items.
    private val MODIFIER_BEFORE_CONJUNCTION = Regex(
        """(?i)(?:\b(?:refined|palm|nature\s+identical|artificial|mono|di|mono\s*&\s*di|""" +
            """emulsifier|raising\s+agent|antioxidant|stabiliser|stabilizer|acidity\s+regulator|colour|color)\s*$|\]\s*$)"""
    )

    /** Backward-compatible entry point returning extracted ingredient names in label order. */
    fun parse(ocrText: String): List<String> = parseDetailed(ocrText).ingredients

    /**
     * Parses raw OCR text into ingredients, separate allergen declarations, warnings,
     * extraction confidence, and detected region diagnostics.
     */
    fun parseDetailed(
        ocrText: String,
        qualityHint: String? = null,
        meanOcrConfidence: Float? = null
    ): IngredientParseResult {
        val startNs = System.nanoTime()
        if (ocrText.isBlank()) {
            return IngredientParseResult(
                ingredients = emptyList(),
                rawOcrText = ocrText,
                confidence = ExtractionConfidence.LOW,
                confidenceReason = "No text was detected on the label.",
                durationMs = elapsedMs(startNs)
            )
        }

        val normalised = PAGE_MARKER.replace(
            ocrText.replace("\r\n", "\n").replace('\r', '\n'),
            ""
        ).trim()

        val allergens = extractAllergens(normalised)
        val regionDetection = detectIngredientRegion(normalised)
        val hasExplicitHeader = regionDetection.hasExplicitHeader

        val truncation = truncateSection(
            body = regionDetection.regionBody,
            hasExplicitHeader = hasExplicitHeader
        )
        val unwrapped = joinWrappedLines(truncation.keptText)
        val correctedText = applyConservativeOcrFixes(unwrapped)
        val bracketCheck = analyseBrackets(correctedText)

        val splitChunks = splitOutsideBrackets(correctedText, bracketCheck.matchedOpenPositions)
            .flatMap { splitMergedAdjacentIngredients(it) }

        val seen = LinkedHashMap<String, String>()
        val filteredOut = mutableListOf<String>()

        splitChunks.forEach { raw ->
            val cleaned = clean(raw)
            if (cleaned == null) {
                val candidate = WHITESPACE.replace(raw.trim(), " ")
                if (candidate.isNotBlank() && isPackagingOrAddressNoise(candidate)) {
                    filteredOut += candidate
                }
            } else if (!hasExplicitHeader && !regionDetection.hasCroppedHeader &&
                looksLikeOcrGarble(cleaned) && !INGREDIENT_VOCAB_HINT.containsMatchIn(cleaned)
            ) {
                filteredOut += cleaned
            } else {
                seen.putIfAbsent(cleaned.lowercase(), cleaned)
            }
        }

        val ingredients = seen.values.take(MAX_INGREDIENTS)
        val looksUnreliable = assessUnreliableExtraction(
            ingredients = ingredients,
            hasExplicitHeader = hasExplicitHeader,
            hasCroppedHeader = regionDetection.hasCroppedHeader,
            bracketUnclosed = bracketCheck.hasUnclosed,
            abruptEnding = truncation.abruptEnding || endsAbruptly(correctedText),
            qualityHint = qualityHint,
            meanOcrConfidence = meanOcrConfidence
        )

        val warnings = buildList {
            if (!hasExplicitHeader && ingredients.isNotEmpty()) {
                add(
                    if (regionDetection.hasCroppedHeader) {
                        "The Ingredients heading appears partially cropped at the edge. Verify the first few items carefully."
                    } else {
                        "No clear 'Ingredients' heading was detected. Verify the extracted items carefully."
                    }
                )
            }
            if (bracketCheck.hasUnclosed) {
                add("Some brackets in the ingredient declaration look unclosed or cut off.")
            }
            if (truncation.abruptEnding || endsAbruptly(correctedText)) {
                add("The ingredient list may be incomplete or cut off at the edge of the photo.")
            }
            if (hasExplicitHeader && ingredients.isEmpty()) {
                add("An Ingredients heading was found, but the ingredient text could not be read clearly.")
            }
            if (looksUnreliable.confidence == ExtractionConfidence.LOW && ingredients.isNotEmpty()) {
                add(looksUnreliable.reason)
            }
        }

        return IngredientParseResult(
            ingredients = ingredients,
            allergenDeclarations = allergens,
            warnings = warnings,
            rawOcrText = ocrText.trim(),
            detectedRegionText = truncation.keptText,
            hasExplicitHeader = hasExplicitHeader,
            isIncomplete = warnings.isNotEmpty() || looksUnreliable.confidence != ExtractionConfidence.HIGH,
            confidence = looksUnreliable.confidence,
            confidenceReason = looksUnreliable.reason,
            filteredNonIngredients = filteredOut.distinct(),
            durationMs = elapsedMs(startNs)
        )
    }

    private data class RegionDetection(
        val regionBody: String,
        val hasExplicitHeader: Boolean,
        val hasCroppedHeader: Boolean
    )

    /**
     * Locates the ingredient paragraph using:
     *  1. Exact / OCR-tolerant `INGREDIENTS` headings,
     *  2. Partially cropped left-edge headings (`GREDIENTS:`, `REDIENTS:`),
     *  3. Fuzzy Levenshtein match against `ingredients` at line start,
     *  4. Surrounding-layout candidate block scoring when no heading survives OCR.
     */
    private fun detectIngredientRegion(normalised: String): RegionDetection {
        val headerMatch = findHeadingMatch(normalised)
        if (headerMatch != null) {
            val body = normalised.substring(headerMatch.bodyStartIndex)
            return RegionDetection(
                regionBody = body,
                hasExplicitHeader = !headerMatch.isCropped,
                hasCroppedHeader = headerMatch.isCropped
            )
        }

        // No heading found: score candidate paragraphs separated by blank lines so
        // Nutrition / Manufacturer / Title blocks before or beside ingredients are skipped.
        val blocks = normalised.split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (blocks.size > 1) {
            var bestScore = Int.MIN_VALUE
            var bestBlock: String? = null
            for (block in blocks) {
                val score = scoreIngredientBlock(block)
                if (score > bestScore) {
                    bestScore = score
                    bestBlock = block
                }
            }
            if (bestBlock != null && bestScore > 0) {
                return RegionDetection(
                    regionBody = bestBlock,
                    hasExplicitHeader = false,
                    hasCroppedHeader = false
                )
            }
        }

        return RegionDetection(
            regionBody = normalised,
            hasExplicitHeader = false,
            hasCroppedHeader = false
        )
    }

    private data class HeadingMatch(val bodyStartIndex: Int, val isCropped: Boolean)

    private fun findHeadingMatch(text: String): HeadingMatch? {
        val regexMatches = EXPLICIT_OR_CLIPPED_HEADER.findAll(text).toList()
        for (match in regexMatches) {
            val matchedWord = match.value.lowercase().replace(Regex("""[^a-z0-9]"""), "")
            val isCropped = matchedWord.endsWith("redients") && !matchedWord.startsWith("ing") &&
                !matchedWord.startsWith("lng") && !matchedWord.startsWith("inq") &&
                !matchedWord.startsWith("1ng")
            // Guard cropped forms like `gredients:`/`redients:` against random body words by checking colon or food list after it
            if (isCropped) {
                val tail = text.substring(match.range.last + 1).take(120)
                val hasColon = match.value.contains(':') || match.value.contains('-')
                if (!hasColon && !INGREDIENT_VOCAB_HINT.containsMatchIn(tail)) {
                    continue
                }
            }
            return HeadingMatch(bodyStartIndex = match.range.last + 1, isCropped = isCropped)
        }

        // Fuzzy line-start heading fallback (Levenshtein distance <= 2 against "ingredients")
        var cursor = 0
        for (line in text.split('\n')) {
            val lineStart = cursor
            cursor += line.length + 1
            val trimmed = line.trimStart()
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx in 5..15) {
                val prefix = trimmed.substring(0, colonIdx).lowercase().replace(Regex("""[^a-z]"""), "")
                if (prefix.length >= 6 && (levenshtein(prefix, "ingredients") <= 2 || "ingredients".endsWith(prefix))) {
                    val isCropped = !prefix.startsWith("in")
                    val absoluteColon = lineStart + (line.length - trimmed.length) + colonIdx
                    return HeadingMatch(bodyStartIndex = absoluteColon + 1, isCropped = isCropped)
                }
            }
        }
        return null
    }

    private fun scoreIngredientBlock(block: String): Int {
        var score = 0
        score += block.count { it == ',' || it == ';' } * 4
        score += INGREDIENT_VOCAB_HINT.findAll(block).count() * 7
        if (Regex("""\d+(?:[.,]\d+)?\s*%""").containsMatchIn(block)) score += 6
        if (Regex("""(?:\(|\[)[^)\]]{2,}(?:\)|\])""").containsMatchIn(block)) score += 6

        if (NUTRITION_LINE.containsMatchIn(block)) score -= 24
        if (PACKAGING_OR_ADDRESS_NOISE.containsMatchIn(block)) score -= 26
        if (MARKETING_CLAIM.matches(block.trim())) score -= 20
        return score
    }

    private data class SectionTruncation(
        val keptText: String,
        val abruptEnding: Boolean
    )

    private fun truncateSection(body: String, hasExplicitHeader: Boolean): SectionTruncation {
        val lines = body.split('\n')
        val kept = StringBuilder()
        var bracketDepth = 0
        var sawAnyText = false

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                if (sawAnyText && bracketDepth == 0) {
                    // Only stop on blank line if the preceding line ended cleanly with '.' or next block isn't ingredients,
                    // OR keep normal blank line termination between separate ML Kit columns/blocks.
                    break
                }
                continue
            }

            val inlineStop = findInlineStopIndex(trimmed, initialDepth = bracketDepth)
            val linePart = if (inlineStop != null) {
                trimmed.substring(0, inlineStop).trim()
            } else {
                trimmed
            }

            if (bracketDepth == 0 && (STOP_LINE_HEADER.containsMatchIn(linePart) || isPureNutritionOrAddressLine(linePart))) {
                break
            }

            // Skip leading packaging/nutrition lines when scanning unheaded text.
            if (!hasExplicitHeader && !sawAnyText && isPureNutritionOrAddressLine(linePart)) {
                continue
            }

            if (linePart.isNotEmpty()) {
                kept.append(linePart).append('\n')
                sawAnyText = true
                bracketDepth = updateDepth(bracketDepth, linePart)
            }

            if (inlineStop != null) break
        }

        val keptText = kept.toString().trim()
        val abrupt = bracketDepth > 0
        return SectionTruncation(keptText = keptText, abruptEnding = abrupt)
    }

    private fun isPureNutritionOrAddressLine(line: String): Boolean {
        if (NUTRITION_LINE.containsMatchIn(line) && !line.contains(',')) return true
        if (NUTRITION_MEASUREMENT.containsMatchIn(line) && line.count { it == ',' } <= 1) return true
        if (PACKAGING_OR_ADDRESS_NOISE.containsMatchIn(line) && !INGREDIENT_VOCAB_HINT.containsMatchIn(line)) return true
        if (MARKETING_CLAIM.matches(line)) return true
        if (Regex("""(?i)\b(?:kolkata|mumbai|delhi|bengaluru|bangalore|chennai|hyderabad|pune)\b.*\b[1-9]\d{5}\b""").containsMatchIn(line)) {
            return true
        }
        return false
    }

    private fun findInlineStopIndex(line: String, initialDepth: Int): Int? {
        val matches = STOP_INLINE_PATTERN.findAll(line)
        for (match in matches) {
            val prefix = line.substring(0, match.range.first)
            if (updateDepth(initialDepth, prefix) == 0) {
                return match.range.first
            }
        }
        var depth = initialDepth
        var i = 0
        while (i < line.length - 2) {
            when (line[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
                '.' -> if (depth == 0 && line[i + 1].isWhitespace()) {
                    val rest = line.substring(i + 1).trimStart()
                    if (STOP_LINE_HEADER.containsMatchIn(rest) || PACKAGING_OR_ADDRESS_NOISE.containsMatchIn(rest)) {
                        return i
                    }
                }
            }
            i++
        }
        return null
    }

    private fun joinWrappedLines(section: String): String {
        if (section.isBlank()) return ""
        val dehyphenated = HYPHENATED_WRAP.replace(section) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}"
        }
        return WHITESPACE.replace(dehyphenated.replace('\n', ' '), " ").trim()
    }

    /** Applies only well-supported contextual OCR character fixes without touching additive codes or percentages. */
    internal fun applyConservativeOcrFixes(text: String): String {
        if (text.isBlank()) return text
        var fixed = text
        // Fix missing space before '(' when glued by OCR (e.g. `Flour(Maida)` -> `Flour (Maida)`), keeping `500(ii)` untouched.
        fixed = fixed.replace(Regex("""([A-Za-z%])(\()"""), "$1 $2")
        // Ensure commas/semicolons followed immediately by letters have spacing unless inside numbers like `32,5`.
        fixed = fixed.replace(Regex("""([,;])([A-Za-z])"""), "$1 $2")

        for ((pattern, replacement) in OCR_WORD_CORRECTIONS) {
            fixed = pattern.replace(fixed) { match ->
                matchCase(match.value, replacement)
            }
        }
        return WHITESPACE.replace(fixed, " ").trim()
    }

    private fun matchCase(original: String, replacement: String): String {
        val letters = original.filter { it.isLetter() }
        val firstChar = original.firstOrNull()
        return when {
            letters.isNotEmpty() && letters.all { it.isUpperCase() } -> replacement.uppercase()
            firstChar?.isUpperCase() == true || firstChar == '5' || firstChar == '0' ->
                replacement.replaceFirstChar { it.uppercaseChar() }
            else -> replacement.lowercase()
        }
    }

    private data class BracketAnalysis(
        val matchedOpenPositions: Set<Int>,
        val hasUnclosed: Boolean
    )

    private fun analyseBrackets(text: String): BracketAnalysis {
        val stack = ArrayDeque<Pair<Char, Int>>()
        val matched = HashSet<Int>()
        for (i in text.indices) {
            when (val ch = text[i]) {
                '(', '[', '{' -> stack.addLast(ch to i)
                ')', ']', '}' -> {
                    val expectedOpen = when (ch) {
                        ')' -> '('
                        ']' -> '['
                        else -> '{'
                    }
                    var foundIndex = -1
                    for (k in stack.lastIndex downTo 0) {
                        if (stack[k].first == expectedOpen) {
                            foundIndex = k
                            break
                        }
                    }
                    if (foundIndex >= 0) {
                        val (_, openPos) = stack[foundIndex]
                        matched += openPos
                        while (stack.size > foundIndex) {
                            stack.removeLast()
                        }
                    }
                }
            }
        }
        return BracketAnalysis(
            matchedOpenPositions = matched,
            hasUnclosed = stack.isNotEmpty()
        )
    }

    private fun splitOutsideBrackets(text: String, matchedOpenPositions: Set<Int>): List<String> {
        if (text.isEmpty()) return emptyList()
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        val activeOpens = ArrayDeque<Char>()

        for (i in text.indices) {
            val ch = text[i]
            when {
                (ch == '(' || ch == '[' || ch == '{') && i in matchedOpenPositions -> {
                    depth++
                    activeOpens.addLast(ch)
                    current.append(ch)
                }
                (ch == ')' || ch == ']' || ch == '}') && activeOpens.isNotEmpty() -> {
                    val expected = when (ch) {
                        ')' -> '('
                        ']' -> '['
                        else -> '{'
                    }
                    if (activeOpens.last() == expected) {
                        activeOpens.removeLast()
                        depth = (depth - 1).coerceAtLeast(0)
                    }
                    current.append(ch)
                }
                (ch == ',' || ch == ';' || ch == '•' || ch == '·' || ch == '|') && depth == 0 -> {
                    parts += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) {
            parts += current.toString()
        }
        return parts
    }

    /**
     * Splits two top-level ingredients that merged because OCR dropped a comma after a bracket/percentage,
     * or at a trailing `... AND <Last Ingredient>` outside brackets, without splitting inside modifiers
     * like `Refined Palm & Palmolein Oil` or `Nature Identical & Artificial ... Flavouring Substances`.
     */
    internal fun splitMergedAdjacentIngredients(chunk: String): List<String> {
        val trimmed = chunk.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 1. Split dropped comma after closing bracket `)` or `]` when followed by a genuinely new ingredient
        val afterCloseBoundaries = mutableListOf<Int>()
        var depth = 0
        for (i in trimmed.indices) {
            when (trimmed[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    if (depth > 0) depth--
                    if (depth == 0 && i + 2 < trimmed.length && trimmed[i + 1].isWhitespace()) {
                        val after = trimmed.substring(i + 1).trimStart()
                        val startsWord = after.firstOrNull()?.isLetter() == true
                        if (startsWord && !SAME_PHRASE_AFTER_PAREN.containsMatchIn(after)) {
                            afterCloseBoundaries += i + 1
                        }
                    }
                }
            }
        }

        val firstPass = if (afterCloseBoundaries.isEmpty()) {
            listOf(trimmed)
        } else {
            buildList {
                var start = 0
                for (cut in afterCloseBoundaries) {
                    add(trimmed.substring(start, cut).trim())
                    start = cut
                }
                add(trimmed.substring(start).trim())
            }.filter { it.isNotEmpty() }
        }

        // 2. Split obvious comma-dropped distinct ingredients outside brackets (e.g. `INVERT SUGAR SYRUP IODISED SALT`)
        val secondPass = firstPass.flatMap { piece -> splitCommaDroppedKnownIngredients(piece) }

        // 3. Split top-level ` AND ` outside brackets when joining final distinct ingredients
        return secondPass.flatMap { piece -> splitTopLevelConjunction(piece) }
    }

    private fun splitCommaDroppedKnownIngredients(piece: String): List<String> {
        val tailPrefix = Regex("""(?i)\b(?:syrup|oil|fat|flour|salt|sugar|starch|gluten|yeast|powder|solids|paste|butter|water|milk|extract)$""")
        val nextStarter = Regex(
            """(?i)^(?:iodised\s+salt|invert\s+sugar|refined\s+(?:wheat|palm|vegetable|soy|soya|sunflower|rice)|""" +
                """whole\s+wheat|wheat\s+(?:flour|fiber|fibre)|vital\s+gluten|raising\s+agents?|emulsifiers?|""" +
                """antioxidants?|stabili[sz]ers?|acidity\s+regulators?|soya\s+flour|corn\s+starch|""" +
                """cocoa\s+solids|edible\s+(?:common\s+)?salt|bread\s+improver|yeast)\b"""
        )

        val cuts = mutableListOf<Int>()
        var depth = 0
        for (i in piece.indices) {
            when (piece[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
                ' ' -> if (depth == 0) {
                    val left = piece.substring(0, i).trimEnd()
                    val right = piece.substring(i + 1).trimStart()
                    if (tailPrefix.containsMatchIn(left) && nextStarter.containsMatchIn(right)) {
                        cuts += i
                    }
                }
            }
        }
        if (cuts.isEmpty()) return listOf(piece)
        val out = mutableListOf<String>()
        var start = 0
        for (cut in cuts) {
            val part = piece.substring(start, cut).trim()
            if (part.isNotEmpty()) out += part
            start = cut + 1
        }
        val tail = piece.substring(start).trim()
        if (tail.isNotEmpty()) out += tail
        return out
    }

    private fun splitTopLevelConjunction(piece: String): List<String> {
        var depth = 0
        val cuts = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < piece.length) {
            when (piece[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
            }
            if (depth == 0) {
                val rest = piece.substring(i)
                val match = Regex("""^(?i)\s+(?:and|&)\s+""").find(rest)
                if (match != null) {
                    val left = piece.substring(0, i).trim()
                    val right = piece.substring(i + match.value.length).trim()
                    val looksLikeTrailingJoin = left.endsWith("]") || left.endsWith(")") ||
                        left.endsWith("%") ||
                        (match.value.contains("and", ignoreCase = true) && !MODIFIER_BEFORE_CONJUNCTION.containsMatchIn(left))
                    val rightIsNoun = right.isNotEmpty() && !SAME_PHRASE_AFTER_PAREN.containsMatchIn(right) &&
                        !Regex("""(?i)^(?:flavouring|flavoring|substances|oil|fat|powder|solids)\b""").containsMatchIn(right)
                    if (left.isNotEmpty() && rightIsNoun && looksLikeTrailingJoin) {
                        cuts += i to (i + match.value.length)
                    }
                }
            }
            i++
        }

        if (cuts.isEmpty()) return listOf(piece)
        val out = mutableListOf<String>()
        var start = 0
        for ((cutStart, cutEnd) in cuts) {
            val left = piece.substring(start, cutStart).trim()
            if (left.isNotEmpty()) out += left
            start = cutEnd
        }
        val tail = piece.substring(start).trim()
        if (tail.isNotEmpty()) out += tail
        return out
    }

    private fun clean(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        value = BULLET_PREFIX.replace(value, "")
        value = LEADING_CONJUNCTION.replace(value, "")
        value = EMPTY_BRACKETS.replace(value, " ")
        value = WHITESPACE.replace(value, " ").trim()
        value = value.trim('.', ',', ':', '-', '–', '—', ';', ' ')
        value = stripUnmatchedEdgeBrackets(value)
        value = value.trim('.', ',', ':', '-', ' ')

        if (PERCENTAGE_ONLY.matches(value)) return null
        if (value.length < 2 || value.length > MAX_ITEM_LENGTH) return null
        if (value.none { it.isLetter() }) return null
        if (value.lowercase() in STANDALONE_CITY_OR_NOISE) return null
        if (isPackagingOrAddressNoise(value)) return null
        return value
    }

    /**
     * Removes stray unmatched brackets (`()`, `[]`, `{}`) anywhere in an extracted item
     * while preserving genuinely balanced pairs such as `(71%)` or `[11000, 1104]`.
     */
    private fun stripUnmatchedEdgeBrackets(input: String): String {
        val openStack = ArrayDeque<Pair<Char, Int>>()
        val unmatchedIndices = HashSet<Int>()

        for (i in input.indices) {
            when (val ch = input[i]) {
                '(', '[', '{' -> openStack.addLast(ch to i)
                ')', ']', '}' -> {
                    val expectedOpen = when (ch) {
                        ')' -> '('
                        ']' -> '['
                        else -> '{'
                    }
                    var matchPos = -1
                    for (k in openStack.lastIndex downTo 0) {
                        if (openStack[k].first == expectedOpen) {
                            matchPos = k
                            break
                        }
                    }
                    if (matchPos >= 0) {
                        while (openStack.lastIndex > matchPos) {
                            unmatchedIndices += openStack.removeLast().second
                        }
                        openStack.removeLast()
                    } else {
                        unmatchedIndices += i
                    }
                }
            }
        }
        while (openStack.isNotEmpty()) {
            unmatchedIndices += openStack.removeLast().second
        }

        if (unmatchedIndices.isEmpty()) return WHITESPACE.replace(input, " ").trim()
        val rebuilt = StringBuilder(input.length)
        for (i in input.indices) {
            if (i !in unmatchedIndices) rebuilt.append(input[i])
        }
        return WHITESPACE.replace(rebuilt.toString(), " ").trim()
    }

    private fun isPackagingOrAddressNoise(value: String): Boolean {
        if (PACKAGING_OR_ADDRESS_NOISE.containsMatchIn(value)) return true
        if (NUTRITION_MEASUREMENT.containsMatchIn(value)) return true
        if (NUTRITION_LINE.containsMatchIn(value)) return true
        if (MARKETING_CLAIM.matches(value)) return true
        return false
    }

    private data class ConfidenceAssessment(
        val confidence: ExtractionConfidence,
        val reason: String
    )

    private fun assessUnreliableExtraction(
        ingredients: List<String>,
        hasExplicitHeader: Boolean,
        hasCroppedHeader: Boolean,
        bracketUnclosed: Boolean,
        abruptEnding: Boolean,
        qualityHint: String?,
        meanOcrConfidence: Float?
    ): ConfidenceAssessment {
        if (ingredients.isEmpty()) {
            return ConfidenceAssessment(
                ExtractionConfidence.LOW,
                "No clear ingredients could be extracted. Retake a sharper photo showing the full Ingredients section."
            )
        }

        val garbledItems = ingredients.count { looksLikeOcrGarble(it) }
        val recognizedFoodItems = ingredients.count { INGREDIENT_VOCAB_HINT.containsMatchIn(it) }
        val lowOcrSignal = meanOcrConfidence != null && meanOcrConfidence < 0.62f

        val isLow = (!hasExplicitHeader && !hasCroppedHeader && ingredients.size <= 2 && recognizedFoodItems == 0) ||
            (garbledItems * 2 > ingredients.size) ||
            (lowOcrSignal && ingredients.size <= 2)

        if (isLow) {
            return ConfidenceAssessment(
                ExtractionConfidence.LOW,
                "Extraction confidence is low and may be incomplete or misread. Retake a closer photo of the Ingredients label or verify the original OCR text."
            )
        }

        val hasCautionSignal = !hasExplicitHeader || hasCroppedHeader || bracketUnclosed ||
            abruptEnding || qualityHint != null || garbledItems > 0 || (meanOcrConfidence != null && meanOcrConfidence < 0.78f)

        return if (hasCautionSignal) {
            ConfidenceAssessment(
                ExtractionConfidence.MEDIUM,
                "Some parts of the label had minor formatting or edge warnings. Review the extracted list and original OCR text."
            )
        } else {
            ConfidenceAssessment(
                ExtractionConfidence.HIGH,
                "Clear Ingredients section detected and parsed cleanly."
            )
        }
    }

    internal fun looksLikeOcrGarble(item: String): Boolean {
        val tokens = item.split(WHITESPACE).filter { it.any { ch -> ch.isLetter() } }
        if (tokens.isEmpty()) return true
        val weirdTokens = tokens.count { token ->
            val letters = token.filter { it.isLetter() }
            if (letters.length < 3) return@count false
            val hasMixedRandomCase = letters.drop(1).any { it.isUpperCase() } && letters.any { it.isLowerCase() }
            val digitsInsideWord = token.any { it.isDigit() } && letters.length >= 3 &&
                !Regex("""(?i)^(?:ins|e\d+|b\d+|\d+[%a-z]?|\d+\([ivx]+\))$""").containsMatchIn(token)
            val lowVowels = letters.length >= 5 && letters.count { it.lowercaseChar() in "aeiouy" } == 0
            hasMixedRandomCase || digitsInsideWord || lowVowels
        }
        return weirdTokens * 2 >= tokens.size
    }

    private fun extractAllergens(text: String): List<String> {
        val found = LinkedHashMap<String, String>()
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val headerMatch = ALLERGEN_LINE.find(line)
            if (headerMatch != null) {
                val combined = StringBuilder(headerMatch.groupValues[1].trim())
                var nextIdx = i + 1
                while (nextIdx < lines.size) {
                    val next = lines[nextIdx].trim()
                    if (next.isEmpty() || STOP_LINE_HEADER.containsMatchIn(next)) break
                    if (combined.toString().endsWith(".")) break
                    combined.append(' ').append(next)
                    nextIdx++
                }
                val cleaned = WHITESPACE.replace(combined.toString(), " ").trim().trimEnd('.')
                if (cleaned.isNotEmpty() && !cleaned.lowercase().startsWith("added flavour")) {
                    found.putIfAbsent(cleaned.lowercase(), cleaned)
                }
                i = nextIdx
                continue
            }

            CONTAINS_ALLERGEN_SENTENCE.findAll(line).forEach { m ->
                val rawDecl = WHITESPACE.replace(m.value, " ").trim().trimEnd('.')
                val normalizedDecl = if (rawDecl.lowercase().startsWith("ontains ")) "C$rawDecl" else rawDecl
                if (normalizedDecl.isNotEmpty()) {
                    found.putIfAbsent(normalizedDecl.lowercase(), normalizedDecl)
                }
            }
            i++
        }
        return found.values.toList()
    }

    private fun endsAbruptly(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return trimmed.endsWith(",") ||
            trimmed.endsWith(";") ||
            trimmed.endsWith("-") ||
            Regex("""(?i)\b(?:and|with|or|of|from)\s*$""").containsMatchIn(trimmed)
    }

    private fun updateDepth(startDepth: Int, text: String): Int {
        var depth = startDepth
        for (ch in text) {
            when (ch) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
            }
        }
        return depth
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val cur = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) {
                    prev
                } else {
                    1 + min(prev, min(dp[j], dp[j - 1]))
                }
                prev = cur
            }
        }
        return dp[b.length]
    }

    private fun elapsedMs(startNs: Long): Long =
        ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)

    /**
     * Turns free-text verdict words from the model into a stable enumeration. Unknown
     * wording stays [VerdictLevel.UNKNOWN] rather than being guessed.
     */
    fun normaliseVerdict(raw: String?): VerdictLevel {
        val value = raw?.trim()?.lowercase() ?: return VerdictLevel.UNKNOWN
        return when {
            value.isEmpty() -> VerdictLevel.UNKNOWN
            value.contains("unknown") || value.contains("uncertain") || value.contains("insufficient") ->
                VerdictLevel.UNKNOWN

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
