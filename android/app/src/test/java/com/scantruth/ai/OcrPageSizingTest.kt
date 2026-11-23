package com.scantruth.ai

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF pages are rendered at 72 dpi, so an A4 page arrives at 595x842 px and body text is
 * only ~11 px tall — ML Kit then returned a single line for a 13-line report. Sizing must
 * now upscale small pages, keep one aspect ratio, and still bound the bitmap.
 */
class OcrPageSizingTest {

    private fun assertAspectPreserved(pageWidth: Int, pageHeight: Int, size: OcrPageSizing.Size) {
        val expected = pageWidth.toDouble() / pageHeight.toDouble()
        val actual = size.width.toDouble() / size.height.toDouble()
        assertTrue(
            "aspect ratio drifted: expected $expected, got $actual (${size.width}x${size.height})",
            abs(expected - actual) < 0.01
        )
    }

    @Test
    fun `a4 page is upscaled to the target edge`() {
        val size = OcrPageSizing.compute(595, 842)

        assertEquals(OcrPageSizing.MAX_EDGE_PX, size.height)
        assertTrue("width should track the aspect ratio, got ${size.width}", size.width in 1830..1846)
        assertAspectPreserved(595, 842, size)
        assertTrue(size.pixelCount <= OcrPageSizing.MAX_PIXELS)
    }

    @Test
    fun `landscape page is upscaled on its long edge`() {
        val size = OcrPageSizing.compute(842, 595)

        assertEquals(OcrPageSizing.MAX_EDGE_PX, size.width)
        assertAspectPreserved(842, 595, size)
    }

    @Test
    fun `small page is upscaled instead of left unreadable`() {
        val size = OcrPageSizing.compute(300, 200)

        assertEquals(OcrPageSizing.MAX_EDGE_PX, size.width)
        assertAspectPreserved(300, 200, size)
    }

    @Test
    fun `oversized page is bounded by the pixel budget`() {
        val size = OcrPageSizing.compute(10_000, 7_000)

        assertTrue("bitmap too large: ${size.pixelCount}", size.pixelCount <= OcrPageSizing.MAX_PIXELS)
        assertTrue(size.width <= OcrPageSizing.MAX_EDGE_PX)
        assertTrue(size.height <= OcrPageSizing.MAX_EDGE_PX)
        assertAspectPreserved(10_000, 7_000, size)
    }

    @Test
    fun `pixel budget wins when the target edge alone would be too large`() {
        val size = OcrPageSizing.compute(
            pageWidth = 5_000,
            pageHeight = 5_000,
            maxEdge = 4_000,
            maxPixels = 1_000_000
        )

        assertTrue(size.pixelCount <= 1_000_000)
        assertAspectPreserved(5_000, 5_000, size)
    }

    @Test
    fun `degenerate page dimensions cannot produce a zero bitmap`() {
        val size = OcrPageSizing.compute(0, 0)
        assertTrue(size.width >= 1)
        assertTrue(size.height >= 1)
    }
}
