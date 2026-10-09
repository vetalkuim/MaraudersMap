package com.vetalkuim.maraudersmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build

/**
 * Рисует обои по слоям:
 * 0 — пергамент ([drawBackground]), растянутый по принципу center-crop без искажений;
 * 1 — нарисованная карта ([drawMap]), вписанная в экран целиком;
 * 2 — путники со следами и подписями ([drawCreatures]).
 */
class MapRenderer(private val context: Context) {

    private val bitmaps = mutableMapOf<MapBackground, Bitmap>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()

    /**
     * Чернила впитываются в пергамент: белый фон PNG-рисунка не перекрывает бумагу (API 29+).
     * Карта рисуется той же сепией, что и следы путников.
     */
    private val mapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) blendMode = BlendMode.MULTIPLY
        colorFilter = ColorMatrixColorFilter(inkTint(INK))
    }
    private var mapCache: Bitmap? = null

    /** Картинка карты, по которой посчитана сетка путников: пересчёт — только при новой. */
    private var walkAreaSource: Bitmap? = null

    /**
     * Изображение слоя карты; null — слой выключен или ещё загружается.
     * Растеризуется при первой отрисовке — так делает превью в настройках.
     */
    var mapImage: MapImage? = null
        set(value) {
            field = value
            mapCache?.recycle()
            mapCache = null
        }

    /**
     * Готовая карта под размер экрана ([MapLayers.raster]): обои получают её заранее, из кэша
     * на диске или из фонового потока, чтобы не растеризовать сложный вектор во время кадра.
     * Если задана — главнее [mapImage].
     */
    var mapRaster: Bitmap? = null

    /**
     * Интенсивность цвета карты в процентах: до 100 — чернила бледнее,
     * выше 100 — карта накладывается второй раз и чернила гуще.
     */
    var mapIntensity: Int = MapPrefs.DEFAULT_MAP_INTENSITY

    /** Слой 2; null — не рисуется. */
    var creatures: MapCreatures? = null

    private val creatureRenderer by lazy { CreatureRenderer(context) }

    var background: MapBackground = MapBackground.DEFAULT
        set(value) {
            field = value
            releaseUnused()
        }

    fun draw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        drawBackground(canvas)
        drawMap(canvas)
        updateWalkArea(canvas.width, canvas.height)
        drawCreatures(canvas)
    }

    /**
     * Путники ходят только по чистой бумаге — там, где нет рисунка карты.
     * Без карты (или с невидимой) — по всему экрану.
     */
    fun updateWalkArea(width: Int, height: Int) {
        val world = creatures ?: return
        if ((mapRaster == null && mapImage == null) || mapIntensity <= 0) {
            if (world.walkArea != null) world.walkArea = null
            walkAreaSource = null
            return
        }
        val bitmap = mapBitmap(width, height) ?: return
        if (bitmap === walkAreaSource && world.walkArea != null) return
        world.walkArea = walkAreaOf(bitmap, world.dp)
        walkAreaSource = bitmap
    }

    /** Сетка чернил: клетка занята, если в ней есть хоть одна тёмная точка рисунка. */
    private fun walkAreaOf(bitmap: Bitmap, dp: Float): WalkArea {
        val width = bitmap.width
        val height = bitmap.height
        val cell = WalkArea.CELL_DP * dp
        val (cols, rows) = WalkArea.gridSize(width.toFloat(), height.toFloat(), cell)
        val ink = BooleanArray(cols * rows)
        val line = IntArray(width)
        val colOf = IntArray(width) { minOf((it / cell).toInt(), cols - 1) }
        for (y in 0 until height) {
            bitmap.getPixels(line, 0, width, 0, y, width, 1)
            val rowStart = minOf((y / cell).toInt(), rows - 1) * cols
            for (x in 0 until width) {
                if (isInk(line[x])) ink[rowStart + colOf[x]] = true
            }
        }
        return WalkArea.fromInk(width.toFloat(), height.toFloat(), cell, dp, ink)
    }

    private fun drawMap(canvas: Canvas) {
        val strength = 255 * mapIntensity / 100
        if (strength <= 0) return
        val bitmap = mapBitmap(canvas.width, canvas.height) ?: return
        mapPaint.alpha = strength.coerceAtMost(255)
        canvas.drawBitmap(bitmap, 0f, 0f, mapPaint)
        if (strength > 255) {
            mapPaint.alpha = (strength - 255).coerceAtMost(255)
            canvas.drawBitmap(bitmap, 0f, 0f, mapPaint)
        }
    }

    private fun drawCreatures(canvas: Canvas) {
        val world = creatures ?: return
        creatureRenderer.draw(canvas, world)
    }

    /**
     * Карта под размер экрана: готовая [mapRaster] или растеризованная один раз из [mapImage] —
     * перерисовывать сложный вектор каждый кадр анимации слишком дорого.
     * Готовая карта другого размера (после поворота) не рисуется, пока не придёт новая.
     */
    private fun mapBitmap(width: Int, height: Int): Bitmap? {
        mapRaster?.let { return if (it.width == width && it.height == height) it else null }
        val image = mapImage ?: return null
        mapCache?.let { if (it.width == width && it.height == height) return it }
        mapCache?.recycle()
        mapCache = rasterize(image, width, height)
        return mapCache
    }

    private fun drawBackground(canvas: Canvas) {
        val bitmap = bitmapFor(background)
        centerCrop(bitmap.width, bitmap.height, canvas.width, canvas.height, srcRect)
        dstRect.set(0, 0, canvas.width, canvas.height)
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
    }

    private fun bitmapFor(bg: MapBackground): Bitmap = bitmaps.getOrPut(bg) {
        BitmapFactory.decodeResource(context.resources, bg.drawable, BitmapFactory.Options().apply {
            inScaled = false
        })
    }

    private fun releaseUnused() {
        val needed = setOf(background)
        val iterator = bitmaps.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in needed) {
                entry.value.recycle()
                iterator.remove()
            }
        }
    }

    fun release() {
        bitmaps.values.forEach(Bitmap::recycle)
        bitmaps.clear()
        mapImage = null
        mapRaster = null
        creatureRenderer.release()
    }

    companion object {
        /**
         * Вписывает карту в экран целиком с полями [MAP_MARGIN].
         * Долго для больших SVG — обои вызывают это не в главном потоке.
         */
        fun rasterize(image: MapImage, width: Int, height: Int): Bitmap? {
            if (width <= 0 || height <= 0 || image.width <= 0f || image.height <= 0f) return null
            val margin = minOf(width, height) * MAP_MARGIN
            val scale = minOf((width - 2 * margin) / image.width, (height - 2 * margin) / image.height)
            val w = image.width * scale
            val h = image.height * scale
            val left = (width - w) / 2
            val top = (height - h) / 2
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            image.draw(Canvas(bitmap), RectF(left, top, left + w, top + h))
            return bitmap
        }


        /** Поля вокруг карты — доля меньшей стороны экрана. */
        private const val MAP_MARGIN = 0.04f

        /** Порог «чернил»: насколько точка темнее бумаги с учётом прозрачности, из 255. */
        private const val INK_THRESHOLD = 64

        /** Тёмная непрозрачная точка; белый фон PNG-рисунка чернилами не считается. */
        private fun isInk(pixel: Int): Boolean {
            val alpha = pixel ushr 24
            if (alpha < INK_THRESHOLD) return false
            val luma = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
            return (255 - luma) * alpha / 255 >= INK_THRESHOLD
        }

        /**
         * Перекрашивает рисунок в [ink] по яркости: чёрное становится [ink], белое остаётся белым,
         * промежуточное — между ними; прозрачность не меняется.
         */
        private fun inkTint(ink: Int): ColorMatrix {
            fun row(channel: Int): FloatArray {
                val k = (255 - channel) / 255f
                return floatArrayOf(k * 0.299f, k * 0.587f, k * 0.114f, 0f, channel.toFloat())
            }
            return ColorMatrix(
                row(Color.red(ink)) + row(Color.green(ink)) + row(Color.blue(ink)) +
                    floatArrayOf(0f, 0f, 0f, 1f, 0f),
            )
        }

        /** Вычисляет область исходника, которая заполнит экран без искажений. */
        private fun centerCrop(srcW: Int, srcH: Int, dstW: Int, dstH: Int, out: Rect) {
            if (dstW <= 0 || dstH <= 0) {
                out.set(0, 0, srcW, srcH)
                return
            }
            if (srcW.toLong() * dstH > srcH.toLong() * dstW) {
                val cropW = (srcH.toLong() * dstW / dstH).toInt()
                val left = (srcW - cropW) / 2
                out.set(left, 0, left + cropW, srcH)
            } else {
                val cropH = (srcW.toLong() * dstH / dstW).toInt()
                val top = (srcH - cropH) / 2
                out.set(0, top, srcW, top + cropH)
            }
        }
    }
}
