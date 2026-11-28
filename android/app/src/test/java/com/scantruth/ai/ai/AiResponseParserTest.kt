package com.scantruth.ai.ai

import com.scantruth.ai.model.AiEngine
import com.scantruth.ai.model.FactorDirection
import com.scantruth.ai.model.MarkerStatus
import com.scantruth.ai.model.SafetyBand
import com.scantruth.ai.model.VerdictLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model is untrusted input. These tests pin down that only coherent, in-range data
 * reaches the UI, and that unusable responses fail instead of being papered over.
 */
class AiResponseParserTest {

    @Suppress("UNCHECKED_CAST")
    private fun <T> ParseResult<T>.successOrNull(): T? =
        (this as? ParseResult.Success<*>)?.value as? T

    private fun <T> ParseResult<T>.requireSuccess(): T =
        successOrNull() ?: throw AssertionError("expected a Success result, got: $this")

    private fun ParseResult<*>.isInvalid(): Boolean = this is ParseResult.Invalid

    private val validMedicalJson = """
        {
          "reportTitle": "Complete Blood Count",
          "reportType": "Lab report",
          "conditions": ["Iron deficiency anaemia"],
          "allergies": [],
          "medications": ["Ferrous sulfate"],
          "dietaryRestrictions": [],
          "healthNotes": ["Follow up in six weeks"],
          "markers": [
            {"name": "Hemoglobin", "value": "10.8", "unit": "g/dL", "status": "low"},
            {"name": "WBC", "value": "6.4", "unit": "10^9/L", "status": "normal"},
            {"name": "", "value": "1", "status": "high"}
          ]
        }
    """.trimIndent()

    @Test
    fun `parses a valid medical extraction and keeps the engine`() {
        val extraction = AiResponseParser
            .parseMedicalExtraction(validMedicalJson, AiEngine.CLOUD)
            .requireSuccess()

        assertEquals("Complete Blood Count", extraction.reportTitle)
        assertEquals("Lab report", extraction.reportType)
        assertEquals(listOf("Iron deficiency anaemia"), extraction.conditions)
        assertEquals(listOf("Ferrous sulfate"), extraction.medications)
        assertEquals(2, extraction.markers.size)
        assertEquals(MarkerStatus.LOW, extraction.markers[0].status)
        assertEquals(MarkerStatus.NORMAL, extraction.markers[1].status)
        assertEquals(AiEngine.CLOUD, extraction.engine)
    }

    @Test
    fun `accepts json wrapped in a markdown fence`() {
        val fenced = "Here is the result you asked for:\n```json\n$validMedicalJson\n```"
        val result = AiResponseParser.parseMedicalExtraction(fenced, AiEngine.CLOUD)
        assertFalse(result.isInvalid())
    }

    @Test
    fun `rejects an empty response`() {
        assertTrue(
            AiResponseParser.parseMedicalExtraction("   ", AiEngine.CLOUD).isInvalid()
        )
    }

    @Test
    fun `rejects an extraction with no usable medical content`() {
        val empty = """{"reportTitle": "Report", "conditions": [], "allergies": []}"""
        assertTrue(AiResponseParser.parseMedicalExtraction(empty, AiEngine.CLOUD).isInvalid())
    }

    @Test
    fun `rejects text that is not json at all`() {
        val result = AiResponseParser.parseMedicalExtraction("I cannot help with that.", AiEngine.CLOUD)
        assertTrue(result.isInvalid())
    }

    private val validIngredientJson = """
        {
          "productName": "Oat Bar",
          "productCategory": "Snack",
          "safetyScore": 74,
          "headline": "Mostly suitable, watch the added sugar.",
          "aiSummary": "Oats are fine; the sugar content is high.",
          "healthFactors": [
            {"label": "Added sugar", "detail": "12 g per bar", "direction": "caution"},
            {"label": "Whole grain", "detail": "Oat base", "direction": "positive"}
          ],
          "ingredientBreakdown": [
            {"name": "Oats", "verdict": "safe", "note": "Whole grain"},
            {"name": "Sugar", "verdict": "limit", "note": "High"}
          ],
          "recommendedIntake": "One bar a day",
          "uncertaintyNotes": ["Sugar content read from the front of pack"],
          "confidence": "high"
        }
    """.trimIndent()

