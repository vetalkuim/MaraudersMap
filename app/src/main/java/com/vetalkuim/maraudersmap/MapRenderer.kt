package com.vetalkuim.maraudersmap

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock

/**
 * Рисует фон карты на канвасе обоев. Растягивает пергамент по принципу center-crop,
 * чтобы он заполнял экран любой пропорции без искажений.
 *
 * Слои с дополнительной информацией в будущем рисуются поверх [drawBackground].
 */
class MapRenderer(private val resources: Resources) {

    private val bitmaps = mutableMapOf<MapBackground, Bitmap>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()

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
            SystemClock.uptimeMillis() - unfoldStart < UNFOLD_TOTAL_MS

    fun draw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        drawBackground(canvas)
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
        BitmapFactory.decodeResource(resources, bg.drawable, BitmapFactory.Options().apply {
            inScaled = false
        })
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
    }

    private companion object {
        const val UNFOLD_HOLD_MS = 250L
        const val UNFOLD_STEP_MS = 650L
        val UNFOLD_TOTAL_MS = UNFOLD_HOLD_MS + UNFOLD_STEP_MS * (MapBackground.unfoldFrames.size - 1)

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
