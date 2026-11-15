package com.asciiwallpaper

import android.content.Context
import android.content.SharedPreferences

/** The procedural ASCII scenes supported by the wallpaper. */
enum class ShapeStyle(val prefValue: String) {
    FUSILLI("fusilli"),
    CUBES("cubes"),
    BIG_CUBE("big_cube"),
    DNA("dna"),
    BLACK_HOLE("black_hole"),
    DUCK("duck"),
    DONUT("donut"),
    FASTFETCH("fastfetch"),
    ANDROID_LOGO("android_logo"),
    WAVES("waves"),
    LOCK_HOME("lock_home"),
    PHOTO("photo");

    /**
     * The first two styles are deliberately two separate objects, so unlocking
     * swaps their corners. The newer compositions own the whole screen and use
     * a different unlock animation instead.
     */
    val usesCornerSwap: Boolean
        get() = this == FUSILLI || this == CUBES

    companion object {
        fun fromValue(value: String?): ShapeStyle =
            entries.firstOrNull { it.prefValue == value } ?: FUSILLI
    }
}

/**
 * How the luminance grid is turned into visible marks.
 *
 * ASCII uses the classic monospace ramp. NOTHING uses a dot matrix: bright
 * cells become large round dots, dark cells become small dots, and black
 * cells stay empty.
 */
enum class SymbolStyle(val prefValue: String) {
    ASCII("ascii"),
    NOTHING("nothing");

    companion object {
        fun fromValue(value: String?): SymbolStyle =
            entries.firstOrNull { it.prefValue == value } ?: ASCII
    }
}

/**
 * How the imported photo is fitted into the wallpaper surface.
 *
 * CONTAIN keeps the complete image visible and adds letterbox bars when the
 * aspect ratios differ. COVER fills the screen without distorting the photo,
 * cropping the overflowing edges instead.
 */
enum class PhotoFit(val prefValue: String) {
    CONTAIN("contain"),
    COVER("cover");

    companion object {
        fun fromValue(value: String?): PhotoFit =
            entries.firstOrNull { it.prefValue == value } ?: CONTAIN
    }
}

/** Storage slot for imported media. MAIN is the original single-photo style. */
enum class MediaSlot(val suffix: String) {
    MAIN(""),
    LOCK("_lock"),
    HOME("_home")
}