    @Test
    fun `parses a valid ingredient analysis`() {
        val analysis = AiResponseParser
            .parseIngredientAnalysis(validIngredientJson, AiEngine.ON_DEVICE)
            .requireSuccess()

        assertEquals("Oat Bar", analysis.productName)
        assertEquals(74, analysis.safetyScore)
        assertEquals(SafetyBand.GOOD, analysis.band)
        assertEquals(AiEngine.ON_DEVICE, analysis.engine)
        assertEquals(2, analysis.healthFactors.size)
        assertEquals(FactorDirection.CAUTION, analysis.healthFactors[0].direction)
        assertEquals(FactorDirection.POSITIVE, analysis.healthFactors[1].direction)
        assertEquals(VerdictLevel.SAFE, analysis.ingredientBreakdown[0].verdict)
        assertEquals(VerdictLevel.CAUTION, analysis.ingredientBreakdown[1].verdict)
        assertEquals("Good match", analysis.badgeLabel)
    }

    @Test
    fun `parses unknown verdicts and profile conflicts cleanly`() {
        val json = """
            {
              "productName": "Bourbon Biscuits",
              "productCategory": "food",
              "safetyScore": 32,
              "headline": "Contains wheat and dairy.",
              "ingredientBreakdown": [
                {"name": "Milk Solids", "verdict": "avoid", "note": "Contains lactose and dairy", "conflict": "Lactose intolerance"},
                {"name": "Emulsifier 322", "verdict": "unknown", "note": "Source not specified", "conflict": "none"},
                {"name": "Cocoa Solids", "verdict": "safe", "note": "No identified conflict"}
              ]
            }
        """.trimIndent()
        val analysis = AiResponseParser
            .parseIngredientAnalysis(json, AiEngine.CLOUD)
            .requireSuccess()

        assertEquals(VerdictLevel.AVOID, analysis.ingredientBreakdown[0].verdict)
        assertEquals("Lactose intolerance", analysis.ingredientBreakdown[0].conflict)
        assertEquals(VerdictLevel.UNKNOWN, analysis.ingredientBreakdown[1].verdict)
        assertNull(analysis.ingredientBreakdown[1].conflict)
        assertEquals(VerdictLevel.SAFE, analysis.ingredientBreakdown[2].verdict)
    }

    @Test
    fun `rejects a safety score outside the allowed range`() {
        val tooHigh = validIngredientJson.replace("\"safetyScore\": 74", "\"safetyScore\": 150")
        assertTrue(
            AiResponseParser.parseIngredientAnalysis(tooHigh, AiEngine.CLOUD).isInvalid()
        )
    }

    @Test
    fun `rejects an analysis without a safety score`() {
        val missing = validIngredientJson.replace("\"safetyScore\": 74,", "")
        assertTrue(
            AiResponseParser.parseIngredientAnalysis(missing, AiEngine.CLOUD).isInvalid()
        )
    }

    @Test
    fun `rejects an analysis without a headline`() {
        val missing = validIngredientJson.replace(
            "\"headline\": \"Mostly suitable, watch the added sugar.\",",
            ""
        )
        assertTrue(
            AiResponseParser.parseIngredientAnalysis(missing, AiEngine.CLOUD).isInvalid()
        )
    }

    @Test
    fun `low confidence is surfaced as an uncertainty note`() {
        val low = validIngredientJson.replace("\"confidence\": \"high\"", "\"confidence\": \"low\"")
        val analysis = AiResponseParser
            .parseIngredientAnalysis(low, AiEngine.CLOUD)
            .requireSuccess()
        assertTrue(analysis.uncertaintyNotes.any { it.contains("low confidence") })
    }

    @Test
    fun `nano identifier list drops blank values`() {
        val items = AiResponseParser.parseNanoIdentifiers(
            """{"items":[{"value":"Jane Doe","category":"name"},{"value":"","category":"x"}]}"""
        )
        assertEquals(1, items.size)
    }

    @Test
    fun `nano identifier parsing returns nothing for garbage`() {
        assertTrue(AiResponseParser.parseNanoIdentifiers("not json").isEmpty())
    }

    // ------------------------------------------------- marker grounding against text

    @Test
    fun `markers that do not appear in the approved text are dropped`() {
        val raw = """
            {
              "reportTitle": "CBC",
              "markers": [
                {"name": "Hemoglobin", "value": "13.2", "unit": "g/dL", "status": "normal"},
                {"name": "Ferritin", "value": "250", "unit": "ng/mL", "status": "high"}
              ]
            }
        """.trimIndent()

        val extraction = AiResponseParser
            .parseMedicalExtraction(raw, AiEngine.CLOUD, "Hemoglobin 13.2 g/dL\nWBC 6.4")
            .requireSuccess()

        assertEquals(1, extraction.markers.size)
        assertEquals("Hemoglobin", extraction.markers[0].name)
        assertEquals("g/dL", extraction.markers[0].unit)
    }

