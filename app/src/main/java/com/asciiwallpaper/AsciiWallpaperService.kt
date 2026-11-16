package com.asciiwallpaper

import android.app.KeyguardManager
import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Live wallpaper service.
 *
 * Rendering used to run on the app's main thread. A live wallpaper shares the
 * process (and therefore the main thread) with the settings activity, so a
 * heavy 60 fps canvas could make the whole app feel stuck. Frames are now
 * produced on a dedicated HandlerThread. The main thread only handles
 * lifecycle, preference and broadcast callbacks.
 *
 * The per-frame loop also uses an uptime deadline rather than a fixed
 * postDelayed gap. If a frame takes longer than the target interval the next
 * one is due immediately; when the device can keep up, frames land on a stable
 * cadence instead of accumulating drift.
 */
class AsciiWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        // The color-aware engine overrides API 27 WallpaperService.Engine
        // callbacks. Load it only on API 27+ and by name so the API 19
        // Dalvik verifier never has to resolve android.app.WallpaperColors
        // while starting the wallpaper service.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                val engineClass = Class.forName(
                    "com.asciiwallpaper.AsciiWallpaperService\$ModernAsciiEngine",
                    false,
                    javaClass.classLoader
                )
                val constructor = engineClass.getDeclaredConstructor(
                    AsciiWallpaperService::class.java
                )
                constructor.isAccessible = true
                return constructor.newInstance(this) as Engine
            } catch (t: Throwable) {
                Log.w(TAG, "Color-aware wallpaper engine unavailable", t)
            }
        }
        return AsciiEngine()
    }

    private open inner class AsciiEngine :
        Engine(),
        SharedPreferences.OnSharedPreferenceChangeListener,
        AppUiState.Listener {

        private val appContext: Context = applicationContext
        private val prefs = Prefs.sharedPreferences(appContext)
        private val mainHandler = Handler(Looper.getMainLooper())
        // A live wallpaper is decorative: when the launcher is drawing or
        // scrolling, its render thread must yield instead of competing with
        // the home screen at the same priority. Otherwise a 60/120 fps ASCII
        // frame can steal just enough CPU to make launcher animations stutter.
        private val renderThread = HandlerThread(
            "ascii-render",
            Process.THREAD_PRIORITY_BACKGROUND
        ).apply { start() }
        private val renderHandler = Handler(renderThread.looper)
        private val renderer = AsciiRenderer(appContext)

        @Volatile
        private var settings = Prefs.load(appContext)
        private var cornerPhase = Prefs.cornerPhase(appContext)

        @Volatile
        private var visible = false

        @Volatile
        private var surfaceReady = false

        // While the launcher is swiping between pages, drawing the wallpaper
        // at the full 90/120 fps competes with the home-screen animation. A
        // short, automatically expiring cap keeps the desktop responsive and
        // then returns to the user's chosen cadence.
        @Volatile
        private var launcherInteractionUntil = 0L
        private var lastOffsetX = Float.NaN
        private var lastOffsetY = Float.NaN

        @Volatile
        private var charging = false

        @Volatile
        private var batteryPercent = 50

        @Volatile
        private var screenOn = true

        // These fields are only touched by renderThread.
        private var frameScheduled = false
        private var nextFrameAtMillis = 0L
        private var lastDrawDurationMillis = 0L
        private var renderStarted = false

        private var lastUnlockHandledAt = 0L

        @Volatile
        private var pendingUnlockAnimation = false

        private var userPresentSeen = false
        private var receiverRegistered = false
        private var batteryReceiverRegistered = false

        private val frameRunnable = object : Runnable {
            override fun run() {
                frameScheduled = false
                if (!visible || !surfaceReady || !screenOn ||
                    AppUiState.isSettingsVisible()
                ) {
                    return
                }

                renderer.setBattery(charging, batteryPercent)
                if (!renderStarted) {
                    renderStarted = true
                    renderer.updateSettings(effectiveSettings())
                    renderer.setCornerPhase(cornerPhase)
                }
                val drawStartedAt = SystemClock.uptimeMillis()
                drawOneFrame(System.nanoTime())
                lastDrawDurationMillis =
                    SystemClock.uptimeMillis() - drawStartedAt

                if (visible && surfaceReady && screenOn &&
                    renderer.needsContinuousRendering()
                ) {
                    scheduleNextFrame()
                } else {
                    nextFrameAtMillis = 0L
                }
            }
        }

        /**
         * ACTION_SCREEN_ON fallback for ROMs that do not send
         * ACTION_USER_PRESENT. If the keyguard is already gone, run the unlock
         * animation; otherwise the real USER_PRESENT will do it later.
         */
        private val screenOnFallback = Runnable {
            if (userPresentSeen) return@Runnable
            val keyguard = appContext.getSystemService(Context.KEYGUARD_SERVICE)
                as? KeyguardManager
            if (keyguard == null || !keyguard.isKeyguardLocked) {
                updateLockedState(false)
                requestUnlockAnimation()
            }
        }

        private fun updateLockedState(locked: Boolean) {
            renderHandler.post { renderer.setLocked(locked) }
        }

        private fun keyguardLocked(): Boolean {
            val keyguard = appContext.getSystemService(Context.KEYGUARD_SERVICE)
                as? KeyguardManager ?: return false
            return try {
                keyguard.isKeyguardLocked
            } catch (_: Throwable) {
                false
            }
        }

        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_USER_PRESENT -> {
                        screenOn = true
                        userPresentSeen = true
                        mainHandler.removeCallbacks(screenOnFallback)
                        updateLockedState(false)
                        startRenderingIfPossible()
                        requestUnlockAnimation()
                    }

                    Intent.ACTION_SCREEN_ON -> {
                        screenOn = true
                        userPresentSeen = false
                        updateLockedState(keyguardLocked())
                        mainHandler.removeCallbacks(screenOnFallback)
                        mainHandler.postDelayed(screenOnFallback, 1_500L)
                        startRenderingIfPossible()
                    }

                    Intent.ACTION_SCREEN_OFF -> {
                        screenOn = false
                        userPresentSeen = false
                        pendingUnlockAnimation = false
                        // A deliberate lock/unlock cycle can be shorter than
                        // the duplicate-broadcast guard. The new screen-on
                        // event is a new chance for the transition.
                        lastUnlockHandledAt = 0L
                        mainHandler.removeCallbacks(screenOnFallback)
                        stopRendering()
                    }
                }
            }
        }

        private val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED -> charging = true
                    Intent.ACTION_POWER_DISCONNECTED -> charging = false
                    else -> {
                        charging = isCharging(intent)
                        readBatteryPercent(intent)?.let { batteryPercent = it }
                    }
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            screenOn = try {
                (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                    ?.isInteractive ?: true
            } catch (_: Throwable) {
                true
            }
            val initialBattery = readBatteryStatus()
            charging = initialBattery.first
            batteryPercent = initialBattery.second
            prefs.registerOnSharedPreferenceChangeListener(this)
            registerScreenReceiver()
            registerBatteryReceiver()
            AppUiState.addListener(this)
            renderHandler.post {
                renderer.setBattery(charging, batteryPercent)
                renderer.updateSettings(effectiveSettings())
                renderer.setCornerPhase(cornerPhase)
                renderer.setLocked(keyguardLocked())
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder?) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
            startRenderingIfPossible()
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder?,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            // Rotation/resize invalidates the previous frame immediately.
            startRenderingIfPossible(force = true)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            surfaceReady = false
            stopRendering()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                // Re-check on every appearance. A missed USER_PRESENT or an
                // OEM that flips the keyguard flag late must not leave the
                // lock/home style stuck on the lock image at home. Once
                // USER_PRESENT arrived, keep the unlocked state even if the
                // keyguard flag is briefly stale while the transition is
                // starting.
                updateLockedState(
                    if (userPresentSeen) false else keyguardLocked()
                )
                startRenderingIfPossible()
            } else {
                stopRendering()
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int
        ) {
            super.onOffsetsChanged(
                xOffset,
                yOffset,
                xOffsetStep,
                yOffsetStep,
                xPixelOffset,
                yPixelOffset
            )
            if (xOffset == lastOffsetX && yOffset == lastOffsetY) return
            lastOffsetX = xOffset
            lastOffsetY = yOffset
            launcherInteractionUntil = SystemClock.uptimeMillis() +
                LAUNCHER_INTERACTION_MS
        }

        /**
         * Subclasses may publish palette changes on API 27+. The base engine
         * deliberately has no reference to android.app.WallpaperColors so it
         * stays loadable on Android 4.4.
         */
        protected open fun onPaletteChanged() = Unit

        protected fun paletteSettings(): WallpaperSettings = settings

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            if (key != null && key !in Prefs.wallpaperKeys) return

            val updated = Prefs.load(appContext)
            if (updated == settings) return

            val styleChanged = updated.style != settings.style
            val targetChanged = updated.targetFps != settings.targetFps ||
                updated.columns != settings.columns ||
                updated.autoPerformanceEnabled != settings.autoPerformanceEnabled ||
                styleChanged ||
                updated.photoRevision != settings.photoRevision ||
                updated.photoAnimated != settings.photoAnimated ||
                updated.lockMediaRevision != settings.lockMediaRevision ||
                updated.lockMediaAnimated != settings.lockMediaAnimated ||
                updated.homeMediaRevision != settings.homeMediaRevision ||
                updated.homeMediaAnimated != settings.homeMediaAnimated
            val colorsChanged = updated.backgroundColorArgb != settings.backgroundColorArgb ||
                updated.glyphColorArgb != settings.glyphColorArgb
            settings = updated
            if (colorsChanged) onPaletteChanged()
            if (styleChanged) updateLockedState(keyguardLocked())
            renderHandler.post {
                renderer.setBattery(charging, batteryPercent)
                renderer.updateSettings(effectiveSettings())
                if (targetChanged) {
                    nextFrameAtMillis = 0L
                }
            }
            startRenderingIfPossible(force = true)
        }

        override fun onSettingsVisibilityChanged(visible: Boolean) {
            if (visible) {
                stopRendering()
            } else {
                startRenderingIfPossible()
            }
        }

        private fun startRenderingIfPossible(force: Boolean = false) {
            if (!visible || !surfaceReady || !screenOn ||
                AppUiState.isSettingsVisible()
            ) {
                return
            }
            renderHandler.post {
                if (!visible || !surfaceReady || !screenOn ||
                    AppUiState.isSettingsVisible()
                ) {
                    return@post
                }
                if (pendingUnlockAnimation) {
                    pendingUnlockAnimation = false
                    performUnlockAnimationOnRenderThread()
                }
                if (!renderStarted || force) {
                    val freshStart = !renderStarted
                    renderStarted = true
                    renderer.setBattery(charging, batteryPercent)
                    renderer.setRenderingActive(true)
                    renderer.updateSettings(effectiveSettings())
                    // Resetting the corner positions on every color or
                    // density edit would snap a running unlock animation.
                    // Only restore the saved phase for a fresh engine start;
                    // updateSettings() already handles style transitions.
                    if (freshStart) renderer.setCornerPhase(cornerPhase)
                    renderer.resetFrameClock()
                    nextFrameAtMillis = 0L
                }
                if (force && frameScheduled) {
                    // A preference change should be visible immediately, not
                    // after a pending Fastfetch refresh deadline (up to 1 s).
                    renderHandler.removeCallbacks(frameRunnable)
                    frameScheduled = false
                }
                if (!frameScheduled) {
                    scheduleNextFrame()
                }
            }
        }

        private fun targetFrameRate(): Int {
            val effective = effectiveSettings()
            return when {
                settings.style == ShapeStyle.FASTFETCH -> 1
                settings.style == ShapeStyle.ANDROID_LOGO -> 1
                settings.style == ShapeStyle.PHOTO -> {
                    if (renderer.hasAnimatedMedia()) {
                        minOf(
                            renderer.animatedFrameRate(),
                            effective.targetFps.coerceAtLeast(1)
                        )
                    } else {
                        1
                    }
                }
                settings.style == ShapeStyle.LOCK_HOME -> {
                    when {
                        // The pixel collapse/expand needs the full selected cadence
                        // even when both still images are static.
                        renderer.isLockHomeTransitionActive() ->
                            effective.targetFps.coerceAtLeast(1)
                        renderer.hasAnimatedLockHomeMedia() -> minOf(
                            effective.targetFps.coerceAtLeast(1),
                            renderer.lockHomeAnimatedFrameRate()
                        )
                        else -> 1
                    }
                }
                else -> effective.targetFps.coerceAtLeast(1)
            }
        }

        private fun effectiveSettings(): WallpaperSettings =
            PerformancePolicy.effective(settings, appContext)

        /**
         * Rendering cadence for the frame loop. During a launcher swipe we
         * deliberately draw fewer wallpaper frames so the home screen gets
         * the CPU it needs. The cap expires by itself after the gesture.
         *
         * No Surface.setFrameRate() hint is sent. On 120 Hz Xperia panels the
         * old 60 Hz hint could make the compositor choose the 60 Hz display
         * mode for the whole desktop: the wallpaper looked fine, but every
         * launcher animation started stuttering. The compositor now chooses
         * the display mode from the launcher and all other layers.
         */
        private fun renderFrameRate(): Int {
            val requested = targetFrameRate()
            return if (SystemClock.uptimeMillis() < launcherInteractionUntil) {
                requested.coerceAtMost(LAUNCHER_INTERACTION_FPS)
            } else {
                requested
            }
        }

        private fun scheduleNextFrame() {
            if (!visible || !surfaceReady || !screenOn || frameScheduled ||
                AppUiState.isSettingsVisible()
            ) {
                return
            }
            // Fastfetch is a readable system-info screen, not a 3D animation.
            // One quiet refresh per second keeps the clock/battery current
            // without burning the render budget at 60–120 fps. An imported
            // clip only has 12-ish distinct frames per second; drawing its
            // ASCII grid at 60 fps would repeat identical work five times.
            val targetFps = renderFrameRate()
            val interval = (1_000L / targetFps).coerceAtLeast(1L)
            val now = SystemClock.uptimeMillis()
            if (nextFrameAtMillis == 0L) {
                nextFrameAtMillis = now
            } else if (nextFrameAtMillis < now) {
                // If the draw itself overran the interval, drop the missed
                // slot instead of running back-to-back: a saturated render
                // thread is exactly what makes the launcher stutter. Plain
                // scheduler latency with a fast draw is allowed to start now.
                nextFrameAtMillis = if (lastDrawDurationMillis > interval) {
                    now + interval
                } else {
                    now
                }
            }
            frameScheduled = true
            renderHandler.postAtTime(frameRunnable, nextFrameAtMillis)
            nextFrameAtMillis += interval
        }

        private fun stopRendering() {
            if (Looper.myLooper() == renderThread.looper) {
                stopRenderingOnThread()
            } else {
                renderHandler.post { stopRenderingOnThread() }
            }
        }

        private fun stopRenderingOnThread() {
            if (frameScheduled) {
                renderHandler.removeCallbacks(frameRunnable)
                frameScheduled = false
            }
            nextFrameAtMillis = 0L
            lastDrawDurationMillis = 0L
            renderStarted = false
            renderer.setRenderingActive(false)
            renderer.resetFrameClock()
        }

        private fun drawOneFrame(frameTimeNanos: Long) {
            val holder = surfaceHolder
            if (!surfaceReady || !holder.surface.isValid) {
                surfaceReady = false
                return
            }

            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null && surfaceReady) {
                    renderer.renderFrame(
                        canvas = canvas,
                        width = canvas.width,
                        height = canvas.height,
                        timeNanos = frameTimeNanos,
                        newSettings = effectiveSettings()
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Frame skipped: ${e.message}")
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        Log.w(TAG, "unlockCanvasAndPost failed: ${e.message}")
                    }
                }
            }
        }

        private fun requestUnlockAnimation() {
            // The lock/home style uses the unlock event for its pixelated
            // bridge even when the optional spin/switcheroo effects are off.
            if (!settings.unlockAnimationEnabled &&
                settings.style != ShapeStyle.LOCK_HOME
            ) {
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastUnlockHandledAt < 1_200L) return
            lastUnlockHandledAt = now

            if (!visible || !surfaceReady || AppUiState.isSettingsVisible()) {
                pendingUnlockAnimation = true
                return
            }
            renderHandler.post { performUnlockAnimationOnRenderThread() }
        }

        private fun performUnlockAnimationOnRenderThread() {
            if (!settings.unlockAnimationEnabled &&
                settings.style != ShapeStyle.LOCK_HOME
            ) {
                return
            }
            // A perform block can be queued just before the screen turns off.
            // Drop it instead of running an effect that nobody can see.
            if (!screenOn) return
            // Do not burn the lock/home transition while the settings screen
            // covers the wallpaper. It will be consumed by the next
            // startRenderingIfPossible() when the user returns.
            if (!visible || !surfaceReady || AppUiState.isSettingsVisible()) {
                pendingUnlockAnimation = true
                return
            }
            // A pending unlock can arrive before the first frame after a
            // surface/style change. Make sure onUnlock() sees the current
            // style and loaded lock/home slots, not the renderer defaults.
            renderer.updateSettings(effectiveSettings())
            if (settings.unlockAnimationEnabled && settings.style.usesCornerSwap) {
                cornerPhase = 1 - cornerPhase
                Prefs.saveCornerPhase(appContext, cornerPhase)
            }
            renderer.onUnlock(cornerPhase, System.nanoTime())
            if (!frameScheduled) {
                renderStarted = true
                nextFrameAtMillis = 0L
                scheduleNextFrame()
            }
        }

        private fun readBatteryStatus(): Pair<Boolean, Int> {
            val intent = try {
                appContext.registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
            } catch (_: Throwable) {
                null
            }
            return Pair(isCharging(intent), readBatteryPercent(intent) ?: 50)
        }

        private fun readBatteryPercent(intent: Intent?): Int? {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level < 0 || scale <= 0) return null
            return (level * 100f / scale).roundToInt().coerceIn(0, 100)
        }

        private fun isCharging(intent: Intent?): Boolean {
            if (intent == null) return false
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            // EXTRA_STATUS can briefly be NOT_CHARGING while a cable is
            // connected (charge limit, thermal pause, battery-care modes).
            // What matters here is whether external power is connected.
            return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged != 0
        }

        private fun registerScreenReceiver() {
            if (receiverRegistered) return
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(
                appContext,
                screenReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        }

        private fun registerBatteryReceiver() {
            if (batteryReceiverRegistered) return
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            ContextCompat.registerReceiver(
                appContext,
                batteryReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            batteryReceiverRegistered = true
        }

        private fun unregisterScreenReceiver() {
            if (!receiverRegistered) return
            receiverRegistered = false
            try {
                appContext.unregisterReceiver(screenReceiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered by the framework.
            }
        }

        private fun unregisterBatteryReceiver() {
            if (!batteryReceiverRegistered) return
            batteryReceiverRegistered = false
            try {
                appContext.unregisterReceiver(batteryReceiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered by the framework.
            }
        }

        override fun onDestroy() {
            surfaceReady = false
            visible = false
            stopRendering()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            AppUiState.removeListener(this)
            unregisterScreenReceiver()
            unregisterBatteryReceiver()
            mainHandler.removeCallbacksAndMessages(null)
            renderHandler.post {
                renderHandler.removeCallbacksAndMessages(null)
                renderer.release()
                renderThread.quitSafely()
            }
            super.onDestroy()
        }
    }

    /**
     * API 27+ engine. This class is loaded by reflection only, so its
     * android.app.WallpaperColors signature is never resolved on Android 4.4.
     */
    private inner class ModernAsciiEngine : AsciiEngine() {
        override fun onComputeColors(): WallpaperColors? {
            return runCatching {
                val palette = paletteSettings()
                WallpaperColors(
                    Color.valueOf(palette.backgroundColorArgb or OPAQUE_BLACK),
                    Color.valueOf(palette.glyphColorArgb or OPAQUE_BLACK),
                    Color.valueOf(CHARGING_GREEN)
                )
            }.getOrNull()
        }

        override fun onPaletteChanged() {
            try {
                notifyColorsChanged()
            } catch (_: Throwable) {
                // Some launchers/OEM wallpaper services do not observe live
                // palette changes; the next onComputeColors call still works.
            }
        }
    }

    companion object {
        private const val TAG = "AsciiWallpaper"
        private const val OPAQUE_BLACK = 0xFF000000.toInt()
        private const val CHARGING_GREEN = 0xFF6DFF82.toInt()
        private const val LAUNCHER_INTERACTION_MS = 750L
        private const val LAUNCHER_INTERACTION_FPS = 30
    }
}
