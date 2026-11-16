package com.asciiwallpaper

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Automatic render budget for low-end devices.
 *
 * The wallpaper is a software Canvas renderer. A 2016 Snapdragon 425 can draw
 * a 60 fps ASCII grid, but doing so at 96-200 columns leaves too little CPU
 * headroom for the launcher. In Auto mode the wallpaper keeps its shape and
 * motion but caps the grid and cadence to values that stay smooth on a
 * four-core Cortex-A53 class SoC.
 *
 * The user's stored values are never overwritten. The cap is applied only
 * when frames are handed to the renderer, so choosing 200 columns / 120 fps
 * still works on a fast phone and can be restored by turning Auto off.
 */
object PerformancePolicy {
    const val LOW_END_COLUMNS = 72
    const val LOW_END_FPS = 30
    const val LEGACY_COLUMNS = 64
    const val LEGACY_FPS = 30

    private const val LOW_CPU_KHZ = 1_800_000L

    @Volatile
    private var cachedLowEnd: Boolean? = null

    fun isLowEnd(context: Context): Boolean {
        cachedLowEnd?.let { return it }
        return synchronized(this) {
            cachedLowEnd?.let { return it }
            val detected = detectLowEnd(context.applicationContext)
            cachedLowEnd = detected
            detected
        }
    }

    fun effective(settings: WallpaperSettings, context: Context): WallpaperSettings {
        if (!settings.autoPerformanceEnabled) return settings
        if (!isLowEnd(context)) return settings
        val columns = if (Build.VERSION.SDK_INT <= 19) {
            LEGACY_COLUMNS
        } else {
            LOW_END_COLUMNS
        }
        val fps = if (Build.VERSION.SDK_INT <= 19) {
            LEGACY_FPS
        } else {
            LOW_END_FPS
        }
        return settings.copy(
            columns = settings.columns.coerceAtMost(columns),
            targetFps = settings.targetFps.coerceAtMost(fps)
        )
    }

    fun isCapped(settings: WallpaperSettings, context: Context): Boolean =
        effective(settings, context) != settings

    private fun detectLowEnd(context: Context): Boolean {
        // Android 4.4 hardware is far below the current baseline. Keeping the
        // legacy build at 30 fps / 64 columns also avoids first-frame jank on
        // devices with only 512 MB-1 GB of RAM.
        if (Build.VERSION.SDK_INT <= 19) return true

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
            as? ActivityManager
        if (activityManager?.isLowRamDevice == true) return true

        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val maxKhz = readMaxCpuKhz()
        if (cores <= 4 && maxKhz in 1L..LOW_CPU_KHZ) return true

        val maxHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
        if (cores <= 4 && maxHeapMb <= 256L) return true

        // Some Android 7-9 devices do not expose cpufreq to third-party apps.
        // Match the known low-end Qualcomm SoCs as a conservative fallback.
        val hardware = buildString {
            append(Build.HARDWARE.lowercase())
            append(' ')
            append(Build.BOARD.lowercase())
            if (Build.VERSION.SDK_INT >= 31) {
                // Reflective access keeps the API 19 class verifier away from
                // the API 31 Build.SOC_MODEL field.
                val soc = try {
                    Build::class.java.getField("SOC_MODEL").get(null) as? String
                } catch (_: Throwable) {
                    null
                }
                if (!soc.isNullOrBlank()) {
                    append(' ')
                    append(soc.lowercase())
                }
            }
        }
        return LOW_END_SOC_MARKERS.any { hardware.contains(it) }
    }

    private fun readMaxCpuKhz(): Long {
        for (path in CPU_MAX_FREQ_PATHS) {
            try {
                val value = File(path).readText().trim().toLongOrNull() ?: continue
                if (value > 0L) return value
            } catch (_: Throwable) {
                // Fall through to the next path or to heap/SoC detection.
            }
        }
        return -1L
    }

    private val CPU_MAX_FREQ_PATHS = listOf(
        "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq",
        "/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq"
    )

    /**
     * Snapdragon 425/430/435 and closely related 2016-2017 parts. The CPU
     * frequency check above catches most of them; this list is a fallback for
     * kernels that hide cpufreq.
     */
    private val LOW_END_SOC_MARKERS = listOf(
        "msm8917", // Snapdragon 425
        "msm8937", // Snapdragon 430
        "msm8940", // Snapdragon 435
        "msm8952", // Snapdragon 617
        "sdm429",  // Snapdragon 429
        "sdm439"   // Snapdragon 439
    )
}
