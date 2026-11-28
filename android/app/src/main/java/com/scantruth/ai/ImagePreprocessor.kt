package com.scantruth.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.scantruth.ai.domain.PixelRegion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Quantitative quality metrics computed from an ingredient label image. */
data class ImageQualityMetrics(
    val width: Int,
    val height: Int,
    val meanLuma: Double,
    val stdDevLuma: Double,
    val meanEdgeGradient: Double,
    val glareFraction: Double,
    val darkFraction: Double,
    val qualityHint: String?,
    val needsEnhancement: Boolean
)

/**
 * Non-destructive image quality analysis, EXIF orientation normalization, deskewing,
 * glare-tolerant local contrast enhancement, and high-resolution ingredient-region cropping.
 *
 * Original user photos/URIs are never overwritten or modified in place.
 */
object ImagePreprocessor {

    private const val MAX_FULL_PASS_EDGE_PX = 2200
    private const val MIN_CROP_LONG_EDGE_PX = 1400
    private const val MAX_CROP_EDGE_PX = 2400

    /** Pure pixel-array evaluation; unit-tested directly without Android graphics mocks. */
    fun evaluatePixels(
        rawWidth: Int,
        rawHeight: Int,
        sampleWidth: Int,
        sampleHeight: Int,
        pixels: IntArray
    ): ImageQualityMetrics {
        if (rawWidth <= 0 || rawHeight <= 0 || sampleWidth < 4 || sampleHeight < 4 || pixels.isEmpty()) {
            return ImageQualityMetrics(
                width = rawWidth,
                height = rawHeight,
                meanLuma = 0.0,
                stdDevLuma = 0.0,
                meanEdgeGradient = 0.0,
                glareFraction = 0.0,
                darkFraction = 0.0,
                qualityHint = null,
                needsEnhancement = false
            )
        }

        val luma = DoubleArray(pixels.size)
        var sumLuma = 0.0
        var sumSqLuma = 0.0
        var glareCount = 0
        var darkCount = 0
        var edgeSum = 0.0
        var edgeCount = 0

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val y = 0.299 * r + 0.587 * g + 0.114 * b
            luma[i] = y
            sumLuma += y
            sumSqLuma += y * y
            if (y >= 240.0) glareCount++
            if (y <= 42.0) darkCount++
        }

        for (y in 0 until sampleHeight) {
            val row = y * sampleWidth
            for (x in 0 until sampleWidth) {
                val cur = luma[row + x]
                if (x + 1 < sampleWidth) {
                    edgeSum += abs(cur - luma[row + x + 1])
                    edgeCount++
                }
                if (y + 1 < sampleHeight) {
                    edgeSum += abs(cur - luma[row + sampleWidth + x])
                    edgeCount++
                }
            }
        }

        val n = pixels.size.toDouble()
        val mean = sumLuma / n
        val stdDev = sqrt(max(0.0, (sumSqLuma / n) - (mean * mean)))
        val meanEdge = if (edgeCount > 0) edgeSum / edgeCount else 0.0
        val glareFrac = glareCount / n
        val darkFrac = darkCount / n

        val hint = when {
            min(rawWidth, rawHeight) < 420 ->
                "Low image resolution (${rawWidth}x${rawHeight}). Hold the camera closer if any ingredient looks missed."
            mean < 42.0 || darkFrac > 0.72 ->
                "The photo appears very dark. Brighter lighting improves ingredient accuracy."
            glareFrac > 0.22 ->
                "Strong glare or reflection detected on the wrapper. Tilt the pack slightly if text looks washed out."
            stdDev < 18.0 || meanEdge < 4.0 ->
                "The photo appears blurry or low-contrast. Hold steady and let the camera focus."
            else -> null
        }

        val needsEnhancement = mean < 90.0 || glareFrac > 0.06 || stdDev < 44.0 || meanEdge < 10.0

