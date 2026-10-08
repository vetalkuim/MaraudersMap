package com.vetalkuim.maraudersmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock

/**
 * Рисует обои по слоям:
 * 0 — пергамент ([drawBackground]), растянутый по принципу center-crop без искажений;
 * 1 — нарисованная карта ([drawMap]), вписанная в экран целиком.
 *
 * Следующие слои (имена, следы) рисуются поверх в [draw].
 */
class MapRenderer(private val context: Context) {

    private val bitmaps = mutableMapOf<MapBackground, Bitmap>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()

    /** Чернила впитываются в пергамент: белый фон PNG-рисунка не перекрывает бумагу (API 29+). */
    private val mapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) blendMode = BlendMode.MULTIPLY
    }
    private var mapCache: Bitmap? = null

    /** Изображение слоя карты; null — слой выключен или ещё загружается. */
    var mapImage: MapImage? = null
        set(value) {
            field = value
            mapCache?.recycle()
            mapCache = null
        }

    var background: MapBackground = MapBackground.DEFAULT
        set(value) {
            field = value
            releaseUnused()
        }

    private var unfoldStart = 0L

    /** Запускает анимацию раскрытия заново (для режима [MapBackground.UNFOLD]). */
    fun restartUnfold() {
        unfoldStart = SystemClock.uptimeMillis()
    }

    /** true, пока идёт анимация и нужны следующие кадры. */
    val isAnimating: Boolean
        get() = background == MapBackground.UNFOLD &&
            SystemClock.uptimeMillis() - unfoldStart < UNFOLD_TOTAL_MS + (if (mapImage != null) MAP_REVEAL_MS else 0L)

    fun draw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        drawBackground(canvas)
        drawMap(canvas)
    }

    /** В режиме раскрытия карта проступает чернилами, когда лист уже развёрнут. */
    private fun drawMap(canvas: Canvas) {
        val image = mapImage ?: return
        val alpha = if (background == MapBackground.UNFOLD) {
            val elapsed = SystemClock.uptimeMillis() - unfoldStart - UNFOLD_TOTAL_MS
            (easeInOut((elapsed.toFloat() / MAP_REVEAL_MS).coerceIn(0f, 1f)) * 255).toInt()
        } else {
            255
        }
        if (alpha <= 0) return
        val bitmap = mapBitmap(image, canvas.width, canvas.height) ?: return
        mapPaint.alpha = alpha
        canvas.drawBitmap(bitmap, 0f, 0f, mapPaint)
    }

    /**
     * Карта растеризуется один раз под размер экрана: перерисовывать сложный вектор
     * каждый кадр анимации слишком дорого.
     */
    private fun mapBitmap(image: MapImage, width: Int, height: Int): Bitmap? {
        mapCache?.let { if (it.width == width && it.height == height) return it }
        mapCache?.recycle()
        mapCache = null
        if (width <= 0 || height <= 0 || image.width <= 0f || image.height <= 0f) return null
        val margin = minOf(width, height) * MAP_MARGIN
        val scale = minOf((width - 2 * margin) / image.width, (height - 2 * margin) / image.height)
        val w = image.width * scale
        val h = image.height * scale
        val left = (width - w) / 2
        val top = (height - h) / 2
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        image.draw(Canvas(bitmap), RectF(left, top, left + w, top + h))
        mapCache = bitmap
        return bitmap
    }

    private fun drawBackground(canvas: Canvas) {
        if (background != MapBackground.UNFOLD) {
            drawLayer(canvas, background, 255)
            return
        }
        val frames = MapBackground.unfoldFrames
        val elapsed = SystemClock.uptimeMillis() - unfoldStart
        val progress = ((elapsed - UNFOLD_HOLD_MS).toFloat() / UNFOLD_STEP_MS)
            .coerceIn(0f, (frames.size - 1).toFloat())
        val index = progress.toInt().coerceAtMost(frames.size - 2)
        val fraction = easeInOut(progress - index)

        drawLayer(canvas, frames[index], 255)
        if (fraction > 0f) {
            drawLayer(canvas, frames[index + 1], (fraction * 255).toInt())
        }
    }

    private fun drawLayer(canvas: Canvas, bg: MapBackground, alpha: Int) {
        val bitmap = bitmapFor(bg)
        centerCrop(bitmap.width, bitmap.height, canvas.width, canvas.height, srcRect)
        dstRect.set(0, 0, canvas.width, canvas.height)
        paint.alpha = alpha
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
    }

    private fun bitmapFor(bg: MapBackground): Bitmap = bitmaps.getOrPut(bg) {
        val custom = if (bg == MapBackground.CUSTOM) CustomBackground.load(context) else null
        custom ?: BitmapFactory.decodeResource(context.resources, bg.drawable, BitmapFactory.Options().apply {
            inScaled = false
        })
    }

    /** Сбрасывает закэшированную свою картинку, чтобы при следующей отрисовке прочитать новую. */
    fun invalidateCustom() {
        bitmaps.remove(MapBackground.CUSTOM)?.recycle()
    }

    private fun releaseUnused() {
        val needed = if (background == MapBackground.UNFOLD) {
            MapBackground.unfoldFrames.toSet()
        } else {
            setOf(background)
        }
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
    }

    private companion object {
        const val UNFOLD_HOLD_MS = 250L
        const val UNFOLD_STEP_MS = 650L
        val UNFOLD_TOTAL_MS = UNFOLD_HOLD_MS + UNFOLD_STEP_MS * (MapBackground.unfoldFrames.size - 1)
        const val MAP_REVEAL_MS = 900L

        /** Поля вокруг карты — доля меньшей стороны экрана. */
        const val MAP_MARGIN = 0.04f

        fun easeInOut(t: Float): Float = t * t * (3f - 2f * t)

        /** Вычисляет область исходника, которая заполнит экран без искажений. */
        fun centerCrop(srcW: Int, srcH: Int, dstW: Int, dstH: Int, out: Rect) {
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
