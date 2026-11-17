package com.asciiwallpaper

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Movie
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Import and cache animated GIF/video sources.
 *
 * Decoding a video or GIF on the live wallpaper render thread is not realistic
 * on a phone: MediaMetadataRetriever is sync/blocking and software GIF
 * decoders allocate a lot. Instead the clip is sampled once at import time
 * into a compact grayscale frame set.
 *
 * The whole clip is sampled, not just its first seconds. A fixed frame budget
 * keeps the cache bounded: short clips get the full 12 fps, while long clips
 * are sampled more sparsely and their playback rate is stored so the original
 * duration is preserved. Resolution is reduced as the frame count grows, and
 * one byte per pixel keeps the cache practical even for several minutes of
 * video. On low-RAM devices the budget is lower.
 *
 * The frame set is stored in app-private storage with a tiny binary header.
 * The wallpaper can then draw a frame with no media decoder and no content
 * permission, and the settings preview shares the same in-process cache.
 */
object MediaAsciiStore {
    private const val TAG = "MediaAsciiStore"
    private const val FILE_NAME = "media_frames.bin"
    private const val MAGIC = 0x41534346 // "ASCF"
    private const val VERSION = 1
    private const val TARGET_FPS = 12f
    private const val MAX_FRAMES = 720
    private const val MAX_LOW_RAM_FRAMES = 480
    private const val MAX_LONG_SIDE = 540
    private const val LOW_RAM_LONG_SIDE = 360
    private const val MAX_FRAME_COUNT_READ = 1200
    private const val MAX_SIDE_READ = 2048

    private data class FramePlan(
        val count: Int,
        val fps: Float,
        val longSide: Int
    )

    data class FrameSet(
        val width: Int,
        val height: Int,
        val fps: Float,
        val frames: Array<ByteArray>
    )

    sealed interface ImportResult {
        data class Still(val revision: Int) : ImportResult
        data class Animated(val revision: Int) : ImportResult
    }

    @Volatile
    private var cachedRevision = IntArray(MediaSlot.entries.size) { Int.MIN_VALUE }

    @Volatile
    private var cachedFrames = arrayOfNulls<FrameSet>(MediaSlot.entries.size)

    fun file(context: Context, slot: MediaSlot = MediaSlot.MAIN): File =
        File(context.filesDir, fileName(slot))

    fun hasMedia(context: Context, slot: MediaSlot = MediaSlot.MAIN): Boolean =
        file(context, slot).isFile

    fun hasVisual(context: Context, slot: MediaSlot = MediaSlot.MAIN): Boolean =
        PhotoAsciiStore.hasPhoto(context, slot) || hasMedia(context, slot)

    @Synchronized
    fun clear(context: Context, slot: MediaSlot = MediaSlot.MAIN) {
        val index = slot.ordinal
        cachedFrames[index] = null
        cachedRevision[index] = Int.MIN_VALUE
        val target = file(context, slot)
        if (target.exists()) target.delete()
    }

