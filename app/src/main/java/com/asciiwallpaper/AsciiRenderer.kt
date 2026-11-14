package com.asciiwallpaper

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.BatteryManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Рендерер живых обоев: собственная 3D-математика + Canvas + ASCII-фильтр.
 *
 * Каждый кадр:
 *  1. сцена рисуется в маленький offscreen Bitmap (примерно 2–3 сэмпла на
 *     будущий символ), поэтому трапеции/треугольники растеризуются дёшево;
 *  2. фигуры проецируются вручную (перспектива, painter's algorithm);
 *  3. AsciiFilter усредняет пиксели по ячейкам и рисует ряды символов.
 *
 * Рендерер не знает о WallpaperService: его же использует живой предпросмотр
 * на экране настроек.
 */
class AsciiRenderer(private val context: Context? = null) {
    companion object {
        private const val CAMERA_DISTANCE = 7f
        private const val FUSILLI_RADIUS_FRACTION = 0.25f
        private const val CUBE_RADIUS_FRACTION = 0.21f
        private const val BIG_CUBE_WIDTH_FRACTION = 0.94f
        private const val BIG_CUBE_HEIGHT_FRACTION = 0.90f
        private const val MAX_DT_SECONDS = 0.10f
        private const val CORNER_ANIMATION_SECONDS = 1.0f
        private const val SPIN_BOOST_FACTOR = 4.5f
        private const val SPIN_BOOST_RISE_SECONDS = 0.14f
        private const val SPIN_BOOST_HOLD_SECONDS = 0.50f
        private const val SPIN_BOOST_DECAY_SECONDS = 2.30f
        private const val DNA_SPEED_SCALE = 1.35f
        private const val BLACK_HOLE_SPEED_SCALE = 1.10f
        private const val BLACK_HOLE_SPIN_BOOST = 3.0f
        private const val MAX_SCENE_WIDTH = 560
        private const val MAX_SCENE_HEIGHT = 1800
        private const val SAMPLES_PER_CHAR = 3
        private const val SPECTRUM_RETRY_NANOS = 5_000_000_000L

        // Направления на источник света. Свет зафиксирован в мире, поэтому
        // при вращении фигуры грани естественно меняют яркость.
        private val LIGHT_KEY = Vec3(-0.42f, 0.62f, 0.66f).normalized()
        private val LIGHT_FILL = Vec3(0.52f, -0.25f, 0.58f).normalized()
    }

    private val asciiFilter = AsciiFilter(context)
    private val fusilliMesh = FusilliMesh.create()
    private val cubeMesh = CubeMesh.create()
    private val dnaScene = DnaSceneRenderer()
    private val blackHoleScene = BlackHoleSceneRenderer()
    private val waveScene = WaveSceneRenderer()
    private val fastfetchRenderer = FastfetchRenderer(context)
    private val androidLogoRenderer = AndroidLogoRenderer(context)
    private val musicSignal = MusicSignal(context)
    private val audioSpectrum = AudioSpectrumSource(context)
    private val realSpectrumLevels = FloatArray(AudioSpectrumSource.BAND_COUNT)
    private var renderingActive = true
    private var spectrumMusicActive = false
    private var spectrumRetryAtNanos = 0L

    private var settings = WallpaperSettings()
    private var sceneBitmap: Bitmap? = null
    private var sceneCanvas: Canvas? = null
    private var sceneWidth = 0
    private var sceneHeight = 0
    private var charging = false
    private var batteryPercent = 50
    private var mediaFrameSet: MediaAsciiStore.FrameSet? = null

    init {
        context?.let { ctx ->
            try {
                val battery = ctx.registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
                val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL ||
                    plugged != 0
                batteryPercent = readBatteryPercent(battery) ?: batteryPercent
            } catch (_: Throwable) {
                Unit
            }
        }
    }

    private fun readBatteryPercent(intent: Intent?): Int? {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return null
        return (level * 100f / scale).roundToInt().coerceIn(0, 100)
    }

    private var lastFrameNanos = 0L
    private var elapsedSeconds = 0f

    // Углы вращения в градусах. Оси независимы, поэтому смена угла/фазы
    // переезда никогда не останавливает вращение.
    private var rot0X = -24f
    private var rot0Y = 31f
    private var rot0Z = 9f
    private var rot1X = 17f
    private var rot1Y = -46f
    private var rot1Z = -13f

    // Новые полноэкранные сцены имеют собственные оси вращения.
    private var bigCubeAngle = 12f
    private var dnaSpin = 0f
    private var blackHoleSpin = 0f
    private var bigCubeOffsetX = 0f
    private var bigCubeOffsetY = 0f
    private var bigCubeFitWidth = -1
    private var bigCubeFitHeight = -1
    private var bigCubeFitLineWidth = -1f
    private var bigCubeFixedRadius = 0f

    // Общий таймер для мерцания звёзд и эффектов разблокировки. Он копится
    // только по реально нарисованным кадрам, поэтому после паузы нет прыжка.
    private var unlockEffectStyle: ShapeStyle? = null
    private var unlockEffectStartNanos = 0L

    // Нормализованные центры фигур. При каждом разблокировании они меняются
    // местами по вертикали, как в условии задачи.
    private var cornerPhase = 0
    private val centerX = floatArrayOf(0.26f, 0.74f)
    private val centerY = floatArrayOf(0.28f, 0.72f)
    private val fromX = floatArrayOf(0.26f, 0.74f)
    private val fromY = floatArrayOf(0.28f, 0.72f)
    private var cornerAnimationStart = 0L
    private var cornerAnimating = false

    private val rotation = Mat3()

    // Photo source and scene-side drawing buffers. The bitmap itself is owned
    // by PhotoAsciiStore's process-wide cache, so it is never recycled here.
    private var photoBitmap: Bitmap? = null
    private val photoPaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
        isDither = true
    }
    private val photoDestination = RectF()
    private val placeholderPath = Path()
    private val placeholderStroke = Paint().apply {
        style = Paint.Style.STROKE
        color = 0xFF8D9A8E.toInt()
        strokeWidth = 1.4f
        isAntiAlias = true
    }
    private val placeholderFill = Paint().apply {
        style = Paint.Style.FILL
        color = 0xFFB6C2B7.toInt()
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = false
    }
    private val batteryOverlayPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = false
    }
    private val edgePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = 0xFFF4FFF4.toInt()
        strokeWidth = 1.45f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val trianglePath = Path()

    // Переиспользуемые буферы кадра — избегаем тысяч Short/Int объектов.
    private var screenX = FloatArray(0)
    private var screenY = FloatArray(0)
    private var vertexDepth = FloatArray(0)
    private var vertexValid = BooleanArray(0)
    private var faceOrder = IntArray(0)
    private var faceDepth = FloatArray(0)
    private var faceColors = IntArray(0)

    fun updateSettings(newSettings: WallpaperSettings) {
        if (newSettings === settings) return
        val clean = newSettings.sanitized()
        val styleChanged = clean.style != settings.style
        val revisionChanged = clean.photoRevision != settings.photoRevision

        if (styleChanged) {
            unlockEffectStyle = null
            unlockEffectStartNanos = 0L

            // Если пользователь возвращается к парным фигурам, сразу ставим
            // их в сохранённые углы, а не в позиции от старой сцены.
            if (clean.style.usesCornerSwap && !settings.style.usesCornerSwap) {
                setCornerPhase(cornerPhase)
            }
            if (clean.style == ShapeStyle.WAVES) {
                // Set the battery meter to the current state and let the
                // normal per-frame easing handle any later plug/unplug. Clear
                // the cached playback state so a stale "music playing" value
                // from before a pause is never shown on re-entry.
                musicSignal.reset()
                waveScene.onShown(charging, batteryPercent)
            }
        }
        settings = clean
        syncSpectrumCapture()

        if (revisionChanged) {
            mediaFrameSet = context?.let {
                MediaAsciiStore.load(it, clean.photoRevision)
            }
            photoBitmap = if (mediaFrameSet == null) {
                context?.let { PhotoAsciiStore.load(it, clean.photoRevision) }
            } else {
                null
            }
        }
    }

    /**
     * Called by the wallpaper service from the battery receiver. The preview
     * reads the sticky battery state once at construction and is also allowed
     * to set it explicitly.
     */
    fun setBattery(valueCharging: Boolean, percent: Int) {
        charging = valueCharging
        batteryPercent = percent.coerceIn(0, 100)
    }

    /**
     * The wallpaper pauses capture when it is hidden or the screen turns off,
     * and restarts it on the next visible frame. The settings preview leaves
     * this at true for its lifetime.
     */
    fun setRenderingActive(active: Boolean) {
        renderingActive = active
        syncSpectrumCapture()
    }

    fun release() {
        audioSpectrum.stop()
    }

    private fun syncSpectrumCapture() {
        val wantsSpectrum = renderingActive &&
            settings.style == ShapeStyle.WAVES &&
            settings.realSpectrumEnabled
        if (!wantsSpectrum) {
            spectrumMusicActive = false
            spectrumRetryAtNanos = 0L
            audioSpectrum.stop()
            return
        }
        // Visualizer is a native audio effect attached to the global mix.
        // Keeping it alive while no music is playing costs CPU and can make
        // the whole device feel janky, so it is only enabled for an active
        // music stream. The render loop starts/stops it on music transitions.
        if (musicSignal.isPlaying) {
            spectrumMusicActive = true
            if (!audioSpectrum.isAvailable) {
                audioSpectrum.start()
                // Give the effect time to deliver its first callback. If it
                // never does, the next retry restarts the dead instance.
                spectrumRetryAtNanos = System.nanoTime() + SPECTRUM_RETRY_NANOS
            }
        } else {
            spectrumMusicActive = false
            spectrumRetryAtNanos = 0L
            audioSpectrum.stop()
        }
    }

    fun hasAnimatedMedia(): Boolean = mediaFrameSet != null

    /** Playback rate of the imported clip, clamped to a sane range. */
    fun animatedFrameRate(): Int =
        mediaFrameSet?.fps?.roundToInt()?.coerceIn(1, 30) ?: 12

    fun needsContinuousRendering(): Boolean {
        // The Android logo is deliberately static: the same fastfetch ASCII
        // artwork is drawn once and only redrawn for a setting/surface change.
        if (settings.style == ShapeStyle.ANDROID_LOGO) return false
        return settings.style != ShapeStyle.PHOTO || mediaFrameSet != null
    }

    /**
     * Немедленно устанавливает расстановку (например, при старте обоев,
     * чтобы восстановить последнюю фазу после пересоздания сервиса).
     */
    fun setCornerPhase(phase: Int) {
        cornerPhase = phase and 1
        for (i in 0..1) {
            centerX[i] = targetX(i)
            centerY[i] = targetY(i, cornerPhase)
            fromX[i] = centerX[i]
            fromY[i] = centerY[i]
        }
        cornerAnimating = false
    }

    /**
     * Запускает анимацию текущего стиля. Парные фигуры меняются местами,
     * большой куб и ДНК получают импульс угловой скорости, а чёрная дыра —
     * расходящуюся гравитационную волну.
     */
    fun onUnlock(phase: Int, timeNanos: Long) {
        cornerPhase = phase and 1
        if (settings.style == ShapeStyle.PHOTO ||
            settings.style == ShapeStyle.ANDROID_LOGO
        ) {
            // Static styles should not rotate or sparkle. Their lock-screen
            // toggle is hidden, but a stale broadcast must still be harmless.
            unlockEffectStyle = null
            unlockEffectStartNanos = 0L
        } else if (settings.style.usesCornerSwap) {
            unlockEffectStyle = null
            unlockEffectStartNanos = 0L
            startCornerAnimation(cornerPhase, timeNanos)
        } else {
            unlockEffectStyle = settings.style
            unlockEffectStartNanos = timeNanos
        }
    }

    /**
     * Запускает плавный переезд на 1 секунду. Стартовая точка — текущее
     * положение, поэтому повторный вызов посреди анимации не даёт рывка.
     */
    private fun startCornerAnimation(phase: Int, timeNanos: Long) {
        for (i in 0..1) {
            fromX[i] = centerX[i]
            fromY[i] = centerY[i]
        }
        cornerPhase = phase and 1
        cornerAnimationStart = timeNanos
        cornerAnimating = true
    }

    /** Вызывается после паузы, чтобы фигуры не прыгнули на десятки градусов. */
    fun resetFrameClock() {
        lastFrameNanos = 0L
    }

    fun renderFrame(
        canvas: Canvas,
        width: Int,
        height: Int,
        timeNanos: Long,
        newSettings: WallpaperSettings
    ) {
        if (width <= 1 || height <= 1) return

        updateSettings(newSettings)

        val dt = if (lastFrameNanos == 0L) {
            0f
        } else {
            ((timeNanos - lastFrameNanos) / 1_000_000_000f)
                .coerceIn(0f, MAX_DT_SECONDS)
        }
        lastFrameNanos = timeNanos
        elapsedSeconds += dt
        if (elapsedSeconds > 3_600f) elapsedSeconds -= 3_600f

        advanceRotation(dt, timeNanos)
        updateCornerAnimation(timeNanos)
        val waveSpeed = (settings.speedDegPerSec / 9f).coerceIn(0.25f, 5f)
        val waveTime = elapsedSeconds * waveSpeed
        if (settings.style == ShapeStyle.WAVES) {
            musicSignal.poll(timeNanos)
            if (settings.realSpectrumEnabled) {
                if (musicSignal.isPlaying) {
                    val unhealthy = !audioSpectrum.isAvailable ||
                        !audioSpectrum.hasRecentFft()
                    val dueForRetry = unhealthy &&
                        timeNanos >= spectrumRetryAtNanos
                    if (!spectrumMusicActive || dueForRetry) {
                        // Start only when playback actually begins. A failure
                        // or a Visualizer that stops delivering callbacks is
                        // retried after a pause, not on every frame.
                        spectrumMusicActive = true
                        if (audioSpectrum.isAvailable) {
                            audioSpectrum.stop()
                        }
                        audioSpectrum.start()
                        spectrumRetryAtNanos =
                            timeNanos + SPECTRUM_RETRY_NANOS
                    }
                } else if (spectrumMusicActive) {
                    spectrumMusicActive = false
                    spectrumRetryAtNanos = 0L
                    audioSpectrum.stop()
                }
            }
            val realSpectrum = if (settings.realSpectrumEnabled &&
                audioSpectrum.readInto(realSpectrumLevels)
            ) {
                realSpectrumLevels
            } else {
                null
            }
            waveScene.advance(
                dt = dt,
                charging = charging,
                batteryPercent = batteryPercent,
                musicActive = musicSignal.isPlaying,
                musicVolume = musicSignal.volume,
                timeSeconds = waveTime,
                realSpectrum = realSpectrum
            )
        }

        if (settings.style == ShapeStyle.FASTFETCH) {
            fastfetchRenderer.draw(
                canvas = canvas,
                width = width,
                height = height,
                columns = settings.columns,
                glyphColorArgb = settings.glyphColorArgb,
                backgroundColorArgb = settings.backgroundColorArgb,
                symbolStyle = settings.symbolStyle,
                charging = charging
            )
            return
        }

        if (settings.style == ShapeStyle.ANDROID_LOGO) {
            androidLogoRenderer.draw(
                canvas = canvas,
                width = width,
                height = height,
                glyphColorArgb = settings.glyphColorArgb,
                backgroundColorArgb = settings.backgroundColorArgb,
                symbolStyle = settings.symbolStyle
            )
            return
        }

        if (settings.style == ShapeStyle.PHOTO && mediaFrameSet != null) {
            val frameSet = mediaFrameSet ?: return
            val frameIndex = ((elapsedSeconds * frameSet.fps).toInt() %
                frameSet.frames.size.coerceAtLeast(1))
            asciiFilter.drawGray(
                canvas = canvas,
                pixels = frameSet.frames[frameIndex],
                sourceWidth = frameSet.width,
                sourceHeight = frameSet.height,
                viewWidth = width,
                viewHeight = height,
                requestedColumns = settings.columns,
                glyphColorArgb = settings.glyphColorArgb,
                backgroundColorArgb = settings.backgroundColorArgb,
                fit = settings.photoFit,
                symbolStyle = settings.symbolStyle
            )
            return
        }

        val columns = settings.columns
        ensureScene(width, height, columns)

        val bitmap = sceneBitmap ?: return
        val scene = sceneCanvas ?: return
        scene.drawColor(Color.BLACK)
        drawObjects(scene, sceneWidth, sceneHeight, timeNanos, columns)

        asciiFilter.draw(
            canvas = canvas,
            scene = bitmap,
            viewWidth = width,
            viewHeight = height,
            requestedColumns = columns,
            glyphColorArgb = effectiveGlyphColor(),
            backgroundColorArgb = settings.backgroundColorArgb,
            photoRamp = settings.style == ShapeStyle.PHOTO,
            symbolStyle = settings.symbolStyle
        )
        if (settings.style == ShapeStyle.WAVES) {
            drawBatteryOverlay(canvas, width, height)
        }
    }

    /**
     * A translucent green wash below the battery line. The scene already turns
     * the filled area into dense glyphs; this tint makes the lower part read as
     * a green battery fill on both black and custom backgrounds without using
     * a bitmap or another filter pass.
     */
    private fun drawBatteryOverlay(canvas: Canvas, width: Int, height: Int) {
        val amount = waveScene.chargeColorMix()
        if (amount <= 0.001f) return
        val top = height * waveScene.batteryFillTopFraction()
        batteryOverlayPaint.color = Color.argb(
            (amount * 74f).toInt().coerceIn(0, 255),
            22,
            196,
            74
        )
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), batteryOverlayPaint)
    }

    /**
     * The WAVES style blends the glyph colour towards green while the battery
     * meter is visible. The rest of the styles keep the selected colour.
     */
    private fun effectiveGlyphColor(): Int {
        if (settings.style != ShapeStyle.WAVES) return settings.glyphColorArgb
        val mix = waveScene.chargeColorMix()
        if (mix <= 0.001f) return settings.glyphColorArgb
        val base = settings.glyphColorArgb
        val green = 0xFF6DFF82.toInt()
        val inv = 1f - mix.coerceIn(0f, 1f)
        val a = ((base ushr 24) and 0xFF) * inv + 255 * mix
        val r = ((base ushr 16) and 0xFF) * inv + ((green ushr 16) and 0xFF) * mix
        val g = ((base ushr 8) and 0xFF) * inv + ((green ushr 8) and 0xFF) * mix
        val b = (base and 0xFF) * inv + (green and 0xFF) * mix
        return (a.toInt().coerceIn(0, 255) shl 24) or
            (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or
            b.toInt().coerceIn(0, 255)
    }

    private fun advanceRotation(dt: Float, timeNanos: Long) {
        val speed = settings.speedDegPerSec
        rot0X = wrapDegrees(rot0X + speed * dt * 0.48f)
        rot0Y = wrapDegrees(rot0Y + speed * dt * 0.82f)
        rot0Z = wrapDegrees(rot0Z + speed * dt * 0.18f)

        rot1X = wrapDegrees(rot1X - speed * dt * 0.42f)
        rot1Y = wrapDegrees(rot1Y - speed * dt * 0.76f)
        rot1Z = wrapDegrees(rot1Z + speed * dt * 0.22f)

        when (settings.style) {
            ShapeStyle.BIG_CUBE -> {
                val boost = spinBoostMultiplier(timeNanos, ShapeStyle.BIG_CUBE)
                bigCubeAngle = wrapDegrees(bigCubeAngle + speed * dt * boost)
            }

            ShapeStyle.DNA -> {
                val boost = spinBoostMultiplier(timeNanos, ShapeStyle.DNA)
                dnaSpin = wrapDegrees(
                    dnaSpin + speed * DNA_SPEED_SCALE * dt * boost
                )
            }

            ShapeStyle.BLACK_HOLE -> {
                val boost = blackHoleBoostMultiplier(timeNanos)
                blackHoleSpin = wrapDegrees(
                    blackHoleSpin + speed * BLACK_HOLE_SPEED_SCALE * dt * boost
                )
            }

            else -> Unit
        }
    }

    private fun updateCornerAnimation(timeNanos: Long) {
        if (!cornerAnimating) return

        val raw = (timeNanos - cornerAnimationStart) /
            (CORNER_ANIMATION_SECONDS * 1_000_000_000f)
        val t = raw.coerceIn(0f, 1f)
        val eased = easeInOutCubic(t)

        for (i in 0..1) {
            centerX[i] = lerp(fromX[i], targetX(i), eased)
            centerY[i] = lerp(fromY[i], targetY(i, cornerPhase), eased)
        }

        if (t >= 1f) cornerAnimating = false
    }

    private fun targetX(index: Int): Float = if (index == 0) 0.26f else 0.74f

    private fun targetY(index: Int, phase: Int): Float {
        val isTop = (index == 0) == (phase == 0)
        return if (isTop) 0.28f else 0.72f
    }

    private fun ensureScene(viewWidth: Int, viewHeight: Int, columns: Int) {
        val targetWidth = (columns * SAMPLES_PER_CHAR)
            .coerceIn(120, MAX_SCENE_WIDTH)
        val aspect = viewHeight.toFloat() / viewWidth.toFloat()
        val proportionalHeight = targetWidth * aspect
        // Keep the offscreen aspect exactly equal to the surface aspect. If
        // the height cap clamps only one dimension, a circle rendered here
        // would become an ellipse; scale both dimensions instead.
        val capScale = if (proportionalHeight > MAX_SCENE_HEIGHT) {
            MAX_SCENE_HEIGHT / proportionalHeight
        } else {
            1f
        }
        val desiredWidth = (targetWidth * capScale).roundToInt().coerceAtLeast(2)
        val desiredHeight = (proportionalHeight * capScale)
            .roundToInt()
            .coerceAtLeast(2)

        if (sceneBitmap != null &&
            desiredWidth == sceneWidth &&
            desiredHeight == sceneHeight
        ) {
            return
        }

        sceneBitmap?.recycle()
        val bitmap = Bitmap.createBitmap(
            desiredWidth,
            desiredHeight,
            Bitmap.Config.ARGB_8888
        )
        sceneBitmap = bitmap
        sceneCanvas = Canvas(bitmap)
        sceneWidth = desiredWidth
        sceneHeight = desiredHeight
    }

    private fun drawObjects(
        canvas: Canvas,
        width: Int,
        height: Int,
        timeNanos: Long,
        columns: Int
    ) {
        when (settings.style) {
            ShapeStyle.FUSILLI, ShapeStyle.CUBES -> drawCornerPair(canvas, width, height)

            ShapeStyle.BIG_CUBE -> {
                // One fixed camera distance for the whole rotation: the cube must
                // never grow/shrink or touch the viewport edges while tumbling.
                val radius = bigCubeRadius(width, height)
                updateBigCubeOffset(radius)
                drawObject(
                    canvas = canvas,
                    mesh = cubeMesh,
                    centerX = width * 0.5f + bigCubeOffsetX,
                    centerY = height * 0.5f + bigCubeOffsetY,
                    radius = radius,
                    rx = bigCubeAngle,
                    ry = bigCubeAngle,
                    rz = 0f,
                    drawEdges = true,
                    edgeWidthScale = 1.05f
                )
            }

            ShapeStyle.DNA -> {
                dnaScene.draw(
                    canvas = canvas,
                    width = width,
                    height = height,
                    columns = columns,
                    spinDegrees = dnaSpin,
                    excitement = dnaExcitement(timeNanos),
                    lineWidthScale = settings.lineWidth
                )
            }

            ShapeStyle.BLACK_HOLE -> {
                blackHoleScene.draw(
                    canvas = canvas,
                    width = width,
                    height = height,
                    columns = columns,
                    spinDegrees = blackHoleSpin,
                    timeSeconds = elapsedSeconds,
                    unlockAgeSeconds = unlockAgeSeconds(timeNanos, ShapeStyle.BLACK_HOLE),
                    lineWidthScale = settings.lineWidth
                )
            }

            ShapeStyle.ANDROID_LOGO, ShapeStyle.FASTFETCH -> Unit

            ShapeStyle.WAVES -> {
                val waveSpeed = (settings.speedDegPerSec / 9f)
                    .coerceIn(0.25f, 5f)
                waveScene.draw(
                    canvas = canvas,
                    width = width,
                    height = height,
                    columns = columns,
                    timeSeconds = elapsedSeconds * waveSpeed,
                    lineWidthScale = settings.lineWidth,
                    idleAmplitudeScale = settings.waveBeatPercent / 100f,
                    idleSpreadScale = settings.waveSpreadPercent / 100f
                )
            }

            ShapeStyle.PHOTO -> drawPhoto(canvas, width, height)
        }
    }

    /**
     * Draws the photo without distorting its aspect ratio. CONTAIN fits the
     * complete image with letterbox bars, while COVER fills the surface and
     * lets Canvas clip the overflowing edges. ASCII cells are not square, so
     * the filter's row sizing preserves the same proportions afterwards.
     */
    private fun drawPhoto(canvas: Canvas, width: Int, height: Int) {
        val bitmap = photoBitmap
        if (bitmap == null || bitmap.isRecycled) {
            drawPhotoPlaceholder(canvas, width, height)
            return
        }

        val widthScale = width.toFloat() / bitmap.width.coerceAtLeast(1)
        val heightScale = height.toFloat() / bitmap.height.coerceAtLeast(1)
        val scale = when (settings.photoFit) {
            PhotoFit.CONTAIN -> min(widthScale, heightScale)
            PhotoFit.COVER -> max(widthScale, heightScale)
        }
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = (width - drawWidth) * 0.5f
        val top = (height - drawHeight) * 0.5f
        photoDestination.set(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(bitmap, null, photoDestination, photoPaint)
    }

    /**
     * A quiet "photo frame" scene is shown before the user picks an image. It
     * gives the live preview a recognizable placeholder and keeps the chosen
     * style from looking like a black empty screen.
     */
    private fun drawPhotoPlaceholder(canvas: Canvas, width: Int, height: Int) {
        val margin = min(width, height) * 0.13f
        val left = margin
        val top = margin * 0.72f
        val right = width - margin
        val bottom = height - margin * 0.72f
        val frameWidth = (right - left).coerceAtLeast(1f)
        val frameHeight = (bottom - top).coerceAtLeast(1f)
        val horizon = top + frameHeight * 0.64f

        placeholderStroke.strokeWidth = (
            width.toFloat() / settings.columns.coerceAtLeast(1) * 0.5f
            ).coerceAtLeast(1.2f)
        canvas.drawRect(left, top, right, bottom, placeholderStroke)

        placeholderPath.rewind()
        placeholderPath.moveTo(left + frameWidth * 0.06f, horizon)
        placeholderPath.lineTo(left + frameWidth * 0.33f, top + frameHeight * 0.39f)
        placeholderPath.lineTo(left + frameWidth * 0.52f, horizon)
        placeholderPath.lineTo(left + frameWidth * 0.66f, top + frameHeight * 0.49f)
        placeholderPath.lineTo(left + frameWidth * 0.92f, horizon)
        placeholderPath.close()
        canvas.drawPath(placeholderPath, placeholderFill)

        canvas.drawCircle(
            left + frameWidth * 0.72f,
            top + frameHeight * 0.28f,
            min(frameWidth, frameHeight) * 0.09f,
            placeholderFill
        )
        canvas.drawLine(left, horizon, right, horizon, placeholderStroke)
    }

    private fun drawCornerPair(canvas: Canvas, width: Int, height: Int) {
        val mesh = if (settings.style == ShapeStyle.FUSILLI) fusilliMesh else cubeMesh
        val radiusFraction = if (settings.style == ShapeStyle.FUSILLI) {
            FUSILLI_RADIUS_FRACTION
        } else {
            CUBE_RADIUS_FRACTION
        }
        val radius = min(width, height) * radiusFraction

        drawObject(
            canvas = canvas,
            mesh = mesh,
            centerX = centerX[0] * width,
            centerY = centerY[0] * height,
            radius = radius,
            rx = rot0X,
            ry = rot0Y,
            rz = rot0Z,
            drawEdges = mesh.edgeIndices.isNotEmpty()
        )
        drawObject(
            canvas = canvas,
            mesh = mesh,
            centerX = centerX[1] * width,
            centerY = centerY[1] * height,
            radius = radius,
            rx = rot1X,
            ry = rot1Y,
            rz = rot1Z,
            drawEdges = mesh.edgeIndices.isNotEmpty()
        )
    }

    /**
     * The giant cube uses one fixed scale for every orientation.  The scale is
     * solved once per viewport from the widest/tallest silhouette of a full
     * rotation, so the cube is as close as possible without ever touching an
     * edge and without a size pulse while it spins.
     */
    private fun bigCubeRadius(width: Int, height: Int): Float {
        val lineWidth = settings.lineWidth.coerceIn(
            WallpaperSettings.MIN_LINE_WIDTH,
            WallpaperSettings.MAX_LINE_WIDTH
        )
        if (width == bigCubeFitWidth &&
            height == bigCubeFitHeight &&
            lineWidth == bigCubeFitLineWidth &&
            bigCubeFixedRadius > 0f
        ) {
            return bigCubeFixedRadius
        }

        val positions = cubeMesh.positions
        var maxHalfWidth = 0f
        var maxHalfHeight = 0f
        for (degrees in 0 until 360) {
            rotation.setEulerDegrees(degrees.toFloat(), degrees.toFloat(), 0f)
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var p = 0
            while (p < positions.size) {
                val x = positions[p]
                val y = positions[p + 1]
                val z = positions[p + 2]
                p += 3
                val depth = CAMERA_DISTANCE - rotation.tz(x, y, z)
                if (depth < 0.35f) continue
                val px = rotation.tx(x, y, z) / depth
                val py = rotation.ty(x, y, z) / depth
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
            }
            if (minX == Float.MAX_VALUE) continue
            val halfWidth = (maxX - minX) * 0.5f
            val halfHeight = (maxY - minY) * 0.5f
            if (halfWidth > maxHalfWidth) maxHalfWidth = halfWidth
            if (halfHeight > maxHalfHeight) maxHalfHeight = halfHeight
        }

        val bound = cubeMesh.boundingRadius
        // The stroke is centred on the projected edge, so reserve half of it
        // on each side in addition to the viewport fractions. This keeps the
        // cube inside the screen even at the maximum 2.5x line width.
        val strokePx = (
            width.toFloat() / settings.columns.coerceAtLeast(1)
            ).coerceAtLeast(1.8f) *
            1.15f *
            1.05f *
            lineWidth
        val targetHalfWidth = (
            width * BIG_CUBE_WIDTH_FRACTION - strokePx
            ).coerceAtLeast(1f) * 0.5f
        val targetHalfHeight = (
            height * BIG_CUBE_HEIGHT_FRACTION - strokePx
            ).coerceAtLeast(1f) * 0.5f
        val radius = if (maxHalfWidth <= 1e-5f || maxHalfHeight <= 1e-5f) {
            min(width, height) * 0.46f
        } else {
            val radiusForWidth = targetHalfWidth * bound /
                (CAMERA_DISTANCE * maxHalfWidth)
            val radiusForHeight = targetHalfHeight * bound /
                (CAMERA_DISTANCE * maxHalfHeight)
            min(radiusForWidth, radiusForHeight)
        }

        bigCubeFitWidth = width
        bigCubeFitHeight = height
        bigCubeFitLineWidth = lineWidth
        bigCubeFixedRadius = radius
        return radius
    }

    /**
     * Perspective makes the silhouette centroid drift slightly with orientation.
     * Compensating it keeps the fixed-size cube centred instead of letting one
     * side graze the screen edge.
     */
    private fun updateBigCubeOffset(radius: Float) {
        rotation.setEulerDegrees(bigCubeAngle, bigCubeAngle, 0f)
        val positions = cubeMesh.positions
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var p = 0
        while (p < positions.size) {
            val x = positions[p]
            val y = positions[p + 1]
            val z = positions[p + 2]
            p += 3
            val depth = CAMERA_DISTANCE - rotation.tz(x, y, z)
            if (depth < 0.35f) {
                bigCubeOffsetX = 0f
                bigCubeOffsetY = 0f
                return
            }
            val px = rotation.tx(x, y, z) / depth
            val py = rotation.ty(x, y, z) / depth
            if (px < minX) minX = px
            if (px > maxX) maxX = px
            if (py < minY) minY = py
            if (py > maxY) maxY = py
        }
        if (minX == Float.MAX_VALUE) {
            bigCubeOffsetX = 0f
            bigCubeOffsetY = 0f
            return
        }
        val focal = radius * CAMERA_DISTANCE / cubeMesh.boundingRadius
        bigCubeOffsetX = -((minX + maxX) * 0.5f) * focal
        bigCubeOffsetY = ((minY + maxY) * 0.5f) * focal
    }

    private fun drawObject(
        canvas: Canvas,
        mesh: Mesh,
        centerX: Float,
        centerY: Float,
        radius: Float,
        rx: Float,
        ry: Float,
        rz: Float,
        drawEdges: Boolean,
        edgeWidthScale: Float = 1f,
        outline: Boolean = false
    ) {
        rotation.setEulerDegrees(rx, ry, rz)

        val vertexCount = mesh.vertexCount
        if (screenX.size < vertexCount) {
            screenX = FloatArray(vertexCount)
            screenY = FloatArray(vertexCount)
            vertexDepth = FloatArray(vertexCount)
            vertexValid = BooleanArray(vertexCount)
        }

        val focalLength = radius * CAMERA_DISTANCE / mesh.boundingRadius
        val positions = mesh.positions
        var p = 0
        for (i in 0 until vertexCount) {
            val x = positions[p]
            val y = positions[p + 1]
            val z = positions[p + 2]
            p += 3

            val rotatedX = rotation.tx(x, y, z)
            val rotatedY = rotation.ty(x, y, z)
            val rotatedZ = rotation.tz(x, y, z)

            val depth = CAMERA_DISTANCE - rotatedZ
            if (depth < 0.35f) {
                vertexValid[i] = false
            } else {
                val invDepth = focalLength / depth
                screenX[i] = centerX + rotatedX * invDepth
                screenY[i] = centerY - rotatedY * invDepth
                vertexDepth[i] = depth
                vertexValid[i] = true
            }
        }

        // Кубы рисуются как каркас: сплошные тёмные грани после ASCII-
        // даунсемплинга сливались бы в неразборчивое пятно. Одна яркая линия
        // толщиной примерно в один будущий символ читается гораздо лучше.
        if (drawEdges) {
            drawMeshEdges(canvas, mesh, edgeWidthScale)
            return
        }

        val faceCount = mesh.faceCount
        if (faceOrder.size < faceCount) {
            faceOrder = IntArray(faceCount)
            faceDepth = FloatArray(faceCount)
            faceColors = IntArray(faceCount)
        }

        val indices = mesh.faceIndices
        val normals = mesh.faceNormals
        var visibleCount = 0

        for (face in 0 until faceCount) {
            val i0 = indices[face * 3]
            val i1 = indices[face * 3 + 1]
            val i2 = indices[face * 3 + 2]
            if (!vertexValid[i0] || !vertexValid[i1] || !vertexValid[i2]) continue

            val x0 = screenX[i0]
            val y0 = screenY[i0]
            val x1 = screenX[i1]
            val y1 = screenY[i1]
            val x2 = screenX[i2]
            val y2 = screenY[i2]

            // Все меши генерируются с внешней намоткой. После переворота Y
            // (экранная ось вниз) лицевая грань имеет отрицательную площадь.
            val area = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
            if (area >= 0f) continue

            val nx = normals[face * 3]
            val ny = normals[face * 3 + 1]
            val nz = normals[face * 3 + 2]
            val wx = rotation.tx(nx, ny, nz)
            val wy = rotation.ty(nx, ny, nz)
            val wz = rotation.tz(nx, ny, nz)

            val keyLight = (
                wx * LIGHT_KEY.x + wy * LIGHT_KEY.y + wz * LIGHT_KEY.z
                ).coerceAtLeast(0f)
            val fillLight = (
                wx * LIGHT_FILL.x + wy * LIGHT_FILL.y + wz * LIGHT_FILL.z
                ).coerceAtLeast(0f)

            // Мягкий свет с заметной заполняющей компонентой: трубка должна
            // оставаться непрерывной, а не рассыпаться на отдельные блики.
            val shade = (0.28f + 0.58f * keyLight + 0.14f * fillLight)
                .coerceIn(0f, 1f)
            val value = (80f + 175f * shade).toInt().coerceIn(0, 255)
            val color = 0xFF000000.toInt() or
                (value shl 16) or (value shl 8) or value

            faceOrder[visibleCount] = face
            // Depth/color are indexed by the original face index: the sorter
            // looks them up through faceOrder[...], not through the compacted
            // visible slot.
            faceDepth[face] = (
                vertexDepth[i0] + vertexDepth[i1] + vertexDepth[i2]
                ) / 3f
            faceColors[face] = color
            visibleCount++
        }

        if (visibleCount > 1) {
            sortFacesByDepth(faceOrder, faceDepth, 0, visibleCount - 1)
        }

        // Painter's algorithm: сначала дальние грани, затем ближние.
        for (orderIndex in visibleCount - 1 downTo 0) {
            val face = faceOrder[orderIndex]
            val i0 = indices[face * 3]
            val i1 = indices[face * 3 + 1]
            val i2 = indices[face * 3 + 2]

            fillPaint.color = faceColors[face]
            trianglePath.rewind()
            trianglePath.moveTo(screenX[i0], screenY[i0])
            trianglePath.lineTo(screenX[i1], screenY[i1])
            trianglePath.lineTo(screenX[i2], screenY[i2])
            trianglePath.close()
            canvas.drawPath(trianglePath, fillPaint)
        }

        if (outline) {
            drawMeshEdges(canvas, mesh, edgeWidthScale)
        }
    }

    private fun drawMeshEdges(canvas: Canvas, mesh: Mesh, edgeWidthScale: Float) {
        if (mesh.edgeIndices.isEmpty()) return
        edgePaint.strokeWidth = (
            sceneWidth.toFloat() / settings.columns.coerceAtLeast(1)
            ).coerceAtLeast(1.8f) *
            1.15f *
            edgeWidthScale *
            settings.lineWidth.coerceIn(
                WallpaperSettings.MIN_LINE_WIDTH,
                WallpaperSettings.MAX_LINE_WIDTH
            )
        val edges = mesh.edgeIndices
        var e = 0
        while (e < edges.size) {
            val a = edges[e]
            val b = edges[e + 1]
            if (vertexValid[a] && vertexValid[b]) {
                canvas.drawLine(
                    screenX[a],
                    screenY[a],
                    screenX[b],
                    screenY[b],
                    edgePaint
                )
            }
            e += 2
        }
    }

    /**
     * Быстрая сортировка индексов граней по средней глубине (по возрастанию:
     * ближние первыми, затем обходим массив с конца).
     */
    private fun sortFacesByDepth(order: IntArray, depths: FloatArray, lo: Int, hi: Int) {
        var left = lo
        var right = hi
        if (left >= right) return

        val pivot = depths[order[(left + right) ushr 1]]
        while (left <= right) {
            while (depths[order[left]] < pivot) left++
            while (depths[order[right]] > pivot) right--
            if (left <= right) {
                val tmp = order[left]
                order[left] = order[right]
                order[right] = tmp
                left++
                right--
            }
        }
        if (lo < right) sortFacesByDepth(order, depths, lo, right)
        if (left < hi) sortFacesByDepth(order, depths, left, hi)
    }

    private fun spinBoostMultiplier(timeNanos: Long, style: ShapeStyle): Float {
        if (unlockEffectStyle != style) return 1f
        val age = ((timeNanos - unlockEffectStartNanos) / 1_000_000_000f)
        return boostEnvelope(age, SPIN_BOOST_FACTOR)
    }

    private fun blackHoleBoostMultiplier(timeNanos: Long): Float {
        if (unlockEffectStyle != ShapeStyle.BLACK_HOLE) return 1f
        val age = ((timeNanos - unlockEffectStartNanos) / 1_000_000_000f)
        return boostEnvelope(age, BLACK_HOLE_SPIN_BOOST)
    }

    /**
     * Half a second at high speed, then a smooth return to the user's chosen
     * speed. A short ease-in avoids an angular jump at the unlock instant.
     */
    private fun boostEnvelope(age: Float, maxFactor: Float): Float {
        if (age <= 0f) return 1f
        val rise = (age / SPIN_BOOST_RISE_SECONDS).coerceIn(0f, 1f)
        val riseEase = 1f - (1f - rise) * (1f - rise) * (1f - rise)
        if (age <= SPIN_BOOST_HOLD_SECONDS) {
            return 1f + (maxFactor - 1f) * riseEase
        }
        val fall = ((age - SPIN_BOOST_HOLD_SECONDS) / SPIN_BOOST_DECAY_SECONDS)
            .coerceIn(0f, 1f)
        return 1f + (maxFactor - 1f) * (1f - easeInOutCubic(fall))
    }

    private fun unlockAgeSeconds(timeNanos: Long, style: ShapeStyle): Float {
        if (unlockEffectStyle != style) return -1f
        val age = (timeNanos - unlockEffectStartNanos) / 1_000_000_000f
        return if (age >= 0f) age else -1f
    }

    private fun dnaExcitement(timeNanos: Long): Float {
        val age = unlockAgeSeconds(timeNanos, ShapeStyle.DNA)
        if (age < 0f) return 0f
        return (1f - age / 1.8f).coerceIn(0f, 1f)
    }

    private fun easeInOutCubic(t: Float): Float =
        if (t < 0.5f) {
            4f * t * t * t
        } else {
            val f = -2f * t + 2f
            1f - (f * f * f) * 0.5f
        }

    private fun lerp(from: Float, to: Float, t: Float): Float =
        from + (to - from) * t

    private fun wrapDegrees(value: Float): Float {
        var v = value % 360f
        if (v < 0f) v += 360f
        return v
    }
}
