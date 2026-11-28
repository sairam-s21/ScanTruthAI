package com.scantruth.ai.domain

import com.scantruth.ai.model.IngredientVerdict
import com.scantruth.ai.model.VerdictLevel

/** Result of reading the ingredients section of a label. */
data class ParsedIngredients(
    /** False when no ingredients heading was found; the caller should not guess. */
    val sectionFound: Boolean,
    /** The text between the heading and the next label section. */
    val sectionText: String,
    val ingredients: List<String>
)

/**
 * Reads the ingredient list out of a label's OCR text. Same rules as the website's
 * `ingredientParser.ts`, so both apps read a label the same way.
 *
 * Pure and deterministic so it can be unit tested without Android: no I/O, no AI. Used on
 * its own when the cloud model is unavailable, and to check the cloud model's answer.
 */
object IngredientParser {

    const val MAX_INGREDIENTS = 60

    /** Headings that start the ingredient list. OCR often garbles these, so matching is fuzzy. */
    private val HEADER_WORDS = listOf("ingredients", "ingredient", "composition", "contents")

    /** Next label sections. They end the list wherever they occur, even inside a broken bracket. */
    private val SECTION_HEADINGS = listOf(
        """allerg[a-z]*\b""",
        """commodity\b""",
        """category\s*[:\-]""",
        """nutrition(?:al)?\b""",
        """(?:typical|approximate)\s+values\b""",
        """storage\b""",
        """store\s+(?:in|at|below)\b""",
        """directions?\b""",
        """how\s+to\s+(?:use|prepare|make)\b""",
        """serving\s+(?:suggestions?|size)\b""",
        """recommended\s+(?:serving|dosage|daily)\b""",
        """best\s+before\b""",
        """use\s+by\b""",
        """(?:mfg|mfd|pkd)\.?\s*(?:date|on|by)\b""",
        """exp(?:iry)?\.?\s*date\b""",
        """(?:manufactured|marketed|packed|distributed|imported)\s+(?:&\s+\w+\s+)?by\b""",
        """fssai\b""",
        """lic(?:ence|ense)?\.?\s*no\b""",
        """net\s+(?:wt|weight|qty|quantity|content)\b""",
        """mrp\b""",
        """(?:batch|lot)\s*(?:no|number)\b""",
        """customer\s+care\b""",
        """warning\b""",
        """disclaimer\b"""
    ).map { Regex("^$it") }

    /** Statements that end the list only outside brackets ("(contains added flavours)" is fine). */
    private val STATEMENTS = listOf(
        """may\s+contain\b""",
        """contains\s+(?:non|naturally|no|added)\b""",
        """contains\s*[:\-]""",
        """not\s+(?:for|to\s+be)\b""",
        """do\s+not\b""",
        """keep\s+out\s+of\b""",
        """processed\s+(?:on|in)\b""",
        """caution\b"""
    ).map { Regex("^$it") }

    /** Additive classes that name what an ingredient does rather than what it is. */
    private val FUNCTIONAL_CLASSES = setOf(
        "sweetener", "sweeteners", "emulsifier", "emulsifiers", "preservative", "preservatives",
        "stabiliser", "stabilizer", "stabilisers", "stabilizers", "thickener", "thickeners",
        "colour", "color", "colours", "colors", "flavour", "flavor", "flavouring", "flavoring",
        "acidity regulator", "acidity regulators", "antioxidant", "antioxidants",
        "raising agent", "raising agents", "humectant", "anticaking agent", "anti-caking agent",
        "probiotic", "probiotics", "prebiotic", "prebiotics"
    )

    /** Words that are never an ingredient on their own (nutrition-table rows, connectors). */
    private val NOT_INGREDIENTS = Regex(
        """(?i)^(?:and|or|with|contains?|ingredients?|energy|carbohydrates?|total\s+(?:fat|salt|sugars?|carbohydrates?)|added\s+sugars?|sodium|dietary\s+fib(?:re|er)|protein|per\s+serve|nil|na|n/a)$"""
    )

