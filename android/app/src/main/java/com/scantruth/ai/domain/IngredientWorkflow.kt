package com.scantruth.ai.domain

import android.content.Context
import android.net.Uri
import com.scantruth.ai.OcrService

/** Result of reading a label: ingredient names plus how many pages were read. */
data class LabelScan(
    val ingredients: List<String>,
    val pageCount: Int
)

/**
 * Label OCR + ingredient parsing. The raw OCR text is used for parsing and then
 * discarded; only ingredient names go on to the AI step and Firestore.
 */
object IngredientWorkflow {

    suspend fun readLabel(
        context: Context,
        uri: Uri,
        displayName: String?
    ): LabelScan {
        val document = OcrService.extract(context, uri, displayName)
        val ingredients = IngredientParser.parse(document.plainText)
        return LabelScan(ingredients = ingredients, pageCount = document.pageCount)
    }
}
