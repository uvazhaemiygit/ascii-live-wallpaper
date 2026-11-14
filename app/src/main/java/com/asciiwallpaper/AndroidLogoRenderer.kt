package com.asciiwallpaper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import kotlin.math.min

/**
 * Standalone, large rendering of the same Android ASCII logo used by the
 * fastfetch screen. The previous implementation built a different, blocky 3D
 * robot; this path draws the recognisable fastfetch artwork directly.
 */
class AndroidLogoRenderer(context: Context?) {
    companion object {
        private const val TAG = "AndroidLogoRenderer"
        private const val FONT_ASSET = "fonts/JetBrainsMono-Regular.ttf"
        private const val CHAR_ASPECT = 1.58f
        private const val SIDE_MARGIN = 0.04f
        private const val VERTICAL_MARGIN = 0.07f
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
        typeface = this@AndroidLogoRenderer.typeface
        isAntiAlias = true
        isDither = false
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        glyphColorArgb: Int,
        backgroundColorArgb: Int,
        symbolStyle: SymbolStyle
    ) {
        canvas.drawColor(backgroundColorArgb)
        if (width <= 1 || height <= 1) return

        val availableWidth = width * (1f - SIDE_MARGIN * 2f)
        val availableHeight = height * (1f - VERTICAL_MARGIN * 2f)
        val cellWidth = min(
            availableWidth / AndroidAsciiArt.WIDTH,
            availableHeight / (AndroidAsciiArt.HEIGHT * CHAR_ASPECT)
        ).coerceAtLeast(1f)
        val lineHeight = cellWidth * CHAR_ASPECT
        val artWidth = cellWidth * AndroidAsciiArt.WIDTH
        val artHeight = lineHeight * AndroidAsciiArt.HEIGHT
        val left = (width - artWidth) * 0.5f
        val top = (height - artHeight) * 0.5f

        if (symbolStyle == SymbolStyle.NOTHING) {
            drawDots(canvas, left, top, cellWidth, lineHeight, glyphColorArgb)
            return
        }

        val textSize = (lineHeight * 0.80f).coerceAtLeast(1f)
        textPaint.textSize = textSize
        textPaint.color = glyphColorArgb
        textPaint.textScaleX = 1f
        val measuredAdvance = textPaint.measureText("M").coerceAtLeast(0.001f)
        textPaint.textScaleX = (cellWidth / measuredAdvance).coerceIn(0.35f, 3f)
        val fontMetrics = textPaint.fontMetrics
        val baselineShift = (lineHeight - (fontMetrics.descent - fontMetrics.ascent)) *
            0.5f - fontMetrics.ascent

        var baseline = top + baselineShift
        for (line in AndroidAsciiArt.LINES) {
            canvas.drawText(line, left, baseline, textPaint)
            baseline += lineHeight
        }
    }

    private fun drawDots(
        canvas: Canvas,
        left: Float,
        top: Float,
        cellWidth: Float,
        lineHeight: Float,
        glyphColorArgb: Int
    ) {
        dotPaint.color = glyphColorArgb
        val radius = min(cellWidth, lineHeight) * 0.30f
        for (row in AndroidAsciiArt.LINES.indices) {
            val line = AndroidAsciiArt.LINES[row]
            val centerY = top + row * lineHeight + lineHeight * 0.5f
            for (col in line.indices) {
                val char = line[col]
                if (char == ' ') continue
                val density = when (char) {
                    '@', '#', 'M', 'W' -> 1f
                    '%', '*', 'N', 'm', 'd' -> 0.86f
                    '+', '=', 'o', 'O', '0', 'y', 'h' -> 0.72f
                    ':', ';' -> 0.52f
                    '.', '\'', '`', '-' -> 0.32f
                    else -> 0.62f
                }
                canvas.drawCircle(
                    left + (col + 0.5f) * cellWidth,
                    centerY,
                    radius * (0.36f + density * 0.64f),
                    dotPaint
                )
            }
        }
    }
}
