package com.asciiwallpaper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private val AppBackground = Color(0xFF040705)
private val Panel = Color(0xFF09110B)
private val PanelBorder = Color(0xFF1D3423)
private val AccentGreen = Color(0xFF95F7A4)
private val AccentGreenStrong = Color(0xFF6DFF82)
private val TextPrimary = Color(0xFFE3EFE4)
private val TextMuted = Color(0xFF91A794)
private val Amber = Color(0xFFFFB23F)

private val GLYPH_COLOR_PRESETS = intArrayOf(
    0xFFFFFFFF.toInt(),
    0xFF6DFF82.toInt(),
    0xFFFFB23F.toInt(),
    0xFF6DE8FF.toInt(),
    0xFFFF8BCB.toInt(),
    0xFFB79CFF.toInt()
)

private val BACKGROUND_COLOR_PRESETS = intArrayOf(
    0xFF000000.toInt(),
    0xFF0A0F0C.toInt(),
    0xFF101820.toInt(),
    0xFF18101E.toInt(),
    0xFF07131A.toInt(),
    0xFF20140A.toInt()
)

/** Dark terminal-inspired Material 3 theme used only by the settings activity. */
@Composable
fun AsciiWallpaperTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val mono = FontFamily.Monospace

    val typography = base.copy(
        headlineMedium = base.headlineMedium.copy(
            fontFamily = mono,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1.2).sp
        ),
        titleLarge = base.titleLarge.copy(
            fontFamily = mono,
            fontWeight = FontWeight.SemiBold
        ),
        titleMedium = base.titleMedium.copy(
            fontFamily = mono,
            fontWeight = FontWeight.SemiBold
        ),
        bodyMedium = base.bodyMedium.copy(
            fontFamily = FontFamily.SansSerif
        ),
        bodySmall = base.bodySmall.copy(
            fontFamily = FontFamily.SansSerif
        ),
        labelSmall = base.labelSmall.copy(
            fontFamily = mono,
            letterSpacing = 0.7.sp
        ),
        labelLarge = base.labelLarge.copy(
            fontFamily = mono,
            letterSpacing = 0.8.sp,
            fontWeight = FontWeight.Bold
        )
    )

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AccentGreen,
            onPrimary = Color(0xFF032108),
            primaryContainer = Color(0xFF173B1E),
            onPrimaryContainer = Color(0xFFD4FFD8),
            secondary = Amber,
            onSecondary = Color(0xFF261600),
            background = AppBackground,
            onBackground = TextPrimary,
            surface = Panel,
            onSurface = TextPrimary,
            surfaceVariant = Color(0xFF101A12),
            onSurfaceVariant = TextMuted,
            outline = PanelBorder,
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005)
        ),
        typography = typography,
        content = content
    )
}

/**
 * Compose settings screen. Style/color changes are committed immediately.
 * Slider values drive the preview live, and are committed when the user lifts
 * their finger; the wallpaper engine observes SharedPreferences and updates
 * its canvas without being restarted.
 */
