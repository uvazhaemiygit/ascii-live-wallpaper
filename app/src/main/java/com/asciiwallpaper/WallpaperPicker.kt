package com.asciiwallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.widget.Toast

/** Opens the system live-wallpaper picker and falls back to the generic chooser. */
object WallpaperPicker {
    fun open(activity: Activity) {
        val component = ComponentName(activity, AsciiWallpaperService::class.java)
        val changeLiveWallpaper = Intent(
            WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER
        ).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                component
            )
        }

        try {
            activity.startActivity(changeLiveWallpaper)
            return
        } catch (_: ActivityNotFoundException) {
            // Some OEMs only expose the generic live wallpaper chooser.
        } catch (_: SecurityException) {
            // Fall through to the chooser as well.
        }

        try {
            activity.startActivity(
                Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
            )
        } catch (_: ActivityNotFoundException) {
            showMissing(activity)
        } catch (_: SecurityException) {
            showMissing(activity)
        }
    }

    private fun showMissing(activity: Activity) {
        Toast.makeText(
            activity,
            R.string.wallpaper_picker_missing,
            Toast.LENGTH_LONG
        ).show()
    }
}
