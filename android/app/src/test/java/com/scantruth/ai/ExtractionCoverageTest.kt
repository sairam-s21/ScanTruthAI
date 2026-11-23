package com.scantruth.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A page that renders but yields no text used to be dropped silently, so a two-page
 * report with one blank page looked complete ("1 page read"). The extraction now records
 * unreadable pages and the review screen must surface them.
 */
class ExtractionCoverageTest {

    private fun page(number: Int, text: String = "Hemoglobin 13.2 g/dL") = PageText(number, text)

    @Test
    fun `a fully read document is not partial`() {
        val extracted = ExtractedText(pages = listOf(page(1), page(2)), truncated = false)

        assertFalse(extracted.isPartial)
        assertEquals(0, extracted.unreadablePageCount)
        assertNull(extracted.coverageNote())
        assertEquals(2, extracted.pageCount)
    }

    @Test
    fun `blank pages are reported with their numbers`() {
        val extracted = ExtractedText(
            pages = listOf(page(1), page(3)),
            truncated = false,
            skippedPageNumbers = listOf(2, 4)
        )

        assertTrue(extracted.isPartial)
        assertEquals(2, extracted.unreadablePageCount)
        assertEquals("2 pages could not be read (page 2, 4) and are not part of this review.", extracted.coverageNote())
        assertEquals("only readable pages count as read", 2, extracted.pageCount)
    }

    @Test
    fun `a single unreadable page reads naturally`() {
        val extracted = ExtractedText(
            pages = listOf(page(1)),
            truncated = false,
            skippedPageNumbers = listOf(2)
        )

        assertEquals("1 page could not be read (page 2) and are not part of this review.", extracted.coverageNote())
    }

    @Test
    fun `many unreadable pages omit a long page list`() {
        val extracted = ExtractedText(
            pages = listOf(page(1)),
            truncated = false,
            skippedPageNumbers = listOf(2, 3, 4, 5)
        )

        val note = extracted.coverageNote().orEmpty()
        assertTrue(note.contains("4 pages could not be read"))
        assertFalse("page list should be omitted for many pages", note.contains("(page"))
    }

    @Test
    fun `partial extraction keeps page markers so line references stay valid`() {
        val extracted = ExtractedText(
            pages = listOf(page(1), page(3)),
            truncated = true,
            skippedPageNumbers = listOf(2)
        )

        assertTrue(extracted.plainText.contains("Page 1"))
        assertTrue(extracted.plainText.contains("Page 3"))
        assertFalse(extracted.plainText.contains("Page 2"))
        assertTrue(extracted.truncated)
    }

    @Test
    fun `a document whose pages were all unreadable has no text`() {
        val extracted = ExtractedText(
            pages = emptyList(),
            truncated = false,
            skippedPageNumbers = listOf(1, 2)
        )

        assertTrue(extracted.isPartial)
        assertEquals(0, extracted.pageCount)
        assertEquals(0, extracted.characterCount)
        assertTrue(extracted.plainText.isEmpty())
    }
}