    private val QUANTITY = Regex(
        """(?i)\b\d+(?:[.,]\d+)?\s*(?:%|percent|mg|mcg|µg|g|gm|kg|ml|l|iu|kcal|kj|cfu(?:\s*/\s*g)?|(?:billion|million|crore|lakh)\s*cfu(?:\s*/\s*g)?)(?=\W|$)|\b\d+(?:[.,]\d+)?\s*(?:billion|million)\b|\(\s*\d+(?:[.,]\d+)?\s*%?\s*\)"""
    )

    private val ABBREVIATIONS = setOf("vit", "nat", "no", "approx", "ins", "eg", "etc", "st", "dr")

    /** Ingredient names in label order, de-duplicated, capped at [MAX_INGREDIENTS]. */
    fun parse(ocrText: String): List<String> = parseSection(ocrText).ingredients

    /**
     * Reads only the ingredients section. Without a recognisable heading it returns nothing
     * rather than treating the whole label (nutrition table, warnings…) as ingredients.
     */
    fun parseSection(ocrText: String): ParsedIngredients {
        val text = normalize(ocrText)
        val start = findHeaderEnd(text)
        if (start < 0) return ParsedIngredients(false, "", emptyList())

        val sectionText = balanceBrackets(sliceSection(text.substring(start)).trim())
        val raw = mutableListOf<String>()
        splitTopLevel(sectionText).forEach { expandItem(it, raw) }

        val seen = LinkedHashMap<String, String>()
        raw.forEach { seen.putIfAbsent(it.lowercase(), it) }
        return ParsedIngredients(true, sectionText, seen.values.take(MAX_INGREDIENTS))
    }

