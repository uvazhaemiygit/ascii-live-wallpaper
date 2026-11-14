package com.asciiwallpaper

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide hand-off between the settings activity and a live wallpaper
 * engine. The wallpaper normally stops when it becomes hidden, but some
 * launchers/OEMs keep a visible engine alive behind the settings screen. In
 * that case both the settings preview and the wallpaper would render heavy
 * ASCII canvases at the same time, making the UI and the wallpaper stutter.
 *
 * The activity publishes its started state here, and the engine pauses its
 * render loop while the settings screen is in front. All callbacks are on the
 * main thread.
 */
object AppUiState {
    fun interface Listener {
        fun onSettingsVisibilityChanged(visible: Boolean)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    private var settingsVisible = false

    fun isSettingsVisible(): Boolean = settingsVisible

    fun setSettingsVisible(visible: Boolean) {
        if (settingsVisible == visible) return
        settingsVisible = visible
        listeners.forEach { it.onSettingsVisibilityChanged(visible) }
    }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
        listener.onSettingsVisibilityChanged(settingsVisible)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }
}
