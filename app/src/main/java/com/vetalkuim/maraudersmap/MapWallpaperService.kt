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
        @Volatile
        private var mapGeneration = 0

        /** Пергамент варианта 2 генерируется отдельно, чтобы не ждать растеризации карты. */
        private val backgroundLoader: ExecutorService = Executors.newSingleThreadExecutor()
        @Volatile
        private var backgroundGeneration = 0

        /** Слой 2 живёт, пока жив движок: поворот экрана только меняет его размер. */
        private val creatures = MapCreatures(resources.displayMetrics.density)

        /** Время прошлого кадра; 0 — отсчёт начнётся заново, без скачка после паузы. */
        private var lastFrameAt = 0L

        /** Размер поверхности; 0 — ещё неизвестен. */
        private var surfaceWidth = 0
        private var surfaceHeight = 0

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            renderer.creatures = creatures
            renderer.background = MapPrefs.background(prefs)
            renderer.drawing = MapPrefs.drawing(prefs)
            applyInscriptions(prefs)
            renderer.mapIntensity = MapPrefs.mapIntensity(prefs)
            creatures.travelerNames = MapPrefs.travelerNames(prefs)
            creatures.dementorCount = MapPrefs.dementorCount(prefs)
            prefs.registerOnSharedPreferenceChangeListener(this)
            loadMap()
        }

        override fun onDestroy() {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            handler.removeCallbacks(drawRunnable)
            mapGeneration++
            backgroundGeneration++
            loader.shutdownNow()
            backgroundLoader.shutdownNow()
            renderer.release()
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            lastFrameAt = 0L
            if (visible) {
                drawFrame()
            } else {
                handler.removeCallbacks(drawRunnable)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            val raster = renderer.mapRaster
            if (raster == null || raster.width != width || raster.height != height) loadMap()
            loadBackground()
            val first = creatures.width == 0f
            creatures.resize(width.toFloat(), height.toFloat())
            // Предпросмотр и первый показ сразу с путниками и цепочкой следов, без касания;
            // если карта уже есть, следы с самого начала идут по чистой бумаге.
            if (first) {
                renderer.updateWalkArea(width, height)
                creatures.warmUp()
            }
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
                // Новые приходят с краёв, лишние уходят за край — никто не исчезает на месте.
                MapPrefs.KEY_TRAVELER_NAMES, MapPrefs.KEY_DEMENTORS -> {
                    creatures.travelerNames = MapPrefs.travelerNames(sharedPreferences)
                    creatures.dementorCount = MapPrefs.dementorCount(sharedPreferences)
                    if (visible) drawFrame()
                }
                MapPrefs.KEY_BACKGROUND -> {
                    renderer.background = MapPrefs.background(sharedPreferences)
                    loadBackground()
                    if (visible) drawFrame()
                }
                MapPrefs.KEY_DRAWING -> {
                    renderer.drawing = MapPrefs.drawing(sharedPreferences)
                    // Карта нужна только своему варианту: иначе её загрузка отменяется, а картинка освобождена.
                    if (renderer.drawing == Drawing.HOGWARTS) loadMap() else mapGeneration++
                    if (visible) drawFrame()
                }
                MapPrefs.KEY_DEDICATION_POSITION, MapPrefs.KEY_TITLE_POSITION,
                MapPrefs.KEY_DEDICATION_LINES, MapPrefs.KEY_TITLE_LINES -> {
                    applyInscriptions(sharedPreferences)
                    if (visible) drawFrame()
                }
                MapPrefs.KEY_MAP_INTENSITY -> {
                    renderer.mapIntensity = MapPrefs.mapIntensity(sharedPreferences)
                    if (visible) drawFrame()
                }
            }
        }

        /** Надписи статичны: после изменения настроек нужен лишь один новый кадр. */
        private fun applyInscriptions(prefs: SharedPreferences) {
            renderer.inscriptions.apply {
                dedicationPosition = MapPrefs.dedicationPosition(prefs)
                titlePosition = MapPrefs.titlePosition(prefs)
                dedicationLineCount = MapPrefs.dedicationLines(prefs)
                titleLineCount = MapPrefs.titleLines(prefs)
            }
        }

        /**
         * Слой 1 должен быть на месте с первого кадра: готовая карта под размер экрана берётся
         * из кэша на диске сразу. Без кэша (впервые, после обновления или на новом размере) SVG разбирается
         * и растеризуется в фоне, и результат сохраняется в кэш для следующих запусков.
         */
        private fun loadMap() {
            val generation = ++mapGeneration
            if (renderer.drawing != Drawing.HOGWARTS) return
            val width = surfaceWidth
            val height = surfaceHeight
            if (width <= 0 || height <= 0) {
                // Размер ещё неизвестен — заранее разбираем SVG, растеризация будет после onSurfaceChanged.
                loader.execute { loadImage() }
                return
            }
            val cached = MapLayers.cachedRaster(this@MapWallpaperService, width, height)
            if (cached != null) {
                renderer.mapRaster = cached
                if (visible) drawFrame()
                return
            }
            loader.execute {
                if (generation != mapGeneration) return@execute
                val raster = try {
                    MapLayers.raster(this@MapWallpaperService, width, height)
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot load map", e)
                    null
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "Map is too large", e)
                    null
                }
                handler.post {
                    if (generation != mapGeneration || renderer.drawing != Drawing.HOGWARTS) return@post
                    renderer.mapRaster = raster
                    if (visible) drawFrame()
                }
            }
        }

        /**
         * Пергамент варианта 2 генерируется под размер экрана в фоне (~100–300 мс);
         * до этого рисуется заливка базовым цветом бумаги.
         */
        private fun loadBackground() {
            val generation = ++backgroundGeneration
            val width = surfaceWidth
            val height = surfaceHeight
            if (renderer.background != Background.DUST || width <= 0 || height <= 0) return
            val current = renderer.dustParchment
            if (current != null && current.width == width && current.height == height) return
            backgroundLoader.execute {
                if (generation != backgroundGeneration) return@execute
                val bitmap = try {
                    ParchmentGenerator.generate(width, height)
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "Parchment is too large", e)
                    null
                } ?: return@execute
                handler.post {
                    if (generation != backgroundGeneration || renderer.background != Background.DUST) {
                        bitmap.recycle()
                        return@post
                    }
                    renderer.dustParchment = bitmap
                    if (visible) drawFrame()
                }
            }
        }

        private fun loadImage() {
            try {
                MapLayers.image(this@MapWallpaperService)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot load map", e)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Map is too large", e)
            }
        }

        private fun drawFrame() {
            handler.removeCallbacks(drawRunnable)
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrameAt == 0L) 0f else (now - lastFrameAt) / 1000f
            creatures.update(dt)
            renderer.update(dt)
            lastFrameAt = now
            val holder = surfaceHolder
            // Аппаратный канвас заметно быстрее рисует пергамент и карту.
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
            if (!creatures.isIdle || renderer.isAnimated) handler.postDelayed(drawRunnable, CREATURE_FRAME_DELAY_MS)
        }
    }

    private companion object {
        /** Путникам, следам и пылинкам хватает ~30 кадров в секунду. */
        const val CREATURE_FRAME_DELAY_MS = 33L
        const val SCROLL_SETTLE_MS = 300L
        const val TAG = "MapWallpaper"
    }
}
