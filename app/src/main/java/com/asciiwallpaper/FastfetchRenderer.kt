package com.asciiwallpaper

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fastfetch-inspired information screen.
 *
 * This is the one style that deliberately bypasses the luminance-to-glyph
 * filter. System information has to stay readable at normal viewing distance,
 * so it is drawn directly with the bundled monospace font. The Android robot
 * artwork still follows the selected ASCII/Nothing symbol mode.
 */
class FastfetchRenderer(private val context: Context?) {
    companion object {
        private const val TAG = "FastfetchRenderer"
        private const val FONT_ASSET = "fonts/JetBrainsMono-Regular.ttf"
        private const val CACHE_MS = 12_000L
        // Text starts at the same inset on both sides. The previous layout
        // started at 8% but still allowed a full-width line, clipping the
        // right end of long kernel lines.
        private const val SIDE_MARGIN = 0.02f
        private val ART = arrayOf(
            "          .-'''-.          ",
            "         /  .-.  \\         ",
            "        |  (o o)  |        ",
            "        |   \\_/   |        ",
            "         \\  ___  /         ",
            "          '-...-'          ",
            "       .-'''     '''-.     ",
            "      /  .-.     .-.  \\    ",
            "     |  (   )   (   )  |   ",
            "      \\  '-'     '-'  /    ",
            "       '--.       .--'     ",
            "          /_/   \\_\\        ",
            "         /_/     \\_\\       "
        )
    }

    private val typeface: Typeface = if (context != null) {
        try {
            Typeface.createFromAsset(context.assets, FONT_ASSET)
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot load bundled font", t)
            Typeface.MONOSPACE
        }
    } else {
        Typeface.MONOSPACE
    }

