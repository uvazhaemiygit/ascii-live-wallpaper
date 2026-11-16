package com.asciiwallpaper

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Classic View settings for Android 4.4 (API 19) and 4.4W.
 *
 * Compose itself requires API 21, so the modern screen is started on 5.0+.
 * This screen intentionally uses only platform widgets and covers the core
 * renderer settings: style, density, FPS, speed, waves, glyph/background
 * colours, opacity, Auto performance, the live-wallpaper picker and the
 * static wallpaper export. Photo/video import and separate lock/home media
 * remain available on Android 5.0+.
 */
class LegacySettingsScreen(private val activity: Activity) {
    private var current = Prefs.load(activity)
    private lateinit var autoStatus: TextView

    /** Persists the values currently displayed by the sliders. */
    fun persist() {
        save()
    }

    fun create(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
            setBackgroundColor(Color.rgb(4, 7, 5))
        }
        rootView = root

        root.addView(title(activity.getString(R.string.settings_title)))
        root.addView(
            body(
                activity.getString(R.string.settings_subtitle),
                top = 6
            )
        )
        root.addView(
            body(
                "Android 4.4: доступны основные настройки. Фото, GIF, видео и раздельные обои блокировки появятся на Android 5.0+.",
                top = 12,
                color = Color.rgb(255, 178, 63)
            )
        )

        root.addView(section("01 / ФОРМА"))
        val styles = ShapeStyle.entries
        styleSpinner(
            title = activity.getString(R.string.style_title),
            items = styles.map(::styleLabel),
            selected = styles.indexOf(current.style).coerceAtLeast(0)
        ) { index ->
            current = current.copy(style = styles[index]).sanitized()
            save()
        }

        root.addView(section("02 / РАЗРЕШЕНИЕ"))
        slider(
            title = activity.getString(R.string.resolution_title),
            min = WallpaperSettings.MIN_COLUMNS,
            max = WallpaperSettings.MAX_COLUMNS,
            value = current.columns,
            format = { activity.getString(R.string.resolution_value, it) },
            onChange = { current = current.copy(columns = it).sanitized() },
            onCommit = { save() }
        )

        root.addView(section("03 / ДВИЖЕНИЕ"))
        slider(
            title = activity.getString(R.string.speed_title),
            min = WallpaperSettings.MIN_SPEED.toInt(),
            max = WallpaperSettings.MAX_SPEED.toInt(),
            value = current.speedDegPerSec.toInt(),
            format = { activity.getString(R.string.speed_value, it) },
            onChange = {
                current = current.copy(speedDegPerSec = it.toFloat()).sanitized()
            },
            onCommit = { save() }
        )

        val fpsOptions = listOf(30, 45, 60, 90, 120)
        spinner(
            title = activity.getString(R.string.fps_title),
            items = fpsOptions.map { activity.getString(R.string.fps_value, it) },
            selected = fpsOptions.indexOf(current.targetFps).coerceAtLeast(0)
        ) { index ->
            current = current.copy(targetFps = fpsOptions[index]).sanitized()
            save()
            updateAutoStatus()
        }
        root.addView(
            body(
                activity.getString(R.string.fps_hint),
                top = 4
            )
        )

        slider(
            title = activity.getString(R.string.wave_beat_title),
            min = WallpaperSettings.MIN_WAVE_BEAT_PERCENT,
            max = WallpaperSettings.MAX_WAVE_BEAT_PERCENT,
            value = current.waveBeatPercent,
            format = { activity.getString(R.string.wave_beat_value, it) },
            onChange = {
                current = current.copy(waveBeatPercent = it).sanitized()
            },
            onCommit = { save() }
        )
        slider(
            title = activity.getString(R.string.wave_spread_title),
            min = WallpaperSettings.MIN_WAVE_SPREAD_PERCENT,
            max = WallpaperSettings.MAX_WAVE_SPREAD_PERCENT,
            value = current.waveSpreadPercent,
            format = { activity.getString(R.string.wave_spread_value, it) },
            onChange = {
                current = current.copy(waveSpreadPercent = it).sanitized()
            },
            onCommit = { save() }
        )

