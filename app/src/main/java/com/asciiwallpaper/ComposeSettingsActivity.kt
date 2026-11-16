package com.asciiwallpaper

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.LocalContext

/**
 * Compose settings UI. This activity is only started on Android 5.0+.
 * Android 4.4 uses [SettingsActivity] and the classic View-based screen.
 */
class ComposeSettingsActivity : ComponentActivity() {

    override fun onStart() {
        super.onStart()
        // Pause a still-running wallpaper engine before the heavy live
        // preview becomes visible. This also covers ROMs that do not deliver
        // WallpaperService.Engine.onVisibilityChanged(false) promptly.
        AppUiState.setSettingsVisible(true)
    }

    override fun onStop() {
        AppUiState.setSettingsVisible(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        val initial = Prefs.load(applicationContext)

        setContent {
            val context = LocalContext.current
            AsciiWallpaperTheme {
                SettingsScreen(
                    initialSettings = initial,
                    onSettingsChanged = { updated ->
                        // SharedPreferences is observed by the running wallpaper
                        // engine, so changes are applied to the live canvas.
                        Prefs.save(context.applicationContext, updated)
                    },
                    onInstallWallpaper = {
                        WallpaperPicker.open(this@ComposeSettingsActivity)
                    }
                )
            }
        }
    }
}
