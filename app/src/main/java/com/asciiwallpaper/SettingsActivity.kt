package com.asciiwallpaper

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * Launcher entry point.
 *
 * Android 5.0+ starts the Compose settings activity. Android 4.4 (API 19)
 * cannot load Compose UI, so it uses a classic View screen with the core
 * renderer controls. The Compose class is referenced only by name to keep the
 * legacy path free of Compose class loading.
 */
class SettingsActivity : Activity() {
    private var legacySettings: LegacySettingsScreen? = null

    override fun onStart() {
        super.onStart()
        AppUiState.setSettingsVisible(true)
    }

    override fun onPause() {
        legacySettings?.persist()
        super.onPause()
    }

    override fun onStop() {
        AppUiState.setSettingsVisible(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 21) {
            startActivity(
                Intent().setClassName(
                    this,
                    "com.asciiwallpaper.ComposeSettingsActivity"
                )
            )
            finish()
            return
        }

        val screen = LegacySettingsScreen(this)
        legacySettings = screen
        setContentView(screen.create())
    }
}