        root.addView(section("04 / ПРОИЗВОДИТЕЛЬНОСТЬ"))
        checkbox(
            title = activity.getString(R.string.auto_performance_title),
            detail = activity.getString(R.string.auto_performance_detail),
            checked = current.autoPerformanceEnabled
        ) { enabled ->
            current = current.copy(autoPerformanceEnabled = enabled)
            save()
            updateAutoStatus()
        }
        autoStatus = body("", top = 6, color = Color.rgb(149, 247, 164))
        root.addView(autoStatus)
        updateAutoStatus()

        root.addView(section("05 / ЛИНИИ"))
        slider(
            title = activity.getString(R.string.line_width_title),
            min = (WallpaperSettings.MIN_LINE_WIDTH * 10).toInt(),
            max = (WallpaperSettings.MAX_LINE_WIDTH * 10).toInt(),
            value = (current.lineWidth * 10).toInt(),
            format = {
                activity.getString(
                    R.string.line_width_value,
                    String.format(Locale.US, "%.1f", it / 10f)
                )
            },
            onChange = {
                current = current.copy(lineWidth = it / 10f).sanitized()
            },
            onCommit = { save() }
        )

        root.addView(section("06 / СИМВОЛЫ И ЦВЕТ"))
        val symbols = SymbolStyle.entries
        spinner(
            title = activity.getString(R.string.symbol_style_title),
            items = symbols.map(::symbolLabel),
            selected = symbols.indexOf(current.symbolStyle).coerceAtLeast(0)
        ) { index ->
            current = current.copy(symbolStyle = symbols[index])
            save()
        }

        colorSpinner(
            title = activity.getString(R.string.glyph_color_title),
            value = current.glyphColorArgb,
            presets = GLYPH_COLORS
        ) { argb ->
            current = current.copy(glyphColorArgb = argb).sanitized()
            save()
        }
        colorSpinner(
            title = activity.getString(R.string.background_color_title),
            value = current.backgroundColorArgb,
            presets = BACKGROUND_COLORS
        ) { argb ->
            current = current.copy(backgroundColorArgb = argb).sanitized()
            save()
        }
        slider(
            title = activity.getString(R.string.glyph_opacity_title),
            min = WallpaperSettings.MIN_GLYPH_OPACITY_PERCENT,
            max = WallpaperSettings.MAX_GLYPH_OPACITY_PERCENT,
            value = current.glyphOpacityPercent,
            format = { activity.getString(R.string.glyph_opacity_value, it) },
            onChange = {
                current = current.copy(glyphOpacityPercent = it).sanitized()
            },
            onCommit = { save() }
        )

        checkbox(
            title = activity.getString(R.string.real_spectrum_title),
            detail = activity.getString(R.string.real_spectrum_detail),
            checked = current.realSpectrumEnabled
        ) { enabled ->
            current = current.copy(realSpectrumEnabled = enabled)
            save()
        }
        checkbox(
            title = activity.getString(R.string.unlock_title),
            detail = activity.getString(R.string.unlock_detail),
            checked = current.unlockAnimationEnabled
        ) { enabled ->
            current = current.copy(unlockAnimationEnabled = enabled)
            save()
        }

        root.addView(section("07 / ОБОИ"))
        button(activity.getString(R.string.install_wallpaper)) {
            WallpaperPicker.open(activity)
        }
        root.addView(
            body(
                activity.getString(R.string.install_hint),
                top = 6,
                gravity = Gravity.CENTER
            )
        )
        button(
            activity.getString(R.string.static_wallpaper_button),
            secondary = true
        ) {
            confirmStaticWallpaper()
        }
        root.addView(
            body(
                activity.getString(R.string.static_wallpaper_detail),
                top = 6,
                gravity = Gravity.CENTER
            )
        )
        root.addView(
            body(
                activity.getString(R.string.settings_live_note),
                top = 18,
                gravity = Gravity.CENTER,
                color = Color.rgb(145, 167, 148)
            )
        )

