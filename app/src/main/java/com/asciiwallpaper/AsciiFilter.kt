package com.asciiwallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Превращает маленький offscreen-рендер сцены в сетку знаков.
 *
 * Алгоритм:
 *  1. изображение уже нарисовано в отдельный Bitmap размером примерно
 *     2–3 сэмпла на будущую ячейку;
 *  2. каждая ячейка символа усредняется по прямоугольнику пикселей;
 *  3. средняя яркость проходит через гамма-кривую и LUT яркость → символ;
 *  4. ASCII-режим рисует весь кадр с единым горизонтальным масштабом, а
 *     режим Nothing — точки разного диаметра, сгруппированные по размеру.
 *
 * Встроенный JetBrains Mono убирает зависимость от OEM-шрифта: все глифы
 * рампы имеют одинаковый advance, поэтому целый ряд рисуется одним drawText,
 * а не отдельными позициями для каждого знака.
 */
class AsciiFilter(context: Context? = null) {
    companion object {
        const val RAMP = " .:-=+*#%@"
        const val MIN_RENDER_COLUMNS = 4
        private const val CHAR_ASPECT = 1.58f
        private const val DOT_LEVELS = 10
        private const val TAG = "AsciiFilter"
        private const val FONT_ASSET = "fonts/JetBrainsMono-Regular.ttf"
    }

    private val glyphTypeface: Typeface = loadTypeface(context)

    private val glyphPaint = Paint().apply {
        typeface = glyphTypeface
        isAntiAlias = false
        isDither = false
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
        isDither = false
    }

    private val ramp = RAMP.toCharArray()
    private val rampLut = CharArray(256)
    private val photoRampLut = CharArray(256)
    private val dotLut = ByteArray(256)
    private val photoDotLut = ByteArray(256)

    private var columns = 0
    private var rows = 0
    private var configuredWidth = -1
    private var configuredHeight = -1
    private var cellWidth = 1f
    private var cellHeight = 1f
    private var baselineOffset = 0f
    private var blockScaleX = 1f
    private var glyphAdvance = 1f
    private var charBuffer = CharArray(0)
    private var rowHasInk = BooleanArray(0)
    private var scenePixels = IntArray(0)

