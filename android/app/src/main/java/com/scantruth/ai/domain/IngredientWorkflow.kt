package com.scantruth.ai.domain

import android.content.Context
import android.net.Uri
import com.scantruth.ai.HybridAIService
import com.scantruth.ai.OcrService
import kotlinx.coroutines.CancellationException

/**
 * Label OCR + ingredient extraction, the same way as the website: the cloud model reads the
 * Ingredients section and repairs OCR errors, and every item it returns must be traceable to
 * the label text. When the model cannot be reached the on-device section parser is used. An
 * empty list means no ingredients section was found.
 */
object IngredientWorkflow {

    suspend fun readLabel(context: Context, uri: Uri, displayName: String?): List<String> {
        val text = OcrService.extract(context, uri, displayName).pages.joinToString("\n") { it.text }
        return extractIngredients(text)
    }

    suspend fun extractIngredients(ocrText: String): List<String> {
        if (ocrText.isBlank()) return emptyList()
        try {
            val (sectionFound, items) = HybridAIService.extractIngredients(ocrText)
            val grounded = items
                .filter { IngredientParser.isGroundedIn(it, ocrText) }
                .distinctBy { it.lowercase() }
            if (sectionFound && grounded.isNotEmpty()) return grounded.take(IngredientParser.MAX_INGREDIENTS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or App Check failure: fall back to the on-device parser below.
        }
        return IngredientParser.parse(ocrText)
    }
}