/** Immutable snapshot of everything the wallpaper renderer needs. */
data class WallpaperSettings(
    val style: ShapeStyle = ShapeStyle.FUSILLI,
    val columns: Int = 96,
    val speedDegPerSec: Float = 9f,
    val symbolStyle: SymbolStyle = SymbolStyle.ASCII,
    val glyphColorArgb: Int = DEFAULT_GLYPH_COLOR,
    /** 10-100 % opacity for ASCII glyphs and Nothing dots. */
    val glyphOpacityPercent: Int = DEFAULT_GLYPH_OPACITY_PERCENT,
    val backgroundColorArgb: Int = DEFAULT_BACKGROUND_COLOR,
    val targetFps: Int = DEFAULT_FPS,
    val lineWidth: Float = 1f,
    /**
     * Vertical "beat" (oscillation amplitude) of the idle waves, as a
     * percentage of the original hand-tuned range. It only affects the plain
     * WAVES state; music and the charging battery meter keep their own
     * geometry.
     */
    val waveBeatPercent: Int = DEFAULT_WAVE_BEAT_PERCENT,
    /**
     * Vertical distance between the three idle ribbons, as a percentage of
     * the original hand-tuned offsets. The anchors at both screen edges stay
     * shared.
     */
    val waveSpreadPercent: Int = DEFAULT_WAVE_SPREAD_PERCENT,
    /**
     * Opt-in real FFT visualization. It uses Android's Visualizer effect on
     * the global output mix. RECORD_AUDIO is required by the platform, but no
     * microphone samples are recorded; if the permission or device support is
     * missing the renderer keeps the procedural fallback.
     */
    val realSpectrumEnabled: Boolean = false,
    val unlockAnimationEnabled: Boolean = true,
    /**
     * Bumped whenever a new photo or animated media clip is imported. The
     * renderer uses it to reload the cached source without doing IO on every
     * frame.
     */
    val photoRevision: Int = 0,
    val photoFit: PhotoFit = PhotoFit.CONTAIN,
    /** True while the current photo revision is a GIF/video frame set. */
    val photoAnimated: Boolean = false,
    /** Independent media used by the "lock screen / home screen" style. */
    val lockMediaRevision: Int = 0,
    val lockMediaAnimated: Boolean = false,
    val homeMediaRevision: Int = 0,
    val homeMediaAnimated: Boolean = false,
    /**
     * Duration of the unlock transition in the lock/home style. The source
     * is first reduced to a coarse pixel grid and then grows back into the
     * home image.
     */
    val lockHomeTransitionMs: Int = DEFAULT_LOCK_HOME_TRANSITION_MS
) {
    fun sanitized(): WallpaperSettings = copy(
        columns = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS),
        speedDegPerSec = speedDegPerSec.coerceIn(MIN_SPEED, MAX_SPEED),
        glyphColorArgb = glyphColorArgb or ALPHA_OPAQUE,
        glyphOpacityPercent = glyphOpacityPercent.coerceIn(
            MIN_GLYPH_OPACITY_PERCENT,
            MAX_GLYPH_OPACITY_PERCENT
        ),
        backgroundColorArgb = backgroundColorArgb or ALPHA_OPAQUE,
        targetFps = if (targetFps in FPS_OPTIONS) targetFps else DEFAULT_FPS,
        lineWidth = lineWidth.coerceIn(MIN_LINE_WIDTH, MAX_LINE_WIDTH),
        waveBeatPercent = waveBeatPercent.coerceIn(
            MIN_WAVE_BEAT_PERCENT,
            MAX_WAVE_BEAT_PERCENT
        ),
        waveSpreadPercent = waveSpreadPercent.coerceIn(
            MIN_WAVE_SPREAD_PERCENT,
            MAX_WAVE_SPREAD_PERCENT
        ),
        lockHomeTransitionMs = lockHomeTransitionMs.coerceIn(
            MIN_LOCK_HOME_TRANSITION_MS,
            MAX_LOCK_HOME_TRANSITION_MS
        )
    )

    companion object {
        const val MIN_COLUMNS = 40
        const val MAX_COLUMNS = 200
        const val MIN_SPEED = 5f
        const val MAX_SPEED = 45f
        const val MIN_LINE_WIDTH = 0.5f
        const val MAX_LINE_WIDTH = 2.5f
        const val MIN_WAVE_BEAT_PERCENT = 20
        const val MAX_WAVE_BEAT_PERCENT = 200
        const val DEFAULT_WAVE_BEAT_PERCENT = 100
        const val MIN_WAVE_SPREAD_PERCENT = 50
        const val MAX_WAVE_SPREAD_PERCENT = 150
        const val DEFAULT_WAVE_SPREAD_PERCENT = 100
        const val MIN_GLYPH_OPACITY_PERCENT = 10
        const val MAX_GLYPH_OPACITY_PERCENT = 100
        const val DEFAULT_GLYPH_OPACITY_PERCENT = 100
        const val MIN_LOCK_HOME_TRANSITION_MS = 300
        const val MAX_LOCK_HOME_TRANSITION_MS = 3000
        const val DEFAULT_LOCK_HOME_TRANSITION_MS = 900
        const val DEFAULT_FPS = 60

        /** Opaque white. */
        const val DEFAULT_GLYPH_COLOR = -1

        /** Opaque black. */
        const val DEFAULT_BACKGROUND_COLOR = -0x1000000

        private const val ALPHA_OPAQUE = -0x1000000

        val FPS_OPTIONS = listOf(30, 45, 60, 90, 120)
    }
}

/**
 * SharedPreferences is deliberately used instead of DataStore here:
 * a WallpaperService can register a regular OnSharedPreferenceChangeListener
 * and update the live canvas immediately, without an async collection scope.
 */
object Prefs {
    const val FILE_NAME = "ascii_live_wallpaper"

