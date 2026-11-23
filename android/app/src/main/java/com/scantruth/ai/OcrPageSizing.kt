package com.scantruth.ai

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Bounded raster size for a PDF page.
 *
 * PdfRenderer renders at 72 dpi, so an A4 page is only ~595x842 px and 11pt body text
 * arrives at OCR as ~11 px tall — too small to recognise reliably, which previously left
 * a 13-line report reading as a single line. Pages are now upscaled to fill [maxEdge]
 * while one shared scale keeps the aspect ratio, and the total pixel count is capped so a
 * page can never allocate an unbounded bitmap.
 */
internal object OcrPageSizing {

    /** Longest raster edge. 2600 px gives A4 body text roughly 40 px height. */
    const val MAX_EDGE_PX = 2600

    /** About 32 MB at ARGB_8888, the most one page may allocate. */
    const val MAX_PIXELS = 8_000_000

    data class Size(val width: Int, val height: Int) {
        val pixelCount: Long get() = width.toLong() * height.toLong()
    }

    fun compute(
        pageWidth: Int,
        pageHeight: Int,
        maxEdge: Int = MAX_EDGE_PX,
        maxPixels: Int = MAX_PIXELS
    ): Size {
        val width = max(pageWidth, 1)
        val height = max(pageHeight, 1)
        val longest = max(width, height)

        // One scale for both axes preserves the aspect ratio.
        var scale = maxEdge.toFloat() / longest.toFloat()
        val projected = width.toDouble() * height.toDouble() * scale.toDouble() * scale.toDouble()
        if (projected > maxPixels) {
            scale = sqrt(maxPixels.toDouble() / (width.toDouble() * height.toDouble())).toFloat()
        }

        return Size(
            width = max(1, (width * scale).roundToInt()),
            height = max(1, (height * scale).roundToInt())
        )
    }
}