        return ScrollView(activity).apply {
            isFillViewport = true
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun save() {
        Prefs.save(activity.applicationContext, current)
    }

    private fun updateAutoStatus() {
        if (!::autoStatus.isInitialized) return
        val effective = PerformancePolicy.effective(current, activity)
        autoStatus.text = when {
            !current.autoPerformanceEnabled ->
                activity.getString(R.string.auto_performance_off)
            effective != current ->
                activity.getString(
                    R.string.auto_performance_capped,
                    effective.columns,
                    effective.targetFps
                )
            else -> activity.getString(R.string.auto_performance_normal)
        }
    }

    private fun confirmStaticWallpaper() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.static_wallpaper_confirm_title)
            .setMessage(R.string.static_wallpaper_confirm_message)
            .setNegativeButton(R.string.static_wallpaper_cancel, null)
            .setPositiveButton(R.string.static_wallpaper_confirm_yes) { _, _ ->
                exportStaticWallpaper()
            }
            .show()
    }

    private fun exportStaticWallpaper() {
        val settings = PerformancePolicy.effective(current, activity)
        Toast.makeText(
            activity,
            R.string.static_wallpaper_working,
            Toast.LENGTH_SHORT
        ).show()
        Thread {
            val result = StaticWallpaper.export(activity.applicationContext, settings)
            activity.runOnUiThread {
                val message = result.fold(
                    onSuccess = {
                        activity.getString(R.string.static_wallpaper_done)
                    },
                    onFailure = {
                        activity.getString(
                            R.string.static_wallpaper_failed,
                            it.message ?: it.javaClass.simpleName
                        )
                    }
                )
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun title(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(Color.rgb(227, 239, 228))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setPadding(0, 0, 0, dp(4))
    }

    private fun section(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(Color.rgb(149, 247, 164))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun body(
        text: String,
        top: Int = 0,
        gravity: Int = Gravity.START,
        color: Int = Color.rgb(145, 167, 148)
    ): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        this.gravity = gravity
        setLineSpacing(0f, 1.12f)
        setPadding(0, dp(top), 0, 0)
    }

    private fun label(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(Color.rgb(227, 239, 228))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private fun styleSpinner(
        title: String,
        items: List<String>,
        selected: Int,
        onSelected: (Int) -> Unit
    ) = spinner(title, items, selected, onSelected)

    private fun spinner(
        title: String,
        items: List<String>,
        selected: Int,
        onSelected: (Int) -> Unit
    ) {
        val block = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        block.addView(label(title))
        val spinner = Spinner(activity).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_item,
                items
            ).apply {
                setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item
                )
            }
            setSelection(selected, false)
            setPadding(0, dp(6), 0, dp(4))
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var lastSelected = selected

            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (position != lastSelected) {
                    lastSelected = position
                    onSelected(position)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        block.addView(spinner)
        activityRoot().addView(block)
    }

    private fun slider(
        title: String,
        min: Int,
        max: Int,
        value: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit,
        onCommit: () -> Unit
    ) {
        val block = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            label(title),
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        val valueText = TextView(activity).apply {
            text = format(value)
            setTextColor(Color.rgb(149, 247, 164))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.END
        }
        header.addView(valueText)
        block.addView(header)

        val seek = SeekBar(activity).apply {
            this.max = (max - min).coerceAtLeast(1)
            progress = (value - min).coerceIn(0, this.max)
            setPadding(0, dp(6), 0, dp(2))
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                bar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                val actual = min + progress
                valueText.text = format(actual)
                if (fromUser) onChange(actual)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit

            override fun onStopTrackingTouch(bar: SeekBar?) {
                onCommit()
                updateAutoStatus()
            }
        })
        block.addView(seek)
        activityRoot().addView(block)
    }

    private fun checkbox(
        title: String,
        detail: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        val block = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        val check = CheckBox(activity).apply {
            text = title
            isChecked = checked
            setTextColor(Color.rgb(227, 239, 228))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        check.setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        block.addView(check)
        block.addView(body(detail, top = 0))
        activityRoot().addView(block)
    }

    private fun colorSpinner(
        title: String,
        value: Int,
        presets: List<Pair<String, Int>>,
        onSelected: (Int) -> Unit
    ) {
        val options = presets.toMutableList()
        val currentIndex = options.indexOfFirst { it.second == value }
        val selected = if (currentIndex >= 0) {
            currentIndex
        } else {
            options.add("Свой ${hex(value)}" to value)
            options.lastIndex
        }
        spinner(
            title = title,
            items = options.map { it.first },
            selected = selected
        ) { index ->
            onSelected(options[index].second)
        }
    }

    private fun button(
        text: String,
        secondary: Boolean = false,
        onClick: () -> Unit
    ) {
        val button = Button(activity).apply {
            this.text = text
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(
                if (secondary) Color.rgb(149, 247, 164) else Color.rgb(4, 16, 7)
            )
            setBackgroundColor(
                if (secondary) Color.rgb(15, 32, 20) else Color.rgb(109, 255, 130)
            )
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onClick() }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(16)
        activityRoot().addView(button, params)
    }

    private var rootView: LinearLayout? = null

    private fun activityRoot(): LinearLayout {
        rootView?.let { return it }
        error("LegacySettingsScreen root is not ready")
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun hex(argb: Int): String =
        String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

    private fun styleLabel(style: ShapeStyle): String = activity.getString(
        when (style) {
            ShapeStyle.FUSILLI -> R.string.style_fusilli
            ShapeStyle.CUBES -> R.string.style_cubes
            ShapeStyle.BIG_CUBE -> R.string.style_big_cube
            ShapeStyle.DNA -> R.string.style_dna
            ShapeStyle.BLACK_HOLE -> R.string.style_black_hole
            ShapeStyle.DUCK -> R.string.style_duck
            ShapeStyle.DONUT -> R.string.style_donut
            ShapeStyle.FASTFETCH -> R.string.style_fastfetch
            ShapeStyle.ANDROID_LOGO -> R.string.style_android_logo
            ShapeStyle.WAVES -> R.string.style_waves
            ShapeStyle.LOCK_HOME -> R.string.style_lock_home
            ShapeStyle.PHOTO -> R.string.style_photo
        }
    )

    private fun symbolLabel(symbol: SymbolStyle): String = activity.getString(
        when (symbol) {
            SymbolStyle.ASCII -> R.string.symbol_ascii
            SymbolStyle.NOTHING -> R.string.symbol_nothing
        }
    )

    private companion object {
        val GLYPH_COLORS = listOf(
            "Белый" to 0xFFFFFFFF.toInt(),
            "Зелёный" to 0xFF6DFF82.toInt(),
            "Янтарный" to 0xFFFFB23F.toInt(),
            "Голубой" to 0xFF6DE8FF.toInt(),
            "Розовый" to 0xFFFF8BCB.toInt(),
            "Фиолетовый" to 0xFFB79CFF.toInt()
        )
        val BACKGROUND_COLORS = listOf(
            "Чёрный" to 0xFF000000.toInt(),
            "Тёмно-серый" to 0xFF0A0F0C.toInt(),
            "Тёмно-синий" to 0xFF101820.toInt(),
            "Тёмно-зелёный" to 0xFF07131A.toInt(),
            "Тёмно-фиолетовый" to 0xFF18101E.toInt(),
            "Тёмно-коричневый" to 0xFF20140A.toInt()
        )
    }
}
