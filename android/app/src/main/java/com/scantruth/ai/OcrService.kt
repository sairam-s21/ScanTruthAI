package com.scantruth.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.webkit.MimeTypeMap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scantruth.ai.domain.ExtractionConfidence
import com.scantruth.ai.domain.IngredientParser
import com.scantruth.ai.domain.OcrElementBox
import com.scantruth.ai.domain.OcrLayoutReconstructor
import com.scantruth.ai.domain.OcrLineBox
import com.scantruth.ai.domain.ReconstructedOcrLayout
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** One recognised page. Page numbers are 1-based and preserved into the AI prompt. */
data class PageText(val pageNumber: Int, val text: String)

/**
 * OCR result for a whole document.
 *
 * [skippedPageNumbers] records pages that opened but produced no text, plus pages that
 * could not be opened or rendered at all. They are reported to the user instead of being
 * silently dropped, so a partial extraction is never presented as a complete one.
 */
data class ExtractedText(
    val pages: List<PageText>,
    val truncated: Boolean,
    val skippedPageNumbers: List<Int> = emptyList()
) {
    val characterCount: Int get() = pages.sumOf { it.text.length }

    /** Pages that produced readable text. */
    val pageCount: Int get() = pages.size

    val unreadablePageCount: Int get() = skippedPageNumbers.size

    val isPartial: Boolean get() = skippedPageNumbers.isNotEmpty()

    /** Human-readable note for the review screen, or null when every page was read. */
    fun coverageNote(): String? {
        if (!isPartial) return null
        val count = skippedPageNumbers.size
        val pages = if (count == 1) "1 page" else "$count pages"
        val which = if (count <= 3) {
            " (page ${skippedPageNumbers.joinToString(", ")})"
        } else {
            ""
        }
        return "$pages could not be read$which and are not part of this review."
    }

    /** Text as handed to the privacy layer, with page markers kept. */
    val plainText: String
        get() = pages.joinToString("\n\n") { "Page ${it.pageNumber}\n${it.text}" }
}

/**
 * A document problem the UI can explain. Every case is a real, reachable outcome of
 * opening a user-picked file, never a silent empty string.
 */
sealed class ExtractionError(val userMessage: String) {
    data object PasswordProtected : ExtractionError(
        "This PDF is password protected. Remove the password and try again."
    )

    data object Corrupt : ExtractionError(
        "That file could not be opened. It may be corrupted or not a real PDF."
    )

    data object NoPages : ExtractionError("That PDF has no readable pages.")

    data object Unreadable : ExtractionError(
        "That file could not be read. Try another file, or pick it again."
    )

    data object NoTextFound : ExtractionError(
        "No readable text was found. Try a clearer photo, or a PDF with selectable text."
    )

    data object ImageDecodeFailed : ExtractionError(
        "That image could not be opened on this device."
    )
}

/** Label OCR result with block/column-reconstructed text, confidence, and image quality diagnostics. */
data class LabelOcrResult(
    val text: String,
    val primaryTextForParsing: String = text,
    val lineCount: Int,
    val blockCount: Int,
    val meanConfidence: Float? = null,
    val qualityHint: String? = null,
    val durationMs: Long = 0L
)

class OcrException(val error: ExtractionError) : Exception(error.userMessage)

/**
 * Text extraction for medical reports and label images.
 *
 * PDFs are rendered one page at a time by [PdfRenderer] and each bitmap is recycled
 * before the next page, so a long report never holds more than one page in memory.
 * All work runs on [Dispatchers.IO].
 */
object OcrService {

    /** Documents beyond this are truncated, not rejected: partial context is still useful. */
    const val MAX_PDF_PAGES = 10

    /** Longest edge used when rasterising a PDF page; keeps bitmaps well under 25 MB. */
    const val MAX_RENDER_EDGE_PX = OcrPageSizing.MAX_EDGE_PX

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun isPdf(context: Context, uri: Uri, displayName: String?): Boolean {
        val mime = context.contentResolver.getType(uri)
        if (mime == "application/pdf") return true
        if (mime != null) return false
        val name = displayName ?: uri.lastPathSegment.orEmpty()
        if (name.endsWith(".pdf", ignoreCase = true)) return true
        return MimeTypeMap.getFileExtensionFromUrl(name)?.equals("pdf", true) == true
    }

