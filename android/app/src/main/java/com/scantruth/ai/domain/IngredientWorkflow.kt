package com.scantruth.ai.domain

import android.content.Context
import android.net.Uri
import com.scantruth.ai.OcrService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Result of reading a label: cleaned ingredient names, allergen declarations,
 * incomplete/quality warnings, raw OCR text for verification, and timing telemetry.
 */
data class LabelScan(
    val ingredients: List<String>,
    val pageCount: Int,
    val allergenDeclarations: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val rawOcrText: String = "",
    val detectedRegionText: String = "",
    val confidence: ExtractionConfidence = ExtractionConfidence.HIGH,
    val confidenceReason: String = "",
    val qualityHint: String? = null,
    val ocrDurationMs: Long = 0L,
    val parseDurationMs: Long = 0L,
    val filteredNonIngredients: List<String> = emptyList()
)

/**
 * Label OCR + ingredient parsing. The raw OCR text stays on this device (for optional
 * user verification on the review screen); only confirmed ingredient names go on to the
 * AI step and Firestore.
 */
object IngredientWorkflow {

    suspend fun readLabel(
        context: Context,
        uri: Uri,
        displayName: String?,
        onStage: ((String) -> Unit)? = null
    ): LabelScan {
        if (OcrService.isPdf(context, uri, displayName)) {
            val startNs = System.nanoTime()
            onStage?.invoke("Reading ingredients…")
            val document = OcrService.extract(context, uri, displayName)
            val ocrMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            onStage?.invoke("Extracting ingredient list…")
            val parsed = withContext(Dispatchers.Default) {
                IngredientParser.parseDetailed(document.plainText)
            }
            return LabelScan(
                ingredients = parsed.ingredients,
                pageCount = document.pageCount,
                allergenDeclarations = parsed.allergenDeclarations,
                warnings = parsed.warnings,
                rawOcrText = parsed.rawOcrText,
                detectedRegionText = parsed.detectedRegionText,
                confidence = parsed.confidence,
                confidenceReason = parsed.confidenceReason,
                ocrDurationMs = ocrMs,
                parseDurationMs = parsed.durationMs,
                filteredNonIngredients = parsed.filteredNonIngredients
            )
        }

        onStage?.invoke("Reading ingredients…")
        val ocr = OcrService.extractLabel(context, uri)
        onStage?.invoke("Extracting ingredient list…")
        val parsed = withContext(Dispatchers.Default) {
            IngredientParser.parseDetailed(
                ocrText = ocr.primaryTextForParsing,
                qualityHint = ocr.qualityHint,
                meanOcrConfidence = ocr.meanConfidence
            )
        }
        val combinedWarnings = buildList {
            ocr.qualityHint?.let { add(it) }
            addAll(parsed.warnings)
        }.distinct()

        return LabelScan(
            ingredients = parsed.ingredients,
            pageCount = 1,
            allergenDeclarations = parsed.allergenDeclarations,
            warnings = combinedWarnings,
            rawOcrText = ocr.text.ifBlank { parsed.rawOcrText },
            detectedRegionText = parsed.detectedRegionText,
            confidence = parsed.confidence,
            confidenceReason = parsed.confidenceReason,
            qualityHint = ocr.qualityHint,
            ocrDurationMs = ocr.durationMs,
            parseDurationMs = parsed.durationMs,
            filteredNonIngredients = parsed.filteredNonIngredients
        )
    }
}
