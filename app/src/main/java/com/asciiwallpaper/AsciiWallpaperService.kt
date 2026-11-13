package com.asciiwallpaper

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat

/**
 * Live wallpaper service. Rendering is deliberately performed on the engine's
 * main thread with Choreographer pacing:
 *  - no shared mutable state with a background thread;
 *  - lockCanvas/unlockCanvasAndPost and surface destruction cannot race;
 *  - rendering stops immediately when the wallpaper becomes invisible.
 *
 * Settings are read from SharedPreferences and observed live. Frame pacing
 * follows the selected target FPS, and the unlock effect can be disabled in
 * settings. The corner state is persisted separately so unlock-to-unlock
 * alternation survives recreation.
 */
class AsciiWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = AsciiEngine()

    private inner class AsciiEngine :
        Engine(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val appContext: Context = applicationContext
        private val prefs = Prefs.sharedPreferences(appContext)
        private val mainHandler = Handler(Looper.getMainLooper())
        private val renderer = AsciiRenderer(appContext)

        private var settings = Prefs.load(appContext)
        private var cornerPhase = Prefs.cornerPhase(appContext)

        private var visible = false
        private var surfaceReady = false
        private var frameScheduled = false
        private var lastFrameTickNanos = 0L
        private var frameAccumulatorNanos = 0L
        private var lastUnlockHandledAt = 0L
        private var pendingUnlockAnimation = false
        private var userPresentSeen = false
        private var receiverRegistered = false

        /**
         * ACTION_SCREEN_ON fallback на случай прошивок, которые не присылают
         * ACTION_USER_PRESENT. Если keyguard уже снят — запускаем анимацию
         * разблокировки; если экран заблокирован, настоящий USER_PRESENT
         * сделает это позже.
         */
        private val screenOnFallback = Runnable {
            if (userPresentSeen) return@Runnable
            val keyguard = appContext.getSystemService(Context.KEYGUARD_SERVICE)
                as? KeyguardManager
            if (keyguard == null || !keyguard.isKeyguardLocked) {
                requestUnlockAnimation()
            }
        }

        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_USER_PRESENT -> {
                        userPresentSeen = true
                        mainHandler.removeCallbacks(screenOnFallback)
                        requestUnlockAnimation()
                    }

                    Intent.ACTION_SCREEN_ON -> {
                        userPresentSeen = false
                        mainHandler.removeCallbacks(screenOnFallback)
                        mainHandler.postDelayed(screenOnFallback, 1_500L)
                        // Some launchers keep visibility=true across screen
                        // power changes, so resume explicitly as well.
                        startRenderingIfPossible()
                    }

                    Intent.ACTION_SCREEN_OFF -> {
                        userPresentSeen = false
                        mainHandler.removeCallbacks(screenOnFallback)
                        // Guarantee a full stop even if the framework has not
                        // delivered onVisibilityChanged(false) yet.
                        stopRendering()
                    }
                }
            }
        }

        private val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frameScheduled = false
                if (!visible || !surfaceReady) return

                // Accumulate real elapsed time and render only when the next
                // frame is due. Unlike a fixed skip threshold this also works
                // when 60/90/120 Hz refresh rates do not divide the target FPS.
                val firstFrame = lastFrameTickNanos == 0L
                if (!firstFrame) {
                    val elapsed = (frameTimeNanos - lastFrameTickNanos)
                        .coerceIn(0L, 250_000_000L)
                    frameAccumulatorNanos += elapsed
                }
                lastFrameTickNanos = frameTimeNanos

                val interval = 1_000_000_000L /
                    settings.targetFps.coerceAtLeast(1)
                if (!firstFrame &&
                    frameAccumulatorNanos + 1_000_000L < interval
                ) {
                    scheduleNextFrame()
                    return
                }
                if (!firstFrame) {
                    frameAccumulatorNanos =
                        (frameAccumulatorNanos - interval).coerceAtLeast(0L)
                }

                drawOneFrame(frameTimeNanos)
                // A photo is static. Redraw it only when visibility,
                // preferences or the surface change, not at 60–120 fps.
                if (settings.style != ShapeStyle.PHOTO) {
                    scheduleNextFrame()
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            renderer.updateSettings(settings)
            renderer.setCornerPhase(cornerPhase)
            prefs.registerOnSharedPreferenceChangeListener(this)
            registerScreenReceiver()
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
            // Размер поверхности читается рендерером из Canvas на каждом кадре.
            // Просто убеждаемся, что цикл запущен (после смены ориентации).
            startRenderingIfPossible()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            surfaceReady = false
            stopRendering()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                lastFrameTickNanos = 0L
                frameAccumulatorNanos = 0L
                renderer.resetFrameClock()
                startRenderingIfPossible()
            } else {
                stopRendering()
            }
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            if (key != null && key !in Prefs.wallpaperKeys) return

            val updated = Prefs.load(appContext)
            if (updated == settings) return

            if (updated.targetFps != settings.targetFps) {
                frameAccumulatorNanos = 0L
            }
            settings = updated
            renderer.updateSettings(updated)
            // Следующий кадр сам пересоберёт ASCII-сетку при смене columns.
            // Если обои сейчас на паузе, новые значения применятся при показе.
            if (visible && surfaceReady) scheduleNextFrame()
        }

        private fun startRenderingIfPossible() {
            if (!visible || !surfaceReady) return

            if (pendingUnlockAnimation) {
                pendingUnlockAnimation = false
                performUnlockAnimation()
            }

            if (!frameScheduled) {
                renderer.resetFrameClock()
                lastFrameTickNanos = 0L
                frameAccumulatorNanos = 0L
                scheduleNextFrame()
            }
        }

        private fun scheduleNextFrame() {
            if (!visible || !surfaceReady || frameScheduled) return
            frameScheduled = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }

        private fun stopRendering() {
            if (frameScheduled) {
                Choreographer.getInstance().removeFrameCallback(frameCallback)
                frameScheduled = false
            }
            lastFrameTickNanos = 0L
            frameAccumulatorNanos = 0L
            renderer.resetFrameClock()
        }

        private fun drawOneFrame(frameTimeNanos: Long) {
            val holder = surfaceHolder
            if (!holder.surface.isValid) {
                surfaceReady = false
                return
            }

            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    renderer.renderFrame(
                        canvas = canvas,
                        width = canvas.width,
                        height = canvas.height,
                        timeNanos = frameTimeNanos,
                        newSettings = settings
                    )
                }
            } catch (e: Exception) {
                // Surface может исчезнуть между isSurfaceValid() и lockCanvas().
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
            if (!settings.unlockAnimationEnabled) return
            val now = SystemClock.elapsedRealtime()
            // USER_PRESENT и SCREEN_ON fallback могут прийти почти одновременно.
            if (now - lastUnlockHandledAt < 1_200L) return
            lastUnlockHandledAt = now

            if (!visible || !surfaceReady) {
                pendingUnlockAnimation = true
                return
            }
            performUnlockAnimation()
        }

        private fun performUnlockAnimation() {
            if (!settings.unlockAnimationEnabled) return
            if (settings.style.usesCornerSwap) {
                cornerPhase = 1 - cornerPhase
                Prefs.saveCornerPhase(appContext, cornerPhase)
            }
            // The renderer picks its own animation for the current style:
            // corner swap, an angular-velocity burst, or a black-hole pulse.
            renderer.onUnlock(cornerPhase, System.nanoTime())
        }

        private fun registerScreenReceiver() {
            if (receiverRegistered) return
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            // All three actions are protected system broadcasts, so an exported
            // dynamic receiver cannot be spoofed by another app.
            ContextCompat.registerReceiver(
                appContext,
                screenReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
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

        override fun onDestroy() {
            stopRendering()
            mainHandler.removeCallbacksAndMessages(null)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            unregisterScreenReceiver()
            super.onDestroy()
        }
    }

    companion object {
        private const val TAG = "AsciiWallpaper"
    }
}