    private const val KEY_STYLE = "shape_style"
    private const val KEY_COLUMNS = "ascii_columns"
    private const val KEY_SPEED = "rotation_speed"
    private const val KEY_SYMBOL_STYLE = "symbol_style"
    private const val KEY_GLYPH_COLOR = "glyph_color"
    private const val KEY_GLYPH_OPACITY = "glyph_opacity_percent"
    private const val KEY_BACKGROUND_COLOR = "background_color"
    private const val KEY_LEGACY_COLOR = "ascii_color"
    private const val KEY_TARGET_FPS = "target_fps"
    private const val KEY_LINE_WIDTH = "line_width"
    // Keep the old key so the value chosen in 1.4.0 survives the rename from
    // "wave height" to "wave beat".
    private const val KEY_WAVE_BEAT = "wave_height_percent"
    private const val KEY_WAVE_SPREAD = "wave_spread_percent"
    private const val KEY_REAL_SPECTRUM = "real_spectrum_enabled"
    private const val KEY_UNLOCK_ANIMATION = "unlock_animation"
    private const val KEY_PHOTO_REVISION = "photo_revision"
    private const val KEY_PHOTO_FIT = "photo_fit"
    private const val KEY_PHOTO_ANIMATED = "photo_animated"
    private const val KEY_LOCK_MEDIA_REVISION = "lock_media_revision"
    private const val KEY_LOCK_MEDIA_ANIMATED = "lock_media_animated"
    private const val KEY_HOME_MEDIA_REVISION = "home_media_revision"
    private const val KEY_HOME_MEDIA_ANIMATED = "home_media_animated"
    private const val KEY_LOCK_HOME_TRANSITION_MS = "lock_home_transition_ms"
    private const val KEY_CORNER_PHASE = "corner_phase"

    /** Preference keys that affect the renderer. Corner state is intentionally excluded. */
    val wallpaperKeys: Set<String> = setOf(
        KEY_STYLE,
        KEY_COLUMNS,
        KEY_SPEED,
        KEY_SYMBOL_STYLE,
        KEY_GLYPH_COLOR,
        KEY_GLYPH_OPACITY,
        KEY_BACKGROUND_COLOR,
        KEY_TARGET_FPS,
        KEY_LINE_WIDTH,
        KEY_WAVE_BEAT,
        KEY_WAVE_SPREAD,
        KEY_REAL_SPECTRUM,
        KEY_UNLOCK_ANIMATION,
        KEY_PHOTO_REVISION,
        KEY_PHOTO_FIT,
        KEY_PHOTO_ANIMATED,
        KEY_LOCK_MEDIA_REVISION,
        KEY_LOCK_MEDIA_ANIMATED,
        KEY_HOME_MEDIA_REVISION,
        KEY_HOME_MEDIA_ANIMATED,
        KEY_LOCK_HOME_TRANSITION_MS
    )