        return ImageQualityMetrics(
            width = rawWidth,
            height = rawHeight,
            meanLuma = mean,
            stdDevLuma = stdDev,
            meanEdgeGradient = meanEdge,
            glareFraction = glareFrac,
            darkFraction = darkFrac,
            qualityHint = hint,
            needsEnhancement = needsEnhancement
        )
    }

    /** Evaluates image metrics cleanly from a [Uri] without holding a large bitmap in memory. */
    fun assessQuality(context: Context, uri: Uri): ImageQualityMetrics? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            val rawW = bounds.outWidth
            val rawH = bounds.outHeight
            if (rawW <= 0 || rawH <= 0) return null

            val sampleSize = max(1, max(rawW, rawH) / 240)
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val sampled = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            } ?: return null

            try {
                val w = sampled.width
                val h = sampled.height
                val pixels = IntArray(w * h)
                sampled.getPixels(pixels, 0, w, 0, 0, w, h)
                evaluatePixels(rawW, rawH, w, h, pixels)
            } finally {
                sampled.recycle()
            }
        }.getOrNull()
    }

    /**
     * Decodes [uri] with EXIF orientation applied and longest edge bounded to [maxEdgePx].
     * Caller owns and must recycle the returned [Bitmap].
     */
    fun loadOrientedBitmap(
        context: Context,
        uri: Uri,
        maxEdgePx: Int = MAX_FULL_PASS_EDGE_PX
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        var sampleSize = 1
        while (max(rawW / sampleSize, rawH / sampleSize) > maxEdgePx * 2) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: return null

        val exifOrientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val oriented = applyExifOrientation(decoded, exifOrientation)
        if (oriented !== decoded) {
            decoded.recycle()
        }

        val longest = max(oriented.width, oriented.height)
        if (longest <= maxEdgePx) return oriented

        val scale = maxEdgePx.toFloat() / longest.toFloat()
        val targetW = max(1, (oriented.width * scale).roundToInt())
        val targetH = max(1, (oriented.height * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(oriented, targetW, targetH, true)
        if (scaled !== oriented) {
            oriented.recycle()
        }
        return scaled
    }

    /** Applies a fine deskew rotation around the center when lines are tilted. */
    fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        if (abs(degrees) < 1.5f) return source
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Crops the candidate ingredient region with generous left/bottom padding so clipped
     * edges or wrapped final lines are included, and upscales small crops to improve tiny
     * packaging font recognition.
     */
    fun cropIngredientRegion(source: Bitmap, region: PixelRegion): Bitmap? {
        val regionW = max(1, region.right - region.left)
        val regionH = max(1, region.bottom - region.top)

        val padLeft = max(24, (regionW * 0.16f).roundToInt())
        val padRight = max(16, (regionW * 0.08f).roundToInt())
        val padTop = max(20, (regionH * 0.16f).roundToInt())
        val padBottom = max(28, (regionH * 0.24f).roundToInt())

        val left = (region.left - padLeft).coerceIn(0, source.width - 1)
        val top = (region.top - padTop).coerceIn(0, source.height - 1)
        val right = (region.right + padRight).coerceIn(left + 1, source.width)
        val bottom = (region.bottom + padBottom).coerceIn(top + 1, source.height)

        val cropW = right - left
        val cropH = bottom - top
        if (cropW < 40 || cropH < 24) return null
        // Skip cropping if the region already covers nearly the entire frame.
        if (cropW > source.width * 0.90f && cropH > source.height * 0.85f) return null

        val cropped = Bitmap.createBitmap(source, left, top, cropW, cropH)
        val longest = max(cropped.width, cropped.height)
        return if (longest < MIN_CROP_LONG_EDGE_PX) {
            val scale = min(2.4f, MIN_CROP_LONG_EDGE_PX.toFloat() / longest.toFloat())
            val upW = (cropped.width * scale).roundToInt().coerceAtMost(MAX_CROP_EDGE_PX)
            val upH = (cropped.height * scale).roundToInt().coerceAtMost(MAX_CROP_EDGE_PX)
            val upscaled = Bitmap.createScaledBitmap(cropped, upW, upH, true)
            if (upscaled !== cropped) cropped.recycle()
            upscaled
        } else {
            cropped
        }
    }

    /**
     * Non-destructive packaging OCR enhancement:
     * 1. Tile-adaptive local contrast stretching & shadow lift (handles foil glare + dark areas).
     * 2. Mild luminance unsharp sharpening for crisp small typography.
     */
    fun enhanceForPackagingOcr(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val enhanced = enhancePixelsForOcr(w, h, pixels)
        return Bitmap.createBitmap(enhanced, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Pure Kotlin pixel-array enhancer (unit tested without Android Canvas/RenderScript). */
    internal fun enhancePixelsForOcr(width: Int, height: Int, pixels: IntArray): IntArray {
        if (width < 4 || height < 4 || pixels.size != width * height) return pixels.copyOf()

        val luma = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            luma[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        // 4x4 grid local min/max stretch to normalize glossy glare highlights & shadows smoothly
        val gridCols = 4
        val gridRows = 4
        val tileMin = FloatArray(gridCols * gridRows) { 255f }
        val tileMax = FloatArray(gridCols * gridRows) { 0f }

        for (y in 0 until height) {
            val gy = min(gridRows - 1, (y * gridRows) / height)
            val row = y * width
            for (x in 0 until width) {
                val gx = min(gridCols - 1, (x * gridCols) / width)
                val idx = gy * gridCols + gx
                val v = luma[row + x]
                if (v < tileMin[idx]) tileMin[idx] = v
                if (v > tileMax[idx]) tileMax[idx] = v
            }
        }

        val stretched = FloatArray(pixels.size)
        for (y in 0 until height) {
            val fy = ((y.toFloat() / height) * gridRows - 0.5f).coerceIn(0f, (gridRows - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(gridRows - 1, y0 + 1)
            val wy = fy - y0
            val row = y * width

            for (x in 0 until width) {
                val fx = ((x.toFloat() / width) * gridCols - 0.5f).coerceIn(0f, (gridCols - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(gridCols - 1, x0 + 1)
                val wx = fx - x0

                val lo = bilerp(
                    tileMin[y0 * gridCols + x0], tileMin[y0 * gridCols + x1],
                    tileMin[y1 * gridCols + x0], tileMin[y1 * gridCols + x1],
                    wx, wy
                )
                val hi = bilerp(
                    tileMax[y0 * gridCols + x0], tileMax[y0 * gridCols + x1],
                    tileMax[y1 * gridCols + x0], tileMax[y1 * gridCols + x1],
                    wx, wy
                )

                val span = max(28f, hi - lo)
                val norm = ((luma[row + x] - lo) / span).coerceIn(0f, 1f)
                // Gentle shadow lift (gamma 0.88) + glare ceiling compression
                val tone = (norm.toDouble().pow(0.88) * 248.0).toFloat()
                stretched[row + x] = tone
            }
        }

        // Mild 3x3 unsharp mask to crisp small dense packaging characters without blowing up noise
        val out = IntArray(pixels.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val idx = row + x
                val value = if (x in 1 until (width - 1) && y in 1 until (height - 1)) {
                    val center = stretched[idx]
                    val avgNeighbors = (
                        stretched[idx - 1] + stretched[idx + 1] +
                            stretched[idx - width] + stretched[idx + width]
                        ) * 0.25f
                    (center + (center - avgNeighbors) * 0.45f).roundToInt().coerceIn(0, 255)
                } else {
                    stretched[idx].roundToInt().coerceIn(0, 255)
                }
                out[idx] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
            }
        }
        return out
    }

    private fun bilerp(v00: Float, v10: Float, v01: Float, v11: Float, wx: Float, wy: Float): Float {
        val top = v00 + (v10 - v00) * wx
        val bot = v01 + (v11 - v01) * wx
        return top + (bot - top) * wy
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
