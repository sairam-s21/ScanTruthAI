package com.scantruth.ai.domain

import com.scantruth.ai.model.IngredientVerdict
import com.scantruth.ai.model.VerdictLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `preserves percentages inside ingredient names while filtering standalone percentages`() {
        val parsed = IngredientParser.parse("Ingredients: Cocoa (10%), Sugar 5%, Salt, 12%")
        assertEquals(listOf("Cocoa (10%)", "Sugar 5%", "Salt"), parsed)
    }

    @Test
    fun `parses bourbon style multi-line label and excludes manufacturer Kolkata and nutrition info`() {
        val raw = """
            Page 1
            NUTRITION INFORMATION
            Per 100g: Energy 485 kcal, Protein 5.4g, Carbohydrate 71.2g
            Ingred1ents: Refined Wheat Flour (Maida), Sugar, Edible
            Vegetable Oil (Palm Oil), Cocoa Solids (4.5%), Invert
            Sugar Syrup, Liquid Glucose, Raising Agents [500(ii),
            503(ii)], Milk Solids, Emulsifiers (INS 322, 471), Edible
            Common Salt, Artificial Flavour-
            ing Substances (Vanilla).
            Allergen Advice: Contains Wheat, Milk and Soy. May contain Sulphites.
            Marketed by: Britannia Industries Ltd., 5/1A Hungerford Street,
            Kolkata - 700017, West Bengal.
            FSSAI Lic. No. 10015043001129.
            Best before 6 months from packaging.
        """.trimIndent()

        val result = IngredientParser.parseDetailed(raw)

        assertTrue(result.hasExplicitHeader)
        assertFalse(result.isIncomplete)
        assertEquals(raw, result.rawOcrText)
        assertTrue(result.ingredients.contains("Refined Wheat Flour (Maida)"))
        assertTrue(result.ingredients.contains("Edible Vegetable Oil (Palm Oil)"))
        assertTrue(result.ingredients.contains("Cocoa Solids (4.5%)"))
        assertTrue(result.ingredients.contains("Raising Agents [500(ii), 503(ii)]"))
        assertTrue(result.ingredients.contains("Emulsifiers (INS 322, 471)"))
        assertTrue(result.ingredients.contains("Artificial Flavouring Substances (Vanilla)"))
        assertFalse(result.ingredients.any { it.contains("Kolkata", ignoreCase = true) })
        assertFalse(result.ingredients.any { it.contains("Britannia", ignoreCase = true) })
        assertFalse(result.ingredients.any { it.contains("485 kcal", ignoreCase = true) })
        assertFalse(result.ingredients.any { it.contains("Page 1", ignoreCase = true) })
        assertTrue(result.allergenDeclarations.any { it.contains("Wheat, Milk and Soy") })
        assertTrue(result.allergenDeclarations.any { it.contains("May contain Sulphites") })
    }

    @Test
    fun `parses cropped Bourbon label with clipped GREDIENTS heading glare typos and side-by-side nutrition`() {
        val raw = """
            CREME BISCUIT WITH CHOCOLATE

            Marketed By: BRITANNIA INDUSTRIES LTD.,

            GREDIENTS: REFINED WHEAT FLOUR (MAIDA), SUGAR (32.5%), REFINED
            PALM & PALMOLEIN) OIL, EDIBLE STARCH, COLOURS [150a, 150c, 150d, 122,
            110, 133 & 102], WHEY POWDER, COCOA SOLIDS (1.5%), NATURE IDENTICAL &
            ARTIFICIAL (VANILLA, MILKY COCOA & CHOCOLATE) FLAVOURING SUBSTANCES,
            RAISING AGENTS [500(ii) & 503(ii)], EMULSIFIERS [322(i) & 471], MILK
            CHOCOLATE (0.1%) [EMULSIFIER [322(i)] AND NATURE IDENTICAL FLAVOURING
            SUBSTANCES] AND IODISED SALT.
            Numbers in brackets as per International Numbering System)
            CONTAINS WHEAT, MILK & SOY.
            STORE IN A COOL, HYGIENIC AND DRY PLACE.

            NUTRITION INFORMATION
            Energy-492kcal/[2], Protein 5.4g, Sugars-35.4g
        """.trimIndent()

        val result = IngredientParser.parseDetailed(raw)

        assertTrue(result.detectedRegionText.contains("REFINED WHEAT FLOUR (MAIDA)"))
        assertTrue(result.ingredients.contains("REFINED WHEAT FLOUR (MAIDA)"))
        assertTrue(result.ingredients.contains("SUGAR (32.5%)"))
        assertTrue(result.ingredients.contains("REFINED PALM & PALMOLEIN OIL"))
        assertTrue(result.ingredients.contains("COLOURS [150a, 150c, 150d, 122, 110, 133 & 102]"))
        assertTrue(result.ingredients.contains("COCOA SOLIDS (1.5%)"))
        assertTrue(
            result.ingredients.contains(
                "NATURE IDENTICAL & ARTIFICIAL (VANILLA, MILKY COCOA & CHOCOLATE) FLAVOURING SUBSTANCES"
            )
        )
        assertTrue(result.ingredients.contains("RAISING AGENTS [500(ii) & 503(ii)]"))
        assertTrue(result.ingredients.contains("EMULSIFIERS [322(i) & 471]"))
        assertTrue(
            result.ingredients.contains(
                "MILK CHOCOLATE (0.1%) [EMULSIFIER [322(i)] AND NATURE IDENTICAL FLAVOURING SUBSTANCES]"
            )
        )
        assertTrue(result.ingredients.contains("IODISED SALT"))
        assertFalse(result.ingredients.any { it.contains("International Numbering System", ignoreCase = true) })
        assertFalse(result.ingredients.any { it.contains("Britannia", ignoreCase = true) })
        assertFalse(result.ingredients.any { it.contains("492kcal", ignoreCase = true) })
        assertTrue(result.allergenDeclarations.any { it.contains("WHEAT, MILK & SOY") })
    }

    @Test
    fun `parses Britannia rusk packet with OCR typos contextually fixed and excludes nutrition table`() {
        val raw = """
            BRITANNIA TOASTEA PREMIUM BAKE RUSK

            NUTRITION FACTS Per 100g: Energy 420 kcal, Carbohydrates 76g
            Ingred1ents: Refined Wheat F1our (Ma1da), 5ugar (19%), Refined Pa1m Oi1,
            Semolina (Suji) (7%), Invert Sugar Syrup, Yeast, Iod1sed Sa1t, Milk So1ids,
            Wheat Gluten, Cardamom, Emu1sifiers [471, 472e], Antioxidant [300].
            Allergen Advice: Contains Wheat and Milk.
        """.trimIndent()

        val result = IngredientParser.parseDetailed(raw)

        assertTrue(result.hasExplicitHeader)
        assertTrue(result.ingredients.contains("Refined Wheat Flour (Maida)"))
        assertTrue(result.ingredients.contains("Sugar (19%)"))
        assertTrue(result.ingredients.contains("Refined Palm Oil"))
        assertTrue(result.ingredients.contains("Semolina (Suji) (7%)"))
        assertTrue(result.ingredients.contains("Iodised Salt"))
        assertTrue(result.ingredients.contains("Milk Solids"))
        assertTrue(result.ingredients.contains("Emulsifiers [471, 472e]"))
        assertTrue(result.ingredients.contains("Antioxidant [300]"))
        assertEquals(12, result.ingredients.size)
        assertEquals(ExtractionConfidence.HIGH, result.confidence)
    }

    @Test
    fun `separates adjacent ingredients when OCR drops a comma after closing bracket`() {
        val raw = "Ingredients: Refined Wheat Flour (Maida) Sugar (32.5%) Cocoa Solids (1.5%), Iodised Salt."
        val result = IngredientParser.parseDetailed(raw)

        assertEquals(
            listOf("Refined Wheat Flour (Maida)", "Sugar (32.5%)", "Cocoa Solids (1.5%)", "Iodised Salt"),
            result.ingredients
        )
    }

    @Test
    fun `flags garbled manufacturer only OCR as LOW confidence rather than a valid ingredient list`() {
        val raw = "CREME BiSCUET WETH CHOCotATE\nMarketepd Pu RRITANNIAINDUSTRIES LTD"
        val result = IngredientParser.parseDetailed(raw)

        assertEquals(ExtractionConfidence.LOW, result.confidence)
        assertTrue(result.isIncomplete)
        assertTrue(result.ingredients.isEmpty())
    }

    @Test
    fun `preserves compound bracketed ingredients and long names without splitting inside brackets`() {
        val raw = """
            Ingredients: Whole Grains 45% (Rolled Oats, Millets 22% (Ragi Flakes (Ragi, Jaggery)),
            Jowar Flakes (Jowar, Jaggery)), Texturized Soya Protein Flakes,
            Dark Chocolate Paste 13% (Sugar, Edible Vegetable Fat, Cocoa Solids, Soy Lecithin & Natural Flavours),
            Probiotics (Bacillus Coagulans SNZ 1969, 15 Billion CFU/g),
            Vitamin B12, E322, Omega-3 Fatty Acids.
        """.trimIndent()

        val result = IngredientParser.parseDetailed(raw)

        assertEquals(7, result.ingredients.size)
        assertEquals(
            "Whole Grains 45% (Rolled Oats, Millets 22% (Ragi Flakes (Ragi, Jaggery)), Jowar Flakes (Jowar, Jaggery))",
            result.ingredients[0]
        )
        assertEquals(
            "Dark Chocolate Paste 13% (Sugar, Edible Vegetable Fat, Cocoa Solids, Soy Lecithin & Natural Flavours)",
            result.ingredients[2]
        )
        assertEquals("Texturized Soya Protein Flakes", result.ingredients[1])
        assertEquals("Probiotics (Bacillus Coagulans SNZ 1969, 15 Billion CFU/g)", result.ingredients[3])
        assertEquals("Vitamin B12", result.ingredients[4])
        assertEquals("E322", result.ingredients[5])
        assertEquals("Omega-3 Fatty Acids", result.ingredients[6])
    }

    @Test
    fun `handles manufacturer and address mixed on the same line as last ingredient`() {
        val raw = "Ingredients: Wheat Flour, Sugar, Palm Oil. Marketed by Sample Foods Pvt. Ltd., Kolkata - 700017."
        val result = IngredientParser.parseDetailed(raw)

        assertEquals(listOf("Wheat Flour", "Sugar", "Palm Oil"), result.ingredients)
        assertFalse(result.ingredients.any { it.contains("Kolkata", ignoreCase = true) })
    }

    @Test
    fun `warns on incomplete ingredient section or unclosed brackets`() {
        val truncated = "Ingredients: Wheat Flour, Sugar, Vegetable Oil (Palm Oil, Cottonseed Oil, and"
        val result = IngredientParser.parseDetailed(truncated)

        assertTrue(result.isIncomplete)
        assertTrue(result.warnings.isNotEmpty())
        assertTrue(result.ingredients.contains("Wheat Flour"))
        assertTrue(result.ingredients.contains("Sugar"))
    }

    @Test
    fun `warns when explicit ingredients heading is missing`() {
        val unheaded = "Sugar, Cocoa Butter, Milk Solids"
        val result = IngredientParser.parseDetailed(unheaded)

        assertFalse(result.hasExplicitHeader)
        assertTrue(result.isIncomplete)
        assertTrue(result.warnings.any { it.contains("heading", ignoreCase = true) })
        assertEquals(listOf("Sugar", "Cocoa Butter", "Milk Solids"), result.ingredients)
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
        assertEquals(VerdictLevel.UNKNOWN, IngredientParser.normaliseVerdict("unknown"))
        assertEquals(VerdictLevel.UNKNOWN, IngredientParser.normaliseVerdict("maybe?"))
        assertEquals(VerdictLevel.UNKNOWN, IngredientParser.normaliseVerdict(null))
        assertEquals(VerdictLevel.UNKNOWN, IngredientParser.normaliseVerdict("  "))
    }

    @Test
    fun `breakdown is filtered and de-duplicated`() {
        val sanitised = IngredientParser.sanitiseBreakdown(
            listOf(
                IngredientVerdict("Sugar", VerdictLevel.CAUTION, "high", "Diabetes"),
                IngredientVerdict("sugar", VerdictLevel.AVOID, "duplicate"),
                IngredientVerdict("  ", VerdictLevel.SAFE, null),
                IngredientVerdict("Water", VerdictLevel.SAFE, null)
            )
        )
        assertEquals(listOf("Sugar", "Water"), sanitised.map { it.name })
        assertEquals("Diabetes", sanitised[0].conflict)
    }

    @Test
    fun `cleans live rusk ocr edge cases without corrupting valid brackets`() {
        val raw = """
            INGREDIENTS: REFINED WHEAT FLOUR AA1DA) (71%), SUGAR, REFINED PALM AND PALMOLEIN OIL,
            SEMOLINA (SUJ) (1.8%), INVERT SUGAR SYRUP IODISED SALT,
            READ IMPROVER {STARCH, SOYA FLOUR, IMPROVERS [11000, 1104, 1102]}
        """.trimIndent()

        val result = IngredientParser.parseDetailed(raw)
        assertTrue(result.ingredients.contains("REFINED WHEAT FLOUR MAIDA (71%)"))
        assertTrue(result.ingredients.contains("SEMOLINA (SUJI) (1.8%)"))
        assertTrue(result.ingredients.contains("INVERT SUGAR SYRUP"))
        assertTrue(result.ingredients.contains("IODISED SALT"))
        assertTrue(result.ingredients.any { it.startsWith("BREAD IMPROVER") && it.contains("[11000, 1104, 1102]") })
        assertTrue(result.ingredients.none { it.contains("AA1DA") || it.endsWith(") (71%)") })
    }
}