    /**
     * Extracts text from a picked document.
     *
     * @throws OcrException on any failure, so callers must render an explanation.
     */
    suspend fun extract(context: Context, uri: Uri, displayName: String? = null): ExtractedText {
        return try {
            withContext(Dispatchers.IO) {
                if (isPdf(context, uri, displayName)) {
                    extractPdf(context, uri)
                } else {
                    val text = recogniseUri(context, uri)
                    if (text.isBlank()) throw OcrException(ExtractionError.NoTextFound)
                    ExtractedText(pages = listOf(PageText(1, text)), truncated = false)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OcrException) {
            throw e
        } catch (e: Exception) {
            throw OcrException(ExtractionError.Unreadable)
        }
    }

    private suspend fun extractPdf(context: Context, uri: Uri): ExtractedText {
        val descriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: FileNotFoundException) {
            null
        } catch (e: SecurityException) {
            null
        } ?: throw OcrException(ExtractionError.Unreadable)

        descriptor.use { fileDescriptor ->
            val renderer = openRenderer(fileDescriptor)
            renderer.use {
                val totalPages = renderer.pageCount
                if (totalPages <= 0) throw OcrException(ExtractionError.NoPages)

                val limit = min(totalPages, MAX_PDF_PAGES)
                val pages = ArrayList<PageText>(limit)
                val skipped = ArrayList<Int>()

                for (index in 0 until limit) {
                    val page = try {
                        renderer.openPage(index)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        skipped.add(index + 1)
                        continue
                    }
                    page.use { openPage ->
                        val bitmap = try {
                            renderPageBitmap(openPage)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            skipped.add(index + 1)
                            return@use
                        }
                        try {
                            val text = recognise(bitmap)
                            if (text.isNotBlank()) {
                                pages.add(PageText(pageNumber = index + 1, text = text))
                            } else {
                                // A blank page is reported, not hidden: it may be a scan
                                // page whose text failed to render or recognise.
                                skipped.add(index + 1)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }

                if (pages.isEmpty()) throw OcrException(ExtractionError.NoTextFound)
                return ExtractedText(
                    pages = pages,
                    truncated = totalPages > limit,
                    skippedPageNumbers = skipped
                )
            }
        }
    }

    private fun openRenderer(fileDescriptor: android.os.ParcelFileDescriptor): PdfRenderer {
        return try {
            PdfRenderer(fileDescriptor)
        } catch (e: SecurityException) {
            throw OcrException(ExtractionError.PasswordProtected)
        } catch (e: IOException) {
            val message = e.message.orEmpty().lowercase()
            if (message.contains("password") || message.contains("encrypt")) {
                throw OcrException(ExtractionError.PasswordProtected)
            }
            throw OcrException(ExtractionError.Corrupt)
        } catch (e: IllegalStateException) {
            throw OcrException(ExtractionError.PasswordProtected)
        }
    }

    /**
     * Rasterises one PDF page at a bounded size. Page dimensions come from the file,
     * so they are clamped before allocating: an oversized PDF page cannot allocate a
     * huge bitmap here.
     */
    private fun renderPageBitmap(page: PdfRenderer.Page): Bitmap {
        val size = OcrPageSizing.compute(page.width, page.height)
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        // PdfRenderer does not clear the bitmap and PDF pages carry no background, so
        // without this the page is transparent and text recognition sees a blank image.
        bitmap.eraseColor(android.graphics.Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    /**
     * Multi-pass on-device label OCR:
     *  1. Evaluates resolution, brightness, contrast, blur and glare non-destructively.
     *  2. Runs Pass 1 on the EXIF-oriented full image with element/column geometry reconstruction.
     *  3. Applies deskew / tile-adaptive contrast enhancement when skewed, dark or glare-affected.
     *  4. Runs Pass 2 on a high-resolution crop around the candidate ingredient region so dense
     *     small packaging fonts are recognized cleanly.
     *
     * Recognized text from passes is preserved without silent dropping.
     */
    suspend fun extractLabel(context: Context, uri: Uri): LabelOcrResult {
        return try {
            withContext(Dispatchers.IO) {
                val startNs = System.nanoTime()
                val quality = ImagePreprocessor.assessQuality(context, uri)
                val qualityHint = quality?.qualityHint

                val baseBitmap = ImagePreprocessor.loadOrientedBitmap(context, uri)
                if (baseBitmap == null) {
                    val image = try {
                        InputImage.fromFilePath(context, uri)
                    } catch (e: IOException) {
                        throw OcrException(ExtractionError.ImageDecodeFailed)
                    } catch (e: SecurityException) {
                        throw OcrException(ExtractionError.Unreadable)
                    }
                    val visionText = recognizer.process(image).await()
                    val layout = reconstructLayout(visionText)
                    val text = layout.fullText.ifBlank { visionText.text.trim() }
                    if (text.isBlank()) throw OcrException(ExtractionError.NoTextFound)
                    val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                    return@withContext LabelOcrResult(
                        text = text,
                        primaryTextForParsing = text,
                        lineCount = visionText.textBlocks.sumOf { it.lines.size },
                        blockCount = visionText.textBlocks.size,
                        meanConfidence = layout.meanLineConfidence,
                        qualityHint = qualityHint,
                        durationMs = elapsedMs
                    )
                }

                var deskewedBitmap: Bitmap? = null
                var enhancedFullBitmap: Bitmap? = null
                var cropBitmap: Bitmap? = null
                var enhancedCropBitmap: Bitmap? = null

                try {
                    val pass1Vision = recognizer.process(InputImage.fromBitmap(baseBitmap, 0)).await()
                    val pass1Layout = reconstructLayout(pass1Vision)

                    var activeBitmap = baseBitmap
                    var activeVision = pass1Vision
                    var activeLayout = pass1Layout

                    // Pass 1B: Deskew tilted labels and/or run glare-tolerant enhancement if needed
                    val skew = pass1Layout.estimatedSkewDegrees
                    if (abs(skew) in 2.5f..45f) {
                        deskewedBitmap = ImagePreprocessor.rotateBitmap(baseBitmap, -skew)
                        if (deskewedBitmap !== baseBitmap) {
                            activeBitmap = deskewedBitmap
                            activeVision = recognizer.process(InputImage.fromBitmap(activeBitmap, 0)).await()
                            val deskewedLayout = reconstructLayout(activeVision)
                            if (scoreCandidateOcr(deskewedLayout.fullText) >= scoreCandidateOcr(activeLayout.fullText)) {
                                activeLayout = deskewedLayout
                            } else {
                                // Keep deskewed bitmap only if it actually helped
                                activeBitmap = baseBitmap
                                activeVision = pass1Vision
                            }
                        }
                    }

                    val initialParse = IngredientParser.parseDetailed(
                        ocrText = activeLayout.fullText,
                        qualityHint = qualityHint,
                        meanOcrConfidence = activeLayout.meanLineConfidence
                    )
                    if (quality?.needsEnhancement == true ||
                        activeLayout.fullText.isBlank() ||
                        initialParse.confidence != ExtractionConfidence.HIGH
                    ) {
                        enhancedFullBitmap = ImagePreprocessor.enhanceForPackagingOcr(activeBitmap)
                        val enhancedVision = recognizer.process(InputImage.fromBitmap(enhancedFullBitmap, 0)).await()
                        val enhancedLayout = reconstructLayout(enhancedVision)
                        if (scoreCandidateOcr(enhancedLayout.fullText) > scoreCandidateOcr(activeLayout.fullText)) {
                            activeLayout = enhancedLayout
                            activeVision = enhancedVision
                        }
                    }

                    // Pass 2: High-res crop of the candidate ingredient region from oriented activeBitmap
                    var bestTextForParsing = activeLayout.fullText
                    var bestMeanConf = activeLayout.meanLineConfidence
                    var cropExtraText: String? = null

                    val regionBox = activeLayout.ingredientRegionBox
                    if (regionBox != null) {
                        cropBitmap = ImagePreprocessor.cropIngredientRegion(activeBitmap, regionBox)
                        val targetCrop = cropBitmap
                        if (targetCrop != null) {
                            val rawCropVision = recognizer.process(InputImage.fromBitmap(targetCrop, 0)).await()
                            val rawCropLayout = reconstructLayout(rawCropVision)

                            enhancedCropBitmap = ImagePreprocessor.enhanceForPackagingOcr(targetCrop)
                            val enhCropVision = recognizer.process(InputImage.fromBitmap(enhancedCropBitmap, 0)).await()
                            val enhCropLayout = reconstructLayout(enhCropVision)

                            val winningCropLayout =
                                if (scoreCandidateOcr(enhCropLayout.fullText) >= scoreCandidateOcr(rawCropLayout.fullText)) {
                                    enhCropLayout
                                } else {
                                    rawCropLayout
                                }

                            if (winningCropLayout.fullText.isNotBlank()) {
                                cropExtraText = winningCropLayout.fullText
                                val fullScore = scoreCandidateOcr(bestTextForParsing)
                                val cropScore = scoreCandidateOcr(winningCropLayout.fullText)
                                if (cropScore >= fullScore) {
                                    bestTextForParsing = mergeCropAndFullAllergens(
                                        cropText = winningCropLayout.fullText,
                                        fullText = activeLayout.fullText
                                    )
                                    bestMeanConf = winningCropLayout.meanLineConfidence ?: bestMeanConf
                                }
                            }
                        }
                    }

                    val preservedRaw = buildString {
                        append(activeLayout.fullText.trim())
                        if (!cropExtraText.isNullOrBlank() && cropExtraText.trim() != activeLayout.fullText.trim()) {
                            if (isNotEmpty()) append("\n\n--- High-res ingredient crop ---\n")
                            append(cropExtraText.trim())
                        }
                    }.trim()

                    if (preservedRaw.isBlank()) throw OcrException(ExtractionError.NoTextFound)
                    val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                    LabelOcrResult(
                        text = preservedRaw,
                        primaryTextForParsing = bestTextForParsing.ifBlank { preservedRaw },
                        lineCount = activeVision.textBlocks.sumOf { it.lines.size },
                        blockCount = activeVision.textBlocks.size,
                        meanConfidence = bestMeanConf,
                        qualityHint = qualityHint,
                        durationMs = elapsedMs
                    )
                } finally {
                    enhancedCropBitmap?.recycle()
                    cropBitmap?.recycle()
                    enhancedFullBitmap?.recycle()
                    if (deskewedBitmap != null && deskewedBitmap !== baseBitmap) {
                        deskewedBitmap.recycle()
                    }
                    baseBitmap.recycle()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OcrException) {
            throw e
        } catch (e: Exception) {
            throw OcrException(ExtractionError.Unreadable)
        }
    }

    private fun reconstructLayout(visionText: Text): ReconstructedOcrLayout {
        val lineBoxes = visionText.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val box = line.boundingBox ?: block.boundingBox ?: return@mapNotNull null
                val elements = line.elements.mapNotNull { el ->
                    val elBox = el.boundingBox ?: return@mapNotNull null
                    OcrElementBox(
                        text = el.text,
                        left = elBox.left,
                        top = elBox.top,
                        right = elBox.right,
                        bottom = elBox.bottom
                    )
                }
                OcrLineBox(
                    text = line.text,
                    left = box.left,
                    top = box.top,
                    right = box.right,
                    bottom = box.bottom,
                    angle = line.angle,
                    confidence = line.confidence,
                    elements = elements
                )
            }
        }
        return if (lineBoxes.isNotEmpty()) {
            OcrLayoutReconstructor.reconstruct(lineBoxes)
        } else {
            val fallback = visionText.text.trim()
            ReconstructedOcrLayout(
                fullText = fallback,
                columnCount = if (fallback.isBlank()) 0 else 1,
                estimatedSkewDegrees = 0f,
                meanLineConfidence = null,
                ingredientRegionBox = null
            )
        }
    }

    private fun scoreCandidateOcr(text: String): Int {
        if (text.isBlank()) return -100
        val parsed = IngredientParser.parseDetailed(text)
        var score = 0
        if (parsed.hasExplicitHeader) score += 50
        score += parsed.ingredients.size * 8
        score += parsed.allergenDeclarations.size * 6
        score -= parsed.warnings.size * 8
        score -= parsed.ingredients.count { IngredientParser.looksLikeOcrGarble(it) } * 12
        return score
    }

    private fun mergeCropAndFullAllergens(cropText: String, fullText: String): String {
        val cropAllergens = IngredientParser.parseDetailed(cropText).allergenDeclarations
        if (cropAllergens.isNotEmpty()) return cropText
        val fullAllergens = IngredientParser.parseDetailed(fullText).allergenDeclarations
        if (fullAllergens.isEmpty()) return cropText
        return buildString {
            append(cropText.trim())
            append("\n\nAllergen Advice: ")
            append(fullAllergens.joinToString(". "))
        }
    }

    private suspend fun recogniseUri(context: Context, uri: Uri): String {
        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (e: IOException) {
            throw OcrException(ExtractionError.ImageDecodeFailed)
        } catch (e: SecurityException) {
            throw OcrException(ExtractionError.Unreadable)
        }
        return recognizer.process(image).await().text.trim()
    }

    private suspend fun recognise(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        return recognizer.process(image).await().text.trim()
    }
}