    // The final ASCII/Nothing grid is one pixel per character cell. Scaling
    // the scene into this tiny bitmap lets Canvas do the expensive box filter
    // in native code instead of walking every offscreen pixel in Kotlin on
    // each frame. At 120 fps this is the difference between a slideshow and
    // a smooth animation on a phone.
    private var gridBitmap: Bitmap? = null
    private var gridCanvas: Canvas? = null
    private var gridPixels = IntArray(0)
    private val gridRect = Rect()
    private val gridPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
        isDither = false
    }

    private val dotPositions = Array(DOT_LEVELS) { FloatArray(0) }
    private val dotCounts = IntArray(DOT_LEVELS)

    init {
        for (value in 0..255) {
            val normalized = value / 255f
            val level = (sqrt(normalized) * (ramp.size - 1)).toInt()
            rampLut[value] = if (value < 4) {
                ' '
            } else {
                ramp[level.coerceIn(1, ramp.size - 1)]
            }

            val photoLevel = (normalized * (ramp.size - 1) + 0.5f).toInt()
            photoRampLut[value] = if (value < 4) {
                ' '
            } else {
                ramp[photoLevel.coerceIn(1, ramp.size - 1)]
            }

            val proceduralDot = if (value < 4) {
                0
            } else {
                (1 + sqrt(normalized) * (DOT_LEVELS - 1))
                    .toInt()
                    .coerceIn(1, DOT_LEVELS - 1)
            }
            dotLut[value] = proceduralDot.toByte()

            val photoDot = if (value < 4) {
                0
            } else {
                (1 + normalized * (DOT_LEVELS - 1))
                    .toInt()
                    .coerceIn(1, DOT_LEVELS - 1)
            }
            photoDotLut[value] = photoDot.toByte()
        }
    }

    private fun loadTypeface(context: Context?): Typeface {
        if (context != null) {
            try {
                return Typeface.createFromAsset(context.assets, FONT_ASSET)
            } catch (t: Throwable) {
                Log.w(TAG, "Cannot load bundled monospace font", t)
            }
        }
        return Typeface.MONOSPACE
    }

    fun configure(viewWidth: Int, viewHeight: Int, requestedColumns: Int) {
        val width = viewWidth.coerceAtLeast(1)
        val height = viewHeight.coerceAtLeast(1)
        val cols = requestedColumns.coerceIn(
            MIN_RENDER_COLUMNS,
            WallpaperSettings.MAX_COLUMNS
        )
        if (width == configuredWidth &&
            height == configuredHeight &&
            cols == columns
        ) {
            return
        }

        configuredWidth = width
        configuredHeight = height
        columns = cols
        cellWidth = width.toFloat() / columns

        // Символ моноширинного шрифта выше, чем шире. Это даёт нормальные
        // пропорции при выводе на экран с любым соотношением сторон.
        val desiredCellHeight = cellWidth * CHAR_ASPECT
        rows = (height / desiredCellHeight).toInt().coerceAtLeast(1)
        cellHeight = height.toFloat() / rows

        glyphPaint.textSize = cellHeight
        glyphPaint.textScaleX = 1f

        val fm = glyphPaint.fontMetrics
        baselineOffset = (cellHeight - (fm.descent - fm.ascent)) * 0.5f - fm.ascent

        // The bundled font is monospaced, so one drawText call can draw a
        // complete row at the font's natural advances. A single global
        // horizontal scale then stretches those rows edge-to-edge. This is
        // much faster than Canvas.drawPosText with a per-glyph position
        // array, which is the hot path that made ASCII scenes fall behind
        // while the batched Nothing dots stayed smooth.
        val referenceWidth = glyphPaint.measureText("M".repeat(columns))
        val referenceAdvance = (referenceWidth / columns).coerceAtLeast(0.001f)
        glyphAdvance = referenceAdvance
        blockScaleX = width / (referenceAdvance * columns)

        val cells = columns * rows
        // Keep the largest buffers while the lock/home transition animates the
        // column count every frame. Reallocating the whole grid 120 times a
        // second would create avoidable GC pauses exactly during the unlock.
        if (charBuffer.size < cells) charBuffer = CharArray(cells)
        if (rowHasInk.size < rows) rowHasInk = BooleanArray(rows)
        gridRect.set(0, 0, columns, rows)
        // Dot buffers and the native downsampling target are allocated lazily
        // by their respective draw paths. In particular, drawGray() must not
        // allocate a grid bitmap on every frame of the lock/home transition.
    }

    fun draw(
        canvas: Canvas,
        scene: Bitmap,
        viewWidth: Int,
        viewHeight: Int,
        requestedColumns: Int,
        glyphColorArgb: Int,
        backgroundColorArgb: Int,
        photoRamp: Boolean = false,
        symbolStyle: SymbolStyle = SymbolStyle.ASCII
    ) {
        configure(viewWidth, viewHeight, requestedColumns)
        canvas.drawColor(backgroundColorArgb)

        if (scene.isRecycled || scene.width <= 0 || scene.height <= 0) return

        val currentGrid = gridBitmap
        if (currentGrid == null || gridCanvas == null ||
            currentGrid.isRecycled ||
            currentGrid.width < columns || currentGrid.height < rows
        ) {
            // Grow only. The lock/home transition changes the column count
            // every frame, and recycling/allocating a bitmap 120 times a
            // second would stutter the very animation it is drawing.
            val capacityWidth = maxOf(columns, currentGrid?.width ?: 0)
            val capacityHeight = maxOf(rows, currentGrid?.height ?: 0)
            currentGrid?.recycle()
            gridBitmap = Bitmap.createBitmap(
                capacityWidth,
                capacityHeight,
                Bitmap.Config.ARGB_8888
            )
            gridCanvas = Canvas(gridBitmap!!)
        }
        val grid = gridBitmap ?: return
        val gridCanvas = gridCanvas ?: return
        val cells = columns * rows
        if (gridPixels.size < cells) gridPixels = IntArray(cells)

        // A single filtered native scale produces one luminance sample per
        // ASCII cell. The old Kotlin loop averaged every offscreen pixel on
        // every frame, which made 90/120 fps presets impossible.
        gridCanvas.drawColor(0xFF000000.toInt())
        gridCanvas.drawBitmap(scene, null, gridRect, gridPaint)
        grid.getPixels(gridPixels, 0, columns, 0, 0, columns, rows)
        scenePixels = gridPixels

        if (symbolStyle == SymbolStyle.NOTHING) {
            drawNothingGrid(canvas, columns, rows, glyphColorArgb, photoRamp)
        } else {
            drawAsciiGrid(
                canvas = canvas,
                sceneWidth = columns,
                sceneHeight = rows,
                glyphColorArgb = glyphColorArgb,
                photoRamp = photoRamp
            )
        }
    }

    /**
     * Draws an already grayscale frame, such as an exported GIF/video frame.
     * The source is fitted into the view with CONTAIN or COVER math inside the
     * cell loop, so no per-frame Bitmap allocation or full-screen resampling is
     * needed. A 3x3 sample grid per cell also makes the letterbox edge dim
     * smoothly instead of producing a hard white row.
     */
    fun drawGray(
        canvas: Canvas,
        pixels: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        requestedColumns: Int,
        glyphColorArgb: Int,
        backgroundColorArgb: Int,
        fit: PhotoFit,
        symbolStyle: SymbolStyle,
        samplesPerAxis: Int = 3
    ) {
        configure(viewWidth, viewHeight, requestedColumns)
        canvas.drawColor(backgroundColorArgb)

        if (sourceWidth <= 0 || sourceHeight <= 0 ||
            pixels.size < sourceWidth * sourceHeight
        ) {
            return
        }

        val widthScale = viewWidth.toFloat() / sourceWidth
        val heightScale = viewHeight.toFloat() / sourceHeight
        val scale = when (fit) {
            PhotoFit.CONTAIN -> min(widthScale, heightScale)
            PhotoFit.COVER -> kotlin.math.max(widthScale, heightScale)
        }.coerceAtLeast(0.0001f)
        val drawWidth = sourceWidth * scale
        val drawHeight = sourceHeight * scale
        val left = (viewWidth - drawWidth) * 0.5f
        val top = (viewHeight - drawHeight) * 0.5f
        val invScale = 1f / scale

        val samples = samplesPerAxis.coerceIn(1, 3)
        if (symbolStyle == SymbolStyle.NOTHING) {
            drawNothingGray(
                canvas, pixels, sourceWidth, sourceHeight,
                left, top, invScale, glyphColorArgb, samples
            )
        } else {
            drawAsciiGray(
                canvas, pixels, sourceWidth, sourceHeight,
                left, top, invScale, glyphColorArgb, samples
            )
        }
    }

    private fun averageGray(
        pixels: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        left: Float,
        top: Float,
        invScale: Float,
        cellLeft: Float,
        cellTop: Float,
        samplesPerAxis: Int
    ): Int {
        var sum = 0
        var samples = 0
        for (sy in 0 until samplesPerAxis) {
            val viewY = cellTop + cellHeight * (sy + 0.5f) / samplesPerAxis
            val sourceY = ((viewY - top) * invScale).toInt()
            if (sourceY < 0 || sourceY >= sourceHeight) {
                samples += samplesPerAxis
                continue
            }
            for (sx in 0 until samplesPerAxis) {
                val viewX = cellLeft + cellWidth * (sx + 0.5f) / samplesPerAxis
                val sourceX = ((viewX - left) * invScale).toInt()
                if (sourceX in 0 until sourceWidth) {
                    sum += pixels[sourceY * sourceWidth + sourceX].toInt() and 0xFF
                }
                samples++
            }
        }
        return if (samples == 0) 0 else sum / samples
    }

    private fun drawAsciiGray(
        canvas: Canvas,
        pixels: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        left: Float,
        top: Float,
        invScale: Float,
        glyphColorArgb: Int,
        samplesPerAxis: Int
    ) {
        var writeIndex = 0
        for (row in 0 until rows) {
            rowHasInk[row] = false
            val cellTop = row * cellHeight
            for (col in 0 until columns) {
                val average = averageGray(
                    pixels, sourceWidth, sourceHeight, left, top, invScale,
                    col * cellWidth, cellTop, samplesPerAxis
                )
                val ch = photoRampLut[average.coerceIn(0, 255)]
                charBuffer[writeIndex++] = ch
                if (ch != ' ') rowHasInk[row] = true
            }
        }

        drawAsciiRows(canvas, glyphColorArgb)
    }

    private fun drawNothingGray(
        canvas: Canvas,
        pixels: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        left: Float,
        top: Float,
        invScale: Float,
        glyphColorArgb: Int,
        samplesPerAxis: Int
    ) {
        ensureDotBuffers(columns * rows)
        for (row in 0 until rows) {
            val cellTop = row * cellHeight
            val centerY = (row + 0.5f) * cellHeight
            for (col in 0 until columns) {
                val average = averageGray(
                    pixels, sourceWidth, sourceHeight, left, top, invScale,
                    col * cellWidth, cellTop, samplesPerAxis
                )
                val level = photoDotLut[average.coerceIn(0, 255)].toInt()
                if (level == 0) continue
                val count = dotCounts[level]
                val target = dotPositions[level]
                val offset = count * 2
                target[offset] = (col + 0.5f) * cellWidth
                target[offset + 1] = centerY
                dotCounts[level] = count + 1
            }
        }

        val minCell = min(cellWidth, cellHeight)
        dotPaint.color = glyphColorArgb
        for (level in 1 until DOT_LEVELS) {
            val count = dotCounts[level]
            if (count == 0) continue
            val fraction = level / (DOT_LEVELS - 1f)
            dotPaint.strokeWidth = minCell * (0.10f + 0.84f * fraction)
            canvas.drawPoints(dotPositions[level], 0, count * 2, dotPaint)
        }
    }

    private fun drawAsciiGrid(
        canvas: Canvas,
        sceneWidth: Int,
        sceneHeight: Int,
        glyphColorArgb: Int,
        photoRamp: Boolean
    ) {
        val lut = if (photoRamp) photoRampLut else rampLut
        var writeIndex = 0
        for (row in 0 until rows) {
            rowHasInk[row] = false
            val y0 = row * sceneHeight / rows
            val y1 = ((row + 1) * sceneHeight / rows).coerceAtLeast(y0 + 1)
                .coerceAtMost(sceneHeight)

            for (col in 0 until columns) {
                val average = if (sceneWidth == columns && sceneHeight == rows) {
                    // The native scale stores ARGB pixels here, not luminance.
                    // Feed the same weighted luminance used by the legacy path
                    // into the LUT, otherwise every opaque pixel is clamped to
                    // zero and the whole wallpaper renders as blank cells.
                    val rgb = scenePixels[row * columns + col]
                    val r = (rgb ushr 16) and 0xFF
                    val g = (rgb ushr 8) and 0xFF
                    val b = rgb and 0xFF
                    (r * 54 + g * 183 + b * 19) ushr 8
                } else {
                    val x0 = col * sceneWidth / columns
                    val x1 = ((col + 1) * sceneWidth / columns)
                        .coerceAtLeast(x0 + 1)
                        .coerceAtMost(sceneWidth)
                    var sum = 0
                    var samples = 0
                    for (y in y0 until y1) {
                        var index = y * sceneWidth + x0
                        for (x in x0 until x1) {
                            val rgb = scenePixels[index++]
                            val r = (rgb ushr 16) and 0xFF
                            val g = (rgb ushr 8) and 0xFF
                            val b = rgb and 0xFF
                            sum += (r * 54 + g * 183 + b * 19) ushr 8
                            samples++
                        }
                    }
                    if (samples == 0) 0 else sum / samples
                }
                val ch = lut[average.coerceIn(0, 255)]
                charBuffer[writeIndex++] = ch
                if (ch != ' ') rowHasInk[row] = true
            }
        }

        drawAsciiRows(canvas, glyphColorArgb)
    }

    /**
     * Draws the already classified [charBuffer]. Each row is one drawText
     * call instead of a drawPosText call with a position for every glyph.
     * On the software Canvas used by live wallpapers this is the difference
     * between a slideshow and a smooth ASCII animation.
     */
    private fun drawAsciiRows(canvas: Canvas, glyphColorArgb: Int) {
        glyphPaint.color = glyphColorArgb
        val rightEdgeScale = if (blockScaleX > 0f) blockScaleX else 1f
        canvas.save()
        canvas.scale(rightEdgeScale, 1f)
        var rowStart = 0
        for (row in 0 until rows) {
            if (rowHasInk[row]) {
                // Most procedural scenes are outlines, so trimming the blank
                // margins avoids asking Skia to lay out a full 96-200 glyph
                // row when only the central dozen cells carry ink.
                var first = 0
                while (first < columns && charBuffer[rowStart + first] == ' ') {
                    first++
                }
                var last = columns - 1
                while (last >= first && charBuffer[rowStart + last] == ' ') {
                    last--
                }
                if (first <= last) {
                    canvas.drawText(
                        charBuffer,
                        rowStart + first,
                        last - first + 1,
                        first * glyphAdvance,
                        row * cellHeight + baselineOffset,
                        glyphPaint
                    )
                }
            }
            rowStart += columns
        }
        canvas.restore()
    }

    private fun drawNothingGrid(
        canvas: Canvas,
        sceneWidth: Int,
        sceneHeight: Int,
        glyphColorArgb: Int,
        photoRamp: Boolean
    ) {
        ensureDotBuffers(columns * rows)
        val lut = if (photoRamp) photoDotLut else dotLut

        for (row in 0 until rows) {
            val y0 = row * sceneHeight / rows
            val y1 = ((row + 1) * sceneHeight / rows).coerceAtLeast(y0 + 1)
                .coerceAtMost(sceneHeight)
            val centerY = (row + 0.5f) * cellHeight

            for (col in 0 until columns) {
                val average = if (sceneWidth == columns && sceneHeight == rows) {
                    // The native scale stores ARGB pixels here, not luminance.
                    // Feed the same weighted luminance used by the legacy path
                    // into the LUT, otherwise every opaque pixel is clamped to
                    // zero and the whole wallpaper renders as blank cells.
                    val rgb = scenePixels[row * columns + col]
                    val r = (rgb ushr 16) and 0xFF
                    val g = (rgb ushr 8) and 0xFF
                    val b = rgb and 0xFF
                    (r * 54 + g * 183 + b * 19) ushr 8
                } else {
                    val x0 = col * sceneWidth / columns
                    val x1 = ((col + 1) * sceneWidth / columns)
                        .coerceAtLeast(x0 + 1)
                        .coerceAtMost(sceneWidth)
                    var sum = 0
                    var samples = 0
                    for (y in y0 until y1) {
                        var index = y * sceneWidth + x0
                        for (x in x0 until x1) {
                            val rgb = scenePixels[index++]
                            val r = (rgb ushr 16) and 0xFF
                            val g = (rgb ushr 8) and 0xFF
                            val b = rgb and 0xFF
                            sum += (r * 54 + g * 183 + b * 19) ushr 8
                            samples++
                        }
                    }
                    if (samples == 0) 0 else sum / samples
                }
                val level = lut[average.coerceIn(0, 255)].toInt()
                if (level == 0) continue

                val count = dotCounts[level]
                val target = dotPositions[level]
                val offset = count * 2
                target[offset] = (col + 0.5f) * cellWidth
                target[offset + 1] = centerY
                dotCounts[level] = count + 1
            }
        }

        val minCell = min(cellWidth, cellHeight)
        dotPaint.color = glyphColorArgb
        for (level in 1 until DOT_LEVELS) {
            val count = dotCounts[level]
            if (count == 0) continue
            val fraction = level / (DOT_LEVELS - 1f)
            dotPaint.strokeWidth = minCell * (0.10f + 0.84f * fraction)
            canvas.drawPoints(dotPositions[level], 0, count * 2, dotPaint)
        }
    }

    private fun ensureDotBuffers(cellCount: Int) {
        for (level in 1 until DOT_LEVELS) {
            if (dotPositions[level].size < cellCount * 2) {
                dotPositions[level] = FloatArray(cellCount * 2)
            }
        }
        dotCounts.fill(0)
    }
}
