package com.asciiwallpaper

import android.app.WallpaperManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Exports one rendered frame as a normal static wallpaper.
 *
 * Nothing OS (and several other launchers) refuses to apply the translucent
 * widget blur while the home wallpaper is live. That is a launcher/system
 * limitation, not something a WallpaperService can override. A static copy
 * restores the blur; the trade-off is that the ASCII animation stops until
 * the user selects the live wallpaper again.
 *
 * Some OEM wallpaper apps (including Nothing's) apply extra wallpaper
 * processing only when an image is chosen through their own picker. Therefore
 * this object also exports the frame to a cache file and provides an intent
 * that opens the system "set wallpaper" flow. That path is the fallback when
 * a direct WallpaperManager call stops the live wallpaper but does not enable
 * the launcher's widget effect.
 */
object StaticWallpaper {
    private const val FLAG_SYSTEM = 1
    private const val FLAG_LOCK = 2
    private const val PNG_MIME = "image/png"
    private const val PNG_DIR = "wallpaper"
    private const val PNG_NAME = "ascii-static-wallpaper.png"

    fun export(context: Context, settings: WallpaperSettings): Result<Unit> {
        val appContext = context.applicationContext
        return runCatching {
            val bitmap = render(appContext, settings)
            try {
                val manager = WallpaperManager.getInstance(appContext)

                // First try both targets in one call. This is important on
                // Nothing OS, where the home and lock screens normally share
                // one wallpaper and the widget effect can stay disabled if
                // only the home screen is replaced.
                var applied = trySetStatic(manager, bitmap, FLAG_SYSTEM or FLAG_LOCK)

                // setBitmap() may leave the WallpaperService attached on a
                // few OEM builds. Clearing first and applying again is the
                // only public-API way to force a live wallpaper to detach.
                if (!applied || manager.wallpaperInfo != null) {
                    clearWallpapers(manager)
                    applied = trySetStatic(manager, bitmap, FLAG_SYSTEM or FLAG_LOCK)
                }

                if (!applied) {
                    error("WallpaperManager rejected the static image")
                }

                // The binder call is synchronous on stock Android, but some
                // OEM launchers publish the new WallpaperInfo a moment later.
                repeat(6) {
                    if (manager.wallpaperInfo == null) return@repeat
                    Thread.sleep(100L)
                }
                if (manager.wallpaperInfo != null) {
                    error(
                        "Live wallpaper is still active; use the system wallpaper picker"
                    )
                }

                applyWallpaperColors(manager, bitmap)
            } finally {
                bitmap.recycle()
            }
        }
    }

    /**
     * Saves the frame as a temporary PNG for the system/OEM wallpaper picker.
     *
     * This is intentionally a separate action from [export]: a wallpaper set
     * through Nothing's own "Wallpaper & style" screen can receive the
     * launcher's glass/blur treatment even when a direct setBitmap() call did
     * not trigger it.
     */
    fun exportPng(context: Context, settings: WallpaperSettings): Result<Unit> {
        val appContext = context.applicationContext
        return runCatching {
            renderToCacheFile(appContext, settings)
        }
    }

    /**
     * Opens the system wallpaper picker with the PNG produced by [exportPng].
     * Must be called after [exportPng] succeeds.
     */
    fun openSystemPicker(context: Context): Result<Unit> {
        val appContext = context.applicationContext
        return runCatching {
            val file = File(File(appContext.cacheDir, PNG_DIR), PNG_NAME)
            check(file.isFile) { "Static wallpaper file is missing" }
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file
            )
            openPickerIntent(appContext, uri)
        }
    }

    /** Deletes a previously exported temporary frame when the UI is done. */
    fun clearExportedFile(context: Context) {
        runCatching {
            File(context.cacheDir, PNG_DIR).deleteRecursively()
        }
    }

    private fun render(context: Context, settings: WallpaperSettings): Bitmap {
        val size = screenSize(context)
        val width = size.x.coerceIn(320, 4096)
        val height = size.y.coerceIn(320, 4096)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val renderer = AsciiRenderer(context)
        try {
            renderer.renderFrame(
                canvas = Canvas(bitmap),
                width = width,
                height = height,
                timeNanos = System.nanoTime(),
                newSettings = settings
            )
        } catch (t: Throwable) {
            bitmap.recycle()
            throw t
        } finally {
            renderer.release()
        }
        return bitmap
    }

    private fun renderToCacheFile(
        context: Context,
        settings: WallpaperSettings
    ): File {
        val bitmap = render(context, settings)
        try {
            val dir = File(context.cacheDir, PNG_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                error("Cannot create cache directory")
            }
            val file = File(dir, PNG_NAME)
            FileOutputStream(file).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    error("Cannot encode PNG")
                }
            }
            return file
        } finally {
            bitmap.recycle()
        }
    }

    private fun trySetStatic(
        manager: WallpaperManager,
        bitmap: Bitmap,
        which: Int
    ): Boolean {
        if (Build.VERSION.SDK_INT >= 24) {
            // Keep the four-argument method behind reflection: it does not
            // exist on Android 4.4, while this source file is also loaded by
            // the API 19 build.
            try {
                val method = WallpaperManager::class.java.getMethod(
                    "setBitmap",
                    Bitmap::class.java,
                    Rect::class.java,
                    java.lang.Boolean.TYPE,
                    java.lang.Integer.TYPE
                )
                method.invoke(manager, bitmap, null, true, which)
                return true
            } catch (_: Throwable) {
                // Some OEM implementations reject FLAG_SYSTEM | FLAG_LOCK.
                // Try each target separately before falling back to the
                // deprecated no-flag method.
            }

            if (which and FLAG_SYSTEM != 0 &&
                invokeSetBitmap(manager, bitmap, FLAG_SYSTEM)
            ) {
                if (which and FLAG_LOCK == 0 ||
                    invokeSetBitmap(manager, bitmap, FLAG_LOCK)
                ) {
                    return true
                }
            }
            if (which and FLAG_LOCK != 0 &&
                invokeSetBitmap(manager, bitmap, FLAG_LOCK)
            ) {
                return true
            }
        }

        return try {
            @Suppress("DEPRECATION")
            manager.setBitmap(bitmap)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun invokeSetBitmap(
        manager: WallpaperManager,
        bitmap: Bitmap,
        which: Int
    ): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        return try {
            val method = WallpaperManager::class.java.getMethod(
                "setBitmap",
                Bitmap::class.java,
                Rect::class.java,
                java.lang.Boolean.TYPE,
                java.lang.Integer.TYPE
            )
            method.invoke(manager, bitmap, null, true, which)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun clearWallpapers(manager: WallpaperManager) {
        try {
            // clear() is available since API 1. On API 24+ it may clear both
            // the home and lock screen; either way it detaches the live
            // wallpaper before the static bitmap is applied again.
            @Suppress("DEPRECATION")
            manager.clear()
        } catch (_: Throwable) {
            // If clearing is not permitted, the second setBitmap attempt may
            // still succeed; the caller verifies the final state.
        }
    }

    private fun applyWallpaperColors(manager: WallpaperManager, bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT < 27) return
        try {
            val colorsClass = Class.forName("android.app.WallpaperColors")
            val colors = colorsClass
                .getMethod("fromBitmap", Bitmap::class.java)
                .invoke(null, bitmap)
            val method = WallpaperManager::class.java.getMethod(
                "setWallpaperColors",
                colorsClass,
                java.lang.Integer.TYPE
            )
            method.invoke(manager, colors, FLAG_SYSTEM or FLAG_LOCK)
        } catch (_: Throwable) {
            // The static wallpaper itself is already installed. Color
            // extraction is best-effort because some OEMs hide this method.
        }
    }

    private fun openPickerIntent(context: Context, uri: Uri) {
        val grant = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val clip = ClipData.newRawUri("ASCII wallpaper", uri)

        val cropIntent = Intent(WallpaperManager.ACTION_CROP_AND_SET_WALLPAPER).apply {
            setDataAndType(uri, PNG_MIME)
            putExtra("mimeType", PNG_MIME)
            addFlags(grant or Intent.FLAG_ACTIVITY_NEW_TASK)
            this.clipData = clip
        }

        val attachIntent = Intent(Intent.ACTION_ATTACH_DATA).apply {
            setDataAndType(uri, PNG_MIME)
            putExtra("mimeType", PNG_MIME)
            addFlags(grant or Intent.FLAG_ACTIVITY_NEW_TASK)
            this.clipData = clip
        }

        val target = when {
            cropIntent.resolveActivity(context.packageManager) != null -> cropIntent
            attachIntent.resolveActivity(context.packageManager) != null -> attachIntent
            else -> Intent(Intent.ACTION_SEND).apply {
                type = PNG_MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(grant or Intent.FLAG_ACTIVITY_NEW_TASK)
                this.clipData = clip
            }
        }

        try {
            context.startActivity(
                Intent.createChooser(
                    target,
                    context.getString(R.string.static_wallpaper_picker_title)
                ).apply {
                    addFlags(grant or Intent.FLAG_ACTIVITY_NEW_TASK)
                    clipData = clip
                }
            )
        } catch (t: Throwable) {
            error("No system wallpaper picker found: ${t.message}")
        }
    }

    private fun screenSize(context: Context): Point {
        val manager = context.getSystemService(Context.WINDOW_SERVICE)
            as? WindowManager
            ?: return Point(1080, 1920)
        val point = Point()
        val display = manager.defaultDisplay
        try {
            display.getRealSize(point)
        } catch (_: Throwable) {
            @Suppress("DEPRECATION")
            display.getSize(point)
        }
        if (point.x <= 0 || point.y <= 0) {
            point.set(1080, 1920)
        }
        return point
    }
}