@Composable
fun SettingsScreen(
    initialSettings: WallpaperSettings,
    onSettingsChanged: (WallpaperSettings) -> Unit,
    onInstallWallpaper: () -> Unit
) {
    var styleValue by rememberSaveable { mutableStateOf(initialSettings.style.prefValue) }
    var symbolStyleValue by rememberSaveable {
        mutableStateOf(initialSettings.symbolStyle.prefValue)
    }
    var glyphColorValue by rememberSaveable {
        mutableIntStateOf(initialSettings.glyphColorArgb)
    }
    var glyphOpacity by rememberSaveable {
        mutableIntStateOf(initialSettings.glyphOpacityPercent)
    }
    var backgroundColorValue by rememberSaveable {
        mutableIntStateOf(initialSettings.backgroundColorArgb)
    }
    var columns by rememberSaveable { mutableStateOf(initialSettings.columns.toFloat()) }
    var speed by rememberSaveable { mutableStateOf(initialSettings.speedDegPerSec) }
    var waveBeat by rememberSaveable {
        mutableIntStateOf(initialSettings.waveBeatPercent)
    }
    var waveSpread by rememberSaveable {
        mutableIntStateOf(initialSettings.waveSpreadPercent)
    }
    var realSpectrum by rememberSaveable {
        mutableStateOf(initialSettings.realSpectrumEnabled)
    }
    var spectrumDenied by rememberSaveable { mutableStateOf(false) }
    var targetFps by rememberSaveable { mutableIntStateOf(initialSettings.targetFps) }
    var lineWidth by rememberSaveable { mutableStateOf(initialSettings.lineWidth) }
    var unlockAnimationEnabled by rememberSaveable {
        mutableStateOf(initialSettings.unlockAnimationEnabled)
    }
    var photoFitValue by rememberSaveable {
        mutableStateOf(initialSettings.photoFit.prefValue)
    }
    val context = LocalContext.current
    val appContext = context.applicationContext
    var audioPermissionGranted by remember {
        mutableStateOf(hasAudioPermission(appContext))
    }
    var photoRevision by rememberSaveable {
        mutableIntStateOf(initialSettings.photoRevision)
    }
    var photoAnimated by rememberSaveable {
        mutableStateOf(initialSettings.photoAnimated)
    }
    var photoReady by rememberSaveable {
        mutableStateOf(MediaAsciiStore.hasVisual(appContext))
    }
    var photoBusy by remember { mutableStateOf(false) }
    var photoError by remember { mutableStateOf(false) }
    var lockMediaRevision by rememberSaveable {
        mutableIntStateOf(initialSettings.lockMediaRevision)
    }
    var lockMediaAnimated by rememberSaveable {
        mutableStateOf(initialSettings.lockMediaAnimated)
    }
    var homeMediaRevision by rememberSaveable {
        mutableIntStateOf(initialSettings.homeMediaRevision)
    }
    var homeMediaAnimated by rememberSaveable {
        mutableStateOf(initialSettings.homeMediaAnimated)
    }
    var lockHomeTransitionMs by rememberSaveable {
        mutableIntStateOf(initialSettings.lockHomeTransitionMs)
    }
    var lockMediaReady by rememberSaveable {
        mutableStateOf(
            MediaAsciiStore.hasVisual(appContext, MediaSlot.LOCK)
        )
    }
    var homeMediaReady by rememberSaveable {
        mutableStateOf(
            MediaAsciiStore.hasVisual(appContext, MediaSlot.HOME)
        )
    }
    var lockMediaBusy by remember { mutableStateOf(false) }
    var homeMediaBusy by remember { mutableStateOf(false) }
    var lockMediaError by remember { mutableStateOf(false) }
    var homeMediaError by remember { mutableStateOf(false) }
    var showPhotoFitSheet by rememberSaveable { mutableStateOf(false) }
    var showGlyphColorPicker by rememberSaveable { mutableStateOf(false) }
    var showBackgroundColorPicker by rememberSaveable { mutableStateOf(false) }
    val photoScope = rememberCoroutineScope()

    val style = remember(styleValue) { ShapeStyle.fromValue(styleValue) }
    val symbolStyle = remember(symbolStyleValue) { SymbolStyle.fromValue(symbolStyleValue) }
    val photoFit = remember(photoFitValue) { PhotoFit.fromValue(photoFitValue) }
    val shownColumns = columns.roundToInt()
    val currentSettings = WallpaperSettings(
        style = style,
        columns = shownColumns,
        speedDegPerSec = speed,
        symbolStyle = symbolStyle,
        glyphColorArgb = glyphColorValue,
        glyphOpacityPercent = glyphOpacity,
        backgroundColorArgb = backgroundColorValue,
        targetFps = targetFps,
        lineWidth = lineWidth,
        waveBeatPercent = waveBeat,
        waveSpreadPercent = waveSpread,
        realSpectrumEnabled = realSpectrum,
        unlockAnimationEnabled = unlockAnimationEnabled,
        photoRevision = photoRevision,
        photoFit = photoFit,
        photoAnimated = photoAnimated,
        lockMediaRevision = lockMediaRevision,
        lockMediaAnimated = lockMediaAnimated,
        homeMediaRevision = homeMediaRevision,
        homeMediaAnimated = homeMediaAnimated,
        lockHomeTransitionMs = lockHomeTransitionMs
    ).sanitized()

    fun commit(updated: WallpaperSettings = currentSettings) {
        onSettingsChanged(updated.sanitized())
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        photoBusy = true
        photoError = false
        photoScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    MediaAsciiStore.import(appContext, uri)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            photoBusy = false
            if (result == null) {
                photoError = true
            } else {
                val revision = when (result) {
                    is MediaAsciiStore.ImportResult.Still -> result.revision
                    is MediaAsciiStore.ImportResult.Animated -> result.revision
                }
                photoRevision = revision
                photoAnimated = result is MediaAsciiStore.ImportResult.Animated
                photoReady = true
                // Preserve whatever style/resolution is current. The user may
                // have switched away while the media was being processed.
                onSettingsChanged(
                    Prefs.load(appContext)
                        .copy(
                            photoRevision = revision,
                            photoAnimated = result is MediaAsciiStore.ImportResult.Animated
                        )
                        .sanitized()
                )
                showPhotoFitSheet = true
            }
        }
    }

    val lockMediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        lockMediaBusy = true
        lockMediaError = false
        photoScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    MediaAsciiStore.import(appContext, uri, MediaSlot.LOCK)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            lockMediaBusy = false
            if (result == null) {
                lockMediaError = true
            } else {
                val revision = when (result) {
                    is MediaAsciiStore.ImportResult.Still -> result.revision
                    is MediaAsciiStore.ImportResult.Animated -> result.revision
                }
                lockMediaRevision = revision
                lockMediaAnimated = result is MediaAsciiStore.ImportResult.Animated
                lockMediaReady = true
                onSettingsChanged(
                    Prefs.load(appContext)
                        .copy(
                            lockMediaRevision = revision,
                            lockMediaAnimated =
                                result is MediaAsciiStore.ImportResult.Animated
                        )
                        .sanitized()
                )
            }
        }
    }

    val homeMediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        homeMediaBusy = true
        homeMediaError = false
        photoScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    MediaAsciiStore.import(appContext, uri, MediaSlot.HOME)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            homeMediaBusy = false
            if (result == null) {
                homeMediaError = true
            } else {
                val revision = when (result) {
                    is MediaAsciiStore.ImportResult.Still -> result.revision
                    is MediaAsciiStore.ImportResult.Animated -> result.revision
                }
                homeMediaRevision = revision
                homeMediaAnimated = result is MediaAsciiStore.ImportResult.Animated
                homeMediaReady = true
                onSettingsChanged(
                    Prefs.load(appContext)
                        .copy(
                            homeMediaRevision = revision,
                            homeMediaAnimated =
                                result is MediaAsciiStore.ImportResult.Animated
                        )
                        .sanitized()
                )
            }
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        audioPermissionGranted = granted
        realSpectrum = granted
        spectrumDenied = !granted
        commit(currentSettings.copy(realSpectrumEnabled = granted))
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, appContext, realSpectrum) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = hasAudioPermission(appContext)
                audioPermissionGranted = granted
                if (!granted && realSpectrum) {
                    // The permission can be revoked from system settings while
                    // the feature is enabled. Keep the stored setting truthful
                    // so the wallpaper does not keep trying to start capture.
                    realSpectrum = false
                    spectrumDenied = true
                    onSettingsChanged(
                        Prefs.load(appContext)
                            .copy(realSpectrumEnabled = false)
                            .sanitized()
                    )
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun selectStyle(newStyle: ShapeStyle) {
        if (style == newStyle) return
        styleValue = newStyle.prefValue
        commit(currentSettings.copy(style = newStyle))
    }

    fun selectFps(fps: Int) {
        if (targetFps == fps) return
        targetFps = fps
        commit(currentSettings.copy(targetFps = fps))
    }

    fun selectSymbolStyle(newStyle: SymbolStyle) {
        if (symbolStyle == newStyle) return
        symbolStyleValue = newStyle.prefValue
        commit(currentSettings.copy(symbolStyle = newStyle))
    }

    fun selectGlyphColor(argb: Int) {
        val opaque = argb or 0xFF000000.toInt()
        glyphColorValue = opaque
        commit(currentSettings.copy(glyphColorArgb = opaque))
    }

    fun selectBackgroundColor(argb: Int) {
        val opaque = argb or 0xFF000000.toInt()
        backgroundColorValue = opaque
        commit(currentSettings.copy(backgroundColorArgb = opaque))
    }

    fun selectPhotoFit(fit: PhotoFit) {
        if (photoFit == fit) return
        photoFitValue = fit.prefValue
        commit(currentSettings.copy(photoFit = fit))
    }

    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AppBackground
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xFF071009),
                        0.42f to AppBackground,
                        1f to AppBackground
                    )
                )
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Header()

                AsciiPreviewCard(
                    settings = currentSettings,
                    columns = shownColumns,
                    photoReady = photoReady,
                    photoBusy = photoBusy
                )

                SettingsCard {
                    SectionLabel(stringResource(R.string.section_shape))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.style_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(6.dp))
                    StyleOption(
                        title = stringResource(R.string.style_fusilli),
                        detail = stringResource(R.string.style_fusilli_detail),
                        tag = "FUSILLI",
                        selected = style == ShapeStyle.FUSILLI,
                        onClick = { selectStyle(ShapeStyle.FUSILLI) }
                    )
                    StyleOption(
                        title = stringResource(R.string.style_cubes),
                        detail = stringResource(R.string.style_cubes_detail),
                        tag = "CUBES",
                        selected = style == ShapeStyle.CUBES,
                        onClick = { selectStyle(ShapeStyle.CUBES) }
                    )
                    StyleOption(
                        title = stringResource(R.string.style_big_cube),
                        detail = stringResource(R.string.style_big_cube_detail),
                        tag = "BIG CUBE",
                        selected = style == ShapeStyle.BIG_CUBE,
                        onClick = { selectStyle(ShapeStyle.BIG_CUBE) }
                    )
                    StyleOption(
                        title = stringResource(R.string.style_dna),
                        detail = stringResource(R.string.style_dna_detail),
                        tag = "DNA",
                        selected = style == ShapeStyle.DNA,
                        onClick = { selectStyle(ShapeStyle.DNA) }
                    )
                    StyleOption(
                        title = stringResource(R.string.style_black_hole),
                        detail = stringResource(R.string.style_black_hole_detail),
                        tag = "BLACK HOLE",
                        selected = style == ShapeStyle.BLACK_HOLE,
                        onClick = { selectStyle(ShapeStyle.BLACK_HOLE) }
                    )
                    StyleOption(
                        title = stringResource(R.string.style_duck),
                        detail = stringResource(R.string.style_duck_detail),
                        tag = "3D DUCK",
                        selected = style == ShapeStyle.DUCK,
                        onClick = { selectStyle(ShapeStyle.DUCK) }
                    )
                    StyleOption(
                        title = stringResource(R.string.style_donut),
                        detail = stringResource(R.string.style_donut_detail),
                        tag = "3D DONUT",
                        selected = style == ShapeStyle.DONUT,
                        onClick = { selectStyle(ShapeStyle.DONUT) }
                    )
                    StyleOption(
                        title = stringResource(R.string.style_fastfetch),
                        detail = stringResource(R.string.style_fastfetch_detail),
                        tag = "SYSINFO",
                        selected = style == ShapeStyle.FASTFETCH,
                        onClick = { selectStyle(ShapeStyle.FASTFETCH) }
                    )
                    StyleOption(
                        title = stringResource(R.string.style_android_logo),
                        detail = stringResource(R.string.style_android_logo_detail),
                        tag = "ANDROID",
                        selected = style == ShapeStyle.ANDROID_LOGO,
                        onClick = { selectStyle(ShapeStyle.ANDROID_LOGO) }
                    )
                    StyleOption(
                        title = stringResource(R.string.style_waves),
                        detail = stringResource(R.string.style_waves_detail),
                        tag = "AUDIO",
                        selected = style == ShapeStyle.WAVES,
                        onClick = { selectStyle(ShapeStyle.WAVES) }
                    )
                    StyleOption(
                        title = stringResource(R.string.style_lock_home),
                        detail = stringResource(R.string.style_lock_home_detail),
                        tag = "LOCK / HOME",
                        selected = style == ShapeStyle.LOCK_HOME,
                        onClick = { selectStyle(ShapeStyle.LOCK_HOME) }
                    )
                    StyleOption(
                        title = stringResource(R.string.style_photo),
                        detail = stringResource(R.string.style_photo_detail),
                        tag = "MEDIA",
                        selected = style == ShapeStyle.PHOTO,
                        onClick = { selectStyle(ShapeStyle.PHOTO) }
                    )
                }

                if (style == ShapeStyle.PHOTO) {
                    PhotoPickerCard(
                        ready = photoReady,
                        busy = photoBusy,
                        error = photoError,
                        fit = photoFit,
                        onPick = {
                            photoError = false
                            photoPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                )
                            )
                        },
                        onConfigureFit = { showPhotoFitSheet = true }
                    )
                } else if (style == ShapeStyle.LOCK_HOME) {
                    LockHomePickerCard(
                        lockReady = lockMediaReady,
                        homeReady = homeMediaReady,
                        lockBusy = lockMediaBusy,
                        homeBusy = homeMediaBusy,
                        lockError = lockMediaError,
                        homeError = homeMediaError,
                        fit = photoFit,
                        transitionMs = lockHomeTransitionMs,
                        onPickLock = {
                            lockMediaError = false
                            lockMediaPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                )
                            )
                        },
                        onPickHome = {
                            homeMediaError = false
                            homeMediaPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                )
                            )
                        },
                        onConfigureFit = { showPhotoFitSheet = true },
                        onTransitionChange = { lockHomeTransitionMs = it },
                        onTransitionFinished = {
                            commit(currentSettings.copy(
                                lockHomeTransitionMs = lockHomeTransitionMs
                            ))
                        }
                    )
                }

                if (style != ShapeStyle.ANDROID_LOGO) {
                    SettingsCard {
                        SectionLabel(
                            stringResource(
                                if (style == ShapeStyle.PHOTO) {
                                    R.string.section_resolution_photo
                                } else {
                                    R.string.section_resolution
                                }
                            )
                        )
                        Spacer(Modifier.height(10.dp))
                        LabelWithValue(
                            title = stringResource(
                                if (style == ShapeStyle.PHOTO) {
                                    R.string.photo_resolution_title
                                } else {
                                    R.string.resolution_title
                                }
                            ),
                            value = stringResource(R.string.resolution_value, shownColumns)
                        )
                        Slider(
                            value = columns,
                            onValueChange = { columns = it },
                            onValueChangeFinished = {
                                commit(currentSettings.copy(columns = columns.roundToInt()))
                            },
                            valueRange = WallpaperSettings.MIN_COLUMNS.toFloat()..
                                WallpaperSettings.MAX_COLUMNS.toFloat(),
                            steps = 39,
                            colors = asciiSliderColors()
                        )
                        SliderLimits(
                            start = WallpaperSettings.MIN_COLUMNS.toString(),
                            end = WallpaperSettings.MAX_COLUMNS.toString()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(
                                if (style == ShapeStyle.PHOTO) {
                                    R.string.photo_resolution_hint
                                } else {
                                    R.string.resolution_hint
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }

                if (style != ShapeStyle.PHOTO &&
                    style != ShapeStyle.FASTFETCH &&
                    style != ShapeStyle.ANDROID_LOGO
                ) {
                    SettingsCard {
                        SectionLabel(stringResource(R.string.section_motion))
                        Spacer(Modifier.height(10.dp))
                        if (style != ShapeStyle.LOCK_HOME) {
                        LabelWithValue(
                            title = stringResource(R.string.speed_title),
                            value = stringResource(R.string.speed_value, speed.roundToInt())
                        )
                        Slider(
                            value = speed,
                            onValueChange = { speed = it },
                            onValueChangeFinished = {
                                commit(currentSettings.copy(speedDegPerSec = speed))
                            },
                            valueRange = WallpaperSettings.MIN_SPEED..WallpaperSettings.MAX_SPEED,
                            steps = 39,
                            colors = asciiSliderColors()
                        )
                        SliderLimits(
                            start = stringResource(R.string.speed_slow),
                            end = stringResource(R.string.speed_fast)
                        )
                        if (style == ShapeStyle.WAVES) {
                            Spacer(Modifier.height(16.dp))
                            LabelWithValue(
                                title = stringResource(R.string.wave_beat_title),
                                value = stringResource(
                                    R.string.wave_beat_value,
                                    waveBeat
                                )
                            )
                            Slider(
                                value = waveBeat.toFloat(),
                                onValueChange = { waveBeat = it.roundToInt() },
                                onValueChangeFinished = {
                                    commit(currentSettings.copy(
                                        waveBeatPercent = waveBeat
                                    ))
                                },
                                valueRange =
                                    WallpaperSettings.MIN_WAVE_BEAT_PERCENT.toFloat()..
                                        WallpaperSettings.MAX_WAVE_BEAT_PERCENT.toFloat(),
                                steps = 35,
                                colors = asciiSliderColors()
                            )
                            SliderLimits(
                                start = stringResource(R.string.wave_beat_low),
                                end = stringResource(R.string.wave_beat_high)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.wave_beat_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                            Spacer(Modifier.height(14.dp))
                            LabelWithValue(
                                title = stringResource(R.string.wave_spread_title),
                                value = stringResource(
                                    R.string.wave_spread_value,
                                    waveSpread
                                )
                            )
                            Slider(
                                value = waveSpread.toFloat(),
                                onValueChange = { waveSpread = it.roundToInt() },
                                onValueChangeFinished = {
                                    commit(currentSettings.copy(
                                        waveSpreadPercent = waveSpread
                                    ))
                                },
                                valueRange =
                                    WallpaperSettings.MIN_WAVE_SPREAD_PERCENT.toFloat()..
                                        WallpaperSettings.MAX_WAVE_SPREAD_PERCENT.toFloat(),
                                steps = 19,
                                colors = asciiSliderColors()
                            )
                            SliderLimits(
                                start = stringResource(R.string.wave_spread_low),
                                end = stringResource(R.string.wave_spread_high)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.wave_spread_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                            Spacer(Modifier.height(14.dp))
                            RealSpectrumRow(
                                checked = realSpectrum && audioPermissionGranted,
                                permissionDenied = spectrumDenied,
                                onToggle = { enabled ->
                                    when {
                                        !enabled -> {
                                            realSpectrum = false
                                            spectrumDenied = false
                                            commit(
                                                currentSettings.copy(
                                                    realSpectrumEnabled = false
                                                )
                                            )
                                        }

                                        audioPermissionGranted -> {
                                            realSpectrum = true
                                            spectrumDenied = false
                                            commit(
                                                currentSettings.copy(
                                                    realSpectrumEnabled = true
                                                )
                                            )
                                        }

                                        else -> audioPermissionLauncher.launch(
                                            Manifest.permission.RECORD_AUDIO
                                        )
                                    }
                                }
                            )
                        }
                        } // LOCK_HOME has no spinning object to tune.
                        Spacer(Modifier.height(16.dp))
                        LabelWithValue(
                            title = stringResource(R.string.fps_title),
                            value = stringResource(R.string.fps_value, targetFps)
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            WallpaperSettings.FPS_OPTIONS.forEach { fps ->
                                FpsChoice(
                                    modifier = Modifier.weight(1f),
                                    fps = fps,
                                    selected = targetFps == fps,
                                    onClick = { selectFps(fps) }
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.fps_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }

                    if (style != ShapeStyle.LOCK_HOME) {
                    SettingsCard {
                        SectionLabel(stringResource(R.string.section_lines))
                        Spacer(Modifier.height(10.dp))
                        LabelWithValue(
                            title = stringResource(R.string.line_width_title),
                            value = stringResource(
                                R.string.line_width_value,
                                String.format(Locale.getDefault(), "%.1f", lineWidth)
                            )
                        )
                        Slider(
                            value = lineWidth,
                            onValueChange = { lineWidth = it },
                            onValueChangeFinished = {
                                commit(currentSettings.copy(lineWidth = lineWidth))
                            },
                            valueRange = WallpaperSettings.MIN_LINE_WIDTH..
                                WallpaperSettings.MAX_LINE_WIDTH,
                            steps = 19,
                            colors = asciiSliderColors()
                        )
                        SliderLimits(
                            start = stringResource(R.string.line_width_thin),
                            end = stringResource(R.string.line_width_thick)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.line_width_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    }

                    if (style != ShapeStyle.WAVES &&
                        style != ShapeStyle.LOCK_HOME
                    ) {
                        UnlockNoteCard(
                            enabled = unlockAnimationEnabled,
                            onToggle = { enabled ->
                                unlockAnimationEnabled = enabled
                                commit(currentSettings.copy(
                                    unlockAnimationEnabled = enabled
                                ))
                            }
                        )
                    }
                }

                SettingsCard {
                    SectionLabel(
                        stringResource(
                            if (style == ShapeStyle.PHOTO) {
                                R.string.section_symbols_color_photo
                            } else {
                                R.string.section_symbols_color
                            }
                        )
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.symbol_style_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.symbol_style_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SymbolStyleChoice(
                            modifier = Modifier.weight(1f),
                            symbolStyle = SymbolStyle.ASCII,
                            selected = symbolStyle == SymbolStyle.ASCII,
                            glyphColor = glyphColorValue,
                            onClick = { selectSymbolStyle(SymbolStyle.ASCII) }
                        )
                        SymbolStyleChoice(
                            modifier = Modifier.weight(1f),
                            symbolStyle = SymbolStyle.NOTHING,
                            selected = symbolStyle == SymbolStyle.NOTHING,
                            glyphColor = glyphColorValue,
                            onClick = { selectSymbolStyle(SymbolStyle.NOTHING) }
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    ColorSettingRow(
                        title = stringResource(R.string.glyph_color_title),
                        argb = glyphColorValue,
                        onEdit = { showGlyphColorPicker = true }
                    )
                    Spacer(Modifier.height(8.dp))
                    ColorSettingRow(
                        title = stringResource(R.string.background_color_title),
                        argb = backgroundColorValue,
                        onEdit = { showBackgroundColorPicker = true }
                    )
                    Spacer(Modifier.height(14.dp))
                    LabelWithValue(
                        title = stringResource(R.string.glyph_opacity_title),
                        value = stringResource(
                            R.string.glyph_opacity_value,
                            glyphOpacity
                        )
                    )
                    Slider(
                        value = glyphOpacity.toFloat(),
                        onValueChange = { glyphOpacity = it.roundToInt() },
                        onValueChangeFinished = {
                            commit(currentSettings.copy(
                                glyphOpacityPercent = glyphOpacity
                            ))
                        },
                        valueRange = WallpaperSettings.MIN_GLYPH_OPACITY_PERCENT
                            .toFloat()..WallpaperSettings.MAX_GLYPH_OPACITY_PERCENT
                            .toFloat(),
                        steps = 8,
                        colors = asciiSliderColors()
                    )
                    SliderLimits(
                        start = stringResource(R.string.glyph_opacity_soft),
                        end = stringResource(R.string.glyph_opacity_solid)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.glyph_opacity_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.color_custom_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.color_system_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            commit()
                            onInstallWallpaper()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentGreenStrong,
                            contentColor = Color(0xFF041007)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.install_wallpaper),
                            style = MaterialTheme.typography.labelLarge,
                            letterSpacing = 0.8.sp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("→", fontSize = 22.sp, fontWeight = FontWeight.Black)
                    }
                    Text(
                        text = stringResource(R.string.install_hint),
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_live_note),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentGreen.copy(alpha = 0.78f),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "ASCII LIVE WALLPAPER  /  v1.6.0",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    if (showPhotoFitSheet &&
        (photoReady ||
            (style == ShapeStyle.LOCK_HOME && (lockMediaReady || homeMediaReady)))
    ) {
        PhotoFitSheet(
            selectedFit = photoFit,
            onSelect = ::selectPhotoFit,
            onDismiss = { showPhotoFitSheet = false }
        )
    }

    if (showGlyphColorPicker) {
        ColorPickerDialog(
            title = stringResource(R.string.glyph_color_title),
            initialColor = glyphColorValue,
            presets = GLYPH_COLOR_PRESETS,
            onDismiss = { showGlyphColorPicker = false },
            onConfirm = { color ->
                selectGlyphColor(color)
                showGlyphColorPicker = false
            }
        )
    }

    if (showBackgroundColorPicker) {
        ColorPickerDialog(
            title = stringResource(R.string.background_color_title),
            initialColor = backgroundColorValue,
            presets = BACKGROUND_COLOR_PRESETS,
            onDismiss = { showBackgroundColorPicker = false },
            onConfirm = { color ->
                selectBackgroundColor(color)
                showBackgroundColorPicker = false
            }
        )
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(AccentGreenStrong)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "ASCII // LIVE WALLPAPER",
                style = MaterialTheme.typography.labelSmall,
                color = AccentGreen,
                letterSpacing = 1.8.sp
            )
        }
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary
        )
        Text(
            text = stringResource(R.string.settings_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted
        )
    }
}

@Composable
private fun AsciiPreviewCard(
    settings: WallpaperSettings,
    columns: Int,
    photoReady: Boolean,
    photoBusy: Boolean
) {
    val shape = RoundedCornerShape(18.dp)
    val previewDescription = stringResource(R.string.preview_content_description)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.preview_title),
                style = MaterialTheme.typography.labelSmall,
                color = AccentGreen
            )
            Text(
                text = stringResource(R.string.preview_stats, columns, settings.targetFps),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(Color.Black)
                .border(1.dp, PanelBorder, shape)
                .semantics {
                    contentDescription = previewDescription
                }
        ) {
            LiveAsciiPreview(
                settings = settings,
                modifier = Modifier.fillMaxSize()
            )
            if (settings.style == ShapeStyle.PHOTO && (!photoReady || photoBusy)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .border(1.dp, PanelBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(
                            if (photoBusy) {
                                R.string.photo_preview_busy
                            } else {
                                R.string.photo_preview_missing
                            }
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentGreen
                    )
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.58f))
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(AccentGreenStrong)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.preview_live),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = AccentGreenStrong
                )
            }
            Text(
                text = previewStyleBadge(settings.style),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = Color.White.copy(alpha = 0.56f)
            )
        }
    }
}

@Composable
private fun LockHomePickerCard(
    lockReady: Boolean,
    homeReady: Boolean,
    lockBusy: Boolean,
    homeBusy: Boolean,
    lockError: Boolean,
    homeError: Boolean,
    fit: PhotoFit,
    transitionMs: Int,
    onPickLock: () -> Unit,
    onPickHome: () -> Unit,
    onConfigureFit: () -> Unit,
    onTransitionChange: (Int) -> Unit,
    onTransitionFinished: () -> Unit
) {
    SettingsCard {
        SectionLabel(stringResource(R.string.section_lock_home))
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.lock_home_title),
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.lock_home_hint),
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LockHomeSlotCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.lock_home_lock),
                icon = "◐",
                ready = lockReady,
                busy = lockBusy,
                error = lockError,
                onClick = onPickLock
            )
            LockHomeSlotCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.lock_home_home),
                icon = "▣",
                ready = homeReady,
                busy = homeBusy,
                error = homeError,
                onClick = onPickHome
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0B150D))
                .border(1.dp, PanelBorder, RoundedCornerShape(12.dp))
                .padding(start = 12.dp, top = 7.dp, bottom = 7.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.photo_fit_row_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentGreen
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.photo_fit_current,
                        stringResource(
                            if (fit == PhotoFit.CONTAIN) {
                                R.string.photo_fit_row_contain
                            } else {
                                R.string.photo_fit_row_cover
                            }
                        )
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
            }
            TextButton(
                onClick = onConfigureFit,
                enabled = lockReady || homeReady
            ) {
                Text(
                    text = stringResource(R.string.photo_fit_change),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (lockReady || homeReady) AccentGreen else TextMuted
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        LabelWithValue(
            title = stringResource(R.string.lock_home_transition_title),
            value = stringResource(
                R.string.lock_home_transition_value,
                String.format(Locale.getDefault(), "%.1f", transitionMs / 1000f)
            )
        )
        Slider(
            value = transitionMs / 1000f,
            onValueChange = { onTransitionChange((it * 1000f).roundToInt()) },
            onValueChangeFinished = onTransitionFinished,
            valueRange = WallpaperSettings.MIN_LOCK_HOME_TRANSITION_MS / 1000f..
                WallpaperSettings.MAX_LOCK_HOME_TRANSITION_MS / 1000f,
            steps = 26,
            colors = asciiSliderColors()
        )
        SliderLimits(
            start = stringResource(R.string.lock_home_transition_fast),
            end = stringResource(R.string.lock_home_transition_slow)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.lock_home_transition_hint),
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
    }
}

