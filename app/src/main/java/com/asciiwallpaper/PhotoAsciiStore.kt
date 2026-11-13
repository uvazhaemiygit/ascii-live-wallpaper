package com.asciiwallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Imports a user photo and keeps a compact grayscale copy in app-private
 * storage. The live wallpaper never depends on a content:// permission after
 * the picker returns, and it does not have to decode the original (often
 * 12-megapixel) JPEG on the render thread.
 *
 * The bitmap is cached by revision because the settings preview and the
 * wallpaper service run in the same process and can share the immutable image.
 */
object PhotoAsciiStore {
    private const val TAG = "PhotoAsciiStore"
    const val FILE_NAME = "photo_source.png"

    /**
     * 1200 px on the long side is comfortably above the maximum ASCII grid
     * (200 columns × 3 samples on the scene side) without keeping a huge
     * full-resolution bitmap in memory.
     */
    private const val MAX_SOURCE_SIDE = 1200

    @Volatile
    private var cachedRevision = Int.MIN_VALUE

    @Volatile
    private var cachedBitmap: Bitmap? = null

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun hasPhoto(context: Context): Boolean = file(context).isFile

    /**
     * Decodes, rotates, converts to grayscale, stretches the tonal range and
     * stores the result atomically. Returns a new revision on success.
     */
    @Synchronized
    fun import(context: Context, uri: Uri): Int? {
        val decoded = decodeOriented(context, uri) ?: return null
        var grayscale: Bitmap? = null
        val target = file(context)
        val temporary = File(context.filesDir, "$FILE_NAME.tmp")

        return try {
            grayscale = toGrayscale(decoded)
            FileOutputStream(temporary).use { output ->
                if (!grayscale.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    Log.w(TAG, "PNG compression failed")
                    return null
                }
                output.flush()
            }

            if (target.exists() && !target.delete()) {
                Log.w(TAG, "Cannot remove old photo source")
                return null
            }
            if (!temporary.renameTo(target)) {
                Log.w(TAG, "Cannot install photo source")
                return null
            }

            val revision = (System.currentTimeMillis() and Int.MAX_VALUE.toLong())
                .toInt()
                .coerceAtLeast(1)
            cachedRevision = revision
            cachedBitmap = grayscale
            revision
        } catch (t: Throwable) {
            Log.w(TAG, "Photo import failed", t)
            null
        } finally {
            if (grayscale !== cachedBitmap) grayscale?.recycle()
            decoded.recycle()
            if (temporary.exists()) temporary.delete()
        }
    }

    /**
     * Returns the cache entry when the renderer already knows this revision,
     * otherwise decodes the app-private copy once and caches it. Both callers
     * receive the same immutable bitmap, so it must never be recycled by them.
     */
    @Synchronized
    fun load(context: Context, revision: Int): Bitmap? {
        if (revision == cachedRevision) return cachedBitmap

        val target = file(context)
        val bitmap = if (target.isFile) {
            try {
                BitmapFactory.decodeFile(target.absolutePath)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
        cachedRevision = revision
        cachedBitmap = bitmap
        return bitmap
    }

    private fun decodeOriented(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        try {
            // With inJustDecodeBounds=true BitmapFactory returns null even on
            // success, so the result of decodeStream must not be checked here.
            // Only a failed open / throw means the bounds are unavailable.
            val stream = resolver.openInputStream(uri) ?: return null
            stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot read image bounds", t)
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "Image has invalid bounds: ${bounds.outWidth}x${bounds.outHeight}")
            return null
        }

        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >=
            MAX_SOURCE_SIDE
        ) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot decode image", t)
            null
        } ?: run {
            Log.w(TAG, "BitmapFactory returned null")
            return null
        }

        val oriented = applyExifOrientation(context, uri, decoded)
        if (oriented !== decoded) decoded.recycle()

        val longSide = max(oriented.width, oriented.height)
        if (longSide <= MAX_SOURCE_SIDE) return oriented

        val scale = MAX_SOURCE_SIDE.toFloat() / longSide
        val width = (oriented.width * scale).roundToInt().coerceAtLeast(1)
        val height = (oriented.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(oriented, width, height, true)
        if (scaled !== oriented) oriented.recycle()
        return scaled
    }

    private fun applyExifOrientation(
        context: Context,
        uri: Uri,
        bitmap: Bitmap
    ): Bitmap {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return bitmap
        }

        return try {
            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            )
        } catch (_: Throwable) {
            bitmap
        }
    }

    /**
     * Color → luminance → auto-contrast. A percentile stretch keeps ordinary
     * camera photos from collapsing into only a few ramp characters, while
     * ignoring isolated black/white outliers.
     */
    private fun toGrayscale(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixelCount = width * height
        val pixels = IntArray(pixelCount)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val luminance = IntArray(pixelCount)
        val histogram = IntArray(256)
        for (index in 0 until pixelCount) {
            val color = pixels[index]
            val alpha = (color ushr 24) and 0xFF
            val red = (((color ushr 16) and 0xFF) * alpha) / 255
            val green = (((color ushr 8) and 0xFF) * alpha) / 255
            val blue = ((color and 0xFF) * alpha) / 255
            val value = ((red * 54 + green * 183 + blue * 19) ushr 8)
                .coerceIn(0, 255)
            luminance[index] = value
            histogram[value]++
        }

        val low = percentile(histogram, pixelCount, 0.005f)
        val high = percentile(histogram, pixelCount, 0.995f)
        val span = high - low
        val output = IntArray(pixelCount)
        for (index in 0 until pixelCount) {
            val value = if (span >= 16) {
                (((luminance[index] - low).coerceIn(0, span) * 255 +
                    span / 2) / span).coerceIn(0, 255)
            } else {
                luminance[index]
            }
            output[index] = (0xFF000000.toInt() or
                (value shl 16) or (value shl 8) or value)
        }

        return Bitmap.createBitmap(
            output,
            width,
            height,
            Bitmap.Config.ARGB_8888
        )
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Float): Int {
        if (total <= 0) return 0
        val target = (total * fraction).roundToInt().coerceIn(0, total - 1)
        var seen = 0
        for (value in 0..255) {
            seen += histogram[value]
            if (seen > target) return value
        }
        return 255
    }
}