    private val textPaint = Paint().apply {
        typeface = this@FastfetchRenderer.typeface
        isAntiAlias = true
        isDither = false
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val separatorPaint = Paint().apply {
        typeface = this@FastfetchRenderer.typeface
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private var cachedInfoAt = 0L
    private var cachedCharging = false
    private var cachedInfo: Array<String> = emptyArray()

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        columns: Int,
        glyphColorArgb: Int,
        backgroundColorArgb: Int,
        symbolStyle: SymbolStyle,
        charging: Boolean
    ) {
        canvas.drawColor(backgroundColorArgb)
        val info = infoLines(charging)
        // A tall phone at 72+ columns would make the text physically tiny
        // (about 7sp on a 1080px-wide display). Keep the info block readable;
        // the user can still choose the lower density for even larger text.
        val requested = columns.coerceIn(40, 48)
        val totalLines = ART.size + info.size + 2
        val contentWidth = width.toFloat() * (1f - SIDE_MARGIN * 2f)
        val left = width * SIDE_MARGIN
        var gridColumns = requested
        var cellWidth = contentWidth / gridColumns
        var lineHeight = cellWidth * 1.58f
        while (lineHeight * (totalLines + 1) > height * 0.90f &&
            gridColumns < 96
        ) {
            gridColumns += 2
            cellWidth = contentWidth / gridColumns
            lineHeight = cellWidth * 1.58f
        }

        val textSize = (lineHeight * 0.80f).coerceAtLeast(7f)
        textPaint.textSize = textSize
        textPaint.color = glyphColorArgb
        // measureText() includes the current textScaleX. Reset it first or the
        // horizontal scale would alternate between the desired value and 1.0
        // on every frame.
        textPaint.textScaleX = 1f
        val measuredAdvance = textPaint.measureText("M").coerceAtLeast(0.001f)
        textPaint.textScaleX = (cellWidth / measuredAdvance).coerceIn(0.5f, 2.5f)
        val fontMetrics = textPaint.fontMetrics
        val baselineShift = (lineHeight - (fontMetrics.descent - fontMetrics.ascent)) *
            0.5f - fontMetrics.ascent

        val contentHeight = (totalLines - 1) * lineHeight
        var baseline = max(height * 0.06f, (height - contentHeight) * 0.5f) +
            baselineShift
        val artX = left + contentWidth * 0.5f -
            (ART.maxOf { it.length } * cellWidth) * 0.5f

        for (line in ART) {
            if (symbolStyle == SymbolStyle.NOTHING) {
                drawArtDots(canvas, line, artX, baseline, cellWidth, lineHeight, glyphColorArgb)
            } else {
                canvas.drawText(line, artX, baseline, textPaint)
            }
            baseline += lineHeight
        }

        baseline += lineHeight * 0.25f
        val title = fit("ANDROID // FASTFETCH", gridColumns)
        canvas.drawText(title, left, baseline, textPaint)
        baseline += lineHeight
        val rule = fit("-".repeat(gridColumns.coerceAtMost(64)), gridColumns)
        separatorPaint.textSize = textSize
        separatorPaint.textScaleX = textPaint.textScaleX
        separatorPaint.color = glyphColorArgb
        canvas.drawText(rule, left, baseline, separatorPaint)
        baseline += lineHeight

        for (line in info) {
            canvas.drawText(fit(line, gridColumns), left, baseline, textPaint)
            baseline += lineHeight
        }
    }

    private fun drawArtDots(
        canvas: Canvas,
        line: String,
        x: Float,
        baseline: Float,
        cellWidth: Float,
        lineHeight: Float,
        glyphColorArgb: Int
    ) {
        dotPaint.color = glyphColorArgb
        val radius = min(cellWidth, lineHeight) * 0.24f
        val centerY = baseline - lineHeight * 0.36f
        for (index in line.indices) {
            val char = line[index]
            if (char == ' ') continue
            val density = when (char) {
                '@', '#' -> 1f
                '%', '*' -> 0.84f
                '+', '=', 'o', 'O', '0' -> 0.72f
                ':', ';' -> 0.56f
                '.', '\'' -> 0.34f
                else -> 0.64f
            }
            dotPaint.strokeWidth = 0f
            canvas.drawCircle(
                x + (index + 0.5f) * cellWidth,
                centerY,
                radius * (0.38f + density * 0.62f),
                dotPaint
            )
        }
    }

    private fun infoLines(charging: Boolean): Array<String> {
        val now = SystemClock.elapsedRealtime()
        if (cachedInfo.isNotEmpty() &&
            now - cachedInfoAt < CACHE_MS &&
            charging == cachedCharging
        ) {
            return cachedInfo
        }
        val ctx = context
        val info = ArrayList<String>(10)
        val brand = Build.MANUFACTURER.orEmpty()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val model = Build.MODEL.orEmpty()
        info += "HOST     ${trim(brand + " " + model, 30)}"
        info += "SYSTEM   Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        info += "KERNEL   ${trim("Linux " + System.getProperty("os.version").orEmpty(), 38)}"
        info += "CPU      ${trim(Build.HARDWARE, 24)} · " +
            "${Runtime.getRuntime().availableProcessors()} cores"
        if (ctx != null) {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memory = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memory)
            val total = memory.totalMem.toDouble()
            val available = memory.availMem.toDouble()
            if (total > 0.0) {
                info += "MEMORY   ${gib(total - available)} / ${gib(total)} GB"
            }
            val metrics = ctx.resources.displayMetrics
            info += "DISPLAY  ${metrics.widthPixels}x${metrics.heightPixels} @ " +
                "${metrics.densityDpi}dpi"
            try {
                val stat = StatFs(Environment.getDataDirectory().absolutePath)
                val totalBytes = stat.totalBytes.toDouble()
                val freeBytes = stat.availableBytes.toDouble()
                info += "STORAGE  ${gib(totalBytes - freeBytes)} / ${gib(totalBytes)} GB"
            } catch (_: Throwable) {
                Unit
            }
            val battery = batteryLine(ctx, charging)
            if (battery != null) info += battery
        }
        info += "UPTIME   ${uptime()}"
        cachedInfo = info.toTypedArray()
        cachedInfoAt = now
        cachedCharging = charging
        return cachedInfo
    }

    private fun batteryLine(ctx: Context, charging: Boolean): String? {
        val intent = try {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) {
            null
        } ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val percent = (level * 100f / scale).roundToInt()
        val status = if (charging) "charging" else "discharging"
        return "BATTERY  $percent% · $status"
    }

    private fun uptime(): String {
        val totalMinutes = SystemClock.elapsedRealtime() / 60_000L
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes / 60) % 24
        val minutes = totalMinutes % 60
        return if (days > 0) {
            "${days}d ${hours}h ${minutes}m"
        } else {
            "${hours}h ${minutes}m"
        }
    }

    private fun gib(bytes: Double): String {
        if (bytes <= 0.0) return "0.0"
        return String.format(java.util.Locale.US, "%.1f", bytes / 1_073_741_824.0)
    }

    private fun trim(value: String, maxLength: Int): String =
        if (value.length <= maxLength) value else value.take(maxLength - 1) + "…"

    private fun fit(value: String, maxLength: Int): String =
        if (value.length <= maxLength) value else value.take(maxLength)
}
