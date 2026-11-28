package com.scantruth.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.webkit.MimeTypeMap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
    val skippedPageNumbers: List<Int> = emptyList(),
    /** False for typed text, which has no pages; then no "Page N" markers are added. */
    val pageMarkers: Boolean = true
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
        get() = pages.joinToString("\n\n") {
            if (pageMarkers) "Page ${it.pageNumber}\n${it.text}" else it.text
        }
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