@Composable
private fun LockHomeSlotCard(
    modifier: Modifier = Modifier,
    title: String,
    icon: String,
    ready: Boolean,
    busy: Boolean,
    error: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF0B150D))
            .border(
                1.dp,
                if (error) MaterialTheme.colorScheme.error else PanelBorder,
                shape
            )
            .padding(10.dp)
    ) {
        Text(
            text = icon,
            style = MaterialTheme.typography.headlineMedium,
            color = if (ready) AccentGreen else TextMuted
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = TextPrimary
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(
                when {
                    busy -> R.string.photo_processing_button
                    error -> R.string.lock_home_slot_error
                    ready -> R.string.lock_home_slot_ready
                    else -> R.string.lock_home_slot_empty
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = when {
                error -> MaterialTheme.colorScheme.error
                ready -> AccentGreen
                else -> TextMuted
            }
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onClick,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 6.dp,
                vertical = 7.dp
            ),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF173B1E),
                contentColor = AccentGreen,
                disabledContainerColor = Panel,
                disabledContentColor = TextMuted
            )
        ) {
            Text(
                text = stringResource(
                    if (ready) R.string.lock_home_replace else R.string.lock_home_pick
                ),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun PhotoPickerCard(
    ready: Boolean,
    busy: Boolean,
    error: Boolean,
    fit: PhotoFit,
    onPick: () -> Unit,
    onConfigureFit: () -> Unit
) {
    val fitShape = RoundedCornerShape(12.dp)
    SettingsCard {
        SectionLabel(stringResource(R.string.section_photo))
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.photo_title),
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                when {
                    busy -> R.string.photo_processing
                    error -> R.string.photo_error
                    ready -> R.string.photo_ready
                    else -> R.string.photo_missing
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = when {
                error -> MaterialTheme.colorScheme.error
                ready -> AccentGreen
                else -> TextMuted
            }
        )
        if (ready) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(fitShape)
                    .background(Color(0xFF0B150D))
                    .border(1.dp, PanelBorder, fitShape)
                    .padding(start = 12.dp, top = 7.dp, bottom = 7.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.photo_fit_row_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentGreen
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            R.string.photo_fit_current,
                            stringResource(
                                if (fit == PhotoFit.CONTAIN) {
                                    R.string.photo_fit_row_contain
                                } else {
                                    R.string.photo_fit_row_cover
                                }
                            )
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                }
                TextButton(onClick = onConfigureFit) {
                    Text(
                        text = stringResource(R.string.photo_fit_change),
                        style = MaterialTheme.typography.labelLarge,
                        color = AccentGreen
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onPick,
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF173B1E),
                contentColor = AccentGreen,
                disabledContainerColor = Panel,
                disabledContentColor = TextMuted
            )
        ) {
            Text(
                text = stringResource(
                    when {
                        busy -> R.string.photo_processing_button
                        ready -> R.string.photo_replace
                        else -> R.string.photo_pick
                    }
                ),
                style = MaterialTheme.typography.labelLarge
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.photo_hint),
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoFitSheet(
    selectedFit: PhotoFit,
    onSelect: (PhotoFit) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val sheetScrollState = rememberScrollState()

    fun hideSheet() {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0B150D),
        contentColor = TextPrimary,
        scrimColor = Color.Black.copy(alpha = 0.66f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(sheetScrollState)
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            SectionLabel(stringResource(R.string.photo_fit_sheet_label))
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.photo_fit_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.photo_fit_sheet_hint),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
            Spacer(Modifier.height(14.dp))
            StyleOption(
                title = stringResource(R.string.photo_fit_contain_title),
                detail = stringResource(R.string.photo_fit_contain_detail),
                tag = "FIT",
                selected = selectedFit == PhotoFit.CONTAIN,
                onClick = {
                    onSelect(PhotoFit.CONTAIN)
                    hideSheet()
                }
            )
            StyleOption(
                title = stringResource(R.string.photo_fit_cover_title),
                detail = stringResource(R.string.photo_fit_cover_detail),
                tag = "FULL",
                selected = selectedFit == PhotoFit.COVER,
                onClick = {
                    onSelect(PhotoFit.COVER)
                    hideSheet()
                }
            )
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = ::hideSheet,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = stringResource(R.string.photo_fit_close),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextMuted
                )
            }
        }
    }
}

