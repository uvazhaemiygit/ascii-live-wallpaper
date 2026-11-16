package com.asciiwallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager

/**
 * Exports one rendered frame as a normal static wallpaper.
 *
 * Nothing OS (and several other launchers) refuses to apply the translucent
 * widget blur while the home wallpaper is live. That is a launcher/system
 * limitation, not something a WallpaperService can override. A static copy
 * restores the blur; the trade-off is that the ASCII animation stops until
 * the user selects the live wallpaper again.
 */
object StaticWallpaper {
    fun export(context: Context, settings: WallpaperSettings): Result<Unit> {
        val appContext = context.applicationContext
        val size = screenSize(appContext)
        val width = size.x.coerceIn(320, 4096)
        val height = size.y.coerceIn(320, 4096)
        var bitmap: Bitmap? = null
        val renderer = AsciiRenderer(appContext)
        return try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            renderer.renderFrame(
                canvas = Canvas(bitmap),
                width = width,
                height = height,
                timeNanos = System.nanoTime(),
                newSettings = settings
            )
            val manager = WallpaperManager.getInstance(appContext)
            if (Build.VERSION.SDK_INT >= 24) {
                try {
                    val method = manager.javaClass.getMethod(
                        "setBitmap",
                        Bitmap::class.java,
                        Rect::class.java,
                        java.lang.Boolean.TYPE,
                        java.lang.Integer.TYPE
                    )
                    val flag = manager.javaClass
                        .getField("FLAG_SYSTEM")
                        .getInt(null)
                    method.invoke(manager, bitmap, null, true, flag)
                } catch (_: Throwable) {
                    // The old API still exists on API 24+ and sets the system
                    // wallpaper; it just cannot choose the lock/home target.
                    @Suppress("DEPRECATION")
                    manager.setBitmap(bitmap)
                }
            } else {
                @Suppress("DEPRECATION")
                manager.setBitmap(bitmap)
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            renderer.release()
            bitmap?.recycle()
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