    /**
     * True when an ingredient named by the cloud model is backed by the label text: at least
     * half of its words of 4+ letters appear in the text, allowing for OCR misspellings and
     * words OCR ran together. Stops the model inventing ingredients while still letting it
     * repair "SACILUS" to "Bacillus".
     */
    fun isGroundedIn(ingredient: String, ocrText: String): Boolean {
        val lowerLabel = ocrText.lowercase()
        val labelWords = Regex("""\p{L}{3,}""").findAll(lowerLabel).map { it.value }.toList()
        val compactLabel = lowerLabel.replace(Regex("""[^\p{L}]"""), "")
        // Bracketed notes such as "(sweetener)" are the model's own wording, not label text.
        val name = ingredient.lowercase().replace(Regex("""\([^)]*\)"""), " ")
        val words = Regex("""\p{L}{4,}""").findAll(name).map { it.value }.toList()
        if (words.isEmpty()) {
            val short = name.replace(Regex("""[^\p{L}]"""), "")
            return short.isNotEmpty() && compactLabel.contains(short)
        }
        val matched = words.count { w ->
            if (compactLabel.contains(w)) return@count true
            val tolerance = if (w.length >= 8) 2 else 1
            labelWords.any { lw -> kotlin.math.abs(lw.length - w.length) <= tolerance && levenshtein(lw, w) <= tolerance }
        }
        return matched >= (words.size + 1) / 2
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var diag = prev[0]
            prev[0] = i
            for (j in 1..b.length) {
                val temp = prev[j]
                prev[j] = minOf(prev[j] + 1, prev[j - 1] + 1, diag + if (a[i - 1] == b[j - 1]) 0 else 1)
                diag = temp
            }
        }
        return prev[b.length]
    }

    private fun normalize(text: String): String = text
        .replace('‘', '\'').replace('’', '\'')
        .replace(Regex("""[“”^*]"""), " ")
        .replace(Regex("""[–—]"""), "-")
        .replace(Regex("""[{\[]"""), "(")
        .replace(Regex("""[}\]]"""), ")")
        .replace(Regex("""\s*\r?\n\s*"""), " ")
        .replace(Regex("""[ \t]+"""), " ")
        .trim()

    /**
     * Index just after the ingredients heading (tolerates OCR errors like "INGREDIENIS"), or
     * -1. "Composition" and "Contents" also appear in ordinary sentences, so they count only
     * when followed by a colon; an "Ingredients" heading wins.
     */
    private fun findHeaderEnd(text: String): Int {
        var bestEnd = -1
        var bestScore = -1
        for (match in Regex("""[A-Za-z0-9|!]{6,14}""").findAll(text)) {
            val token = match.value.lowercase().replace(Regex("""[0|!1]"""), "i")
            val heading = HEADER_WORDS.firstOrNull { h -> levenshtein(token, h) <= if (h.length >= 10) 2 else 1 }
                ?: continue
            val isIngredientWord = heading.startsWith("ingredient")
            val tokenEnd = match.range.last + 1
            val after = text.substring(tokenEnd, minOf(text.length, tokenEnd + 3))
            val colon = Regex("""^\s*[:;\-.]""").find(after)
            if (colon == null && !isIngredientWord) continue
            if (colon == null && !Regex("""^\s+[A-Za-z]""").containsMatchIn(after)) continue
            val score = (if (colon != null) 2 else 0) + (if (isIngredientWord) 1 else 0)
            if (score > bestScore) {
                bestScore = score
                bestEnd = tokenEnd + (colon?.value?.length ?: 0)
            }
            if (score == 3) break
        }
        return bestEnd
    }

    /** Where the heading before a colon starts, or -1 when it names an additive class. */
    private fun colonHeadingStart(text: String, colon: Int): Int {
        val windowStart = maxOf(0, colon - 40)
        val m = Regex("""([A-Za-z]+(?:\s+[A-Za-z]+){0,2})\s*$""").find(text.substring(windowStart, colon)) ?: return -1
        val phrase = m.groupValues[1].lowercase()
        val lastWord = phrase.split(Regex("""\s+""")).last()
        if (phrase in FUNCTIONAL_CLASSES || lastWord in FUNCTIONAL_CLASSES) return -1
        return windowStart + m.range.first
    }

    /** Cuts the section off where the next part of the label begins. */
    private fun sliceSection(text: String): String {
        val lower = text.lowercase()
        var depth = 0
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '(') depth++ else if (ch == ')') depth = maxOf(0, depth - 1)

            // "Serving Suggestion:", "Category:" … a colon after plain words starts a section.
            if (ch == ':' && i > 3) {
                val start = colonHeadingStart(text, i)
                if (start > 0) return text.substring(0, start)
            }

            val atWordStart = i > 2 && !text[i - 1].isLetterOrDigit()
            if (!atWordStart) continue
            val rest = lower.substring(i, minOf(lower.length, i + 40))
            if (SECTION_HEADINGS.any { it.containsMatchIn(rest) }) return text.substring(0, i)
            if (depth == 0 && STATEMENTS.any { it.containsMatchIn(rest) }) return text.substring(0, i)
        }
        return text
    }

    /** OCR drops or misreads brackets ("(Jowar" → "Wowar"): ignore stray closers, close what is open. */
    private fun balanceBrackets(text: String): String {
        var depth = 0
        val out = StringBuilder()
        for (ch in text) {
            if (ch == '(') depth++
            if (ch == ')') {
                if (depth == 0) continue
                depth--
            }
            out.append(ch)
        }
        return out.toString().trimEnd(' ', ',', ';', '.') + ")".repeat(depth)
    }

    /**
     * Splits on separators outside brackets: commas, semicolons, and full stops that end a
     * word (OCR often reads a comma as a full stop: "Dates Syrup. Peanut Protein").
     */
    private fun splitTopLevel(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '(') depth++
            if (ch == ')') depth = maxOf(0, depth - 1)
            var separator = (ch == ',' || ch == ';') && depth == 0
            if (ch == '.' && depth == 0 && Regex("""^\s+\p{L}""").containsMatchIn(text.substring(i + 1, minOf(text.length, i + 3)))) {
                val prevWord = Regex("""([a-z]+)$""").find(current.toString().lowercase())?.groupValues?.get(1) ?: ""
                separator = prevWord !in ABBREVIATIONS && !current.toString().matches(Regex(""".*\d$"""))
            }
            if (separator) {
                parts += current.toString()
                current.clear()
            } else {
                current.append(ch)
            }
        }
        parts += current.toString()
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Separates "Name (a) (b)" into the name and its bracket groups. */
    private fun splitGroups(item: String): Pair<String, List<String>> {
        val groups = mutableListOf<String>()
        val name = StringBuilder()
        var depth = 0
        val current = StringBuilder()
        for (ch in item) {
            when {
                ch == '(' -> {
                    if (depth == 0) current.clear() else current.append(ch)
                    depth++
                }
                ch == ')' -> {
                    if (depth == 0) continue
                    depth--
                    if (depth == 0) groups += current.toString().trim() else current.append(ch)
                }
                depth > 0 -> current.append(ch)
                else -> name.append(ch)
            }
        }
        if (depth > 0 && current.isNotBlank()) groups += current.toString().trim()
        return name.toString().trim() to groups
    }

    private fun stripQuantities(text: String): String =
        QUANTITY.replace(text, " ").replace(Regex("""\s+"""), " ").trim()

    /** Sentence case for ALL-CAPS labels, keeping codes such as "INS 322" or "SNZ 1969". */
    private fun tidyCase(text: String): String {
        val letters = text.filter { it.isLetter() && it.code < 128 }
        val mostlyUpper = letters.isNotEmpty() && letters.count { it.isUpperCase() }.toDouble() / letters.length > 0.7
        if (!mostlyUpper) return text.replaceFirstChar { it.uppercaseChar() }
        val words = text.lowercase().split(" ")
        val out = words.mapIndexed { i, w ->
            val next = words.getOrElse(i + 1) { "" }
            if (w.any { it.isDigit() } || (w.matches(Regex("[a-z]{1,4}")) && next.firstOrNull()?.isDigit() == true && i > 0)) {
                w.uppercase()
            } else {
                w
            }
        }
        return out.joinToString(" ").replaceFirstChar { it.uppercaseChar() }
    }

    private fun cleanName(raw: String): String = stripQuantities(raw)
        .replace(Regex("""[^\p{L}\p{N}\s&'\-/.()]"""), " ") // OCR junk such as ¥ « | _ %
        .replace(Regex("""^[^\p{L}\p{N}]+|[^\p{L}\p{N})]+$"""), "")
        .replace(Regex("""(?i)^(?:and|&)\s+"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun isIngredient(name: String): Boolean {
        if (name.length < 2 || name.length > 60) return false
        if (name.count { it.isLetter() } < 2) return false
        if (NOT_INGREDIENTS.matches(name)) return false
        return name.any { it.isLetterOrDigit() }
    }

    /** "Soy lecithin & natural flavours" names two ingredients; "Mono & diglycerides" names one. */
    private fun splitPair(name: String): List<String> {
        val m = Regex("""(?i)^(.+?)\s+(?:&|and)\s+(.+)$""").find(name) ?: return listOf(name)
        val (left, right) = m.destructured
        val words = { s: String -> s.trim().split(Regex("""\s+""")).size }
        return if (words(left) >= 2 && words(right) >= 2) listOf(left, right) else listOf(name)
    }

    private fun expandItem(item: String, out: MutableList<String>) {
        val (rawName, groups) = splitGroups(item)
        val name = cleanName(rawName)
        val components = groups
            .map { stripQuantities(it) }
            .flatMap { splitTopLevel(it) }
            .filter { Regex("""\p{L}{2,}""").containsMatchIn(stripQuantities(it)) }

        // "Blend (a, b)" or "Fruits, nuts & seeds (raisins, chia ...)": list each component.
        if (components.size >= 2) {
            components.forEach { expandItem(it, out) }
            return
        }
        if (components.size == 1) {
            val child = cleanName(splitGroups(components[0]).first)
            if (isIngredient(child)) {
                when {
                    name.isEmpty() -> out += tidyCase(child)
                    name.lowercase() in FUNCTIONAL_CLASSES -> out += "${tidyCase(child)} (${name.lowercase()})"
                    isIngredient(name) -> out += "${tidyCase(name)} (${child.lowercase()})"
                    else -> out += tidyCase(child)
                }
                return
            }
        }
        if (isIngredient(name)) splitPair(name).forEach { out += tidyCase(it.trim()) }
    }

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