    fun sharedPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): WallpaperSettings {
        val p = sharedPreferences(context)
        val glyphColor = if (p.contains(KEY_GLYPH_COLOR)) {
            p.getInt(KEY_GLYPH_COLOR, WallpaperSettings.DEFAULT_GLYPH_COLOR)
        } else {
            // 1.2.4 and older stored one of three named palettes. Keep a
            // user's green/amber choice, while the old soft-white preset is
            // promoted to the new pure-white default.
            when (p.getString(KEY_LEGACY_COLOR, null)) {
                "terminal_green" -> 0xFF6DFF82.toInt()
                "amber" -> 0xFFFFB23F.toInt()
                else -> WallpaperSettings.DEFAULT_GLYPH_COLOR
            }
        }
        return WallpaperSettings(
            style = ShapeStyle.fromValue(p.getString(KEY_STYLE, null)),
            columns = p.getInt(KEY_COLUMNS, 96),
            speedDegPerSec = p.getFloat(KEY_SPEED, 9f),
            symbolStyle = SymbolStyle.fromValue(p.getString(KEY_SYMBOL_STYLE, null)),
            glyphColorArgb = glyphColor,
            glyphOpacityPercent = p.getInt(
                KEY_GLYPH_OPACITY,
                WallpaperSettings.DEFAULT_GLYPH_OPACITY_PERCENT
            ),
            backgroundColorArgb = p.getInt(
                KEY_BACKGROUND_COLOR,
                WallpaperSettings.DEFAULT_BACKGROUND_COLOR
            ),
            targetFps = p.getInt(KEY_TARGET_FPS, WallpaperSettings.DEFAULT_FPS),
            lineWidth = p.getFloat(KEY_LINE_WIDTH, 1f),
            waveBeatPercent = p.getInt(
                KEY_WAVE_BEAT,
                WallpaperSettings.DEFAULT_WAVE_BEAT_PERCENT
            ),
            waveSpreadPercent = p.getInt(
                KEY_WAVE_SPREAD,
                WallpaperSettings.DEFAULT_WAVE_SPREAD_PERCENT
            ),
            realSpectrumEnabled = p.getBoolean(KEY_REAL_SPECTRUM, false),
            unlockAnimationEnabled = p.getBoolean(KEY_UNLOCK_ANIMATION, true),
            photoRevision = p.getInt(KEY_PHOTO_REVISION, 0),
            photoFit = PhotoFit.fromValue(p.getString(KEY_PHOTO_FIT, null)),
            photoAnimated = p.getBoolean(KEY_PHOTO_ANIMATED, false),
            lockMediaRevision = p.getInt(KEY_LOCK_MEDIA_REVISION, 0),
            lockMediaAnimated = p.getBoolean(KEY_LOCK_MEDIA_ANIMATED, false),
            homeMediaRevision = p.getInt(KEY_HOME_MEDIA_REVISION, 0),
            homeMediaAnimated = p.getBoolean(KEY_HOME_MEDIA_ANIMATED, false),
            lockHomeTransitionMs = p.getInt(
                KEY_LOCK_HOME_TRANSITION_MS,
                WallpaperSettings.DEFAULT_LOCK_HOME_TRANSITION_MS
            )
        ).sanitized()
    }

    fun save(context: Context, settings: WallpaperSettings) {
        val clean = settings.sanitized()
        sharedPreferences(context).edit()
            .putString(KEY_STYLE, clean.style.prefValue)
            .putInt(KEY_COLUMNS, clean.columns)
            .putFloat(KEY_SPEED, clean.speedDegPerSec)
            .putString(KEY_SYMBOL_STYLE, clean.symbolStyle.prefValue)
            .putInt(KEY_GLYPH_COLOR, clean.glyphColorArgb)
            .putInt(KEY_GLYPH_OPACITY, clean.glyphOpacityPercent)
            .putInt(KEY_BACKGROUND_COLOR, clean.backgroundColorArgb)
            .putInt(KEY_TARGET_FPS, clean.targetFps)
            .putFloat(KEY_LINE_WIDTH, clean.lineWidth)
            .putInt(KEY_WAVE_BEAT, clean.waveBeatPercent)
            .putInt(KEY_WAVE_SPREAD, clean.waveSpreadPercent)
            .putBoolean(KEY_REAL_SPECTRUM, clean.realSpectrumEnabled)
            .putBoolean(KEY_UNLOCK_ANIMATION, clean.unlockAnimationEnabled)
            .putInt(KEY_PHOTO_REVISION, clean.photoRevision)
            .putString(KEY_PHOTO_FIT, clean.photoFit.prefValue)
            .putBoolean(KEY_PHOTO_ANIMATED, clean.photoAnimated)
            .putInt(KEY_LOCK_MEDIA_REVISION, clean.lockMediaRevision)
            .putBoolean(KEY_LOCK_MEDIA_ANIMATED, clean.lockMediaAnimated)
            .putInt(KEY_HOME_MEDIA_REVISION, clean.homeMediaRevision)
            .putBoolean(KEY_HOME_MEDIA_ANIMATED, clean.homeMediaAnimated)
            .putInt(KEY_LOCK_HOME_TRANSITION_MS, clean.lockHomeTransitionMs)
            .apply()
    }

    /** 0 = A(top-left) / B(bottom-right), 1 = A(bottom-left) / B(top-right). */
    fun cornerPhase(context: Context): Int =
        sharedPreferences(context).getInt(KEY_CORNER_PHASE, 0) and 1

    fun saveCornerPhase(context: Context, phase: Int) {
        sharedPreferences(context).edit()
            .putInt(KEY_CORNER_PHASE, phase and 1)
            .apply()
    }
}