private fun previewStyleBadge(style: ShapeStyle): String = when (style) {
    ShapeStyle.FUSILLI -> "FUSILLI ×2"
    ShapeStyle.CUBES -> "CUBES ×2"
    ShapeStyle.BIG_CUBE -> "BIG CUBE ×1"
    ShapeStyle.DNA -> "DNA HELIX"
    ShapeStyle.BLACK_HOLE -> "EVENT HORIZON"
    ShapeStyle.DUCK -> "ЕБАЛО УТИНОЕ / 3D"
    ShapeStyle.DONUT -> "ПОНЧИК / 3D"
    ShapeStyle.FASTFETCH -> "FASTFETCH"
    ShapeStyle.ANDROID_LOGO -> "ANDROID ASCII"
    ShapeStyle.WAVES -> "WAVES / AUDIO"
    ShapeStyle.LOCK_HOME -> "LOCK / HOME"
    ShapeStyle.PHOTO -> "MEDIA"
}

@Composable
private fun LiveAsciiPreview(
    settings: WallpaperSettings,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val renderer = remember(context) {
        AsciiRenderer(context.applicationContext)
    }
    DisposableEffect(renderer) {
        onDispose { renderer.release() }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var frameTimeNanos by remember { mutableLongStateOf(System.nanoTime()) }

    LaunchedEffect(
        lifecycleOwner,
        settings.targetFps,
        settings.style,
        settings.photoRevision,
        settings.photoAnimated,
        settings.lockMediaRevision,
        settings.lockMediaAnimated,
        settings.homeMediaRevision,
        settings.homeMediaAnimated
    ) {
        // The preview honours the user's FPS preset, including 90/120. The
        // filter now performs its downsampling in native Canvas code and the
        // wallpaper scene is paused while settings are visible, so limiting
        // the live preview to 30 fps would falsely make every preset look
        // like a 30 fps wallpaper. Fastfetch and animated media still have
        // their own natural refresh rates.
        if ((settings.style == ShapeStyle.PHOTO && !settings.photoAnimated) ||
            settings.style == ShapeStyle.ANDROID_LOGO ||
            (settings.style == ShapeStyle.LOCK_HOME &&
                !settings.lockMediaAnimated && !settings.homeMediaAnimated)
        ) {
            // A still photo, a static Android logo and a static lock/home
            // pair are complete after their first draw. Recomposition
            // redraws them when a setting changes, so a permanent frame loop
            // would only heat the phone.
            return@LaunchedEffect
        }
        val previewFps = when (settings.style) {
            ShapeStyle.FASTFETCH -> 4
            ShapeStyle.PHOTO -> 12
            ShapeStyle.LOCK_HOME -> 12
            else -> settings.targetFps.coerceIn(1, 120)
        }
        val intervalNanos = 1_000_000_000L / previewFps
        // Advance a fractional target instead of resetting the deadline to the
        // accepted frame. On a 120 Hz display this produces the correct
        // 30/45/60/90/120 cadence; resetting to "now" made 90 collapse to 60
        // and 45 collapse to 40 because only whole display frames can be used.
        var nextFrameAt = 0L
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                withFrameNanos { frameTime ->
                    if (nextFrameAt == 0L) nextFrameAt = frameTime
                    if (frameTime >= nextFrameAt) {
                        frameTimeNanos = frameTime
                        nextFrameAt += intervalNanos
                        if (nextFrameAt <= frameTime) {
                            // The UI fell behind (background resume or jank):
                            // skip stale deadlines instead of drawing a burst.
                            nextFrameAt = frameTime + intervalNanos
                        }
                    }
                }
            }
        }
    }

    Canvas(modifier = modifier) {
        // frameTimeNanos is read inside the draw lambda on purpose: this
        // invalidates only drawing, not the whole settings composition.
        val time = frameTimeNanos
        drawIntoCanvas { nativeCanvas ->
            renderer.renderFrame(
                canvas = nativeCanvas.nativeCanvas,
                width = size.width.roundToInt(),
                height = size.height.roundToInt(),
                timeNanos = time,
                newSettings = settings
            )
        }
    }
}

private fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = AccentGreen.copy(alpha = 0.82f),
        letterSpacing = 1.2.sp
    )
}

@Composable
private fun StyleOption(
    title: String,
    detail: String,
    tag: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) AccentGreen.copy(alpha = 0.075f) else Color.Transparent)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 6.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = AccentGreenStrong,
                unselectedColor = TextMuted.copy(alpha = 0.55f)
            )
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
        Text(
            text = tag,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = if (selected) AccentGreen else TextMuted.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun LabelWithValue(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = AccentGreen,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun SliderLimits(start: String, end: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(start, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Text(end, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

@Composable
private fun asciiSliderColors() = SliderDefaults.colors(
    thumbColor = AccentGreenStrong,
    activeTrackColor = AccentGreenStrong,
    inactiveTrackColor = Color(0xFF263A2B),
    activeTickColor = AppBackground.copy(alpha = 0.6f),
    inactiveTickColor = Color(0xFF516457)
)

@Composable
private fun SymbolStyleChoice(
    modifier: Modifier,
    symbolStyle: SymbolStyle,
    selected: Boolean,
    glyphColor: Int,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) AccentGreen.copy(alpha = 0.08f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) AccentGreen.copy(alpha = 0.8f) else PanelBorder,
                shape = shape
            )
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (symbolStyle == SymbolStyle.ASCII) {
            Text(
                text = "A@#",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = if (selected) AccentGreen else TextPrimary
            )
        } else {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth(0.74f)
                    .height(28.dp)
            ) {
                val radii = floatArrayOf(0.11f, 0.18f, 0.25f, 0.18f, 0.11f)
                radii.forEachIndexed { index, radius ->
                    drawCircle(
                        color = if (selected) AccentGreen else Color(glyphColor),
                        radius = size.minDimension * radius,
                        center = Offset(
                            x = size.width * (0.10f + index * 0.20f),
                            y = size.height * 0.5f
                        )
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                if (symbolStyle == SymbolStyle.ASCII) {
                    R.string.symbol_ascii
                } else {
                    R.string.symbol_nothing
                }
            ),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) AccentGreen else TextPrimary
        )
        Text(
            text = stringResource(
                if (symbolStyle == SymbolStyle.ASCII) {
                    R.string.symbol_ascii_detail
                } else {
                    R.string.symbol_nothing_detail
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = TextMuted,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun ColorSettingRow(
    title: String,
    argb: Int,
    onEdit: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xFF0B150D))
            .border(1.dp, PanelBorder, shape)
            .clickable(onClick = onEdit)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(argb))
                .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Text(
                text = colorHex(argb),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                fontFamily = FontFamily.Monospace
            )
        }
        Text(
            text = stringResource(R.string.color_palette),
            style = MaterialTheme.typography.labelLarge,
            color = AccentGreen
        )
    }
}

@Composable
private fun ColorPickerDialog(
    title: String,
    initialColor: Int,
    presets: IntArray,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val initialHsv = remember(initialColor) {
        FloatArray(3).also { AndroidColor.colorToHSV(initialColor, it) }
    }
    var hue by remember(initialColor) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember(initialColor) { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember(initialColor) { mutableFloatStateOf(initialHsv[2]) }
    var selectedColor by remember(initialColor) { mutableIntStateOf(initialColor) }

    fun updateColor(
        newHue: Float = hue,
        newSaturation: Float = saturation,
        newBrightness: Float = brightness
    ) {
        selectedColor = AndroidColor.HSVToColor(
            floatArrayOf(
                newHue.coerceIn(0f, 360f),
                newSaturation.coerceIn(0f, 1f),
                newBrightness.coerceIn(0f, 1f)
            )
        )
    }

    fun changeHue(newHue: Float) {
        hue = newHue
        // Moving away from white should produce a colour immediately, and
        // moving away from black should not stay invisible.
        if (saturation < 0.05f) saturation = 0.65f
        if (brightness < 0.03f) brightness = 0.55f
        updateColor()
    }

    fun changeSaturation(newSaturation: Float) {
        saturation = newSaturation
        if (brightness < 0.03f) brightness = 0.55f
        updateColor()
    }

    fun applyPreset(color: Int) {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(color, hsv)
        hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
        selectedColor = color
    }

    val hueBrush = Brush.horizontalGradient(
        listOf(
            Color(0xFFFF3B30),
            Color(0xFFFFCC00),
            Color(0xFF34C759),
            Color(0xFF32ADE6),
            Color(0xFF5856D6),
            Color(0xFFFF2D55),
            Color(0xFFFF3B30)
        )
    )
    val saturationBrush = Brush.horizontalGradient(
        listOf(
            Color(
                AndroidColor.HSVToColor(
                    floatArrayOf(hue, 0f, brightness.coerceAtLeast(0.30f))
                )
            ),
            Color(
                AndroidColor.HSVToColor(
                    floatArrayOf(hue, 1f, brightness.coerceAtLeast(0.30f))
                )
            )
        )
    )
    val brightnessBrush = Brush.horizontalGradient(
        listOf(
            Color.Black,
            Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, 1f)))
        )
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF0B150D),
                border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder)
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.color_picker_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Color(selectedColor))
                                .border(
                                    1.dp,
                                    Color.White.copy(alpha = 0.45f),
                                    CircleShape
                                )
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = colorHex(selectedColor),
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = stringResource(R.string.color_preview),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    ColorChannelSlider(
                        label = stringResource(R.string.color_hue),
                        valueLabel = stringResource(
                            R.string.color_degrees_value,
                            hue.roundToInt()
                        ),
                        value = hue,
                        valueRange = 0f..360f,
                        gradient = hueBrush,
                        onValueChange = ::changeHue
                    )
                    ColorChannelSlider(
                        label = stringResource(R.string.color_saturation),
                        valueLabel = stringResource(
                            R.string.color_percent_value,
                            (saturation * 100f).roundToInt()
                        ),
                        value = saturation,
                        valueRange = 0f..1f,
                        gradient = saturationBrush,
                        onValueChange = ::changeSaturation
                    )
                    ColorChannelSlider(
                        label = stringResource(R.string.color_brightness),
                        valueLabel = stringResource(
                            R.string.color_percent_value,
                            (brightness * 100f).roundToInt()
                        ),
                        value = brightness,
                        valueRange = 0f..1f,
                        gradient = brightnessBrush,
                        onValueChange = {
                            brightness = it
                            updateColor()
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.color_quick_presets),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { preset ->
                            val selected = selectedColor == preset
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(CircleShape)
                                    .background(Color(preset))
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) {
                                            AccentGreenStrong
                                        } else {
                                            Color.White.copy(alpha = 0.28f)
                                        },
                                        shape = CircleShape
                                    )
                                    .clickable { applyPreset(preset) }
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(
                                text = stringResource(R.string.color_cancel),
                                color = TextMuted,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onConfirm(selectedColor) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentGreenStrong,
                                contentColor = Color(0xFF041007)
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.color_apply),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorChannelSlider(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    gradient: Brush,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelSmall,
                color = AccentGreen,
                fontFamily = FontFamily.Monospace
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.16f),
                        RoundedCornerShape(9.dp)
                    )
            ) {
                drawRect(brush = gradient)
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                )
            )
        }
    }
}

