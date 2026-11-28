package com.scantruth.ai.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Geometry-aware representation of one OCR word/element within a line. */
data class OcrElementBox(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/** Geometry-aware representation of one recognized OCR line. */
data class OcrLineBox(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val angle: Float = 0f,
    val confidence: Float? = null,
    val elements: List<OcrElementBox> = emptyList()
) {
    val width: Int get() = max(0, right - left)
    val height: Int get() = max(1, bottom - top)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/** Bounding box (in image pixel coordinates) of a candidate ingredient paragraph/column. */
data class PixelRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/** Structured output of layout reconstruction from OCR lines. */
data class ReconstructedOcrLayout(
    val fullText: String,
    val columnCount: Int,
    val estimatedSkewDegrees: Float,
    val meanLineConfidence: Float?,
    val ingredientRegionBox: PixelRegion?
)

/**
 * Reconstructs reading order from ML Kit block/line/element geometry.
 *
 * Pure Kotlin (no Android framework dependencies) so multi-column separation,
 * paragraph stitching, element spacing, skew estimation, and ingredient-region
 * bounding boxes can be thoroughly unit tested on the JVM.
 */
object OcrLayoutReconstructor {

    private val HEADER_HINT = Regex(
        """(?i)\b(?:ingredients?|ingrediants?|ingred[1il]ents?|[l1]ngredients?|inqredients?|ingredienfs?|[a-z]{0,4}redients?)\b\s*[:\-–]?"""
    )

    private val NUTRITION_OR_STOP_HINT = Regex(
        """(?i)^(?:nutrition(?:al)?(?:\s+information|\s+facts)?|typical\s+values|serving\s+size|""" +
            """marketed\s*by|manufactured\s*by|mfd\.?\s*by|mfg\.?\s*by|fssai|best\s+before|store\s+in)\b"""
    )

    private val INGREDIENT_CONTENT_HINT = Regex(
        """(?i)\b(?:wheat|flour|maida|sugar|oil|palm|palmolein|cocoa|milk|whey|salt|water|oats|""" +
            """starch|soy|soya|emulsifier|raising|flavour|flavor|ins\s*\d{3}|e\d{3}|glucose|syrup|solid|solids)\b"""
    )

    fun reconstruct(lines: List<OcrLineBox>): ReconstructedOcrLayout {
        val valid = lines
            .mapNotNull { normalizeLineSpacing(it) }
            .filter { it.text.isNotBlank() }
        if (valid.isEmpty()) {
            return ReconstructedOcrLayout(
                fullText = "",
                columnCount = 0,
                estimatedSkewDegrees = 0f,
                meanLineConfidence = null,
                ingredientRegionBox = null
            )
        }

        val skew = estimateMedianSkew(valid)
        val confidences = valid.mapNotNull { it.confidence }
        val meanConf = if (confidences.isNotEmpty()) confidences.average().toFloat() else null

        val columns = splitIntoColumns(valid)
        val columnTexts = columns.map { col -> stitchColumnParagraphs(col) }
            .filter { it.isNotBlank() }

        val fullText = columnTexts.joinToString("\n\n").trim()
        val regionBox = locateIngredientRegionBox(columns)

        return ReconstructedOcrLayout(
            fullText = fullText,
            columnCount = columns.size,
            estimatedSkewDegrees = skew,
            meanLineConfidence = meanConf,
            ingredientRegionBox = regionBox
        )
    }

    /**
     * Inserts spaces when adjacent elements inside one OCR line have a clear visual gap
     * that ML Kit merged (for example `SUGAR(32.5%)` or `WHEAT FLOUR(MAIDA)`), without
     * breaking tight bracketed codes like `500(ii)` or `322(i)`.
     */
    internal fun normalizeLineSpacing(line: OcrLineBox): OcrLineBox {
        if (line.elements.size < 2) {
            return line.copy(text = line.text.trim())
        }
        val sortedElements = line.elements.sortedBy { it.left }
        val avgCharWidth = sortedElements
            .filter { it.text.isNotEmpty() }
            .map { max(1f, (it.right - it.left).toFloat() / it.text.length) }
            .average()
            .toFloat()
            .coerceAtLeast(4f)

        val rebuilt = StringBuilder()
        var prev: OcrElementBox? = null
        for (el in sortedElements) {
            val token = el.text.trim()
            if (token.isEmpty()) continue
            if (prev != null) {
                val gap = el.left - prev.right
                val prevLast = prev.text.trim().lastOrNull()
                val nextFirst = token.firstOrNull()
                val isTightCodeBracket = (prevLast?.isDigit() == true && nextFirst == '(') ||
                    prevLast == '(' || prevLast == '[' || nextFirst == ')' || nextFirst == ']' ||
                    nextFirst == ',' || nextFirst == '.' || nextFirst == ';'
                if (!isTightCodeBracket && gap > avgCharWidth * 0.25f) {
                    rebuilt.append(' ')
                } else if (prevLast?.isLetterOrDigit() == true && nextFirst == '(' && !isTightCodeBracket) {
                    rebuilt.append(' ')
                } else if (gap >= 0 && prevLast?.isLetter() == true && nextFirst?.isLetter() == true && gap > 1) {
                    rebuilt.append(' ')
                }
            }
            rebuilt.append(token)
            prev = el
        }
        val joined = rebuilt.toString().trim()
        // Only adopt element-rebuilt text if it didn't drop tokens compared to line.text
        val finalText = if (joined.length >= (line.text.trim().length * 0.85f).toInt()) {
            joined
        } else {
            line.text.trim()
        }
        return line.copy(text = finalText)
    }

    /**
     * Separates side-by-side columns (e.g., left Ingredients paragraph vs right Nutrition
     * Facts table) even when a wide header/footer banner (`Marketed By...`) spans above or
     * below both columns.
     */
    internal fun splitIntoColumns(lines: List<OcrLineBox>): List<List<OcrLineBox>> {
        if (lines.size <= 1) return listOf(lines)

        val medianHeight = median(lines.map { it.height.toFloat() }).coerceAtLeast(12f)
        val gutterX = findPrimaryColumnGutter(lines, medianHeight)
            ?: return listOf(lines.sortedWith(compareBy<OcrLineBox> { it.top }.thenBy { it.left }))

        val spanningBanners = mutableListOf<OcrLineBox>()
        val leftCol = mutableListOf<OcrLineBox>()
        val rightCol = mutableListOf<OcrLineBox>()

        val margin = max(12f, medianHeight * 0.4f)
        for (line in lines) {
            when {
                line.left < gutterX - margin && line.right > gutterX + margin -> spanningBanners += line
                line.centerX < gutterX -> leftCol += line
                else -> rightCol += line
            }
        }

        return buildList {
            if (spanningBanners.isNotEmpty()) {
                val topThreshold = (leftCol + rightCol).minOfOrNull { it.top } ?: Int.MAX_VALUE
                val topBanners = spanningBanners.filter { it.bottom <= topThreshold }
                if (topBanners.isNotEmpty()) {
                    add(topBanners.sortedWith(compareBy<OcrLineBox> { it.top }.thenBy { it.left }))
                }
            }
            if (leftCol.isNotEmpty()) {
                add(leftCol.sortedWith(compareBy<OcrLineBox> { it.top }.thenBy { it.left }))
            }
            if (rightCol.isNotEmpty()) {
                add(rightCol.sortedWith(compareBy<OcrLineBox> { it.top }.thenBy { it.left }))
            }
            if (spanningBanners.isNotEmpty()) {
                val topThreshold = (leftCol + rightCol).minOfOrNull { it.top } ?: Int.MAX_VALUE
                val bottomBanners = spanningBanners.filter { it.bottom > topThreshold }
                if (bottomBanners.isNotEmpty()) {
                    add(bottomBanners.sortedWith(compareBy<OcrLineBox> { it.top }.thenBy { it.left }))
                }
            }
        }
    }

    /**
     * Finds the X-coordinate of a genuine vertical gutter separating side-by-side lines
     * that share the same vertical band (e.g. Ingredients on left, Nutrition on right).
     */
    private fun findPrimaryColumnGutter(lines: List<OcrLineBox>, medianHeight: Float): Float? {
        val gutters = mutableListOf<Float>()
        for (i in lines.indices) {
            val a = lines[i]
            for (j in (i + 1) until lines.size) {
                val b = lines[j]
                val verticalOverlap = min(a.bottom, b.bottom) - max(a.top, b.top)
                if (verticalOverlap <= medianHeight * 0.2f) continue

                val leftBox = if (a.left <= b.left) a else b
                val rightBox = if (leftBox === a) b else a
                val horizontalGap = rightBox.left - leftBox.right
                if (horizontalGap > medianHeight * 0.5f) {
                    gutters += (leftBox.right + rightBox.left) / 2f
                }
            }
        }
        return if (gutters.isEmpty()) null else median(gutters)
    }

    /**
     * Stitches lines in a single column so continuous multi-line ingredient paragraphs
     * never get split by false blank lines (`\n\n`), while preserving section breaks when
     * vertical spacing jumps or an explicit new section starts.
     */
    internal fun stitchColumnParagraphs(columnLines: List<OcrLineBox>): String {
        if (columnLines.isEmpty()) return ""
        val heights = columnLines.map { it.height.toFloat() }
        val medianH = median(heights).coerceAtLeast(10f)

        val out = StringBuilder()
        var prev: OcrLineBox? = null
        var inIngredientRun = false

        for (line in columnLines) {
            val text = line.text.trim()
            if (text.isEmpty()) continue

            val startsHeader = HEADER_HINT.containsMatchIn(text) && !NUTRITION_OR_STOP_HINT.containsMatchIn(text)
            val startsStopSection = NUTRITION_OR_STOP_HINT.containsMatchIn(text)

            if (startsHeader) inIngredientRun = true
            if (startsStopSection) inIngredientRun = false

            if (prev != null) {
                val verticalGap = line.top - prev.bottom
                val lineStep = max(prev.height, line.height).toFloat().coerceAtLeast(medianH)
                val sameParagraphThreshold = if (inIngredientRun) lineStep * 1.45f else lineStep * 0.95f
                val horizontalJump = abs(line.left - prev.left) > lineStep * 8f && verticalGap < lineStep * 0.3f

                if (startsStopSection || startsHeader || horizontalJump || verticalGap > sameParagraphThreshold) {
                    out.append("\n\n")
                } else {
                    out.append('\n')
                }
            }
            out.append(text)
            prev = line
        }
        return out.toString().trim()
    }

    /**
     * Locates the bounding box of the most likely ingredient paragraph (for Pass 2 high-res
     * cropping) by scoring paragraphs inside each column.
     */
    internal fun locateIngredientRegionBox(columns: List<List<OcrLineBox>>): PixelRegion? {
        var bestScore = Int.MIN_VALUE
        var bestLines: List<OcrLineBox>? = null

        for (column in columns) {
            // Group into rough paragraphs by vertical gap
            val paragraphs = mutableListOf<MutableList<OcrLineBox>>()
            val medianH = median(column.map { it.height.toFloat() }).coerceAtLeast(10f)
            var current = mutableListOf<OcrLineBox>()

            for (line in column) {
                val prev = current.lastOrNull()
                if (prev != null && (line.top - prev.bottom) > medianH * 1.45f) {
                    paragraphs += current
                    current = mutableListOf()
                }
                current += line
            }
            if (current.isNotEmpty()) paragraphs += current

            for (para in paragraphs) {
                val joined = para.joinToString(" ") { it.text }
                var score = 0
                if (HEADER_HINT.containsMatchIn(joined)) score += 60
                score += joined.count { it == ',' } * 5
                score += INGREDIENT_CONTENT_HINT.findAll(joined).count() * 8
                if (NUTRITION_OR_STOP_HINT.containsMatchIn(joined) && !HEADER_HINT.containsMatchIn(joined)) {
                    score -= 40
                }
                if (para.size >= 2) score += para.size * 3

                if (score > bestScore && score >= 15) {
                    bestScore = score
                    bestLines = para
                }
            }
        }

        val chosen = bestLines ?: return null
        return PixelRegion(
            left = chosen.minOf { it.left },
            top = chosen.minOf { it.top },
            right = chosen.maxOf { it.right },
            bottom = chosen.maxOf { it.bottom }
        )
    }

    internal fun estimateMedianSkew(lines: List<OcrLineBox>): Float {
        val candidateAngles = lines
            .filter { it.text.length >= 6 }
            .map { wrapSkew(it.angle) }
            .filter { it in -45f..45f }
        if (candidateAngles.isEmpty()) return 0f
        return median(candidateAngles)
    }

    private fun wrapSkew(angle: Float): Float {
        var a = angle % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        return a
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2f
        } else {
            sorted[mid]
        }
    }
}