    /**
     * Imports a still image, animated GIF or video. Returns null when the URI
     * cannot be decoded as either kind of media.
     */
    @Synchronized
    fun import(
        context: Context,
        uri: Uri,
        slot: MediaSlot = MediaSlot.MAIN
    ): ImportResult? {
        val resolver = context.contentResolver
        val mime = try {
            resolver.getType(uri).orEmpty().lowercase()
        } catch (_: Throwable) {
            ""
        }
        val name = try {
            resolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0).orEmpty()
                } else {
                    ""
                }
            }.orEmpty().lowercase()
        } catch (_: Throwable) {
            ""
        }

        val videoByName = name.endsWith(".mp4") ||
            name.endsWith(".m4v") ||
            name.endsWith(".mov") ||
            name.endsWith(".webm") ||
            name.endsWith(".mkv") ||
            name.endsWith(".3gp") ||
            name.endsWith(".avi")
        if (mime.startsWith("video/") || videoByName) {
            val frames = extractVideo(context, uri) ?: return null
            return installAnimated(context, frames, slot)
        }

        if (mime == "image/gif" || name.endsWith(".gif")) {
            val frames = extractGif(context, uri)
            if (frames != null && frames.frames.size >= 2) {
                return installAnimated(context, frames, slot)
            }
            // A one-frame GIF is just a still image. Let the still importer
            // use its EXIF/color/auto-contrast pipeline.
            val revision = PhotoAsciiStore.import(context, uri, slot) ?: return null
            return ImportResult.Still(revision)
        }

        // Ordinary image (including a one-frame WebP) that the still importer
        // can handle.
        val revision = PhotoAsciiStore.import(context, uri, slot) ?: return null
        return ImportResult.Still(revision)
    }

    /**
     * Decodes the cached frame set once per revision. Both the wallpaper and
     * the live settings preview receive the same immutable object.
     */
    @Synchronized
    fun load(
        context: Context,
        revision: Int,
        slot: MediaSlot = MediaSlot.MAIN
    ): FrameSet? {
        val index = slot.ordinal
        if (revision == cachedRevision[index]) return cachedFrames[index]
        cachedRevision[index] = revision
        cachedFrames[index] = null

        val target = file(context, slot)
        if (!target.isFile) return null

        return try {
            DataInputStream(BufferedInputStream(FileInputStream(target))).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                    Log.w(TAG, "Unsupported media cache")
                    return null
                }
                val width = input.readInt()
                val height = input.readInt()
                val fps = input.readInt() / 1000f
                val count = input.readInt()
                if (width <= 0 || height <= 0 ||
                    width > MAX_SIDE_READ || height > MAX_SIDE_READ ||
                    count !in 1..MAX_FRAME_COUNT_READ
                ) {
                    Log.w(TAG, "Invalid media cache header")
                    return null
                }
                val frameSize = width * height
                val frames = Array(count) { ByteArray(frameSize) }
                for (frame in frames) {
                    input.readFully(frame)
                }
                FrameSet(width, height, fps.coerceIn(0.05f, 30f), frames)
                    .also { cachedFrames[index] = it }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot load media cache", t)
            null
        }
    }

    /**
     * Loads a slot as a frame set even when the source is a still photo. The
     * lock/home style needs both sides in the same shape so the transition
     * code does not care whether the user picked a JPEG or a video.
     */
    @Synchronized
    fun loadFrameSet(
        context: Context,
        revision: Int,
        animated: Boolean,
        slot: MediaSlot
    ): FrameSet? {
        if (animated) return load(context, revision, slot)
        val bitmap = PhotoAsciiStore.load(context, revision, slot) ?: return null
        return FrameSet(
            width = bitmap.width,
            height = bitmap.height,
            fps = 1f,
            frames = arrayOf(bitmapToGray(bitmap))
        )
    }

    private fun installAnimated(
        context: Context,
        frameSet: FrameSet,
        slot: MediaSlot
    ): ImportResult? {
        if (frameSet.frames.isEmpty()) return null
        val target = file(context, slot)
        val temporary = File(context.filesDir, "${fileName(slot)}.tmp")

        return try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(frameSet.width)
                output.writeInt(frameSet.height)
                output.writeInt((frameSet.fps * 1000f).roundToInt())
                output.writeInt(frameSet.frames.size)
                for (frame in frameSet.frames) output.write(frame)
                output.flush()
            }
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "Cannot replace media cache")
                return null
            }
            if (!temporary.renameTo(target)) {
                Log.w(TAG, "Cannot install media cache")
                return null
            }

            PhotoAsciiStore.clear(context, slot)

            val revision = (System.currentTimeMillis() and Int.MAX_VALUE.toLong())
                .toInt()
                .coerceAtLeast(1)
            val index = slot.ordinal
            cachedRevision[index] = revision
            cachedFrames[index] = frameSet
            ImportResult.Animated(revision)
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot write media cache", t)
            null
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun fileName(slot: MediaSlot): String = when (slot) {
        MediaSlot.MAIN -> FILE_NAME
        MediaSlot.LOCK -> "media_frames_lock.bin"
        MediaSlot.HOME -> "media_frames_home.bin"
    }

    private fun extractGif(context: Context, uri: Uri): FrameSet? {
        val temporary = File(context.cacheDir, "ascii_import_${System.nanoTime()}.gif")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            } ?: return null

            val movie = Movie.decodeFile(temporary.absolutePath) ?: return null
            val sourceWidth = movie.width().coerceAtLeast(1)
            val sourceHeight = movie.height().coerceAtLeast(1)
            val durationMs = movie.duration()
            if (durationMs <= 0) return null

            val plan = framePlan(durationMs.toLong(), context)
            val geometry = targetGeometry(
                context,
                sourceWidth,
                sourceHeight,
                plan.longSide
            )
            val width = geometry.first
            val height = geometry.second

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val scaleX = width.toFloat() / sourceWidth
            val scaleY = height.toFloat() / sourceHeight
            val frames = ArrayList<ByteArray>(plan.count)
            val lastTimeMs = (durationMs - 1L).coerceAtLeast(0L)

            for (index in 0 until plan.count) {
                // Include the final frame too. Sampling only the first N
                // seconds was the old behaviour and made long clips stop.
                val timeMs = if (plan.count <= 1) 0 else {
                    (index.toLong() * lastTimeMs / (plan.count - 1))
                        .coerceAtLeast(0L)
                        .toInt()
                }
                movie.setTime(timeMs)
                canvas.drawColor(Color.BLACK)
                canvas.save()
                canvas.scale(scaleX, scaleY)
                movie.draw(canvas, 0f, 0f)
                canvas.restore()
                frames.add(bitmapToGray(bitmap))
            }
            bitmap.recycle()
            normalizeFrames(frames)
            FrameSet(width, height, plan.fps, frames.toTypedArray())
        } catch (t: Throwable) {
            Log.w(TAG, "GIF import failed", t)
            null
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun extractVideo(context: Context, uri: Uri): FrameSet? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val sourceWidth = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val sourceHeight = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            if (sourceWidth <= 0 || sourceHeight <= 0) return null
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
            val durationMs = (retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 3000L).coerceAtLeast(250L)
            val plan = framePlan(durationMs, context)

            val displayWidth = if (rotation == 90 || rotation == 270) {
                sourceHeight
            } else {
                sourceWidth
            }
            val displayHeight = if (rotation == 90 || rotation == 270) {
                sourceWidth
            } else {
                sourceHeight
            }
            val geometry = targetGeometry(
                context,
                displayWidth,
                displayHeight,
                plan.longSide
            )
            val width = geometry.first
            val height = geometry.second
            val rawTargetWidth = if (rotation == 90 || rotation == 270) height else width
            val rawTargetHeight = if (rotation == 90 || rotation == 270) width else height

            val count = plan.count
            val durationUs = (durationMs * 1000L).coerceAtLeast(1L)
            val lastTimeUs = (durationUs - 1L).coerceAtLeast(0L)
            val frames = ArrayList<ByteArray>(count)
            val matrix = Matrix().apply {
                if (rotation != 0) postRotate(rotation.toFloat())
            }

            for (index in 0 until count) {
                // Span the complete source duration. The final sample is at
                // the end of the clip, so a long gallery video is not cut off
                // after its first six seconds any more.
                val timeUs = if (count <= 1) 0L else {
                    (index.toLong() * lastTimeUs / (count - 1))
                        .coerceAtLeast(0L)
                }
                val raw = scaledVideoFrame(
                    retriever,
                    timeUs,
                    rawTargetWidth,
                    rawTargetHeight
                )
                if (raw == null) {
                    // Duplicate the previous frame instead of collapsing the
                    // whole clip if one timestamp is not seekable.
                    frames.getOrNull(frames.lastIndex)?.let {
                        frames.add(it.copyOf())
                    }
                    continue
                }

                val oriented = if (rotation != 0) {
                    Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                        .also { if (it !== raw) raw.recycle() }
                } else {
                    raw
                }
                val scaled = if (oriented.width != width || oriented.height != height) {
                    Bitmap.createScaledBitmap(oriented, width, height, true)
                        .also { if (it !== oriented) oriented.recycle() }
                } else {
                    oriented
                }
                frames.add(bitmapToGray(scaled))
                scaled.recycle()
            }

            if (frames.isEmpty()) return null
            while (frames.size < count) {
                frames.add(frames.last().copyOf())
            }
            normalizeFrames(frames)
            FrameSet(width, height, plan.fps, frames.toTypedArray())
        } catch (t: Throwable) {
            Log.w(TAG, "Video import failed", t)
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
                Unit
            }
        }
    }

    /**
     * MediaMetadataRetriever has two scaling paths. `getScaledFrameAtTime`
     * exists from API 27 but some OEM decoders return null for it, so fall
     * back to the unscaled frame and scale that. Hardware-backed frames are
     * copied to ARGB_8888 because `getPixels` cannot read them directly.
     */
    private fun scaledVideoFrame(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        var frame: Bitmap? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // Use reflection so the API 19 Dalvik verifier never has to
            // resolve this API 27 method. On 27+ it is a normal call.
            frame = try {
                val method = MediaMetadataRetriever::class.java.getMethod(
                    "getScaledFrameAtTime",
                    java.lang.Long.TYPE,
                    java.lang.Integer.TYPE,
                    java.lang.Integer.TYPE,
                    java.lang.Integer.TYPE
                )
                method.invoke(
                    retriever,
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    targetWidth,
                    targetHeight
                ) as? Bitmap
            } catch (t: Throwable) {
                Log.w(TAG, "Scaled video frame failed at $timeUs us", t)
                null
            }
        }
        if (frame == null) {
            val source = try {
                retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Video frame failed at $timeUs us", t)
                null
            } ?: return null
            frame = if (source.width == targetWidth && source.height == targetHeight) {
                source
            } else {
                try {
                    Bitmap.createScaledBitmap(
                        source, targetWidth, targetHeight, true
                    ).also { if (it !== source) source.recycle() }
                } catch (t: Throwable) {
                    source.recycle()
                    Log.w(TAG, "Video frame scale failed at $timeUs us", t)
                    return null
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            frame.config.name == "HARDWARE"
        ) {
            val software = try {
                frame.copy(Bitmap.Config.ARGB_8888, false)
            } catch (_: Throwable) {
                null
            }
            if (software == null) {
                frame.recycle()
                return null
            }
            frame.recycle()
            return software
        }
        return frame
    }

    /**
     * Chooses a frame budget for the whole clip. We always target 12 fps for
     * short clips, then reduce the sampling rate so that even a multi-minute
     * video fits in memory. `fps` is derived from the actual frame count, so
     * playback lasts exactly as long as the source.
     */
    private fun framePlan(durationMs: Long, context: Context): FramePlan {
        val durationSeconds = (durationMs.coerceAtLeast(250L) / 1000f)
            .coerceAtLeast(0.25f)
        val lowRam = isLowRam(context)
        val maxFrames = if (lowRam) MAX_LOW_RAM_FRAMES else MAX_FRAMES
        val count = (durationSeconds * TARGET_FPS)
            .roundToInt()
            .coerceIn(2, maxFrames)
        val fps = (count / durationSeconds).coerceIn(0.05f, 30f)
        val longSide = when {
            lowRam && count > 360 -> 240
            lowRam -> LOW_RAM_LONG_SIDE
            count > 480 -> 288
            count > 240 -> 360
            count > 120 -> 480
            else -> MAX_LONG_SIDE
        }
        return FramePlan(count, fps, longSide)
    }

    private fun isLowRam(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
            as? ActivityManager
        return activityManager?.isLowRamDevice == true
    }

    private fun targetGeometry(
        context: Context,
        sourceWidth: Int,
        sourceHeight: Int,
        maxSideOverride: Int? = null
    ): Pair<Int, Int> {
        val lowRam = isLowRam(context)
        val maxSide = maxSideOverride ?: if (lowRam) {
            LOW_RAM_LONG_SIDE
        } else {
            MAX_LONG_SIDE
        }
        val longSide = max(sourceWidth, sourceHeight)
        val scale = if (longSide > maxSide) {
            maxSide.toFloat() / longSide
        } else {
            1f
        }
        return Pair(
            (sourceWidth * scale).roundToInt().coerceAtLeast(2),
            (sourceHeight * scale).roundToInt().coerceAtLeast(2)
        )
    }

    private fun bitmapToGray(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val output = ByteArray(pixels.size)
        for (index in pixels.indices) {
            val color = pixels[index]
            val alpha = (color ushr 24) and 0xFF
            val red = (((color ushr 16) and 0xFF) * alpha) / 255
            val green = (((color ushr 8) and 0xFF) * alpha) / 255
            val blue = ((color and 0xFF) * alpha) / 255
            output[index] = ((red * 54 + green * 183 + blue * 19) ushr 8)
                .coerceIn(0, 255)
                .toByte()
        }
        return output
    }

    /**
     * Applies one shared contrast curve to the whole clip. Per-frame
     * auto-contrast makes video flicker because every frame gets a different
     * black/white point.
     */
    private fun normalizeFrames(frames: List<ByteArray>) {
        if (frames.isEmpty()) return
        val histogram = IntArray(256)
        val step = (frames.size / 8).coerceAtLeast(1)
        var sampled = 0
        var index = 0
        while (index < frames.size) {
            val frame = frames[index]
            for (value in frame) histogram[value.toInt() and 0xFF]++
            sampled += frame.size
            index += step
        }
        if (sampled <= 0) return
        val low = percentile(histogram, sampled, 0.01f)
        val high = percentile(histogram, sampled, 0.99f)
        val span = high - low
        if (span < 16) return

        for (frame in frames) {
            for (i in frame.indices) {
                val value = frame[i].toInt() and 0xFF
                val stretched = (((value - low).coerceIn(0, span) * 255) + span / 2) / span
                frame[i] = stretched.coerceIn(0, 255).toByte()
            }
        }
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Float): Int {
        val target = (total * fraction).roundToInt().coerceIn(0, total - 1)
        var seen = 0
        for (value in 0..255) {
            seen += histogram[value]
            if (seen > target) return value
        }
        return 255
    }
}