private fun colorHex(argb: Int): String =
    String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

@Composable
private fun FpsChoice(
    modifier: Modifier,
    fps: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) AccentGreen.copy(alpha = 0.10f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) AccentGreen.copy(alpha = 0.8f) else PanelBorder,
                shape = shape
            )
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = fps.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = if (selected) AccentGreen else TextMuted
        )
    }
}

@Composable
private fun RealSpectrumRow(
    checked: Boolean,
    permissionDenied: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val toggleDescription = stringResource(R.string.real_spectrum_toggle_description)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.real_spectrum_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (checked) TextPrimary else TextMuted
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.real_spectrum_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics {
                    contentDescription = toggleDescription
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AppBackground,
                    checkedTrackColor = AccentGreenStrong,
                    uncheckedThumbColor = TextMuted,
                    uncheckedTrackColor = Color(0xFF263A2B),
                    uncheckedBorderColor = PanelBorder
                )
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                if (permissionDenied) {
                    R.string.real_spectrum_denied
                } else {
                    R.string.real_spectrum_fallback
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (permissionDenied) Amber else TextMuted
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.real_spectrum_echo_hint),
            style = MaterialTheme.typography.bodySmall,
            color = AccentGreen.copy(alpha = 0.78f)
        )
    }
}

@Composable
private fun UnlockNoteCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val toggleDescription = stringResource(R.string.unlock_toggle_description)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B150D)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF243D29))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "↻",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 25.sp,
                color = AccentGreen
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.unlock_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) TextPrimary else TextMuted
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.unlock_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics {
                    contentDescription = toggleDescription
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AppBackground,
                    checkedTrackColor = AccentGreenStrong,
                    uncheckedThumbColor = TextMuted,
                    uncheckedTrackColor = Color(0xFF263A2B),
                    uncheckedBorderColor = PanelBorder
                )
            )
        }
    }
}
