package com.scantruth.ai.ai

import com.scantruth.ai.model.HealthItemValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthItemValidationTest {

    @Test
    fun parsesFencedValidAnswer() {
        val raw = "```json\n{\"isValid\": true, \"normalizedName\": \" Peanuts \", \"reason\": \"\"}\n```"
        val result = AiResponseParser.parseHealthItemValidation(raw)!!
        assertTrue(result.isValid)
        assertEquals("Peanuts", result.normalizedName)
        assertNull(result.reason)
    }

    @Test
    fun parsesRejection() {
        val result = AiResponseParser.parseHealthItemValidation(
            "{\"isValid\": false, \"reason\": \"A banana is a food, not a medication.\"}"
        )!!
        assertFalse(result.isValid)
        assertEquals("A banana is a food, not a medication.", result.reason)
    }

    @Test
    fun answerWithoutVerdictIsUnusable() {
        assertNull(AiResponseParser.parseHealthItemValidation("{\"normalizedName\": \"Peanuts\"}"))
        assertNull(AiResponseParser.parseHealthItemValidation("I think so"))
    }

    @Test
    fun basicCheckMatchesIosAndWebRule() {
        assertTrue(HealthItemValidation.passesBasicCheck("Metformin 500 mg"))
        assertTrue(HealthItemValidation.passesBasicCheck("Vitamin B12 (1,000 mcg)"))
        assertFalse(HealthItemValidation.passesBasicCheck("a"))
        assertFalse(HealthItemValidation.passesBasicCheck("12"))
        assertFalse(HealthItemValidation.passesBasicCheck("x".repeat(61)))
        assertFalse(HealthItemValidation.passesBasicCheck("peanuts; DROP"))
        assertFalse(HealthItemValidation.passesBasicCheck("<script>"))
    }
}
