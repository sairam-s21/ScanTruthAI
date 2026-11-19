package com.scantruthai.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

object OcrService {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractTextFromBitmap(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        return recognizer.process(image).await().text.trim()
    }

    suspend fun extractTextFromUri(context: Context, uri: Uri): String {
        val mime = context.contentResolver.getType(uri).orEmpty()
        if (mime == "application/pdf" || uri.toString().endsWith(".pdf", ignoreCase = true)) {
            return extractTextFromPdfUri(context, uri)
        }
        val image = InputImage.fromFilePath(context, uri)
        return recognizer.process(image).await().text.trim()
    }

    private suspend fun extractTextFromPdfUri(context: Context, uri: Uri): String {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return ""
        val pagesText = mutableListOf<String>()
        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val bitmap = Bitmap.createBitmap(
                            page.width * 2,
                            page.height * 2,
                            Bitmap.Config.ARGB_8888
                        )
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pagesText.add(extractTextFromBitmap(bitmap))
                        bitmap.recycle()
                    }
                }
            }
        }
        return pagesText.joinToString("\n").trim()
    }
}