    @Test
    fun `a unit the report never stated is not shown`() {
        val raw = """
            {"reportTitle": "CBC", "markers": [
              {"name": "Hemoglobin", "value": "13.2", "unit": "mmol/L", "status": "normal"}
            ]}
        """.trimIndent()

        val extraction = AiResponseParser
            .parseMedicalExtraction(raw, AiEngine.CLOUD, "Hemoglobin 13.2 g/dL")
            .requireSuccess()

        assertEquals(1, extraction.markers.size)
        assertNull(extraction.markers[0].unit)
    }

    @Test
    fun `an extraction made only of invented markers is rejected`() {
        val raw = """
            {"reportTitle": "CBC", "markers": [
              {"name": "Ferritin", "value": "250", "unit": "ng/mL", "status": "high"}
            ]}
        """.trimIndent()

        val result = AiResponseParser.parseMedicalExtraction(
            raw = raw,
            engine = AiEngine.CLOUD,
            sourceText = "Hemoglobin 13.2 g/dL"
        )

        assertTrue(result.isInvalid())
    }

    @Test
    fun `grounding compares digits so formatted values still match`() {
        val raw = """
            {"reportTitle": "CBC", "markers": [
              {"name": "Platelets", "value": "1,20,000", "unit": "/uL", "status": "normal"}
            ]}
        """.trimIndent()

        val extraction = AiResponseParser
            .parseMedicalExtraction(raw, AiEngine.CLOUD, "Platelets 120000 /uL")
            .requireSuccess()

        assertEquals(1, extraction.markers.size)
        assertEquals("1,20,000", extraction.markers[0].value)
    }

    // ------------------------------------------------------ fallback metadata survival

    @Test
    fun `ingredient fallback metadata survives parsing`() {
        val withFallback = validIngredientJson.trimEnd().removeSuffix("}") +
            ",\"usedFallback\":true,\"fallbackNotice\":" +
            "\"On-device ingredient analysis was unavailable. Cloud Gemini was used for " +
            "analysis.\"}"

        val analysis = AiResponseParser
            .parseIngredientAnalysis(withFallback, AiEngine.CLOUD)
            .requireSuccess()

        assertTrue(analysis.usedFallback)
        assertEquals(
            "On-device ingredient analysis was unavailable. Cloud Gemini was used for analysis.",
            analysis.fallbackNotice
        )
        assertEquals(AiEngine.CLOUD, analysis.engine)
        assertEquals(74, analysis.safetyScore)
    }

    @Test
    fun `responses without fallback metadata default to no fallback`() {
        val analysis = AiResponseParser
            .parseIngredientAnalysis(validIngredientJson, AiEngine.ON_DEVICE)
            .requireSuccess()
        assertFalse(analysis.usedFallback)
        assertNull(analysis.fallbackNotice)

        val extraction = AiResponseParser
            .parseMedicalExtraction(validMedicalJson, AiEngine.CLOUD)
            .requireSuccess()
        assertFalse(extraction.usedFallback)
        assertNull(extraction.fallbackNotice)
    }

    @Test
    fun `medical fallback metadata survives parsing without changing the schema`() {
        val withFallback = validMedicalJson.trimEnd().removeSuffix("}") +
            ",\"usedFallback\":true,\"fallbackNotice\":\"Local shield used.\"}"

        val extraction = AiResponseParser
            .parseMedicalExtraction(withFallback, AiEngine.CLOUD)
            .requireSuccess()

        assertTrue(extraction.usedFallback)
        assertEquals("Local shield used.", extraction.fallbackNotice)
        assertEquals("Complete Blood Count", extraction.reportTitle)
        assertEquals(2, extraction.markers.size)
    }

    @Test
    fun `a fallback notice without the flag is dropped`() {
        val stray = validIngredientJson.trimEnd().removeSuffix("}") +
            ",\"fallbackNotice\":\"cloud was used\"}"

        val analysis = AiResponseParser
            .parseIngredientAnalysis(stray, AiEngine.CLOUD)
            .requireSuccess()

        assertFalse(analysis.usedFallback)
        assertNull(analysis.fallbackNotice)
    }

    @Test
    fun `extractJsonObject is null without an object`() {
        assertNull(AiResponseParser.extractJsonObject("no braces here"))
        assertNull(AiResponseParser.extractJsonObject("}{"))
    }

    @Test
    fun `extractJsonObject keeps only the object body`() {
        assertEquals(
            """{"a":1}""",
            AiResponseParser.extractJsonObject("sure: {\"a\":1} done")
        )
    }
}
