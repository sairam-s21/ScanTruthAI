package com.scantruth.ai.domain

import com.scantruth.ai.model.IngredientVerdict
import com.scantruth.ai.model.VerdictLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic label parsing: no I/O, no model, so every case here is reproducible. */
class IngredientParserTest {

    @Test
    fun `parses a plain comma separated list after the label`() {
        val parsed = IngredientParser.parse(
            "Ingredients: Water, Sugar, Citric Acid, Sodium Benzoate."
        )
        assertEquals(
            listOf("Water", "Sugar", "Citric Acid", "Sodium Benzoate"),
            parsed
        )
    }

    @Test
    fun `stops at the nutrition panel`() {
        val parsed = IngredientParser.parse(
            """
            Ingredients: Oats, Honey
            Nutrition: Energy 400 kcal
            Sugar 12 g
            """.trimIndent()
        )
        assertEquals(listOf("Oats", "Honey"), parsed)
    }

    @Test
    fun `de-duplicates case insensitively`() {
        val parsed = IngredientParser.parse("Ingredients: Sugar, sugar, SUGAR, Salt")
        assertEquals(listOf("Sugar", "Salt"), parsed)
    }

    @Test
    fun `strips percentages and parenthetical notes`() {
        val parsed = IngredientParser.parse("Ingredients: Cocoa (10%), Sugar 5%, Salt")
        assertEquals(listOf("Cocoa", "Sugar", "Salt"), parsed)
    }

    @Test
    fun `blank input yields no ingredients`() {
        assertTrue(IngredientParser.parse("").isEmpty())
        assertTrue(IngredientParser.parse("   ").isEmpty())
    }

    @Test
    fun `list is capped`() {
        val many = (1..70).joinToString(", ") { "Ingredient $it" }
        val parsed = IngredientParser.parse("Ingredients: $many")
        assertEquals(IngredientParser.MAX_INGREDIENTS, parsed.size)
    }

    @Test
    fun `verdict words map to stable levels and unknown wording is not guessed`() {
        assertEquals(VerdictLevel.AVOID, IngredientParser.normaliseVerdict("avoid"))
        assertEquals(VerdictLevel.AVOID, IngredientParser.normaliseVerdict("Unsafe for you"))
        assertEquals(VerdictLevel.CAUTION, IngredientParser.normaliseVerdict("use with caution"))
        assertEquals(VerdictLevel.SAFE, IngredientParser.normaliseVerdict("Safe"))
        assertEquals(VerdictLevel.UNKNOWN, IngredientParser.normaliseVerdict("maybe?"))
        assertEquals(VerdictLevel.UNKNOWN, IngredientParser.normaliseVerdict(null))
        assertEquals(VerdictLevel.UNKNOWN, IngredientParser.normaliseVerdict("  "))
    }

    @Test
    fun `breakdown is filtered and de-duplicated`() {
        val sanitised = IngredientParser.sanitiseBreakdown(
            listOf(
                IngredientVerdict("Sugar", VerdictLevel.CAUTION, "high"),
                IngredientVerdict("sugar", VerdictLevel.AVOID, "duplicate"),
                IngredientVerdict("  ", VerdictLevel.SAFE, null),
                IngredientVerdict("Water", VerdictLevel.SAFE, null)
            )
        )
        assertEquals(listOf("Sugar", "Water"), sanitised.map { it.name })
    }
}
