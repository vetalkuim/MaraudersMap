package com.vetalkuim.maraudersmap

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MapWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = MapEngine()

    private inner class MapEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val handler = Handler(Looper.getMainLooper())
        private val renderer = MapRenderer(this@MapWallpaperService)
        private val prefs = MapPrefs.get(this@MapWallpaperService)
        private var visible = false
        private val drawRunnable = Runnable { drawFrame() }
        private val loader: ExecutorService = Executors.newSingleThreadExecutor()
        private var mapGeneration = 0

        /** Слой 2 живёт, пока жив движок: поворот экрана только меняет его размер. */
        private val creatures = MapCreatures(resources.displayMetrics.density)

        /** Время прошлого кадра; 0 — отсчёт начнётся заново, без скачка после паузы. */
        private var lastFrameAt = 0L

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            renderer.background = MapPrefs.background(prefs)
            renderer.creatures = creatures
            creatures.travelerCount = MapPrefs.travelerCount(prefs)
            creatures.dementorCount = MapPrefs.dementorCount(prefs)
            prefs.registerOnSharedPreferenceChangeListener(this)
            loadMap()
        }

        override fun onDestroy() {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            handler.removeCallbacks(drawRunnable)
            mapGeneration++
            loader.shutdownNow()
            renderer.release()
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            lastFrameAt = 0L
            if (visible) {
                renderer.restartUnfold()
                drawFrame()
            } else {
                handler.removeCallbacks(drawRunnable)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            val first = creatures.width == 0f
            creatures.resize(width.toFloat(), height.toFloat())
            // Предпросмотр и первый показ сразу с путниками и цепочкой следов, без касания.
            if (first) creatures.warmUp()
            drawFrame()
        }

        /**
         * Пока листают рабочие столы, всё стоит: следующий кадр откладывается,
         * пока смещения не перестанут меняться.
         */
        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int,
        ) {
            if (!visible) return
            handler.removeCallbacks(drawRunnable)
            lastFrameAt = 0L
            handler.postDelayed(drawRunnable, SCROLL_SETTLE_MS)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(drawRunnable)
            super.onSurfaceDestroyed(holder)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
            when (key) {
                MapPrefs.KEY_BACKGROUND -> {
                    renderer.background = MapPrefs.background(sharedPreferences)
                    renderer.restartUnfold()
                    drawFrame()
                }
                MapPrefs.KEY_CUSTOM_VERSION -> {
                    renderer.invalidateCustom()
                    drawFrame()
                }
                MapPrefs.KEY_MAP_LAYER, MapPrefs.KEY_CUSTOM_STAMP -> loadMap()
                // Новые приходят с краёв, лишние уходят за край — никто не исчезает на месте.
                MapPrefs.KEY_TRAVELERS, MapPrefs.KEY_DEMENTORS -> {
                    creatures.travelerCount = MapPrefs.travelerCount(sharedPreferences)
                    creatures.dementorCount = MapPrefs.dementorCount(sharedPreferences)
                    if (visible) drawFrame()
                }
            }
        }

        /** Разбор SVG занимает заметное время, поэтому слой карты грузится в фоне. */
        private fun loadMap() {
            val generation = ++mapGeneration
            val layer = MapPrefs.mapLayer(prefs)
            if (layer == MapLayer.NONE) {
                renderer.mapImage = null
                if (visible) drawFrame()
                return
            }
            loader.execute {
                val image = try {
                    MapLayers.load(this@MapWallpaperService, layer)
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot load map layer $layer", e)
                    null
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "Map layer $layer is too large", e)
                    null
                }
                handler.post {
                    if (generation != mapGeneration) return@post
                    renderer.mapImage = image
                    if (visible) drawFrame()
                }
            }
        }

        private fun drawFrame() {
            handler.removeCallbacks(drawRunnable)
            val now = SystemClock.uptimeMillis()
            // Пока лист разворачивается, путники ждут.
            if (!renderer.isUnfolding) {
                creatures.update(if (lastFrameAt == 0L) 0f else (now - lastFrameAt) / 1000f)
            }
            lastFrameAt = now
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
            if (!visible) return
            when {
                renderer.isAnimating -> handler.postDelayed(drawRunnable, FRAME_DELAY_MS)
                !creatures.isIdle -> handler.postDelayed(drawRunnable, CREATURE_FRAME_DELAY_MS)
            }
        }
    }

    private companion object {
        const val FRAME_DELAY_MS = 16L

        /** Путникам и следам хватает ~30 кадров в секунду — вдвое реже, чем раскрытию листа. */
        const val CREATURE_FRAME_DELAY_MS = 33L
        const val SCROLL_SETTLE_MS = 300L
        const val TAG = "MapWallpaper"
    }
}
