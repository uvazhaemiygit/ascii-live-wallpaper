package com.asciiwallpaper

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.LocalContext

class SettingsActivity : ComponentActivity() {

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
                    onInstallWallpaper = ::openWallpaperPicker
                )
            }
        }
    }

    private fun openWallpaperPicker() {
        val component = ComponentName(this, AsciiWallpaperService::class.java)
        val changeLiveWallpaper = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        }

        try {
            startActivity(changeLiveWallpaper)
            return
        } catch (_: ActivityNotFoundException) {
            // Some OEMs only expose the generic live wallpaper chooser.
        } catch (_: SecurityException) {
            // Fall through to the chooser as well.
        }

        try {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                R.string.wallpaper_picker_missing,
                Toast.LENGTH_LONG
            ).show()
        } catch (_: SecurityException) {
            Toast.makeText(
                this,
                R.string.wallpaper_picker_missing,
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
