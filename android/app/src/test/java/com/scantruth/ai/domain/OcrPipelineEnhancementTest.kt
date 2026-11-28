package com.scantruth.ai.domain

import com.scantruth.ai.ImagePreprocessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPipelineEnhancementTest {

    @Test
    fun `reconstructor separates side-by-side nutrition column from ingredient column`() {
        val lines = listOf(
            // Top banner
            OcrLineBox("Marketed By: BRITANNIA INDUSTRIES LTD.", 180, 40, 820, 80, angle = 7.6f, confidence = 0.92f),
            // Left column: Ingredients
            OcrLineBox("GREDIENTS: REFINED WHEAT FLOUR (MAIDA), SUGAR (32.5%),", 60, 180, 520, 212, angle = 7.5f, confidence = 0.89f),
            OcrLineBox("PALM OIL, COCOA SOLIDS (1.5%), IODISED SALT.", 60, 218, 500, 248, angle = 8.0f, confidence = 0.90f),
            // Right column: side-by-side Nutrition table at same Y coordinates
            OcrLineBox("NUTRITION INFORMATION", 580, 180, 860, 212, angle = 7.8f, confidence = 0.95f),
            OcrLineBox("Energy-492kcal, Sugars-35.4g", 580, 218, 880, 248, angle = 7.9f, confidence = 0.94f)
        )

        val layout = OcrLayoutReconstructor.reconstruct(lines)
        assertTrue(layout.columnCount >= 1)
        assertNotNull(layout.ingredientRegionBox)
        assertTrue(
            "Skew angle from tilted lines should be detected (~7.5-8 deg)",
            layout.estimatedSkewDegrees in 7.0f..8.5f
        )

        val parsed = IngredientParser.parseDetailed(
            ocrText = layout.fullText,
            meanOcrConfidence = layout.meanLineConfidence
        )
        assertEquals(
            listOf(
                "REFINED WHEAT FLOUR (MAIDA)",
                "SUGAR (32.5%)",
                "PALM OIL",
                "COCOA SOLIDS (1.5%)",
                "IODISED SALT"
            ),
            parsed.ingredients
        )
        assertFalse(parsed.ingredients.any { it.contains("Energy", ignoreCase = true) })
        assertFalse(parsed.ingredients.any { it.contains("Britannia", ignoreCase = true) })
    }

    @Test
    fun `reconstructor stitches wrapped lines across split blocks and normalizes element spacing`() {
        val spacedLine = OcrLineBox(
            text = "SUGAR(32.5%),EMULSIFIERS[322(i)&471]",
            left = 50,
            top = 150,
            right = 460,
            bottom = 180,
            elements = listOf(
                OcrElementBox("SUGAR", 50, 150, 120, 180),
                OcrElementBox("(32.5%),", 122, 150, 210, 180),
                OcrElementBox("EMULSIFIERS", 218, 150, 340, 180),
                OcrElementBox("[322", 342, 150, 390, 180),
                OcrElementBox("(i)", 390, 150, 415, 180),
                OcrElementBox("&471]", 418, 150, 460, 180)
            )
        )
        val lines = listOf(
            OcrLineBox("INGREDIENTS: REFINED WHEAT FLOUR (MAIDA),", 50, 100, 460, 130),
            // Vertical gap 20px (< 1.45 * 30px) -> stitched with single '\n', NOT '\n\n'
            spacedLine,
            OcrLineBox("AND IODISED SALT.", 50, 195, 240, 225)
        )

        val layout = OcrLayoutReconstructor.reconstruct(lines)
        assertFalse("Continuous paragraph lines must not insert blank break", layout.fullText.contains("\n\n"))

        val parsed = IngredientParser.parseDetailed(layout.fullText)
        assertEquals(4, parsed.ingredients.size)
        assertTrue(parsed.ingredients.contains("SUGAR (32.5%)"))
        assertTrue(parsed.ingredients.contains("IODISED SALT"))
    }

    @Test
    fun `preprocessor flags dark glare blur and low resolution and expands local contrast`() {
        val w = 24
        val h = 24
        val pixels = IntArray(w * h)

        // Left half dark shadow (luma ~25), right half glossy glare (luma ~248)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val base = if (x < w / 2) 25 else 248
                val ink = if (x in 3..8 && y in 8..14) base + 12 else base
                val v = ink.coerceIn(0, 255)
                pixels[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }

        val lowRes = ImagePreprocessor.evaluatePixels(320, 240, w, h, pixels)
        assertTrue(lowRes.qualityHint!!.contains("Low image resolution"))

        val glareMetrics = ImagePreprocessor.evaluatePixels(1600, 1200, w, h, pixels)
        assertTrue("Glare fraction should exceed threshold", glareMetrics.glareFraction > 0.22)
        assertTrue(glareMetrics.qualityHint!!.contains("glare", ignoreCase = true))
        assertTrue(glareMetrics.needsEnhancement)

        val enhanced = ImagePreprocessor.enhancePixelsForOcr(w, h, pixels)
        assertEquals(pixels.size, enhanced.size)
        // Shadow text ink contrast on the dark left half should expand after tile contrast stretch
        val shadowBg = enhanced[10 * w + 1] and 0xFF
        val shadowText = enhanced[10 * w + 5] and 0xFF
        assertTrue("Local tile contrast stretch must boost dark shadow readability", shadowText - shadowBg > 20)
    }
}
