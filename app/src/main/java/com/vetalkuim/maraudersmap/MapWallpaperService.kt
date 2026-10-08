package com.vetalkuim.maraudersmap

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

class MapWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = MapEngine()

    private inner class MapEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val handler = Handler(Looper.getMainLooper())
        private val renderer = MapRenderer(resources)
        private val prefs = MapPrefs.get(this@MapWallpaperService)
        private var visible = false
        private val drawRunnable = Runnable { drawFrame() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            renderer.background = MapPrefs.background(prefs)
            prefs.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDestroy() {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            handler.removeCallbacks(drawRunnable)
            renderer.release()
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                renderer.restartUnfold()
                drawFrame()
            } else {
                handler.removeCallbacks(drawRunnable)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(drawRunnable)
            super.onSurfaceDestroyed(holder)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
            if (key != MapPrefs.KEY_BACKGROUND) return
            renderer.background = MapPrefs.background(sharedPreferences)
            renderer.restartUnfold()
            drawFrame()
        }

        private fun drawFrame() {
            handler.removeCallbacks(drawRunnable)
            val holder = surfaceHolder
            // Аппаратный канвас заметно быстрее масштабирует пергамент во время анимации.
            val canvas = try {
                holder.lockHardwareCanvas()
            } catch (e: IllegalStateException) {
                null
            } ?: holder.lockCanvas() ?: return
            try {
                renderer.draw(canvas)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            if (visible && renderer.isAnimating) {
                handler.postDelayed(drawRunnable, FRAME_DELAY_MS)
            }
        }
    }

    private companion object {
        const val FRAME_DELAY_MS = 16L
    }
}
